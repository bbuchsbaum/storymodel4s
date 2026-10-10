"""Prepare a local two-recall annotation-assisted control from checksum-admitted releases.

Never copies corpus prose into git. Keeps all 1000 annotation rows on two declared
annotation clocks; it does not claim movie playback identity or compiled perception.
"""
import csv, hashlib, io, json, shutil, sys
from pathlib import Path
repo, output = map(Path, sys.argv[1:])
output.mkdir(parents=True, exist_ok=False)
annotation = repo / 'data/sherlock/Sherlock_Segments_1000_NN_2017.tsv'
lineage = json.loads((repo/'docs/data/sherlock/annotation-lineage.json').read_text())
raw = annotation.read_bytes()
sha = lambda b: hashlib.sha256(b).hexdigest()
assert sha(raw) == lineage['localRepresentation']['sha256']
rows = list(csv.reader(io.StringIO(raw.decode('utf-8')), delimiter='\t'))[1:]
assert len(rows) == 1000
assert [int(r[0]) for r in rows] == list(range(1, 1001))
header = ['ordinal','text','part','onset','end','group','group_label','locations','characters']
scene, label = 0, None
with (output/'annotations.csv').open('w', newline='') as handle:
    writer = csv.writer(handle, lineterminator='\n'); writer.writerow(header)
    for row in rows:
        if row[5].strip():
            scene += 1; label = row[5].strip()
        assert scene > 0 and label
        start, end = int(row[1]), int(row[2]); assert start <= end
        writer.writerow([row[0], row[6].strip(), 'annotation-run-1' if int(row[0]) <= 482 else 'annotation-run-2',
                         start, '' if start == end else end, scene, label, '', ''])
assert scene == 50
parts = [{'part': f'annotation-run-{n}', 'start':'0', 'end':str(max(int(r[2]) for r in block)),
          'numerator':'1', 'denominator':'1'} for n, block in enumerate((rows[:482], rows[482:]), 1)]
(output/'parts.json').write_text(json.dumps(parts))
recall_lineage_path = repo/'docs/data/sherlock/recall-lineage.json'
shutil.copyfile(recall_lineage_path, output/'recall-lineage.json')
recall_lineage = json.loads(recall_lineage_path.read_text())
local_paths = json.loads((repo/'.agent-work/workflow-five/sherlock-local-paths.json').read_text())
recalls, identities = [], []
# Predeclared first two public source IDs; selection is unrelated to mapping outcomes.
for source_id in ('recall-source-01', 'recall-source-02'):
    record = next(r for r in recall_lineage['sources'] if r['id'] == source_id)
    data = Path(local_paths[source_id]).read_bytes()
    assert sha(data) == record['localCsvSha256'] and len(data) == record['localCsvByteLength']
    name = source_id + '.csv'; (output/name).write_bytes(data)
    recalls.append({'participant':source_id,'session':'released','path':name,'timing':None,
                    'admission':{'kind':'sherlock','sourceId':source_id,'lineage':'recall-lineage.json'}})
    identities.append({'source':source_id,'csvSha256':sha(data),'records':record['recordCount']})
(output/'study.json').write_text(json.dumps({'schemaVersion':'storymodel-study/v1',
    'source':{'format':'timed-annotations','path':'annotations.csv','parts':'parts.json'},
    'profile':'recall-baseline-lexical-words/v1','recalls':recalls}))
(output/'mapping.json').write_text(json.dumps({'profile':'mapping-lexical-pair/v1','temperature':'1.0',
    'perLevel':20,'tieBudget':200,'requested':['Mapping'],'networkRequests':0,'retries':0}))
receipt = {'recipe':'released-sherlock-annotation-clock-workspace/v1', 'rows':1000,'scenes':50,
           'annotationSha256':sha(raw),'preparedCsvSha256':sha((output/'annotations.csv').read_bytes()),
           'descriptorSha256':sha((output/'parts.json').read_bytes()),'recalls':identities,
           'scope':'all released annotation rows on run-local annotation clocks; no film playback or perception claim'}
(output/'preparation-receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
print(json.dumps(receipt))
