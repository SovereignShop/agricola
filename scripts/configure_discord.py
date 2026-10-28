"""Configure the documented bot-authenticated app settings; never log credentials.

Run through with_discord_env.py. Activity enablement, URL mappings, redirects,
tester invitations and installation approval remain Developer Portal actions.
"""
import argparse
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--guild')
    args = parser.parse_args()
    client = os.environ.get('DISCORD_CLIENT_ID')
    token = os.environ.get('DISCORD_BOT_TOKEN')
    origin = os.environ.get('EUROZONE_PUBLIC_URL', '').rstrip('/')
    if not client or not token or not origin.startswith('https://'):
        parser.error('Configure the application ID, bot token and public HTTPS URL first')
    if os.environ.get('EUROZONE_PRIVATE') != 'true' or not os.environ.get('DISCORD_ALLOWED_USER_IDS', '').strip():
        parser.error('This private setup requires private mode and a user allowlist')

    def request(method, path, payload=None):
        req = urllib.request.Request('https://discord.com/api/v10' + path,
            data=json.dumps(payload).encode() if payload is not None else None,
            headers={'Authorization': 'Bot ' + token, 'Content-Type': 'application/json',
                     'User-Agent': 'EurozoneSetup/1.0'}, method=method)
        try:
            with urllib.request.urlopen(req, timeout=30) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            raise SystemExit(f'Discord returned HTTP {error.code} for {method} {path}; credentials were not logged.')

    app = request('GET', '/applications/@me')
    if app['id'] != client or app.get('verify_key') != os.environ.get('DISCORD_PUBLIC_KEY'):
        parser.error('Application ID/public key do not match the bot token')
    contexts = dict(app.get('integration_types_config', {}))
    contexts['0'] = {'oauth2_install_params': {'scopes': ['applications.commands', 'bot'], 'permissions': '0'}}
    payload = {'integration_types_config': contexts,
               'install_params': {'scopes': ['applications.commands', 'bot'], 'permissions': '0'},
               'interactions_endpoint_url': origin + '/discord/interactions'}
    out = Path(__file__).resolve().parents[1] / 'data/discord-testing'
    out.mkdir(parents=True, exist_ok=True)
    fields = ['id', 'name', 'bot_public', 'flags', 'interactions_endpoint_url',
              'integration_types_config', 'install_params', 'redirect_uris']
    if args.apply:
        # Discord checks the endpoint's signed ping before accepting the update.
        snapshot = out / 'app-before-configuration.json'
        if not snapshot.exists():
            snapshot.write_text(json.dumps({key: app.get(key) for key in fields}, indent=2))
        request('PATCH', '/applications/@me', payload)
        def normalized(value, field=None):
            if isinstance(value, dict):
                return {k: normalized(v, k) for k, v in value.items()}
            if isinstance(value, list):
                return sorted(value) if field == 'scopes' else value
            return value
        # Portal/API updates can be briefly stale, and scope order has no meaning.
        for attempt in range(8):
            app = request('GET', '/applications/@me')
            mismatches = [key for key, value in payload.items()
                          if normalized(app.get(key), key) != normalized(value, key)]
            if not mismatches:
                break
            time.sleep(1)
        else:
            raise SystemExit('Discord has not yet confirmed: ' + ', '.join(mismatches))
    params = {'client_id': client, 'scope': 'bot applications.commands', 'permissions': '0', 'integration_type': '0'}
    if args.guild:
        params.update(guild_id=args.guild, disable_guild_select='true')
    result = {'applied': args.apply, 'app': {key: app.get(key) for key in fields},
              'planned_settings': payload,
              'install_url': 'https://discord.com/oauth2/authorize?' + urllib.parse.urlencode(params),
              'required_redirect': origin + '/discord/callback',
              'activity_url_mapping': {'prefix': '/', 'target': urllib.parse.urlsplit(origin).netloc}}
    if args.apply:
        (out / 'configured-app.json').write_text(json.dumps(result, indent=2))
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
