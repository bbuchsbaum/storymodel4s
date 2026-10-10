"""Verify archived gate bytes, mutation qualification and browser artifacts."""
import gzip
import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parent
count = 0

def sha(raw):
    return hashlib.sha256(raw).hexdigest()

exchange = json.loads((root / 'exchange/receipt.json').read_text())
for gate in exchange['gates']:
    raw = (root / 'exchange' / (gate['name'] + '.log.gz')).read_bytes()
    assert sha(raw) == gate['compressed_sha256']
    assert sha(gzip.decompress(raw)) == gate['sha256']
    assert gate['exit'] == gate['totals']['failed'] == gate['totals']['errors'] == 0
    count += 1

for name in ['offline', 'lambda', 'workspace']:
    receipt = json.loads((root / name / 'receipt.json').read_text())
    for item in receipt['archives'].values():
        raw = (root / name / item['path']).read_bytes()
        assert sha(raw) == item['gzipSha256']
        assert sha(gzip.decompress(raw)) == item['originalSha256']
        count += 1
    for stage in ['final-checkAll', 'final-consumer']:
        assert receipt[stage]['totals']['failed'] == receipt[stage]['totals']['errors'] == 0

workspace = root / 'workspace'
for item in json.loads((workspace / 'mutation-archives.json').read_text()).values():
    path = workspace / item['archive']
    raw = path.read_bytes()
    assert sha(raw) == item['archiveSha256']
    assert sha(gzip.decompress(raw) if path.suffix == '.gz' else raw) == item['originalSha256']
    count += 1
for prefix, expected in [('privacy-mutations', 3), ('workspace-mutations', 8)]:
    mutants = json.loads((workspace / prefix / 'mutations.json').read_text())
    assert len(mutants) == expected
    assert all(m['compiled'] and m['qualified'] and m['exit'] != 0 for m in mutants)

receipt = json.loads((workspace / 'receipt.json').read_text())
for path, digest in receipt['artifacts'].items():
    assert sha((workspace / path).read_bytes()) == digest
for case in receipt['browserExact']['checks']:
    saved = json.loads((workspace / 'browser' / (case['kind'] + '-saved-view.json')).read_text())
    assert saved['workspace'] == case['packetSha256']
assert len(receipt['browserExact']['checks']) == 4 and not receipt['browserExact']['errors']
assert receipt['declaredDependency']['exit'] == 0
print(f'PASS: {count} archived logs/receipts/patches, 11 mutations, and four browser packet identities.')
