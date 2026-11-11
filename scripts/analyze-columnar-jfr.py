#!/usr/bin/env python3
"""Analyze `jfr print --json` exports. Intersections are correlation, not causation."""
import collections, datetime, json, math, pathlib, re, sys

def seconds(s):
    if isinstance(s, (int,float)): return s
    m=re.fullmatch(r'PT(?:(\d+)H)?(?:(\d+)M)?([\d.]+)?S?',s)
    if not m: raise ValueError(s)
    return int(m[1] or 0)*3600+int(m[2] or 0)*60+float(m[3] or 0)

def instant(s): return datetime.datetime.fromisoformat(s.replace('Z','+00:00')).timestamp()
def span(v):
    a=instant(v['startTime']); return a,a+seconds(v.get('duration','PT0S'))
def overlap(a,b): return a[0]<b[1] and b[0]<a[1]
def percentile(xs,p): return sorted(xs)[math.ceil(len(xs)*p)-1] if xs else None

def analyze(path):
    events=json.loads(path.read_text())['recording']['events']
    by=collections.defaultdict(list)
    for e in events: by[e['type']].append(e['values'])
    req=by['featuredag.Request']; spans=[span(v) for v in req]
    if not req: raise ValueError('No measured requests')
    window=(min(x[0] for x in spans),max(x[1] for x in spans))
    by={k:[v for v in vs if overlap(span(v),window) or window[0]<=span(v)[0]<=window[1]] for k,vs in by.items()}
    durations=[seconds(v['duration'])*1000 for v in req]; p95=percentile(durations,.95)
    slow=[span(v) for v,d in zip(req,durations) if d>=p95]
    out={'requests':len(req),'request_event_p95_ms':p95,'slow_requests':len(slow),'events':{k:len(v) for k,v in by.items()}}
    for label,key in [('gc','jdk.GarbageCollection'),('pause','jdk.GCPhasePause'),('jit','jdk.Compilation')]:
        vs=by.get(key,[]); dur=[seconds(v['duration'])*1000 for v in vs]
        out[label]={'count':len(vs),'total_ms':sum(dur),'max_ms':max(dur,default=0),'slow_overlap':sum(any(overlap(s,span(v)) for v in vs) for s in slow),'all_overlap':sum(any(overlap(s,span(v)) for v in vs) for s in spans)}
    out['gc']['sum_pauses_ms']=sum(seconds(v.get('sumOfPauses','PT0S'))*1000 for v in by.get('jdk.GarbageCollection',[]))
    out['jit']['compiler_counts']=dict(collections.Counter(v.get('compiler') for v in by.get('jdk.Compilation',[])))
    compiled=collections.Counter()
    for v in by.get('jdk.Compilation',[]):
        m=v['method']; name=m['type']['name'].replace('/','.')+'.'+m['name']
        compiled[name]+=seconds(v['duration'])*1000
    out['jit']['top_methods_ms']=compiled.most_common(12)
    loads=by.get('jdk.CPULoad',[])
    out['cpu_load_means']={k:sum(v[k] for v in loads)/len(loads) for k in ['jvmUser','jvmSystem','machineTotal']} if loads else {}
    leaves=collections.Counter(); app=collections.Counter(); buckets=collections.Counter();slow_buckets=collections.Counter()
    for v in by.get('jdk.ExecutionSample',[]):
        frames=(v.get('stackTrace') or {}).get('frames',[])
        methods=[f['method']['type']['name'].replace('/','.')+'.'+f['method']['name'] for f in frames]
        if not methods: continue
        leaves[methods[0]]+=1
        first=next((m for m in methods if m.startswith('com.example.featuredag.')),None)
        if first: app[first]+=1
        if any('FeatureInputDecoder.' in m for m in methods): bucket='decode'
        elif any('FeatureOutputEncoder.' in m or 'ExternalValueMaterializer.' in m or 'GenerateResult.' in m or 'encodeColumnarResult' in m or 'lambda$executeAndEncodeOnline' in m for m in methods): bucket='encode'
        elif any('DagRuntime.' in m for m in methods): bucket='runtime'
        else: bucket='other'
        buckets[bucket]+=1
        t=instant(v['startTime'])
        if any(a<=t<=b for a,b in slow): slow_buckets[bucket]+=1
    out['execution_samples']={'buckets':dict(buckets),'slow_buckets':dict(slow_buckets),'top_leaf':leaves.most_common(15),'top_application_frame':app.most_common(15)}
    allocated=collections.Counter()
    for v in by.get('jdk.ObjectAllocationSample',[]):
        allocated[v['objectClass']['name'].replace('/','.')]+=v['weight']
    out['sampled_allocation_weight_by_class']=allocated.most_common(12)
    grouped=collections.defaultdict(list)
    for v,d in zip(req,durations): grouped[v['inputIndex']].append(d)
    out['input_groups']={k:{'count':len(v),'p50_ms':percentile(v,.5),'p95_ms':percentile(v,.95)} for k,v in grouped.items()}
    windows=[]
    for offset in range(0,30,10):
        w=(window[0]+offset,window[0]+offset+10)
        ds=[d for v,d in zip(req,durations) if w[0]<=span(v)[0]<w[1]]
        windows.append({'offset_seconds':offset,'requests':len(ds),'p50_ms':percentile(ds,.5),'p95_ms':percentile(ds,.95),
                        'jit_count':sum(w[0]<=span(v)[0]<w[1] for v in by.get('jdk.Compilation',[]))})
    out['ten_second_windows']=windows
    return out

if __name__=='__main__':
    for name in sys.argv[1:]:
        p=pathlib.Path(name);result=analyze(p)
        out=p.with_suffix('.summary.json');out.write_text(json.dumps(result,indent=2)+'\n');print(out)
