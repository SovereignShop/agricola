// The browser and server load exactly the same pure rules engine and content.
const path=require('node:path'),readline=require('node:readline');
const base=path.resolve(__dirname,'../resources/public/ascension');
const {defs,npcs,counties}=require(path.join(base,'data.js'));
const engine=require(path.join(base,'engine.js'))(defs,npcs,counties);
readline.createInterface({input:process.stdin,crlfDelay:Infinity}).on('line',line=>{
 try {
  const m=JSON.parse(line);let value;
  if(m.op==='init')value=engine.init(m.players,m.bots,m.seed);
  else if(m.op==='step')value=engine.step(m.state,m.action);
  else if(m.op==='bot')value=engine.step(m.state,engine.choose(m.state));
  else throw Error('Unknown rules operation.');
  process.stdout.write(JSON.stringify({ok:true,value})+'\n');
 } catch(e){process.stdout.write(JSON.stringify({ok:false,error:e.message})+'\n');}
});
