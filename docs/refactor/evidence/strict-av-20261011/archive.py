from pathlib import Path
import gzip,hashlib,json,shutil
root=Path('/Users/bbuchsbaum/code/scala/storymodel4s'); work=root/'.agent-work/strict-av-20261011'; dest=root/'docs/refactor/evidence/strict-av-20261011';dest.mkdir(parents=True,exist_ok=True)
for p in sorted(work.iterdir()):
 if not p.is_file() or p.name in {'progress.txt','paths.json'}:continue
 if p.suffix=='.log':
  with gzip.GzipFile(filename=str(dest/(p.name+'.gz')),mode='wb',mtime=0) as f:f.write(p.read_bytes())
 else:shutil.copy2(p,dest/p.name)
fixture=work/'live-f0-join'
if fixture.exists():
 for p in sorted(fixture.rglob('*')):
  if not p.is_file():continue
  q=dest/'live-f0-join'/p.relative_to(fixture);q.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(p,q)
shutil.copy2(root/'.agent-work/video-imaging-20261011/asset-availability.json',dest/'asset-availability.json')
files=[{'path':str(p.relative_to(dest)),'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'bytes':p.stat().st_size} for p in sorted(dest.rglob('*')) if p.is_file() and p.name not in {'manifest.json','README.md'}]
# Nested publication/package manifests are evidence too.
for p in sorted(dest.rglob('manifest.json')):
 if p.parent!=dest:files.append({'path':str(p.relative_to(dest)),'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'bytes':p.stat().st_size})
(dest/'manifest.json').write_text(json.dumps({'producer':'29997f88d159de8b4dc2620a4ce23c071e9b51ca','documentation':'c3ae9260a948b2011e5ae4144b6f28d65eb5e9cb','consumer':'ac2ca6cb85fdb8a959390e0e3d16e7af87c3e16d','files':sorted(files,key=lambda f:f['path'])},indent=2)+'\n')
print('archived',len(files),'files')
