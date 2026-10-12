#!/usr/bin/env python3
"""Runs real API requests and writes raw answers plus mechanical checks for human review.
No synthetic success scores: connection failures are recorded as errors and exit nonzero.
"""
import argparse,json,re,time,urllib.request,urllib.error
from pathlib import Path

def checks(answer,sources):
    cited=set(re.findall(r"\[chunk:(\d+)\]",answer))
    allowed={str(s['chunkId']) for s in sources}
    return {"has_citations":bool(cited),"invalid_citations":sorted(cited-allowed),
            "retrieved_docs":sorted({s['docId'] for s in sources})}

def main():
    p=argparse.ArgumentParser(); p.add_argument('--base',required=True); p.add_argument('--cases',default='eval/cases.json'); p.add_argument('--output',default='eval/results.local.json'); args=p.parse_args()
    rows=[]; errors=0
    for case in json.loads(Path(args.cases).read_text()):
        start=time.monotonic()
        try:
            req=urllib.request.Request(args.base+case['path'],json.dumps(case['request']).encode(),{'Content-Type':'application/json'})
            with urllib.request.urlopen(req,timeout=300) as response: result=json.load(response)
            answer=result.get('insight',result.get('answer',result.get('comparison','')))
            sources=result.get('sources',result.get('sourcesA',[])+result.get('sourcesB',[]))
            row={**case,'result':result,'checks':checks(answer,sources),'human_review':{'correct':None,'supported':None,'actionable':None,'notes':''}}
        except (OSError,ValueError) as error:
            errors+=1; row={**case,'error':str(error)}
        row['latency_seconds']=round(time.monotonic()-start,3); rows.append(row)
    Path(args.output).write_text(json.dumps(rows,indent=2))
    print(f"Recorded {len(rows)} requests; {errors} errors. Human review required; no accuracy claim.")
    return bool(errors)
if __name__=='__main__': raise SystemExit(main())
