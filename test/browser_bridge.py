"""Black-box helpers for advanced-optimized JS, using the real EDN wire format."""
import json
import subprocess
from browser_smoke import ROOT

# Capture only the public standalone transport API, not private compiled symbols.
TRANSPORT_PROBE = '''(() => {
 let peer;
 Object.defineProperty(window,'EurozonePeer',{configurable:true,get:()=>peer,set:value=>{
   peer=value;const start=value.start;
   value.start=options=>{
     const welcome=options.onWelcome;
     options.onWelcome=message=>{window.testPeerIdentity=message;welcome(message);};
     const transport=start(options);window.testPeerTransport=transport;return transport;
   };
 }});
 const WorkerOriginal=window.Worker;window.testWorkers=[];window.botWorkerErrors=[];
 window.Worker=class extends WorkerOriginal {constructor(...args){super(...args);window.testWorkers.push(this);this.addEventListener('error',e=>window.botWorkerErrors.push(e.message));}};
})()'''

class Bridge:
    def __enter__(self):
        self.process=subprocess.Popen(['clojure','-M','test/eurozone/browser_bot_bridge.clj'],cwd=ROOT,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
        return self
    def request(self,op,text,choose=False):
        self.process.stdin.write(json.dumps({'op':op,'text':text,'choose':choose})+'\n');self.process.stdin.flush()
        line=self.process.stdout.readline()
        if not line:raise RuntimeError('Test bridge exited: '+self.process.stderr.read())
        result=json.loads(line)
        if isinstance(result,dict) and 'bridge-error' in result:raise AssertionError(result)
        return result
    def __exit__(self,*args):
        self.process.terminate();self.process.wait(timeout=10)
    def status(self,child,choose=True):
        saved=child.evaluate("() => EurozonePeer.read(new URL(location.href).searchParams.get('peer'))")
        result=self.request('status',saved,choose)
        assert not child.evaluate('botWorkerErrors'),child.evaluate('botWorkerErrors')
        result.update(child.evaluate("() => ({worker: testWorkers.length>0,error:document.querySelector('[role=alert]')?.textContent||null})"))
        return result
    def move(self,child,message):
        child.evaluate('message => testPeerTransport.send(testPeerIdentity.host,message)',message)
