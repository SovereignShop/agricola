import {DiscordSDK} from '@discord/embedded-app-sdk';

async function json(url, options) {
  const response = await fetch(url, options), body = await response.json();
  if (!response.ok) throw new Error(body.error || 'Discord connection failed');
  return body;
}
function remember(session) {
  sessionStorage.setItem('eurozone-session', session.token);
  sessionStorage.setItem('eurozone-name', session.name.slice(0,40));
  window.EUROZONE_DISCORD_USER = session.userId;
  window.EUROZONE_DISCORD_AUTH = session.auth;
}
async function openDiscord() {
  const params = new URLSearchParams(location.search);
  const embedded = window.parent !== window && params.has('frame_id') && params.has('instance_id');
  const config = await json('/discord/config');
  if (!embedded && params.get('discord') !== '1' && !config.private) {
    window.EUROZONE_RTC_CONFIG = await json('/discord/rtc');
    return;
  }
  if (!config.enabled) throw new Error('Discord has not been configured on this Eurozone server yet.');
  if (!embedded) {
    const response = await fetch('/discord/session');
    if (response.status === 401) {
      location.replace('/discord/login?return=' + encodeURIComponent(location.pathname + location.search + location.hash));
      await new Promise(() => {});
    }
    if (!response.ok) throw new Error('Discord sign-in could not be restored.');
    remember(await response.json());
    window.EUROZONE_RTC_CONFIG = await json('/discord/rtc', {headers:{Authorization:'Bearer ' + window.EUROZONE_DISCORD_AUTH}});
    return;
  }
  window.EUROZONE_ACTIVITY = true;
  document.documentElement.classList.add('discord-activity');
  const sdk = new DiscordSDK(config.clientId);
  window.EUROZONE_DISCORD_SDK = sdk;
  await sdk.ready();
  const authorization = await sdk.commands.authorize({client_id:config.clientId, response_type:'code', state:crypto.randomUUID(), prompt:'none', scope:['identify']});
  const identity = await json('/discord/token', {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({code:authorization.code})});
  await sdk.commands.authenticate({access_token:identity.access_token});
  remember(identity.session);
  // Verify instance membership server-side using Discord's API, not a claimed channel ID.
  const table = await json('/discord/activity', {method:'POST', headers:{'Content-Type':'application/json', Authorization:'Bearer ' + identity.session.auth}, body:JSON.stringify({instance:sdk.instanceId})});
  const url = new URL(location.href);
  url.searchParams.set('peer', table.room); url.searchParams.set('key', table.invite);
  url.searchParams.set('transport','relay'); url.hash = table.room;
  history.replaceState(null,'',url);
  // Discord controls its outer fullscreen window. The app uses the entire frame.
  window.EUROZONE_OPEN_BROWSER = () => sdk.commands.openExternalLink({url:config.publicUrl});
  window.EUROZONE_INVITE = () => sdk.commands.openInviteDialog();
}
window.EUROZONE_READY = openDiscord();
// The CLJS app awaits this promise and presents a readable failure message.
window.EUROZONE_READY.catch(() => {});
