(() => {
  const pair = location.hash.slice(1);
  history.replaceState(null, '', location.pathname);
  const status = document.getElementById('status'), speak = document.getElementById('speak'), stop = document.getElementById('stop');
  const cancel = document.getElementById('cancel');
  const continuous = document.getElementById('continuous'), microphone = document.getElementById('microphone');
  let version = null, connected = false, always = false;
  const resetControls = () => {
    always = false; microphone.textContent = 'Microphone off.';
    continuous.textContent = 'Start continuous listening'; continuous.setAttribute('aria-pressed', 'false');
    continuous.disabled = !connected; speak.disabled = !connected || !window.EurozoneSpeech.supported();
    stop.disabled = true; cancel.disabled = true;
  };
  const show = message => { status.textContent = message; };
  if (!/^[a-f0-9]{64}$/.test(pair)) { show('Open a fresh voice link from your game.'); return; }
  window.EurozoneSpeech.configure({headers:{'X-Eurozone-Voice-Pair':pair}});
  const socket = new WebSocket(`${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/voice`);
  const send = text => {
    if (!connected || !version || socket.readyState !== WebSocket.OPEN) { show('Wait for the paired game to reconnect.'); return; }
    document.getElementById('heard').textContent = 'Heard: ' + text;
    socket.send(JSON.stringify({op:'heard', text, version, id:crypto.randomUUID()}));
  };
  socket.onopen = () => socket.send(JSON.stringify({op:'pair', pair}));
  socket.onmessage = event => {
    const message = JSON.parse(event.data);
    if (message.op === 'status') {
      if (!always && version && version !== message.version) window.EurozoneSpeech.stop();
      version = message.version; connected = true;
      speak.disabled = always || !window.EurozoneSpeech.supported();
      continuous.disabled = !window.EurozoneSpeech.supported();
      document.getElementById('send').disabled = false;
      show(message.message);
    } else if (message.op === 'error' || message.op === 'closed') {
      show(message.message);
      if (message.op === 'closed') socket.close();
    }
  };
  socket.onclose = () => {
    connected = false; version = null; window.EurozoneSpeech.stop(); resetControls();
    document.getElementById('send').disabled = true; show('Voice pairing disconnected. Open a fresh link from the game.');
  };
  socket.onerror = () => show('Could not connect to the game’s voice relay.');
  speak.onclick = () => {
    const started = version;
    microphone.textContent = 'Single command in progress.';
    speak.disabled = true; continuous.disabled = true; stop.disabled = false; cancel.disabled = false; show('Listening…');
    window.EurozoneSpeech.start(text => { if (started === version) send(text); else show('The game changed. Speak again.'); }, show,
      resetControls, show);
  };
  continuous.onclick = () => {
    if (always) { window.EurozoneSpeech.stop(); return; }
    always = true; continuous.textContent = 'Stop listening'; continuous.setAttribute('aria-pressed', 'true');
    speak.disabled = true; stop.disabled = true; cancel.disabled = true;
    window.EurozoneSpeech.startContinuous(
      (text, started) => { if (started === version) send(text); else show('The game changed during that phrase. Please repeat it.'); },
      show, resetControls, text => { microphone.textContent = (always ? 'Microphone on · ' : '') + text; }, () => version);
  };
  stop.onclick = () => window.EurozoneSpeech.finish();
  cancel.onclick = () => { window.EurozoneSpeech.stop(); show('Recording cancelled.'); };
  document.getElementById('command').onsubmit = event => {
    event.preventDefault(); const input = document.getElementById('text');
    if (input.value.trim()) send(input.value.trim()); input.value = '';
  };
  window.addEventListener('pagehide', () => socket.close());
})();
