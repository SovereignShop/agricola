/* Game packets use WebRTC in browsers and an explicit relay inside Discord.
   This transport knows no Agricola rules and never owns game state. */
(() => {
  const LIMIT = 1000000;
  const storage = new Promise((resolve, reject) => {
    const request = indexedDB.open('eurozone-peer-hosts', 1);
    request.onupgradeneeded = () => request.result.createObjectStore('tables');
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
  // A rejected storage promise is reported when a host actually needs it.
  storage.catch(() => {});
  async function read(room) {
    const db = await storage;
    return new Promise((resolve, reject) => {
      const r = db.transaction('tables').objectStore('tables').get(room);
      r.onsuccess = () => resolve(r.result || null); r.onerror = () => reject(r.error);
    });
  }
  async function save(room, text) {
    const db = await storage;
    return new Promise((resolve, reject) => {
      const tx = db.transaction('tables', 'readwrite');
      tx.objectStore('tables').put(text, room);
      tx.oncomplete = resolve; tx.onerror = () => reject(tx.error); tx.onabort = () => reject(tx.error || new Error('Host save was aborted'));
    });
  }
  class Transport {
    constructor(options) {
      this.options = options; this.peers = new Map(); this.links = new Map(); this.closed = false;
      this.connect();
    }
    status(message) { this.options.onStatus(message); }
    raw(message) {
      if (this.ws?.readyState !== 1) throw new Error('The coordinator is reconnecting. Try again when connected.');
      this.ws.send(JSON.stringify(message));
    }
    connect() {
      if (this.closed) return;
      const url = new URL('/peer', location.href); url.protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
      this.ws = new WebSocket(url);
      this.ws.onopen = () => this.raw({op: this.options.createOptions ? 'create' : 'join', room: this.options.room,
        token: this.options.token, auth: window.EUROZONE_DISCORD_AUTH, invite: this.options.invite, options: this.options.createOptions, relay: !!this.options.relay});
      this.ws.onmessage = e => {
        try { this.receive(JSON.parse(e.data)); } catch (error) { this.options.onError(error.message); }
      };
      this.ws.onclose = () => {
        this.status('Coordinator reconnecting');
        if (!this.closed) this.timer = setTimeout(() => this.connect(), 1500);
      };
      this.ws.onerror = () => this.ws.close();
    }
    receive(message) {
      if (message.op === 'error') { this.options.onError(message.message); return; }
      if (message.op === 'welcome') {
        this.self = message.self; this.host = message.host; this.options.createOptions = null;
        this.options.onWelcome(message); return;
      }
      if (message.op === 'roster') {
        this.peers = new Map(message.peers.map(p => [p.id, p]));
        for (const [id, link] of this.links) if (!this.peers.has(id)) { link.pc.close(); this.links.delete(id); }
        if (this.self === this.host && !this.options.relay) {
          for (const [id, peer] of this.peers) if (id !== this.self && !peer.relay && !this.links.has(id)) this.offer(id);
        }
        this.options.onRoster(message);
        this.status(!this.peers.has(this.host) ? 'Waiting for the host to return' : this.options.relay ? 'Browser host · relay' : 'Browser host · connecting peers');
        return;
      }
      if (!this.peers.has(message.from) || !(message.from === this.host || this.self === this.host)) return;
      if (message.op === 'relay') {
        if (typeof message.payload === 'string' && message.payload.length < LIMIT) this.options.onPacket(message.from, message.payload);
        this.status('Browser host · relay');
      } else if (message.op === 'signal' && !this.options.relay) this.signal(message).catch(e => this.options.onError(e.message));
    }
    link(id) {
      if (this.links.has(id)) return this.links.get(id);
      const pc = new RTCPeerConnection(window.EUROZONE_RTC_CONFIG || {iceServers:[{urls:'stun:stun.l.google.com:19302'}]});
      const link = {pc, ice:[], partial:new Map(), chain:Promise.resolve()}; this.links.set(id, link);
      pc.onicecandidate = e => { if (e.candidate) { try { this.raw({op:'signal',to:id,kind:'ice',payload:e.candidate.toJSON()}); } catch (_) {} } };
      pc.ondatachannel = e => this.attach(id, link, e.channel);
      pc.onconnectionstatechange = () => { if (['failed','disconnected'].includes(pc.connectionState)) this.status('Browser host · relay'); };
      return link;
    }
    attach(id, link, dc) {
      if (dc.label !== 'eurozone-game') { dc.close(); return; }
      link.dc = dc;
      dc.onopen = () => { this.status('Browser host · direct P2P'); this.options.onDirect?.(id); };
      dc.onmessage = e => {
        try {
          if (typeof e.data !== 'string' || e.data.length > 18000) return;
          const part = JSON.parse(e.data);
          if (typeof part.id !== 'string' || part.id.length > 50 || !Number.isInteger(part.index) || !Number.isInteger(part.count) || part.count < 1 || part.count > 125 || part.index < 0 || part.index >= part.count || typeof part.data !== 'string' || part.data.length > 8000) return;
          for (const [key, value] of link.partial) if (Date.now() - value.time > 15000) link.partial.delete(key);
          let pending = link.partial.get(part.id);
          if (!pending) {
            if (link.partial.size >= 4) return;
            pending = {time:Date.now(),count:part.count,parts:new Map()}; link.partial.set(part.id,pending);
          }
          if (pending.count !== part.count) return;
          pending.parts.set(part.index,part.data);
          if (pending.parts.size === pending.count) {
            const text = Array.from({length:pending.count},(_,i) => pending.parts.get(i)).join('');
            link.partial.delete(part.id);
            if (text.length < LIMIT) this.options.onPacket(id,text);
          }
        } catch (_) { this.options.onError('A peer sent an unreadable game packet.'); }
      };
    }
    async offer(id) {
      try {
        const link = this.link(id); this.attach(id,link,link.pc.createDataChannel('eurozone-game',{ordered:true}));
        await link.pc.setLocalDescription(await link.pc.createOffer());
        this.raw({op:'signal',to:id,kind:'offer',payload:link.pc.localDescription.toJSON()});
      } catch (_) { this.status('Browser host · relay'); }
    }
    async signal({from,kind,payload}) {
      if (kind === 'offer' && from !== this.host) return;
      const link = this.link(from), pc = link.pc;
      if (kind === 'ice') { if (pc.remoteDescription) await pc.addIceCandidate(payload); else link.ice.push(payload); return; }
      await pc.setRemoteDescription(payload);
      for (const ice of link.ice.splice(0)) await pc.addIceCandidate(ice);
      if (kind === 'offer') {
        await pc.setLocalDescription(await pc.createAnswer());
        this.raw({op:'signal',to:from,kind:'answer',payload:pc.localDescription.toJSON()});
      }
    }
    send(id,text) {
      if (text.length >= LIMIT) throw new Error('The game update is too large to send.');
      if (id === this.self) { this.options.onPacket(id,text); return; }
      const link = this.links.get(id);
      if (link?.dc?.readyState === 'open') {
        const packet = crypto.randomUUID(), count = Math.ceil(text.length / 8000);
        link.chain = link.chain.then(async () => {
          for (let index=0; index<count; index++) {
            while (link.dc.bufferedAmount > 262144 && link.dc.readyState === 'open') await new Promise(r => setTimeout(r,20));
            if (link.dc.readyState !== 'open') throw new Error('Direct connection interrupted. Reconnect to refresh the table.');
            link.dc.send(JSON.stringify({id:packet,index,count,data:text.slice(index*8000,(index+1)*8000)}));
          }
        }).catch(e => this.options.onError(e.message));
      } else { this.raw({op:'relay',to:id,payload:text}); this.status('Browser host · relay'); }
    }
    initialized() { this.raw({op:'initialized'}); }
    close() { this.closed=true; clearTimeout(this.timer); this.ws?.close(); for (const {pc} of this.links.values()) pc.close(); }
  }
  window.EurozonePeer = {start:options => new Transport(options), read, save};
})();
