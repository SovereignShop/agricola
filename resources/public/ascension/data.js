/* Shared original prototype content, used by browser and server. */
(function(root){
const defs=[
['Expand the Demesne','development','♜',2,'Place 2 estates in counties where you already hold land.','estate',2],
['A New Foothold','development','⚑',3,'Place 2 estates anywhere with development capacity.','estate',2],
['Fortify the Frontier','development','♜',4,'Build 1 castle. It provides manpower and costs 1 gold each income phase.','castle',1],
['Patron of the Arts','improvement','❀',4,'Gain 2 legitimacy.','legitimacy',2],
['The Great Aqueduct','improvement','∩',6,'After 2 round changes: gain 3 legitimacy and +1 income.','project',3],
['A Golden Promise','influence','✧',4,'Gain the loyalty of a neutral court character. Choose their card after playing.','loyalty',1],
['Audience with the Patriarch','influence','☩',2,'Gain 1 legitimacy through a public blessing.','legitimacy',1],
['Border Campaign','aggression','⚔',4,'At stability 8 or lower: spend 2 manpower, roll 4+ to replace 2 rival estates. Resolve on the map.','attack',2],
['Restore the Granaries','improvement','♧',4,'Gain 1 legitimacy and raise stability by 1.','restore',1],
['Merchant Charter','development','⚓',3,'Gain a permanent +2 gold income.','income',2],
['An Imperial Marriage','influence','❦',5,'Gain 2 legitimacy and 1 estate.','marriage',2],
['The University','improvement','✎',7,'After 2 round changes: gain 4 legitimacy. Requires 5 power.','project',4],
['Mustering Grounds','development','⚑',2,'Gain 3 manpower, up to your current power.','manpower',3],
['A Daring Usurpation','aggression','⚔',5,'At stability 8 or lower: roll 4+ to replace a rival estate. Gain 2 legitimacy on success.','usurp',1],
['Public Games','improvement','☀',3,'Gain 1 legitimacy and 2 gold.','games',1],
['Provincial Survey','development','⌘',2,'Place 1 estate in any county with room.','estate',1]
].map((d,i)=>({id:i,name:d[0],type:d[1],icon:d[2],cost:d[3],text:d[4],effect:d[5],amount:d[6]}));
const npcs=[['Theodora','Patriarch',2,'Loyal patron gains +1 legitimacy when pledged.','☩'],['Manuel','Grand Admiral',3,'Loyal patron gains +1 gold each income.','⚓'],['Anna','Court Scholar',1,'Loyal patron gains +2 gold when pledged.','✎'],['Nikephoros','Strategos',3,'Loyal patron gains +1 manpower each income.','⚔']];
const counties=[['Philippopolis',170,160,0],['Adrianople',275,205,0],['Byzantion',365,245,0],['Heraclea',490,160,1],['Gangra',590,205,1],['Sinope',660,132,1],['Amaseia',735,245,2],['Trebizond',865,160,2],['Satala',884,295,2],['Ephesos',255,390,3],['Smyrna',365,365,3],['Miletos',340,490,3],['Iconium',570,365,4],['Tarsos',630,495,4],['Seleucia',760,435,4]];

const data={defs,npcs,counties};if(typeof module!=='undefined')module.exports=data;else root.AscensionData=data;
})(typeof window==='undefined'?globalThis:window);
