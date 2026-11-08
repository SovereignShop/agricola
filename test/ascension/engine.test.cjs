const fs=require('fs'),vm=require('vm'),assert=require('node:assert/strict');
const {defs,npcs,counties}=require('../../resources/public/ascension/data.js'),E=require('../../resources/public/ascension/engine.js')(defs,npcs,counties);
const game=()=>E.init(3,2,42),advance=s=>E.step(s,{type:'advance'}),pass=s=>E.step(s,{type:'pass',actor:s.active});
let s=game();s=advance(advance(advance(s)));assert.equal(s.phase,3);let before=JSON.stringify(s);
assert.throws(()=>E.step(s,{type:'draft',actor:(s.active+1)%3,card:s.cards.find(c=>c.zone==='market').id}),/another claimant/);assert.equal(JSON.stringify(s),before);
let market=s.cards.find(c=>c.zone==='market');s=E.step(s,{type:'draft',actor:s.active,card:market.id});assert.equal(s.drafted.length,1);assert.throws(()=>advance(s),/Finish all turns/);s=pass(pass(s));assert.equal(s.phase,4);
let i=s.active,c=s.cards.find(c=>c.owner===i&&c.def===0),gold=s.players[i].gold;
assert.throws(()=>E.step(s,{type:'play',actor:i,card:c.id,targets:[14,14]}),/not legal/);
s=E.step(s,{type:'play',actor:i,card:c.id,targets:[i*3,i*3]});assert.equal(s.players[i].gold,gold-2);assert.equal(E.power(s,i),4);assert.equal(s.pieces.filter(p=>p.county===i*3).length,3);
// Neutral court target is mandatory and ownership changes automatically.
s.active=i;let loyalty=s.cards.find(c=>c.owner===i&&c.def===5);s=E.step(s,{type:'play',actor:i,card:loyalty.id,targets:[1]});assert.equal(s.loyal[1],i);assert.equal(E.power(s,i),7);
// Replay is deterministic from the exact saved state.
let copy=JSON.parse(JSON.stringify(s)),a=E.choose(s);assert.deepEqual(E.step(s,a),E.step(copy,a));
// Endgame is terminal, including collapse, with ties supported.
s.phase=5;s.stability=1;s.targets={power:100,legitimacy:100};s=advance(s);assert.ok(s.winner);assert.throws(()=>advance(s),/ended/);
// Test every card through native transitions with prerequisites and legal targets.
for(const d of defs){let t=game();t.phase=4;t.active=0;t.stability=6;t.players[0].gold=100;t.players[0].manpower=3;
for(let k=0;k<4;k++)t.pieces.push({id:'extra'+k,type:'estate',owner:0,county:13,x:0,y:0});
let card={id:'testcard',def:d.id,owner:0,zone:'hand'};t.cards.push(card);let ts=[];for(let k=0;k<E.count(card);k++)ts.push(E.targetsFor(t,card,ts)[0]);assert.equal(E.reason(t,card),'',d.name);let u=E.step(t,{type:'play',actor:0,card:card.id,targets:ts});assert.equal(u.cards.find(c=>c.id==='testcard').zone,'played',d.name);
if(d.effect==='estate')assert.equal(E.power(u,0),E.power(t,0)+d.amount);
if(d.effect==='castle')assert.equal(u.pieces.filter(p=>p.type==='castle').length,1);
if(d.effect==='legitimacy')assert.equal(u.players[0].legitimacy,t.players[0].legitimacy+d.amount);
if(d.effect==='project'){assert.equal(u.players[0].projects.length,1);u.phase=5;u=advance(u);assert.equal(u.players[0].projects[0].turns,1);u.phase=5;u=advance(u);assert.equal(u.players[0].projects.length,0);assert.equal(u.players[0].legitimacy,t.players[0].legitimacy+d.amount);}
if(d.effect==='loyalty')assert.equal(u.loyal[ts[0]],0);
if(d.effect==='income')assert.equal(u.players[0].income,2);
if(d.effect==='restore')assert.equal(u.stability,t.stability+1);
if(d.effect==='marriage'){assert.equal(E.power(u,0),E.power(t,0)+1);assert.equal(u.players[0].legitimacy,3);}
if(d.effect==='manpower')assert.equal(u.players[0].manpower,6);
if(d.effect==='games')assert.equal(u.players[0].legitimacy,2);
if(['attack','usurp'].includes(d.effect)){assert.ok(u.lastRoll>=1&&u.lastRoll<=6);for(let target of ts)assert.equal(u.pieces.find(p=>p.id===target).owner,u.lastRoll>=4?0:t.pieces.find(p=>p.id===target).owner);}
assert.equal(u.players[0].gold,100-d.cost+(d.effect==='games'?2:0)+(d.effect==='loyalty'&&ts[0]===2?2:0),d.name);}
let results={games:0,crown:0,collapse:0,turns:0,maxTurns:0};
for(let n=2;n<=4;n++)for(let seed=1;seed<=100;seed++){
 let s=E.init(n,n-1,seed),turns=0;
 while(!s.winner&&turns++<2000){let a=E.choose(s);assert.ok(a);s=E.step(s,a);assert.ok(s.players.every(p=>p.gold>=0&&p.legitimacy>=0&&p.manpower>=0));for(let county=0;county<15;county++){assert.ok(s.pieces.filter(p=>p.type==='estate'&&p.county===county).length<=4);assert.ok(s.pieces.filter(p=>p.type==='castle'&&p.county===county).length<=1);}assert.ok(s.players.every((p,i)=>p.manpower<=E.power(s,i)));}
 assert.ok(s.winner,`Unfinished ${n}/${seed}`);results.games++;results[s.winner.collapse?'collapse':'crown']++;results.turns+=turns;results.maxTurns=Math.max(results.maxTurns,turns);
}
console.log('PASS',results);
