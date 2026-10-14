import hashlib,json,pathlib,shutil,sys,tempfile
sys.path.insert(0,str(pathlib.Path('tools/recall-study').resolve()))
from organization_challenge import MANIFEST_PATH,PLAN_PATH,FIT_PATH,preflight_fit,read_data
root=pathlib.Path('/Users/bbuchsbaum/code/scala/storymodel4s/.agent-work/organization-challenge-20261014')
base=root/'development-7a58aab6'
_,_,items=read_data(MANIFEST_PATH,PLAN_PATH,base,'development')
results=[{'control':'unchanged-native-development','accepted':True,'conditions':len(items),'verified_rows':sum(i['verified_rows'] for i in items)}]
for change,expected in [('correspondence','native clause correspondence mismatch'),('cost','do not reproduce'),('drop','inventory mismatch')]:
 with tempfile.TemporaryDirectory(prefix='organization-challenge-tamper-') as directory:
  dest=pathlib.Path(directory)
  for path in base.glob('*.json'): shutil.copy2(path,dest/path.name)
  receipt=json.loads((dest/'manifest.json').read_text())
  file=dest/'challenge-0.json'; item=json.loads(file.read_text())
  if change=='drop':
   file.unlink();receipt['files']=[v for v in receipt['files'] if v['path']!=file.name]
  else:
   if change=='correspondence':item['unitCorrespondence'][0]['index']=1
   if change=='cost':item['costs'][0]['states'][0]['cost']+=.5
   file.write_text(json.dumps(item))
   for entry in receipt['files']:
    if entry['path']==file.name:entry['sha256']=hashlib.sha256(file.read_bytes()).hexdigest()
  (dest/'manifest.json').write_text(json.dumps(receipt))
  try:read_data(MANIFEST_PATH,PLAN_PATH,dest,'development')
  except ValueError as e:
   assert expected in str(e),str(e)
   results.append({'mutation':change,'rehashed_manifest':True,'refused':str(e)})
  else:raise AssertionError(change+' survived')
fit=pathlib.Path(FIT_PATH).read_bytes()
try:preflight_fit(fit+b'\n','c9cd7fe278661488c5e9b914ba45f3a24793466b','7a58aab66e3d793c5ad582aac48c896c51415ef4',pathlib.Path(PLAN_PATH).read_bytes())
except ValueError as e:results.append({'mutation':'uncommitted-fit-bytes','refused':str(e)})
else:raise AssertionError('uncommitted fit survived')
(root/'tamper-receipt.json').write_text(json.dumps(results,indent=2)+'\n')
print(json.dumps(results,indent=2))
