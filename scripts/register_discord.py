"""Register Eurozone commands on a new Discord app without changing other commands."""
import argparse
import json
import os
import urllib.request
import urllib.error


def command():
    options = [
        dict(type=4, name='players', description='Total seats, including humans and bots', min_value=1, max_value=5),
        dict(type=4, name='bots', description='Bot seats within the total (leave at least one human seat)', min_value=0, max_value=4),
        dict(type=3, name='setup', description='Hand setup', choices=[dict(name=n,value=v) for n,v in [('Random 7 + 7','random'),('Draft cards','draw'),('Beginner without cards','beginner')]]),
        dict(type=4, name='extra', description='Extra cards per draft packet', min_value=0, max_value=10)]
    return dict(name='eurozone', description='Play board games with friends', type=1,
                integration_types=[0,1], contexts=[0,1,2],
                options=[dict(type=1,name='agricola',description='Create a browser-hosted Agricola table',options=options),
                         dict(type=1,name='activity',description='Play Agricola inside Discord',options=options)])


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--guild',help='Register in a test server first')
    parser.add_argument('--dry-run',action='store_true')
    args=parser.parse_args()
    payload=command()
    if args.guild:
        payload.pop('integration_types'); payload.pop('contexts')
    if args.dry_run:
        print(json.dumps(payload,indent=2)); return
    client=os.environ.get('DISCORD_CLIENT_ID'); token=os.environ.get('DISCORD_BOT_TOKEN')
    if not client or not token: parser.error('Set DISCORD_CLIENT_ID and DISCORD_BOT_TOKEN in the environment')
    path=f'/applications/{client}' + (f'/guilds/{args.guild}' if args.guild else '') + '/commands'
    request=urllib.request.Request('https://discord.com/api/v10'+path,data=json.dumps(payload).encode(),
                                   headers={'Authorization':'Bot '+token,'Content-Type':'application/json',
                                            'User-Agent':'EurozoneSetup/1.0'},method='POST')
    try:
        with urllib.request.urlopen(request,timeout=20) as response: result=json.load(response)
    except urllib.error.HTTPError as error:
        raise SystemExit(f'Discord command registration failed (HTTP {error.code}); check app credentials and permissions.')
    print('Registered /'+result['name']+' for '+('test server '+args.guild if args.guild else 'global use'))
    print(f'Install link: https://discord.com/oauth2/authorize?client_id={client}')


if __name__=='__main__': main()
