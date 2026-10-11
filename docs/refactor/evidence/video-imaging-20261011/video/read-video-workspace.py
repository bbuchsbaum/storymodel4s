"""Independent known answers for the synthetic two-part video workspace, not empirical media."""
from pathlib import Path
from fractions import Fraction
import hashlib,json,sys
root=Path('/Users/bbuchsbaum/code/scala/storymodel4s');work=root/'.agent-work/video-imaging-20261011'
sys.path.insert(0,str(root/'examples/mapping'))
from read_workspace import inspect
packet=work/'video-workspace.json'
checked=inspect(packet.read_text())
wrapper=json.loads(checked['roles']['SourceInput']);assert wrapper['kind']=='video-captions'
source=json.loads(wrapper['artifact']);assert source['schemaVersion']=='prepared-media-caption-source/v1'
parts=source['parts'];assert [p['occurrence'] for p in parts]==['a','b']
# F0 drops generated frames5 and6, so presentation ordinal4 lasts from tick4000 to7000.
# Part b is an explicitly counterfactual replay: +1000 ticks and a 1/48000 clock.
answers=[]
for p,start,end,den,ordinals,pts,indices,hull in [
 (parts[0],0,48000,24000,[0,4,9],[0,4000,11000],[0,1],(0,7000)),
 (parts[1],1000,49000,48000,[2,13],[3000,16000],[0,1],(3000,17000))]:
 assert (int(p['start']),int(p['end']),int(p['numerator']),int(p['denominator']))==(start,end,1,den)
 assert p['presentedCount']==45
 assert [s['ordinal'] for s in p['samples']]==ordinals
 assert [int(s['pts']) for s in p['samples']]==pts
 assert p['proposals'][0]['samples']==indices
 assert (int(p['proposals'][0]['start']),int(p['proposals'][0]['end']))==hull
 assert p['receipt']['inputs']==[p['framesChecksum'],p['framesIdentity']]
 examined=[ordinals[i] for i in indices]
 missing=sorted(set(range(45))-set(examined))
 assert len(missing)==43
 answers.append({'part':p['occurrence'],'sampled':ordinals,'examined':examined,'missingCount':len(missing),'nativeHullSeconds':[str(Fraction(x,den)) for x in hull]})
bundles=checked['source']['bundles'];assert len(bundles)==2
assert all(b['source_kind']=={'status':'FilmEdition'} for b in bundles)
targets=checked['source']['targets'];assert len(targets)==3
assert sum(t['support_status']['status']=='located' for t in targets)==2
assert sum(t['support_status']['status']=='unlocated' for t in targets)==1
result={'status':'pass','producer':'13bdc9aad68bba8493577f79f212fa07f3b57f44','packetSha256':hashlib.sha256(packet.read_bytes()).hexdigest(),'answers':answers,'targets':3,'crossPartGroup':'unlocated','scope':'synthetic imported Draft native-clock/provenance and paired workspace readback; not JVM execution authority or empirical caption validation'}
(work/'video-independent-readback.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))
