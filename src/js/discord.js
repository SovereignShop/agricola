import {DiscordSDK} from '@discord/embedded-app-sdk';

function progress(label) {
  window.EUROZONE_BOOT_STAGE=label;
  const message=document.querySelector('.loading p');
  if (message) message.textContent=label;
}
function bounded(promise, label, milliseconds=30000) {
  let timer;
  const deadline=new Promise((_,reject)=>{timer=setTimeout(()=>reject(new Error(label+' timed out. Close and reopen the Activity to retry.')),milliseconds);});
  return Promise.race([promise,deadline]).finally(()=>clearTimeout(timer));
}
async function json(url, options={}) {
  const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),20000);
  try {
    const response = await fetch(url, {...options,signal:controller.signal}), body = await response.json();
    if (!response.ok) throw new Error(body.error || 'Discord connection failed');
    return body;
  } catch (error) {
    if (error.name==='AbortError') throw new Error('The server took too long to respond. Please try again.');
    throw error;
  } finally {clearTimeout(timer);}
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
  progress('Connecting to Eurozone…');
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
  progress('Connecting to Discord…');
  await bounded(sdk.ready(), 'Connecting to Discord');
  progress('Signing in with Discord…');
  const authorization = await bounded(sdk.commands.authorize({client_id:config.clientId, response_type:'code', state:crypto.randomUUID(), prompt:'none', scope:['identify']}), 'Discord sign-in', 90000);
  const identity = await json('/discord/token', {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({code:authorization.code})});
  await bounded(sdk.commands.authenticate({access_token:identity.access_token}), 'Discord authentication');
  progress('Opening your Activity…');
  remember(identity.session);
  // List/join/create all verify Activity membership with Discord on the server.
  window.EUROZONE_ACTIVITY_TABLES = (action, room, requestId) => json('/discord/activity/tables', {method:'POST', headers:{'Content-Type':'application/json', Authorization:'Bearer ' + identity.session.auth}, body:JSON.stringify({instance:sdk.instanceId,action,room,requestId})});
  window.EUROZONE_SET_ACTIVITY_URL = table => {
    const url=new URL(location.href);
    if (table) {
      url.searchParams.set('peer',table.room);url.searchParams.set('key',table.invite);
      url.searchParams.set('transport','relay');url.hash=table.room;
    } else {
      for (const key of ['peer','key','transport']) url.searchParams.delete(key);
      url.hash='';
    }
    history.replaceState(null,'',url);
    window.EUROZONE_ACTIVITY_LOBBY=!table;
  };
  window.EUROZONE_ENTER_ACTIVITY_TABLE = table => window.EUROZONE_ACTIVITY_CHANGED(table);
  window.EUROZONE_ACTIVITY_HOME = () => window.EUROZONE_ACTIVITY_CHANGED(null);
  window.EUROZONE_ACTIVITY_LOBBY = !params.get('peer');
  if (!window.EUROZONE_ACTIVITY_LOBBY) {
    const table=await window.EUROZONE_ACTIVITY_TABLES('join',params.get('peer'));
    const unavailable=table['host?'] && table.initialized && !(await window.EurozonePeer.read(table.room));
    if (unavailable) {
      const url=new URL(location.href);
      for (const key of ['peer','key','transport']) url.searchParams.delete(key);
      url.hash='';history.replaceState(null,'',url);
      window.EUROZONE_ACTIVITY_LOBBY=true;
    } else {
      const url=new URL(location.href);
      url.searchParams.set('key',table.invite);url.searchParams.set('transport','relay');url.hash=table.room;
      history.replaceState(null,'',url);
    }
  }
  // Discord controls its outer fullscreen window. The app uses the entire frame.
  window.EUROZONE_OPEN_BROWSER = () => sdk.commands.openExternalLink({url:config.publicUrl});
  window.EUROZONE_INVITE = () => sdk.commands.openInviteDialog();
}
window.EUROZONE_READY = openDiscord();
// The CLJS app awaits this promise and presents a readable failure message.
window.EUROZONE_READY.catch(error => progress(error.message));
