from pathlib import Path
import hashlib,json,re,subprocess,time
root=Path('/Volumes/My Passport for Mac/data/openneuro/ds001132-1.0.0')
head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip()
assert head=='fc91edbbe1b8117f96b40bb4d1f5df43aa1afdce'
rows=[json.loads(x) for x in subprocess.check_output(['git','annex','find','--json'],cwd=root,text=True).splitlines()]
assert len(rows)==65
started=time.time(); records=[]
for r in rows:
 p=root/r['file']; resolved=p.resolve(strict=True)
 assert resolved.is_relative_to(root/'.git'/'annex'/'objects'),r['file']
 m=re.fullmatch(r'MD5E-s(\d+)--([a-f0-9]{32})\..+',r['key']);assert m
 md5=hashlib.md5();sha=hashlib.sha256();size=0
 with p.open('rb') as f:
  while block:=f.read(4*1024*1024):
   size+=len(block);md5.update(block);sha.update(block)
 assert size==int(m[1])==int(r['bytesize']) and md5.hexdigest()==m[2],r['file']
 records.append(dict(file=r['file'],annexKey=r['key'],bytes=size,md5=md5.hexdigest(),sha256=sha.hexdigest()))
tracked=subprocess.check_output(['git','ls-files','-z'],cwd=root).decode().split('\0')
metadata=[]
for name in tracked:
 if not name or (root/name).is_symlink():continue
 b=(root/name).read_bytes();metadata.append(dict(file=name,bytes=len(b),sha256=hashlib.sha256(b).hexdigest()))
out=root.parent/'ds001132-1.0.0-verification.json'
value=dict(schema='local-openneuro-download-verification/v1',dataset='ds001132',release='1.0.0',commit=head,source='https://github.com/OpenNeuroDatasets/ds001132',root=str(root),annexFiles=records,trackedMetadata=metadata,annexBytes=sum(r['bytes'] for r in records),elapsedSeconds=time.time()-started,scope='Exact retrieved bytes only. No participant alias, recording/media origin, temporal mapping, or neural-outcome analysis certified.')
with out.open('x') as f:json.dump(value,f,indent=2);f.write('\n')
print(json.dumps({'status':'pass','files':len(records),'bytes':value['annexBytes'],'verification':str(out),'elapsedSeconds':value['elapsedSeconds']}))
