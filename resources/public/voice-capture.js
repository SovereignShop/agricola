/* Browser speech or configured local transcription. Recordings stay in memory. */
(() => {
  let current = null, timer = null;
  let pairedHeaders = null;
  const canRecord = () => !!(navigator.mediaDevices?.getUserMedia && window.MediaRecorder);
  const configController = new AbortController();
  const configTimeout = setTimeout(() => configController.abort(), 5000);
  const configuration = fetch('/voice/config', {cache:'no-store', signal:configController.signal})
    .then(response => response.ok ? response.json() : {local:false}).catch(() => ({local:false}))
    .finally(() => clearTimeout(configTimeout));
  function headers() {
    return pairedHeaders || {Authorization:'Bearer ' + (sessionStorage.getItem('eurozone-session') || ''),
      'X-Eurozone-Auth':window.EUROZONE_DISCORD_AUTH || ''};
  }
  async function record(session, heard, failed, progress) {
    let stream;
    try {
      progress('Recording… Say one command, then choose Finish recording.');
      stream = await navigator.mediaDevices.getUserMedia({audio:{echoCancellation:true, noiseSuppression:true}});
      if (current !== session) { stream.getTracks().forEach(track => track.stop()); return; }
      const chunks = [];
      const mimeType = ['audio/webm;codecs=opus','audio/mp4','audio/ogg;codecs=opus'].find(type => MediaRecorder.isTypeSupported(type));
      const recorder = new MediaRecorder(stream, mimeType ? {mimeType} : {});
      let cancelled = false;
      session.abort = () => {
        cancelled = true;
        if (recorder.state !== 'inactive') recorder.stop();
        stream.getTracks().forEach(track => track.stop());
        session.controller?.abort();
      };
      session.finish = () => { if (recorder.state === 'recording') recorder.stop(); };
      recorder.ondataavailable = event => { if (event.data.size) chunks.push(event.data); };
      recorder.onerror = () => { if (current === session) { failed('Microphone recording failed. Try again.'); stop(); } };
      recorder.onstop = async () => {
        stream.getTracks().forEach(track => track.stop());
        clearTimeout(timer);
        if (cancelled || current !== session) return;
        session.finish = () => {};
        progress('Transcribing on the game server…');
        session.controller = new AbortController();
        timer = setTimeout(() => session.controller.abort(), 50000);
        try {
          const audio = new Blob(chunks, {type:recorder.mimeType});
          if (!audio.size || audio.size > 2000000) throw new Error('Recording too large or empty. Try again.');
          const response = await fetch('/voice/transcribe', {method:'POST', body:audio,
            headers:headers(), signal:session.controller.signal});
          const result = await response.json();
          if (current !== session) return;
          if (!response.ok) throw new Error(result.error || 'Local transcription failed. Try again.');
          if (result.text?.trim()) heard(result.text.trim().slice(0,300));
          else failed('No speech heard. Tap Speak and try again.');
        } catch (error) {
          if (current === session) failed(error.name === 'AbortError' ? 'Transcription timed out. Try a shorter command.' : error.message);
        } finally {
          if (current === session) { clearTimeout(timer); current = null; session.finished(); }
        }
      };
      recorder.start();
      timer = setTimeout(session.finish, 8000);
    } catch (error) {
      stream?.getTracks().forEach(track => track.stop());
      if (current === session) {
        current = null;
        failed(error.name === 'NotAllowedError' ? 'Microphone access was denied. Allow it in browser settings and try again.' : 'Could not record from the microphone. Check your input device.');
        session.finished();
      }
    }
  }
  const constructor = () => window.SpeechRecognition || window.webkitSpeechRecognition;
  function stop() {
    clearTimeout(timer);
    const old = current;
    current = null;
    if (old) { old.abort(); old.finished(); }
  }
  function deliver(session, text, captured, heard, progress) {
    if (current !== session || !text?.trim()) return;
    text = text.trim();
    if (/^(?:please )?(?:stop listening|turn off (?:the )?(?:microphone|mic))[.!?]*$/i.test(text)) {
      stop(); progress('Microphone off.'); return;
    }
    if (text.length > 300) { progress('That command was too long. Try a shorter phrase.'); return; }
    heard(text, captured);
  }
  function wave(blocks, sampleRate) {
    const length = blocks.reduce((n, block) => n + block.length, 0);
    const buffer = new ArrayBuffer(44 + length * 2), view = new DataView(buffer);
    const string = (offset, text) => { for (let i = 0; i < text.length; i++) view.setUint8(offset+i, text.charCodeAt(i)); };
    string(0, 'RIFF'); view.setUint32(4, 36 + length*2, true); string(8, 'WAVE'); string(12, 'fmt ');
    view.setUint32(16, 16, true); view.setUint16(20, 1, true); view.setUint16(22, 1, true);
    view.setUint32(24, sampleRate, true); view.setUint32(28, sampleRate*2, true);
    view.setUint16(32, 2, true); view.setUint16(34, 16, true); string(36, 'data'); view.setUint32(40, length*2, true);
    let offset = 44;
    for (const block of blocks) for (const sample of block) {
      view.setInt16(offset, Math.max(-1, Math.min(1, sample)) * (sample < 0 ? 32768 : 32767), true); offset += 2;
    }
    return new Blob([buffer], {type:'audio/wav'});
  }
  async function continuousRecord(session, heard, failed, progress, capture) {
    let stream, context, source, processor;
    session.abort = () => {
      session.controller?.abort();
      stream?.getTracks().forEach(track => track.stop());
      if (processor) { processor.port.onmessage = null; processor.disconnect(); processor.port.close(); }
      source?.disconnect();
      if (context && context.state !== 'closed') context.close().catch(() => {});
    };
    try {
      progress('Opening microphone…');
      stream = await navigator.mediaDevices.getUserMedia({audio:{echoCancellation:true, noiseSuppression:true, channelCount:1}});
      if (current !== session) { session.abort(); return; }
      context = new AudioContext({sampleRate:16000});
      await context.audioWorklet.addModule('/voice-processor.js');
      if (current !== session) return;
      await context.resume();
      if (current !== session) return;
      source = context.createMediaStreamSource(stream);
      processor = new AudioWorkletNode(context, 'eurozone-microphone');
      source.connect(processor); processor.connect(context.destination);
      const rate = context.sampleRate;
      let pre = [], frames = [], samples = 0, voiced = 0, quiet = 0, noise = .003;
      let active = false, uploading = false, drain = false, captured;
      const reset = () => { pre = []; frames = []; samples = voiced = quiet = 0; active = false; };
      const upload = async () => {
        if (!active || uploading || current !== session) return;
        const audio = wave(frames, rate), version = captured;
        const enough = voiced >= rate * .2;
        reset();
        if (!enough) return;
        uploading = true; progress('Transcribing…');
        session.controller = new AbortController();
        const timeout = setTimeout(() => session.controller?.abort(), 50000);
        try {
          const response = await fetch('/voice/transcribe', {method:'POST', body:audio,
            headers:headers(), signal:session.controller.signal});
          const result = await response.json();
          if (current !== session) return;
          if (!response.ok) throw new Error(result.error || 'Transcription failed.');
          deliver(session, result.text, version, heard, progress);
        } catch (error) {
          if (current === session) { failed(error.name === 'AbortError' ? 'Transcription timed out. Start listening again.' : error.message); stop(); }
        } finally {
          clearTimeout(timeout); uploading = false;
          if (current === session) { drain = true; quiet = 0; progress('Pause briefly — getting ready for the next command…'); }
        }
      };
      session.finish = upload;
      processor.port.onmessage = event => {
        if (current !== session || uploading) return;
        const block = event.data;
        const rms = Math.sqrt(block.reduce((sum, x) => sum + x*x, 0) / block.length);
        const speech = rms > Math.max(.012, Math.min(.06, noise * 3.5));
        if (drain) { quiet = speech ? 0 : quiet + block.length; if (quiet >= rate*.35) { drain = false; reset(); progress('Listening — pause briefly after each command.'); } return; }
        if (!active) {
          pre.push(block); while (pre.length > Math.ceil(rate*.3/block.length)) pre.shift();
          if (!speech) { noise = noise*.98 + rms*.02; return; }
          active = true; frames = pre; pre = []; samples = frames.reduce((n,b) => n+b.length, 0);
          voiced = block.length; quiet = 0; captured = capture(); progress('Hearing you…');
        } else {
          frames.push(block); samples += block.length;
          if (speech) { voiced += block.length; quiet = 0; } else quiet += block.length;
        }
        if (quiet >= rate*1.0) upload();
        else if (samples >= rate*9) {
          // Do not execute a truncated instruction or its trailing fragment.
          reset(); drain = true; progress('That was too long. Pause, then try a shorter command.');
        }
      };
      for (const track of stream.getTracks()) track.onended = () => {
        if (current === session) { failed('Microphone disconnected. Start listening again.'); stop(); }
      };
      context.onstatechange = () => {
        if (current === session && context.state === 'suspended') {
          failed('The browser paused the microphone. Start listening again.'); stop();
        }
      };
      progress('Listening — pause briefly after each command.');
    } catch (error) {
      if (current === session) {
        failed(error.name === 'NotAllowedError' ? 'Microphone access was denied. Allow it in browser settings.' : 'Could not start continuous listening. Check your microphone or use Speak.');
        stop();
      }
    } finally { if (current !== session) session.abort(); }
  }
  function continuousBrowser(session, heard, failed, progress, capture) {
    const Recognition = constructor();
    if (!Recognition) { failed('Continuous speech is unavailable here. Use local transcription or a supported browser.'); stop(); return; }
    let recognition, restart;
    session.abort = () => { clearTimeout(restart); recognition?.abort(); };
    const begin = () => {
      if (current !== session) return;
      recognition = new Recognition();
      const instance = recognition, delivered = new Set();
      let captured;
      recognition.lang = 'en-US'; recognition.continuous = true; recognition.interimResults = true;
      recognition.onspeechstart = () => { if (current === session) { captured = capture(); progress('Hearing you…'); } };
      recognition.onresult = event => {
        if (current !== session || recognition !== instance) return;
        for (let i = event.resultIndex; i < event.results.length; i++) {
          if (captured === undefined) captured = capture();
          if (event.results[i].isFinal && !delivered.has(i)) {
            delivered.add(i); deliver(session, event.results[i][0].transcript, captured, heard, progress); captured = undefined;
            if (current !== session) return;
            progress('Listening — pause briefly after each command.');
          }
        }
      };
      recognition.onerror = event => {
        if (current !== session || recognition !== instance || event.error === 'no-speech' || event.error === 'aborted') return;
        failed(event.error === 'not-allowed' ? 'Microphone access was denied. Allow it in browser settings.' :
          'The browser speech service stopped. Try again or use local transcription.'); stop();
      };
      recognition.onend = () => { if (current === session && recognition === instance) restart = setTimeout(begin, 250); };
      session.finish = () => recognition.stop();
      try { recognition.start(); progress('Listening — pause briefly after each command.'); }
      catch (_) { failed('Could not start the microphone. Try again.'); stop(); }
    };
    begin();
  }

  window.EurozoneSpeech = {
    supported: () => (!!constructor() || canRecord()) && window.isSecureContext,
    configure: options => { pairedHeaders = options?.headers || null; },
    finish: () => { if (current?.finish) current.finish(); else if (current?.stop) current.stop(); },
    stop,
    async startContinuous(heard, failed, finished, progress = () => {}, capture = () => null) {
      stop();
      const session = {abort:() => {}, finished, continuous:true}; current = session;
      const config = await configuration;
      if (current !== session) return;
      if (!window.isSecureContext) { failed('Use HTTPS or localhost for the microphone.'); stop(); return; }
      if (config.local && canRecord()) await continuousRecord(session, heard, failed, progress, capture);
      else continuousBrowser(session, heard, failed, progress, capture);
    },
    async start(heard, failed, finished, progress = () => {}) {
      stop();
      const waiting = {abort:() => {}, finished};
      current = waiting;
      const config = await configuration;
      if (current !== waiting) return;
      if (config.local && canRecord() && window.isSecureContext) {
        await record(waiting, heard, failed, progress); return;
      }
      current = null;
      const Recognition = constructor();
      if (!Recognition || !window.isSecureContext) {
        failed('Speech recognition is unavailable here. Try Chrome or Edge over HTTPS (or localhost), or type a command.');
        finished(); return;
      }
      const recognition = new Recognition();
      current = recognition;
      recognition.finished = finished;
      recognition.lang = 'en-US';
      recognition.continuous = false;
      recognition.interimResults = false;
      recognition.maxAlternatives = 1;
      let delivered = false;
      recognition.onresult = event => {
        if (current !== recognition || delivered) return;
        for (let i = event.resultIndex; i < event.results.length; i++) {
          if (event.results[i].isFinal) {
            delivered = true;
            heard(event.results[i][0].transcript.slice(0, 300));
            break;
          }
        }
      };
      recognition.onerror = event => {
        if (current !== recognition) return;
        const messages = {'not-allowed':'Microphone access was denied. Allow it in browser settings and try again.',
          'audio-capture':'No microphone is available.', 'network':'The browser’s speech service could not connect. Try again or type a command.',
          'no-speech':'No speech heard. Tap Speak and try again.', 'service-not-allowed':'This browser cannot use its speech service. Try Chrome or Edge, or type a command.'};
        if (event.error !== 'aborted') failed(messages[event.error] || 'Speech recognition failed. Try again or type a command.');
      };
      recognition.onend = () => {
        if (current !== recognition) return;
        clearTimeout(timer); current = null; finished();
      };
      try {
        recognition.start();
        timer = setTimeout(stop, 20000);
      } catch (_) { current = null; failed('Could not start the microphone. Try again.'); finished(); }
    }
  };
  document.addEventListener('visibilitychange', () => { if (document.hidden && !current?.continuous) stop(); });
  window.addEventListener('pagehide', stop);
})();
