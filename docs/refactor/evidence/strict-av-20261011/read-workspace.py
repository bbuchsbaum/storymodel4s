"""Independent Fraction known answers for the synthetic strict annotation-video join."""
from pathlib import Path
from fractions import Fraction
import csv,io,json,hashlib,sys
root=Path(__file__).resolve()
while not (root/'examples/mapping/read_workspace.py').exists():
 if root.parent==root:raise RuntimeError('run from repository evidence directory')
 root=root.parent
sys.path.insert(0,str(root/'examples/mapping'))
from read_workspace import inspect
packet=Path(sys.argv[1]); output=Path(sys.argv[2]);checked=inspect(packet.read_text())
source_input=json.loads(checked['roles']['SourceInput']);assert source_input['kind']=='annotation-video'
joined=json.loads(source_input['artifact']);assert joined['schemaVersion']=='prepared-annotation-video-source/v1'
sha=lambda text:hashlib.sha256(text.encode()).hexdigest()
assert sha(joined['correspondence'])==joined['correspondenceSha256']
c=json.loads(joined['correspondence']);a=json.loads(joined['annotations']);m=json.loads(joined['media'])
assert c['annotationArtifact']==sha(joined['annotations'])
assert all(c[k]==m[k] for k in ('packageManifest','packageIdentity','edition'))
rows=list(csv.DictReader(io.StringIO(a['csv'])));assert [r['text'] for r in rows]==['Anna arrived.','Anna arrived.']
assert [r['group'] for r in rows]==['1','1']
assert c['basis']=='synthetic known affine clocks'
answers=[]
for row,landmarks,expected,seconds in zip(rows,c['parts'],[(0,7000),(3000,17000)],[(Fraction(0),Fraction(7,24)),(Fraction(1,16),Fraction(17,48))]):
 points=[(int(p['annotationTick']),int(p['filmTick'])) for p in landmarks['landmarks']]
 x0,y0=points[0];x1,y1=points[-1]
 slope=Fraction(y1-y0,x1-x0); offset=y0-slope*x0
 result=tuple(slope*int(row[k])+offset for k in ('onset','end'))
 assert result==expected and all(v.denominator==1 for v in result)
 part=next(p for p in m['parts'] if p['occurrence']==landmarks['filmOccurrence'])
 assert tuple(v*Fraction(int(part['numerator']),int(part['denominator'])) for v in result)==seconds
 assert (int(part['proposals'][0]['start']),int(part['proposals'][0]['end']))==expected
 target=next(t for t in checked['source']['targets'] if t['level']==0 and t['target_id'].endswith(':'+row['ordinal']))
 intervals=target['support_status']['source_support']['anchors'][0]['intervals']
 assert len(intervals)==1 and (int(intervals[0]['start_tick']),int(intervals[0]['end_exclusive_tick']))==expected
 assert intervals[0]['axis']==landmarks['filmAxis']
 answers.append({'part':row['part'],'annotationTicks':[int(row['onset']),int(row['end'])], 'filmTicks':list(expected),'nativeSeconds':[str(s) for s in seconds]})
assert len(checked['source']['targets'])==3
assert all(t['support_status']['status']=='unlocated' for t in checked['source']['targets'] if t['level']==1)
result={'status':'pass','producer':'29997f88d159de8b4dc2620a4ce23c071e9b51ca','packetSha256':hashlib.sha256(packet.read_bytes()).hexdigest(),'answers':answers,'crossPartGroup':'unlocated','scope':'synthetic declared correspondence and independently read publication; no physical synchronization or empirical accuracy claim'}
output.write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))
