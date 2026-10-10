"""Archive full logs, statuses and bound test totals for a completed serial gate."""
import argparse,gzip,hashlib,json,re
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('prefix');p.add_argument('--root',type=Path,default=Path.cwd());a=p.parse_args()
work=a.root/'.agent-work/workflow-five';out=a.root/'docs/refactor/evidence/workflow-five-20261010'/a.prefix
completion=json.loads((work/(a.prefix+'-gate-complete.json')).read_text());out.mkdir(exist_ok=True)
def digest(b):return hashlib.sha256(b).hexdigest()
def archive(path):
 raw=path.read_bytes();dest=out/(path.name+'.gz');dest.write_bytes(gzip.compress(raw,mtime=0));return {'path':dest.name,'originalSha256':digest(raw),'gzipSha256':digest(dest.read_bytes())}
def totals(path):
 text=path.read_text();rows=[tuple(int(v or 0) for v in m) for m in re.findall(r'(?:Passed|Failed): Total (\d+), Failed (\d+), Errors (\d+), Passed (\d+)(?:, (?:Ignored|Skipped) (\d+))?',text)]
 assert rows and all(r[1]==r[2]==0 for r in rows),path
 return dict(zip(['total','failed','errors','passed','skipped'],map(sum,zip(*rows)))),len(rows)
receipt=dict(completion,archives={})
for path in sorted(work.glob(a.prefix+'-*')):
 if path.is_file() and (path.name.endswith('.log') or path.name.endswith('.log.meta.json') or path.name.endswith('.patch') or path.name.endswith('reference-scope.txt')):
  receipt['archives'][path.name]=archive(path)
for stage in ['final-checkAll','final-consumer']:
 log=work/(a.prefix+'-'+stage+'.log');meta=json.loads(log.with_suffix(log.suffix+'.meta.json').read_text());assert meta['exit_code']==0
 counts,cells=totals(log);receipt[stage]={'totals':counts,'moduleCells':cells}
(out/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
print(json.dumps({k:v for k,v in receipt.items() if k!='archives'},indent=2))
