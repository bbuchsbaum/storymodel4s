from pathlib import Path
import hashlib,json
from huggingface_hub import hf_hub_download
root=Path('/Users/bbuchsbaum/code/scala/storymodel4s')
request=json.loads((root/'media/src/test/resources/f0/f0-v1.caption-request.json').read_text())['model']
dest=root/'tmp/models/Qwen3-VL-4B-Instruct'
rows=[]
for name,expected in request['filesSha256'].items():
 path=Path(hf_hub_download(repo_id=request['repo'],revision=request['revision'],filename=name,local_dir=dest,token=False))
 with path.open('rb') as f: actual=hashlib.file_digest(f,'sha256').hexdigest()
 assert actual==expected, name+' checksum mismatch'
 rows.append({'name':name,'sha256':actual,'bytes':path.stat().st_size})
 print('VERIFIED',name,path.stat().st_size,flush=True)
(root/'.agent-work/video-imaging-20261011/runtime-model-restored.json').write_text(json.dumps({'repo':request['repo'],'revision':request['revision'],'files':rows},indent=2)+'\n')
