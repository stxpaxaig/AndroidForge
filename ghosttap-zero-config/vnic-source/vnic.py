"""Virtual Neural Intelligence Core: sparse virtual neural mesh and cognition runtime."""
from __future__ import annotations
import argparse, hashlib, json, math, os, platform, random, re, sqlite3, time, tracemalloc
from collections import Counter, deque
from dataclasses import dataclass, asdict
from pathlib import Path

REFERENCE = {
 "system":{"status":"ONLINE","uptime":"12d 14h 37m","cycle":6482773291,"performance_index_pct":99.7},
 "neural_activity":{"total_neurons":"8.7B","active_neurons":"8.4B","active_pct":96.6,"firing_rate_hz":412},
 "neural_mesh":{"neurons":"8.7B","synapses":"87.4B","propagation_speed_m_s":182,"latency_ms":0.8},
 "frequency_bands_pct":{"Delta (0.5–4 Hz)":3.2,"Theta (4–8 Hz)":7.8,"Alpha (8–12 Hz)":12.1,"Beta (12–30 Hz)":46.7,"Gamma (30–100 Hz)":30.2},
 "synaptic_dynamics":{"total":"87.4B","active":"83.1B","active_pct":95.1,"plasticity_rate_sec":12.8,"strength_variance_pct":72.4},
 "memory":{"total_tb":42.6,"distribution_pct":{"Episodic":22.1,"Semantic":34.7,"Procedural":27.3,"Working":15.9},"knowledge_integrity_pct":99.8,"recall_speed_ms":1.4},
 "processing_pipeline":{"efficiency_pct":99.5,"stages":["Interpretation","Reasoning","Decision-Making","Action Planning","Feedback Loop"]},
 "learning":{"adaptation_rate_pct":97.3,"retention_pct":99.1,"improvement_pct":24.7},
 "network_layers":{"Input Layer":"98.7M","Perception":"734M","Association":"1.2B","Reasoning":"1.8B","Memory":"2.1B","Decision":"724M","Output Layer":"377M"},
 "signal_flow":{"throughput_gb_s":728,"data_processing_pct":99.8},
 "system_metrics":{"cpu_neural_load_pct":98.9,"memory_usage_pct":94.6,"synaptic_traffic_gb_s":712,"power_usage_w":47.8,"temperature_c":36.4,"stability_pct":99.9,"overall_performance_pct":99.7},
 "core_structures":["Pattern Recognition","Concept Mapping","Knowledge Graph","Emotional Model","Context Builder","Predictive Engine"]
}

@dataclass
class Config:
    data_dir: str = os.environ.get("VNIC_DATA_DIR", "data")
    simulation_units: int = int(os.environ.get("VNIC_SIM_UNITS", "4096"))
    fanout: int = 12
    learning_rate: float = 0.035
    seed: int = 731991
    recall_limit: int = 12

class SparseNeuralMesh:
    """Billions-scale logical mesh represented by a bounded sparse active simulation."""
    def __init__(self, units=4096, fanout=12, seed=731991, learning_rate=.035):
        if units < 32: raise ValueError("simulation_units must be >= 32")
        self.logical_neurons, self.logical_synapses = 8_700_000_000, 87_400_000_000
        self.units, self.fanout, self.lr = units, min(fanout, units-1), learning_rate
        self.rng = random.Random(seed)
        self.edges = []
        for i in range(units):
            targets = set()
            while len(targets) < self.fanout:
                j = self.rng.randrange(units)
                if j != i: targets.add(j)
            self.edges.append({j:self.rng.uniform(.05,.35) for j in targets})
        self.activation = [0.0]*units
        self.threshold = [.42+self.rng.random()*.25 for _ in range(units)]
        self.firings, self.propagations, self.plasticity = 0, 0, 0
        self.recent = deque(maxlen=10000)

    def activate(self, signal, strength=1.0):
        t0=time.perf_counter()
        digest=hashlib.blake2b(str(signal).encode(),digest_size=32).digest()
        seeds=[int.from_bytes(digest[i:i+4],"big")%self.units for i in range(0,32,4)]
        frontier=set(seeds); fired=set()
        for i in seeds: self.activation[i]=min(1,self.activation[i]*.25+.75*strength)
        for _ in range(3):
            nxt=set()
            for src in frontier:
                value=self.activation[src]
                if value>=self.threshold[src]:
                    fired.add(src); self.firings+=1; self.recent.append(time.time())
                    for dst,w in self.edges[src].items():
                        self.activation[dst]=min(1,self.activation[dst]+value*w*.22)
                        nxt.add(dst); self.propagations+=1
            frontier=nxt
            if not frontier: break
        return {"seed_units":len(seeds),"fired_units":len(fired),
                "propagation_events":self.propagations,
                "latency_ms":(time.perf_counter()-t0)*1000,
                "active_fraction":len(fired)/self.units}

    def reinforce(self,reward=1.0):
        delta=self.lr*max(-1,min(1,reward)); updates=0
        for row in self.edges[:min(256,self.units)]:
            for j,w in row.items():
                row[j]=min(1,max(.001,w+delta*(.5-abs(w-.5)))); updates+=1
        self.plasticity+=updates
        return updates

    def snapshot(self):
        now=time.time()
        return {"logical_neurons":self.logical_neurons,"logical_synapses":self.logical_synapses,
                "materialized_units":self.units,"materialized_sparse_synapses":sum(map(len,self.edges)),
                "active_materialized_units":sum(x>=.42 for x in self.activation),
                "firing_events_last_second":sum(now-t<=1 for t in self.recent),
                "total_firing_events":self.firings,"propagation_events":self.propagations,
                "plasticity_updates":self.plasticity}

class MemoryStore:
    """Persistent episodic, semantic, procedural and working memory plus a concept graph."""
    TYPES=("episodic","semantic","procedural","working")
    def __init__(self,directory):
        Path(directory).mkdir(parents=True,exist_ok=True)
        self.db=sqlite3.connect(str(Path(directory)/"vnic.sqlite3"),check_same_thread=False)
        self.db.row_factory=sqlite3.Row
        self.db.executescript("""
        PRAGMA journal_mode=WAL;
        CREATE TABLE IF NOT EXISTS memories(id INTEGER PRIMARY KEY,kind TEXT,content TEXT,metadata TEXT,created REAL,strength REAL,accesses INTEGER DEFAULT 0);
        CREATE TABLE IF NOT EXISTS concepts(name TEXT PRIMARY KEY,description TEXT,updated REAL);
        CREATE TABLE IF NOT EXISTS relations(source TEXT,relation TEXT,target TEXT,weight REAL,PRIMARY KEY(source,relation,target));
        CREATE TABLE IF NOT EXISTS procedures(name TEXT PRIMARY KEY,steps TEXT,successes INTEGER,failures INTEGER,updated REAL);
        """); self.db.commit()

    def remember(self,kind,content,metadata=None,strength=1.0):
        kind=kind.lower()
        if kind not in self.TYPES: raise ValueError(f"kind must be one of {self.TYPES}")
        cur=self.db.execute("INSERT INTO memories(kind,content,metadata,created,strength) VALUES(?,?,?,?,?)",
            (kind,str(content),json.dumps(metadata or {}),time.time(),float(strength)))
        self.db.commit(); return cur.lastrowid

    def recall(self,query,limit=12,kind=None):
        terms=list(dict.fromkeys(re.findall(r"[a-z0-9_]{2,}",query.lower())))[:12]
        if not terms:return []
        sql="SELECT * FROM memories WHERE ("+" OR ".join("lower(content) LIKE ?" for _ in terms)+")"
        args=[f"%{t}%" for t in terms]
        if kind:sql+=" AND kind=?";args.append(kind)
        sql+=" ORDER BY strength DESC,created DESC LIMIT ?";args.append(limit)
        rows=self.db.execute(sql,args).fetchall()
        for row in rows:self.db.execute("UPDATE memories SET accesses=accesses+1 WHERE id=?",(row["id"],))
        self.db.commit();return [dict(x) for x in rows]

    def concept(self,name,description=""):
        self.db.execute("INSERT INTO concepts VALUES(?,?,?) ON CONFLICT(name) DO UPDATE SET description=excluded.description,updated=excluded.updated",
                        (name.lower(),description,time.time()));self.db.commit()

    def relate(self,a,relation,b,weight=1):
        self.concept(a);self.concept(b)
        self.db.execute("INSERT INTO relations VALUES(?,?,?,?) ON CONFLICT(source,relation,target) DO UPDATE SET weight=MIN(1,weight+.05)",
                        (a.lower(),relation,b.lower(),weight));self.db.commit()

    def graph(self,name,limit=30):
        return [dict(r) for r in self.db.execute("SELECT * FROM relations WHERE source=? OR target=? ORDER BY weight DESC LIMIT ?",
                                                (name.lower(),name.lower(),limit)).fetchall()]

    def save_procedure(self,name,steps,success):
        row=self.db.execute("SELECT successes,failures FROM procedures WHERE name=?",(name,)).fetchone()
        s=(row["successes"] if row else 0)+int(success);f=(row["failures"] if row else 0)+int(not success)
        self.db.execute("INSERT INTO procedures VALUES(?,?,?,?,?) ON CONFLICT(name) DO UPDATE SET steps=excluded.steps,successes=excluded.successes,failures=excluded.failures,updated=excluded.updated",
                        (name,json.dumps(steps),s,f,time.time()));self.db.commit()

class LearningEngine:
    def __init__(self,memory,mesh):
        self.memory,self.mesh=memory,mesh;self.events=self.success=self.failure=0;self.loss=1.0
    def feedback(self,reward,context=None):
        reward=max(0,min(1,float(reward)));self.events+=1
        self.success+=reward>=.7;self.failure+=reward<.7
        self.loss=.92*self.loss+.08*(1-reward)
        updates=self.mesh.reinforce((reward-.5)*2)
        self.memory.remember("episodic",f"Feedback reward={reward:.3f}",
                             {"context":context or {},"synaptic_updates":updates},.8)
        return {"reward":reward,"synaptic_updates":updates,"events":self.events}
    def metrics(self):
        return {"feedback_events":self.events,"successes":self.success,"failures":self.failure,
                "measured_retention_pct":100*(self.success+1)/(self.events+2),
                "measured_loss":self.loss,"measured_improvement_pct":max(0,(1-self.loss)*100)}

class ToolRegistry:
    """Explicit allow-list: the core never runs arbitrary generated shell/code."""
    def __init__(self):self.tools={}
    def register(self,name,fn,description=""):
        if not callable(fn):raise TypeError("tool must be callable")
        self.tools[name]=(fn,description)
    def execute(self,name,arguments=None):
        if name not in self.tools:raise KeyError(f"Unregistered tool: {name}")
        return self.tools[name][0](**(arguments or {}))
    def describe(self):return {k:v[1] for k,v in self.tools.items()}

class VirtualNeuralIntelligenceCore:
    """Integrated observe -> interpret -> reason -> decide -> plan -> act -> evaluate -> learn loop."""
    def __init__(self,config=None):
        self.config=config or Config()
        self.memory=MemoryStore(self.config.data_dir)
        self.mesh=SparseNeuralMesh(self.config.simulation_units,self.config.fanout,
                                   self.config.seed,self.config.learning_rate)
        self.learning=LearningEngine(self.memory,self.mesh);self.tools=ToolRegistry()
        self.cycles=0;self.started=time.time();self.bytes_processed=0
        self._seed_knowledge()
        if not tracemalloc.is_tracing():tracemalloc.start()

    def _seed_knowledge(self):
        concepts={
          "neural mesh":"Sparse virtual neural population and synaptic graph.",
          "episodic memory":"Time-stamped experiences and events.",
          "semantic memory":"Facts, concepts and relationships.",
          "procedural memory":"Learned methods and reusable procedures.",
          "working memory":"Short-lived active context.",
          "plasticity":"Adaptive adjustment of synaptic connection weights.",
          "pattern recognition":"Detection of recurring structures in input.",
          "concept mapping":"Association of concepts and relationships.",
          "predictive engine":"Forecasting from learned patterns."}
        for k,v in concepts.items():self.memory.concept(k,v)

    def interpret(self,text):
        tokens=re.findall(r"[A-Za-z0-9_'-]+",text.lower());counts=Counter(tokens)
        intent="question" if "?" in text else "instruction" if re.search(r"\b(build|create|find|investigate|analyze|explain|compare|run|remember)\b",text,re.I) else "statement"
        return {"raw":text,"tokens":tokens,"keywords":[w for w,_ in counts.most_common(12)],
                "intent":intent,"length":len(text)}

    def process(self,text):
        t0=time.perf_counter();self.cycles+=1;self.bytes_processed+=len(text.encode())
        neural=self.mesh.activate(text);parsed=self.interpret(text)
        query=" ".join(parsed["keywords"]);memories=self.memory.recall(query,8)
        graph=[]
        for word in parsed["keywords"][:6]:graph.extend(self.memory.graph(word,5))
        confidence=min(.95,.25+.08*len(memories)+.035*len(graph))
        if parsed["intent"]=="statement":action="store_context"
        elif memories or graph:action="answer_from_context"
        else:action="research_required"
        plan=[{"stage":"interpretation","status":"complete"},
              {"stage":"reasoning","memories":len(memories),"associations":len(graph)},
              {"stage":"decision-making","selected_action":action},
              {"stage":"action-planning","status":"complete"}]
        if action=="store_context":
            self.memory.remember("working",text,{"cycle":self.cycles},.65)
            outcome={"type":"stored","message":"Added to working memory."}
        elif action=="answer_from_context":
            outcome={"type":"context","memories":memories,"associations":graph}
        else:
            outcome={"type":"research_required","message":"Local evidence is insufficient; connect an authorized research adapter."}
        plan.append({"stage":"feedback-loop","status":"awaiting_feedback"})
        self.mesh.activation=[x*.985 for x in self.mesh.activation]
        return {"cycle":self.cycles,"observation":text,"interpretation":parsed,
                "reasoning":{"confidence":confidence,"memories":memories,"associations":graph},
                "decision":{"selected_action":action,"confidence":confidence},
                "plan":plan,"outcome":outcome,"neural_activity":neural,
                "latency_ms":(time.perf_counter()-t0)*1000}

    def remember(self,kind,content,metadata=None):return self.memory.remember(kind,content,metadata)
    def recall(self,query,limit=None,kind=None):return self.memory.recall(query,limit or self.config.recall_limit,kind)
    def learn(self,reward,context=None):return self.learning.feedback(reward,context)
    def register_tool(self,name,fn,description=""):self.tools.register(name,fn,description)
    def execute_tool(self,name,arguments=None):return self.tools.execute(name,arguments)
    def status(self):
        current,peak=tracemalloc.get_traced_memory();mesh=self.mesh.snapshot()
        return {"reference_profile":REFERENCE,
          "reference_profile_notice":"Exact target analytics profile. Values such as watts, temperature, TB, and billion-scale throughput are reference specifications, not measured host-hardware telemetry.",
          "runtime_observed":{"status":"ONLINE","uptime_seconds":round(time.time()-self.started,3),
            "python_version":platform.python_version(),"platform":platform.platform(),
            **mesh,"processed_input_bytes":self.bytes_processed,"cognition_cycles":self.cycles,
            "tracemalloc_current_bytes":current,"tracemalloc_peak_bytes":peak,
            "memory_records":self.memory.db.execute("SELECT COUNT(*) FROM memories").fetchone()[0],
            "knowledge_concepts":self.memory.db.execute("SELECT COUNT(*) FROM concepts").fetchone()[0],
            "knowledge_relations":self.memory.db.execute("SELECT COUNT(*) FROM relations").fetchone()[0],
            "learning":self.learning.metrics(),"registered_tools":self.tools.describe()}}
    def export_status(self,path):Path(path).write_text(json.dumps(self.status(),indent=2),encoding="utf8")
    def close(self):self.memory.db.close()
    def __enter__(self):return self
    def __exit__(self,*_):self.close()

def main():
    p=argparse.ArgumentParser(description="Virtual Neural Intelligence Core");sp=p.add_subparsers(dest="cmd")
    sp.add_parser("status")
    q=sp.add_parser("process");q.add_argument("text")
    q=sp.add_parser("remember");q.add_argument("kind",choices=MemoryStore.TYPES);q.add_argument("content")
    q=sp.add_parser("recall");q.add_argument("query")
    q=sp.add_parser("learn");q.add_argument("reward",type=float)
    q=sp.add_parser("simulate");q.add_argument("--steps",type=int,default=100)
    a=p.parse_args()
    with VirtualNeuralIntelligenceCore() as core:
        if a.cmd in (None,"status"):out=core.status()
        elif a.cmd=="process":out=core.process(a.text)
        elif a.cmd=="remember":out={"memory_id":core.remember(a.kind,a.content)}
        elif a.cmd=="recall":out=core.recall(a.query)
        elif a.cmd=="learn":out=core.learn(a.reward)
        else:
            for i in range(a.steps):core.mesh.activate(f"simulation event {i}")
            out=core.status()["runtime_observed"]
        print(json.dumps(out,indent=2,default=str))

if __name__=="__main__":main()
