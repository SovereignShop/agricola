"""Protocol/validation tests without spending any ChatGPT plan usage."""
import json
import threading
import unittest
from unittest.mock import MagicMock, patch
from connect import OpenAI, ConnectionError
from game_bridge import GameBridge, pairing_target, validate_result, validated_request


def packet():
    return {'id': 'one', 'version': 'revision-one', 'text': 'get some building materials',
            'context': {'actions': [{'space': 'wood', 'mode': 'place'}]}}


class BridgeTests(unittest.TestCase):
    def test_replaced_pairing_cannot_connect_late(self):
        connections = MagicMock()
        connections.models.return_value = [{'slug': 'test-model', 'name': 'Test model'}]
        bridge = GameBridge(connections)
        first, second = MagicMock(), MagicMock()
        with patch('game_bridge.websocket.WebSocketApp', side_effect=[first, second]) as factory, patch('game_bridge.threading.Thread.start'):
            bridge.pair('wss://game.example/assistant#' + 'a' * 64, 'alice', 'test-model')
            late_open = factory.call_args.kwargs['on_open']
            bridge.pair('wss://game.example/assistant#' + 'b' * 64, 'bob', 'test-model')
            late_open(first)
            first.send.assert_not_called()
            factory.call_args.kwargs['on_open'](second)
            self.assertEqual('b' * 64, json.loads(second.send.call_args.args[0])['pair'])
            self.assertEqual('bob', bridge.profile)
        bridge.close()

    def test_pairing_urls(self):
        secret = 'a' * 64
        self.assertEqual(('wss://game.example/assistant', secret), pairing_target('wss://game.example/assistant#' + secret))
        self.assertEqual(('ws://127.0.0.1:3001/assistant', secret), pairing_target('ws://127.0.0.1:3001/assistant#' + secret))
        for url in ['ws://game.example/assistant#' + secret, 'wss://user:pass@game.example/assistant#' + secret,
                    'wss://game.example/other#' + secret, 'wss://game.example/assistant?token=bad#' + secret,
                    'wss://game.example/assistant#bad']:
            with self.assertRaises(ValueError):
                pairing_target(url)

    def test_only_supplied_actions_can_be_proposed_never_confirmed(self):
        good = {'kind': 'action', 'space': 'wood', 'message': 'Forest has wood.', 'value': None, 'confidence': 'high', 'continue': False}
        self.assertEqual(good, validate_result(json.dumps(good), packet()))
        for bad in [dict(good, space='injected-action'), dict(good, kind='confirm'),
                    dict(good, message='x' * 1001), dict(good, confidence='certain'),
                    dict(good, **{'continue': 'yes'}), dict(good, value='3'), [], {'kind': 'action'}]:
            with self.assertRaises(ValueError):
                validate_result(json.dumps(bad), packet())
        answer = validate_result(json.dumps({'kind': 'answer', 'space': 'wood', 'message': 'Which material?', 'value': None, 'confidence': 'low', 'continue': False}), packet())
        self.assertIsNone(answer['space'])
        with self.assertRaises(ValueError):
            validated_request(dict(packet(), text='x' * 301))

    def test_input_controls_require_values_and_preserve_low_confidence(self):
        request = packet()
        request['context']['actions'] = [{'space': 'ui-12', 'mode': 'input', 'min': '0', 'max': '5'}]
        value = {'kind': 'action', 'space': 'ui-12', 'message': 'Set grain to 2.',
                 'value': '2', 'confidence': 'low', 'continue': True}
        self.assertEqual(value, validate_result(json.dumps(value), request))
        for bad in (None, 2, 'x' * 301):
            with self.assertRaises(ValueError):
                validate_result(json.dumps(dict(value, value=bad)), request)

    def test_structured_response_uses_plan_route_and_requires_completion(self):
        value = {'kind': 'action', 'space': 'wood', 'message': 'Take wood at Forest.', 'value': None, 'confidence': 'high', 'continue': False}
        event = {'type': 'response.completed', 'response': {'output': [
            {'content': [{'type': 'output_text', 'text': json.dumps(value)}]}]}}
        response = MagicMock(status_code=200)
        response.__enter__.return_value = response
        response.iter_lines.return_value = [b'data: ' + json.dumps(event).encode(), b'']
        with patch('connect.requests.post', return_value=response) as post:
            self.assertEqual(value, OpenAI().interpret('secret', 'test-model', packet()))
            args = post.call_args.kwargs
            self.assertFalse(args['json']['store'])
            self.assertTrue(args['json']['stream'])
            schema = args['json']['text']['format']['schema']
            self.assertEqual(['wood', None], schema['properties']['space']['enum'])
            self.assertNotIn('secret', json.dumps(args['json']))
            response.iter_lines.return_value = [b'data: {"type":"response.failed"}', b'']
            with self.assertRaises(ConnectionError):
                OpenAI().interpret('secret', 'test-model', packet())

    def test_disconnect_drops_inflight_result_and_does_not_switch_accounts(self):
        connections = MagicMock()
        entered, release = threading.Event(), threading.Event()
        def interpret(profile, model, request):
            self.assertEqual(('alice', 'alice-model'), (profile, model))
            entered.set(); release.wait(3)
            return {'kind': 'action', 'space': 'wood', 'message': 'Forest'}
        connections.interpret.side_effect = interpret
        bridge = GameBridge(connections)
        socket = MagicMock()
        bridge.socket = socket
        bridge.busy.acquire()
        thread = threading.Thread(target=bridge.answer, args=(socket, 0, 'alice', 'alice-model', packet()))
        thread.start()
        self.assertTrue(entered.wait(2))
        bridge.close()
        release.set(); thread.join(3)
        socket.send.assert_not_called()
        self.assertFalse(bridge.busy.locked())
