"""Archive exact-candidate logs, rejecting incomplete or unsuccessful final runs."""
import gzip,hashlib,json,re,shutil,subprocess
from pathlib import Path
root=Path(__file__).resolve().parents[4]
work=root/'.agent-work/five-beads';out=Path(__file__).resolve().parent
sha='fa80dfa388d6470dfde51322f8e1035b6d8521a4'
pattern=r'(?:Passed|Failed): Total (\d+), Failed (\d+), Errors (\d+), Passed (\d+)(?:, (?:Ignored|Skipped) (\d+))?'
def archive(name,expected_tasks,consumer=None):
 log=work/(name+'.log');meta=json.loads(Path(str(log)+'.meta.json').read_text());assert meta['exit_code']==0,meta
 text=log.read_text();rows=[dict(zip(['total','failed','errors','passed','skipped'],[int(x or 0) for x in row])) for row in re.findall(pattern,text)]
 assert len(rows)==expected_tasks,(name,len(rows),expected_tasks)
 assert not any(r['failed'] or r['errors'] for r in rows)
 assert 'Compilation failed' not in text
 data=log.read_bytes();folder=out/'logs';folder.mkdir(exist_ok=True)
 (folder/(name+'.log.gz')).write_bytes(gzip.compress(data,mtime=0));shutil.copyfile(str(log)+'.meta.json',folder/(name+'.meta.json'))
 receipt={'source_sha':sha,'consumer_sha':consumer,'qualification':'LocallyObserved','grakern_override_sha':'8efc5efade4e56c6e0b29745bd1b108d4c9a9d57','command':meta['command'],'exit':0,'tasks':len(rows),'totals':{k:sum(r[k] for r in rows) for k in rows[0]},'per_task':rows,'raw_log_sha256':hashlib.sha256(data).hexdigest(),'raw_log_bytes':len(data),'archive':'logs/'+name+'.log.gz'}
 (out/(name+'-receipt.json')).write_text(json.dumps(receipt,indent=2)+'\n');print(name,receipt['totals'])
archive('final-checkAll',56)
archive('final-consumer',10,'90f682d1acddd759867098e90f6e3ef163244b0e')
rows=json.loads((work/'integration-mutations/mutations.json').read_text());assert len(rows)==15 and all(r['qualified'] for r in rows)
spec=json.loads((out/'mutation-spec.json').read_text());folder=out/'logs/mutations';folder.mkdir(exist_ok=True)
for r,s in zip(rows,spec):
 assert r['candidate_sha']==sha and r['name']==s['name']
 original=subprocess.check_output(['git','show',sha+':'+s['path']],cwd=root,text=True)
 assert original.count(s['old'])==1
 assert hashlib.sha256(original.replace(s['old'],s['new']).encode()).hexdigest()==r['source_sha256']
 for suffix in ['.log','.log.meta.json','.patch']:
  p=work/'integration-mutations'/(r['name']+suffix)
  if suffix=='.log':
   assert hashlib.sha256(p.read_bytes()).hexdigest()==r['log_sha256']
   (folder/(p.name+'.gz')).write_bytes(gzip.compress(p.read_bytes(),mtime=0))
  else:shutil.copyfile(p,folder/p.name)
shutil.copyfile(work/'integration-mutations/mutations.json',folder/'mutations.json')
shutil.copyfile(work/'final-scope.txt',out/'reference-scope.txt')
print('Archived all fifteen reconstructible mutations and both final gates.')
