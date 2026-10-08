"""Check exact Git inputs, baseline overlay, archived exits/totals and named mutation kills."""
import gzip,hashlib,json,re,subprocess,tempfile
from pathlib import Path
E=Path(__file__).resolve().parent
R=E.parents[3]
def sha(b):return hashlib.sha256(b).hexdigest()
def obj(commit,path):return subprocess.check_output(['git','show',commit+':'+path],cwd=R)
def totals(log):
 return [[int(v or 0) for v in row] for row in re.findall(
  r'(?:Passed|Failed): Total (\d+), Failed (\d+), Errors (\d+), Passed (\d+)(?:, (?:Skipped|Ignored) (\d+))?',log)]
q=json.loads((E/'qualification.json').read_text())
x=json.loads((E/'export.json').read_text())
assert x['source_sha']==q['source_sha'] and x['clean']
for path,value in x['changed_files_sha256'].items():assert sha(obj(q['source_sha'],path))==value,path
for a in json.loads((E/'archive.json').read_text()):
 b=(E/a['path']).read_bytes();b=gzip.decompress(b) if a['gzip'] else b
 assert sha(b)==a['raw_sha256'] and len(b)==a['raw_bytes'],a['path']
b=json.loads((E/'base-capture.json').read_text())
body=(E/'base-body.json').read_bytes()
assert len(body)==b['body_bytes']==29427 and sha(body)==b['body_sha256']
assert body==obj(q['source_sha'],'embed-bench/src/test/resources/storymodel4s/bench/video/historical-embedding-parity-v1.json')
ov=b['test_only_overlay'];assert ov['production_base']==q['base_sha']
assert ov['harness_commit']==q['source_sha'] and b['actual_exit']==0
for path,value in ov['test_only_overlay_sha256'].items():assert sha(obj(q['source_sha'],path))==value,path
base_meta=json.loads((E/'runs/base-capture-exact.log.meta.json').read_text())
assert base_meta['exit_code']==0 and base_meta['reason']=='exited'
mutations=json.loads((E/'mutations.json').read_text());assert len(mutations)==6
for m in mutations:
 assert m['compiled'] and m['qualified'] and m['exit']==1
 assert len(m['failures'])==1 and m['named_failure'] in m['failures'][0]
 assert m['totals'][-1][1:4]==[1,0,1]
 patch=gzip.decompress((E/('runs/mutations/'+m['name']+'.patch.gz')).read_bytes())
 log=gzip.decompress((E/('runs/mutations/'+m['name']+'.log.gz')).read_bytes())
 assert sha(patch)==m['patch_sha256'] and sha(log)==m['log_sha256']
 mutation_meta=json.loads((E/('runs/mutations/'+m['name']+'.log.meta.json')).read_text())
 assert mutation_meta['exit_code']==mutation_meta['child_returncode']==m['exit']==1
 assert mutation_meta['reason']=='exited' and mutation_meta['command']==m['command']
 assert mutation_meta['log_bytes']==len(log)
 text=log.decode()
 actual_totals=totals(text)
 actual_failures=[line for line in text.splitlines() if line.startswith('==> X ')]
 assert '[info] done compiling' in text and 'Compilation failed' not in text
 assert actual_totals==m['totals']==[[2,1,0,1,7]]
 assert actual_failures==m['failures'] and m['named_failure'] in actual_failures[0]
 with tempfile.TemporaryDirectory(prefix='s2b4-verify-') as d:
  p=Path(d)/m['path'];p.parent.mkdir(parents=True);p.write_bytes(obj(m['candidate_sha'],m['path']))
  z=subprocess.run(['patch','-p1','-t'],cwd=d,input=patch,capture_output=True)
  assert z.returncode==0 and sha(p.read_bytes())==m['source_sha256']
s=q['source'];log=gzip.decompress((E/s['log_archive']).read_bytes())
meta=json.loads((E/s['metadata_archive']).read_text())
assert sha(log)==s['log_sha256'] and meta['exit_code']==s['exit']==0
assert meta['command']==s['command'] and meta['command'][-2:]==['scalafmtCheckAll','scalafmtSbtCheck']
rows=totals(log.decode())
assert rows==s['totals'] and len(rows)==43
assert sum(r[3] for r in rows)==s['passed']==7794
assert sum(r[1] for r in rows)==s['failed']==0 and sum(r[2] for r in rows)==s['errors']==0
assert sum(r[4] for r in rows)==s['skipped']==4
print('Verified exact candidate, committed baseline harness/body, six compiled kills and 43-task actual gate receipts.')
