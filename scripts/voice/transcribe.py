"""Loopback CPU transcription worker. Audio is decoded in memory and never saved."""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import subprocess
import threading

import numpy as np
from faster_whisper import WhisperModel

MAX_BYTES = 2_000_000
VOCABULARY = ('Agricola voice commands: take wood, take clay, take reed, take grain, fishing, '
              'Forest, Clay Pit, Reed Bank, Grain Seeds, Day Laborer, Sheep Market, '
              'Western Quarry, Eastern Quarry, take vegetables, take boar, take cattle. Confirm. Cancel.')


def decode(data):
    result = subprocess.run(
        ['ffmpeg', '-nostdin', '-v', 'error', '-protocol_whitelist', 'pipe', '-i', 'pipe:0', '-t', '12.1',
         '-ac', '1', '-ar', '16000', '-f', 'f32le', 'pipe:1'],
        input=data, capture_output=True, timeout=10, check=False)
    if result.returncode or not result.stdout or len(result.stdout) % 4:
        raise ValueError('Could not read this audio recording. Try recording again.')
    audio = np.frombuffer(result.stdout, dtype=np.float32).copy()
    if len(audio) > 12 * 16000:
        raise ValueError('Keep voice commands shorter than 12 seconds.')
    return audio


def handler(model):
    busy = threading.Lock()

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def reply(self, status, value):
            body = json.dumps(value).encode()
            self.send_response(status)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Cache-Control', 'no-store')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            self.reply(200 if self.path == '/health' else 404,
                       {'ready': True} if self.path == '/health' else {'error': 'Not found'})

        def do_POST(self):
            # Browser requests go through the authenticated game server, never directly here.
            if self.path != '/transcribe' or self.headers.get('Origin'):
                return self.reply(403, {'error': 'Use the game server to transcribe audio.'})
            if not busy.acquire(blocking=False):
                return self.reply(429, {'error': 'Transcription is busy. Try again in a moment.'})
            try:
                self.connection.settimeout(15)
                length = int(self.headers.get('Content-Length', '0'))
                if not 0 < length <= MAX_BYTES:
                    return self.reply(413, {'error': 'Recording too large or empty.'})
                audio = decode(self.rfile.read(length))
                segments, _ = model.transcribe(audio, language='en', beam_size=3,
                    condition_on_previous_text=False, vad_filter=True,
                    initial_prompt=VOCABULARY,
                    vad_parameters={'min_speech_duration_ms': 100, 'min_silence_duration_ms': 300})
                text = ' '.join(segment.text.strip() for segment in segments).strip()[:300]
                self.reply(200, {'text': text})
            except (ValueError, subprocess.TimeoutExpired):
                self.reply(400, {'error': 'Could not decode a short audio recording. Try again.'})
            except Exception:
                self.reply(503, {'error': 'Local transcription failed. Try again.'})
            finally:
                busy.release()

    return Handler


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=59300)
    parser.add_argument('--model', default='base.en')
    args = parser.parse_args()
    print('Loading local speech model…', flush=True)
    model = WhisperModel(args.model, device='cpu', compute_type='int8', cpu_threads=4)
    server = ThreadingHTTPServer(('127.0.0.1', args.port), handler(model))
    print(f'Local transcription ready on 127.0.0.1:{server.server_port}', flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == '__main__':
    main()
