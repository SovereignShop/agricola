/* Eurozone transport. No local state changes: the server owns every game move. */
'use strict';
(()=>{
 let socket,connected=false,busy=false,current=null,tables=[],identity,request=0,retry,heartbeat,timeout,lastReceived=Date.now();
 let seats=3,bots=1,playerName='',opening=null;
 const roomFromURL=()=>location.hash.startsWith('#table=')?location.hash.slice(7):null;
 const lobby=document.createElement('section');lobby.id='onlineLobby';document.querySelector('.command').before(lobby);
 const bar=document.createElement('section');bar.id='onlineStatus';document.querySelector('header').after(bar);
 const homeURL=()=>{const u=new URL('/',location.origin);u.search=location.search;for(const k of ['peer','key','transport','replay'])u.searchParams.delete(k);return u;};
 const home=document.createElement('a');home.href=homeURL();home.textContent='← Eurozone games';home.className='games-link';$('nav').prepend(home);
 const clearPending=()=>{busy=false;clearTimeout(timeout);};
 function send(m){if(!connected){toast('Reconnecting to Eurozone…');return false;}socket.send(JSON.stringify({...m,requestId:++request}));return true;}
 function mutation(m){if(busy)return;busy=true;if(!send({...m,id:current?.id,revision:current?.revision})){clearPending();return;}status();timeout=setTimeout(()=>{clearPending();socket.close();},12000);}
 function status(){
  bar.innerHTML=`<span role="status">${connected?'● Connected to Eurozone':'◌ Reconnecting…'}${busy?' · Saving move…':''}${current?` · ${current.seat===null?'Observing':esc(current.seats[current.seat]?.name||'Your seat')}`:''}</span><div>${current?'<button id="allTables">All Ascension tables</button><button id="inviteTable">Copy invitation</button>':''}</div>`;
  $('#allTables')?.addEventListener('click',()=>{close();selection=null;current=null;window.history.replaceState(null,'',location.pathname+location.search);send({op:'list'});renderLobby();});
  $('#inviteTable')?.addEventListener('click',async()=>{const u=new URL('/ascension/index.html',window.EUROZONE_PUBLIC_URL||location.origin);u.hash='table='+current.id;try{await navigator.clipboard.writeText(u.href);toast('Invitation copied.');}catch{modal(`<h2>Table invitation</h2><p>Open this link, sign in, then join an open seat or observe.</p><input readonly aria-label="Table invitation" value="${esc(u.href)}">`);}});
  $('#next').disabled=!connected||busy||!current||current.seat===null||!!state.winner||!!selection||([3,4].includes(state.phase)&&current.seat!==state.active);
 }
 function renderLobby(){
  status();document.body.classList.toggle('online-lobby',!current?.state);if(current?.state)return;
  if(current){lobby.innerHTML=`<small>ASCENSION · CLAIM THE THRONE</small><h2>${esc(current.title)}</h2><p>${current.players} claimants · ${current.bots} bots. The creator starts once every human has joined.</p><div class="online-seats">${current.seats.map((s,i)=>`<div><strong>${crests[i]} ${esc(s.name||'Open seat')}</strong><span>${s.bot?'Bot':s.name?'Human':'Waiting for a player'}</span></div>`).join('')}</div><div class="choice-row">${current.seat===null&&current.seats.some(s=>!s.name)?'<button id="joinSeat" class="gold">Join table</button>':''}${current.host?`<button id="startOnline" class="gold" ${current.seats.some(s=>!s.name)||busy?'disabled':''}>Start game</button>`:''}${current.seat!==null&&!current.host?'<button id="releaseSeat">Leave seat</button>':''}</div><p class="notice">Games are saved on the server. Reopening this table restores your seat; the creator does not need to stay connected.</p>`;
   $('#joinSeat')?.addEventListener('click',()=>mutation({op:'join',name:playerName}));$('#startOnline')?.addEventListener('click',()=>mutation({op:'start'}));$('#releaseSeat')?.addEventListener('click',()=>mutation({op:'leave-seat'}));return;
  }
  lobby.innerHTML=`<small>EUROZONE · MULTIPLAYER</small><h2>Ascension: Claim the Throne</h2><p>Build estates, gain the court’s loyalty, and contest the imperial throne. 2–4 claimants, with optional bots.</p><section class="online-create"><h3>Create a table</h3><label>Your name <input id="onlineName" maxlength="40" value="${esc(playerName)}"></label><h4>Claimants</h4><div class="choice-row">${[2,3,4].map(n=>`<button data-count="${n}" aria-pressed="${n===seats}" class="${n===seats?'gold':''}">${n} players</button>`).join('')}</div><h4>Bots</h4><div class="choice-row">${Array.from({length:seats},(_,n)=>`<button data-bot-count="${n}" aria-pressed="${n===bots}" class="${n===bots?'gold':''}">${n} bots</button>`).join('')}</div><p>${seats-bots} human seats · ${bots} bots</p><button id="createOnline" class="gold" ${!connected||busy?'disabled':''}>Create table</button></section><h3>Ascension tables</h3><div class="online-tables">${tables.map(t=>`<article><div><h4>${esc(t.title)}</h4><p>${t.players} players · ${t.bots} bots · ${t.status==='lobby'?`${t.seats.filter(s=>s.name).length}/${t.players} seats filled`:t.status==='finished'?'Game complete':'In progress'}</p></div><button data-open="${t.id}">${t.seat!==null?'Reopen table':t.status==='lobby'?'Join / observe':'Observe'}</button></article>`).join('')||'<p>No tables yet. Create one to get started.</p>'}</div><p class="notice">Playable prototype based on the Ascension manual. Its undecided rules use the existing prototype conventions: 12 power + 8 legitimacy to win, one draft per round, and shared ties.</p>`;
  $('#onlineName').oninput=e=>{playerName=e.target.value;sessionStorage.setItem('ascension-name',playerName);};
  document.querySelectorAll('[data-count]').forEach(b=>b.onclick=()=>{seats=+b.dataset.count;bots=Math.min(bots,seats-1);renderLobby();});
  document.querySelectorAll('[data-bot-count]').forEach(b=>b.onclick=()=>{bots=+b.dataset.botCount;renderLobby();});
  $('#createOnline').onclick=()=>mutation({op:'create',players:seats,bots,name:playerName});
  document.querySelectorAll('[data-open]').forEach(b=>b.onclick=()=>watch(b.dataset.open));
 }
 function watch(id){opening=id;send({op:'watch',id});}
 function receive(m){
  if(m.op==='welcome'){connected=true;clearPending();status();const id=roomFromURL();if(id)watch(id);else send({op:'list'});return;}
  if(m.op==='tables'){tables=m.tables;if(!current)renderLobby();return;}
  if(m.op==='ack'){clearPending();if(current?.state)render();else renderLobby();return;}
  if(m.op==='error'){clearPending();opening=null;toast(m.message);status();return;}
  if(m.op==='table'){
   const changed=!current||current.id!==m.table.id||current.revision!==m.table.revision;current=m.table;opening=null;
   window.history.replaceState(null,'',location.pathname+location.search+'#table='+current.id);
   if(current.state){if(changed){selection=null;close();}state=current.state;render();if(state.winner&&changed)showWinner();}
   renderLobby();
  }
 }
 function connect(){
  clearTimeout(retry);if(socket&&[0,1].includes(socket.readyState))return;
  connected=false;status();const ws=new WebSocket((location.protocol==='https:'?'wss://':'ws://')+location.host+'/ascension/ws');socket=ws;
  const deadline=setTimeout(()=>ws.close(),12000);
  ws.onopen=()=>{clearTimeout(deadline);ws.send(JSON.stringify({op:'hello',token:identity.token,auth:identity.auth}));};
  ws.onmessage=e=>{lastReceived=Date.now();if(socket===ws)try{receive(JSON.parse(e.data));}catch(error){toast('Could not display the table: '+error.message);}};
  ws.onclose=()=>{clearTimeout(deadline);if(socket!==ws)return;connected=false;clearPending();status();retry=setTimeout(connect,1500);};
  ws.onerror=()=>ws.close();
 }
 window.AscensionOnline={seat:()=>current?.seat??null,canAct:()=>connected&&!busy&&current?.seat!==null&&current?.seat===state.active&&!state.players[state.active].bot,
  command(action){if(!current||current.seat===null)return toast('Observers cannot take turns.');mutation({op:'command',action});}};
 setup=()=>{close();current=null;selection=null;window.history.replaceState(null,'',location.pathname+location.search);send({op:'list'});renderLobby();};
 const baseRender=render;render=()=>{baseRender();clearTimeout(botTimer);status();};
 $('#new').onclick=setup;modeButton.onclick=setup;
 for(const id of ['import','undo','addEstate','addCastle','addNote','die','pauseBots','modeButton','new']){const el=$('#'+id);if(el){el.hidden=true;el.onclick=()=>{};}}
 $('#file').disabled=true;$('#file').onchange=()=>{};
 $('#export').textContent='Save visible snapshot';$('#export').onclick=()=>{if(!current?.state)return;const a=document.createElement('a');const url=URL.createObjectURL(new Blob([JSON.stringify({game:'ascension',table:current.id,revision:current.revision,state},null,2)],{type:'application/json'}));a.href=url;a.download='ascension-visible-'+current.id+'.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);};
 $('#handButton').onclick=()=>handView();
 document.addEventListener('visibilitychange',()=>{if(!document.hidden){if(socket?.readyState!==1)connect();else if(Date.now()-lastReceived>20000){socket.close();}else if(current)watch(current.id);}});
 window.addEventListener('online',connect);
 window.addEventListener('offline',()=>{connected=false;clearPending();socket?.close();status();});
 window.addEventListener('hashchange',()=>{const id=roomFromURL();if(id&&id!==current?.id)watch(id);});
 window.addEventListener('beforeunload',()=>{clearInterval(heartbeat);clearTimeout(retry);socket?.close();});
 close();renderLobby();
 (window.EUROZONE_READY||Promise.resolve()).then(()=>{
  let token=sessionStorage.getItem('eurozone-session');if(!token){token=crypto.randomUUID();sessionStorage.setItem('eurozone-session',token);}
  identity={token,auth:window.EUROZONE_DISCORD_AUTH};playerName=sessionStorage.getItem('ascension-name')||sessionStorage.getItem('eurozone-name')||'Claimant';
  renderLobby();connect();heartbeat=setInterval(()=>{if(connected&&Date.now()-lastReceived>45000)socket.close();else if(connected&&current&&!busy)watch(current.id);else if(connected&&!current)send({op:'list'});},20000);
 }).catch(e=>{lobby.innerHTML=`<h2>Could not connect to Eurozone</h2><p>${esc(e.message)}</p><a href="${esc(homeURL().href)}">Return to Eurozone</a>`;});
})();
