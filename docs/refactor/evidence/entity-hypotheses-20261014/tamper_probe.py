#!/usr/bin/env python3
"""Exercise correspondence and numeric refusal on pinned, actual native output."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import sys
import tempfile
sys.path.insert(0,str(Path(__file__).resolve().parents[4]/'tools/recall-study'))
import entity_hypotheses as e

p=argparse.ArgumentParser();p.add_argument('native',type=Path);p.add_argument('output',type=Path);a=p.parse_args()
control=e.read_native(a.native)[-1]
results=[]
for kind in ['missing-entry','false-clause','false-cost']:
    with tempfile.TemporaryDirectory() as tmp:
        root=Path(tmp)
        for source in a.native.glob('*.json'):shutil.copyfile(source,root/source.name)
        manifest=json.loads((root/'manifest.json').read_text())
        if kind=='missing-entry':
            manifest['files'].pop()
        else:
            name='variant-0.json';item=json.loads((root/name).read_text())
            if kind=='false-clause':item['processedClauseSha256'][0]='0'*64
            else:
                state=next(s for s in item['costs'][0]['states'] if not s['excluded'])
                state['cost']+=0.1
            data=json.dumps(item,allow_nan=False).encode();(root/name).write_bytes(data)
            next(f for f in manifest['files'] if f['path']==name)['sha256']=hashlib.sha256(data).hexdigest()
        (root/'manifest.json').write_text(json.dumps(manifest))
        try:e.read_native(root)
        except ValueError as error:message=str(error)
        else:raise AssertionError('accepted '+kind)
        expected={'missing-entry':'native variant inventory mismatch','false-clause':'native variant correspondence mismatch','false-cost':'emitted costs do not reproduce destination masses'}[kind]
        assert expected in message,(kind,message)
        results.append({'case':kind,'refused':True,'reason':message,'manifest_rehashed':True})
with a.output.open('x') as f:json.dump({'positive_control':control,'refusals':results},f,indent=2);f.write('\n')
print(json.dumps({'positive_control':True,'refusals':len(results)}))
