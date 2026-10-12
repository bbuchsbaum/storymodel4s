"""Read existing synthetic F0 join outputs without rerunning producer commands."""
from pathlib import Path
import hashlib,json,sys
root=Path(__file__).resolve()
while not (root/'examples/mapping/read_mapping.py').exists():
 if root.parent==root:raise RuntimeError('repository root not found')
 root=root.parent
fixture=Path(sys.argv[1]); output=Path(sys.argv[2]);sha='29997f88d159de8b4dc2620a4ce23c071e9b51ca'
ct=(fixture/'correspondence.json').read_text();media=json.loads(ct);hashtext=lambda s:hashlib.sha256(s.encode()).hexdigest()
sys.path.insert(0,str(root/'examples/mapping'));from read_mapping import read_publication;from read_workspace import inspect;from read_weighted_mapping import read_sweep
publication=read_publication({p.name:p.read_text() for p in (fixture/'exchange').iterdir()});assert len(publication['pairs'])==1
_,members=read_sweep({p.name:p.read_text() for p in (fixture/'weighted').iterdir()});assert len(members)==3
packets=list((fixture/'workspace').glob('*-workspace.json'));assert len(packets)==1,packets
checked=inspect(packets[0].read_text());joined=json.loads(json.loads(checked['roles']['SourceInput'])['artifact']);assert joined['correspondence']==ct
assert len(checked['source']['targets'])==3
annotation_only=read_publication({p.name:p.read_text() for p in (fixture/'exchange-a').iterdir()});assert len(annotation_only['pairs'])==1
reference_a=next(p for p in (fixture/'exchange-a').iterdir() if p.name.endswith('--reference--mapping.json'));reference_av=next(p for p in (fixture/'exchange').iterdir() if p.name.endswith('--reference--mapping.json'))
a_record=json.loads(reference_a.read_text());av_record=json.loads(reference_av.read_text());assert a_record['inventory']==av_record['inventory'];assert [t['target_id'] for t in a_record['source']['targets']]==[t['target_id'] for t in av_record['source']['targets']]
receipt={'producer':sha,'scope':'public joined mapping, weights and workspace on actual-worker F0 plus synthetic derived annotation control; not Sherlock synchronization or caption accuracy','packageManifest':media['packageManifest'],'correspondenceSha256':hashtext(ct),'mappingPairs':len(publication['pairs']),'weightedMembers':len(members),'sameAandAVInventory':True,'sameAnnotationTargetIds':True,'targets':3,'files':{str(p.relative_to(fixture)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(fixture.rglob('*')) if p.is_file()}}
output.write_text(json.dumps(receipt,indent=2)+'\n');print('F0_PUBLIC_JOIN_PASS',flush=True)
