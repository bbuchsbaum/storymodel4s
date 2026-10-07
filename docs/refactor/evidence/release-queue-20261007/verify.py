"""Verify this historical queue receipt, not a replacement for live Mote readback."""
from pathlib import Path
import hashlib
import json
from collections import Counter

HERE = Path(__file__).resolve().parent


def read(name):
    return json.loads((HERE / name).read_text())


def indexed(name):
    rows = read(name)
    assert len({r['id'] for r in rows}) == len(rows), ('duplicate issue ID', name)
    return {r['id']: r for r in rows}


before = indexed('before.json')
after = indexed('after.json')
plan = indexed('classification.json')
assert len(before) == 113
assert len(after) == len(plan) == 114
assert set(before) < set(after)
assert set(after) == set(plan)
ci = 'bd-01M4B896MCEE43Q9QMR4HT60R0'
s2a4 = 'bd-01M379NZZ67GZJMWVBT5XNA0ZD'
docs = 'bd-01M31TQTBMBGEAS9A666B5MPA6'
assert set(after) - set(before) == {ci}
assert after[s2a4]['status'] == 'closed'
assert before[s2a4]['status'] == 'open'
assert before[docs]['status'] == 'review' and after[docs]['status'] == 'open'
assert after[ci]['priority'] == 0 and after[ci]['status'] == 'open'
status_changes = {i for i in before if before[i]['status'] != after[i]['status']}
assert status_changes == {s2a4, docs}

for issue, old in before.items():
    new = after[issue]
    assert old['body'] in new['body'], issue
    assert old['title'] == new['title'], issue
    assert old['priority'] == new['priority'], issue
    assert old['assignee'] == new['assignee'], issue
    assert old['relations'] == new['relations'], issue
    assert plan[issue]['body_before_sha256'] == hashlib.sha256(old['body'].encode()).hexdigest()
    assert plan[issue]['body_after_sha256'] == hashlib.sha256(new['body'].encode()).hexdigest()
    assert all(edge in new['deps'] for edge in old['deps']), issue
    if issue.startswith(('bd-01M1606', 'bd-01M168TZ')):
        notes = old['restored_acceptance_notes']
        assert len(notes) == 1 and notes[0]['ts'].startswith('2026-09-22')
        assert notes[0]['op_id'] in new['body']
        assert notes[0]['text'] in new['body'], ('missing restored acceptance', issue)

original_11 = {i for i, r in before.items() if 'release-1.1' in r['tags']}
final_11 = {i for i, r in after.items() if 'release-1.1' in r['tags']}
assert len(original_11) == 24 and original_11 == final_11

counts = Counter()
for issue, row in after.items():
    group = [t for t in row['tags'] if t.startswith('release-')]
    assert group == [plan[issue]['release_group']], (issue, group)
    assert 'milestone-' + plan[issue]['milestone'] in row['tags'], issue
    if row['status'] != 'closed':
        counts[group[0]] += 1
assert counts == {'release-1.0': 27, 'release-1.1': 24, 'release-later': 62}, counts

all_ids = {row['id'] for row in read('all-after.json')}
graph = {i: [e['parent'] for e in r['deps'] if e['kind'] == 'blocks']
         for i, r in after.items() if r['status'] != 'closed'}
for issue, parents in graph.items():
    for parent in parents:
        assert parent in all_ids, (issue, parent)
        if parent in graph and plan[issue]['release_group'] == 'release-1.0':
            assert plan[parent]['release_group'] == 'release-1.0', (issue, parent)

visiting, visited = set(), set()


def visit(issue):
    assert issue not in visiting, ('cycle', issue)
    if issue in visited:
        return
    visiting.add(issue)
    for parent in graph.get(issue, []):
        if parent in graph:
            visit(parent)
    visiting.remove(issue)
    visited.add(issue)


for issue in graph:
    visit(issue)

new_edges = {(i, e['parent'], e['kind']) for i, r in after.items() for e in r['deps']
             if i in before and e not in before[i]['deps']}
requests = [json.loads(line) for line in (HERE / 'planned-batch.jsonl').read_text().splitlines()]
expected_edges = {(r['child'], r['parent'], r['kind']) for r in requests if r['action'] == 'dep_add'}
assert len(expected_edges) == 10 and new_edges == expected_edges, (
    'dependency changes do not match reviewed requests', new_edges, expected_edges)
expected_results = Counter()
for request in requests:
    if request['action'] in ('tag_add', 'tag_remove'):
        for tag in request['tags']:
            expected_results[(request['action'], request['id'], None, tag)] += 1
    else:
        assert request['action'] == 'dep_add'
        expected_results[('dep_add', request['child'], request['parent'], request['kind'])] += 1
outcomes = read('batch-result.json')
if isinstance(outcomes, dict):
    assert outcomes['rejected'] == outcomes['skipped'] == 0
    assert outcomes['accepted'] == len(outcomes['results'])
    outcomes = outcomes['results']
assert outcomes and all(r['status'] == 'accepted' for r in outcomes)
actual_results = Counter((r['action'], r['entity'], r['parent'], r['kind']) for r in outcomes)
assert actual_results == expected_results, ('batch operation coverage', actual_results, expected_results)
assert len({r['op_id'] for r in outcomes}) == len(outcomes)
application = read('application.json')
assert application and all(r['exit'] == 0 for r in application)
ownership = next(r for r in application if r['step'] == 'preflight ownership')
scoped_ownership = json.loads(ownership['stdout'])
assert scoped_ownership['foreign_active_claims'] == scoped_ownership['foreign_active_reservations'] == 0
assert len(ownership['stdout_sha256']) == 64 and ownership['stdout_bytes'] > 0
steps = Counter(r['step'] for r in application)
expected_steps = {'body ' + i for i in before} | {
    'preflight rows', 'preflight ownership', 'correct exact CI totals',
    'release tags and justified dependency edges', 'S2a-4 final acceptance context',
    'S2a-4 evidence-based closure', 'G1 current next action after S2a-4 closure',
    'final canonical rows', 'doctor integrity', 'fsck integrity', 'CI retry observation note',
    'final note fsck integrity'}
assert set(steps) == expected_steps and all(n == 1 for n in steps.values()), steps
native = read('main-ci-native-totals.json')
assert native['cells'] == 15 and native['sum'] == [2705, 1, 0, 2704]
retry = read('native-attempt-2-totals.json')
assert retry['cells'] == 16 and retry['sum'] == [2822, 0, 0, 2822]
observation = read('main-ci-observation.json')
assert observation['attempt'] == 2 and observation['conclusion'] == 'success'
assert observation['headSha'] == '8d4881a931fb809b27ac7ad14478fe60cde1e6e3'
assert len(observation['jobs']) == 4 and all(j['conclusion'] == 'success' for j in observation['jobs'])
assert any(n['text'] == (HERE / 'ci-retry-note.txt').read_text()
           for n in after[ci]['readback_notes']), 'CI retry note not observed in canonical state'

manifest_count = 0
manifest_names = set()
for line in (HERE / 'SHA256SUMS').read_text().splitlines():
    digest, name = line.split('  ', 1)
    assert name not in manifest_names, ('duplicate manifest path', name)
    manifest_names.add(name)
    assert hashlib.sha256((HERE / name).read_bytes()).hexdigest() == digest, name
    manifest_count += 1
artifact_names = {str(p.relative_to(HERE)) for p in HERE.rglob('*') if p.is_file()}
# Generated checker output is excluded to avoid a self-referential manifest.
assert manifest_names == artifact_names - {'SHA256SUMS', 'validation.json'}, ('artifact coverage', manifest_names)
assert manifest_count >= 12

print(json.dumps({'original_issues': len(before), 'reconciled_issues': len(after),
                  'unfinished': sum(counts.values()), 'release_counts': dict(counts),
                  'status_changes': sorted(status_changes), 'new_edges': len(new_edges),
                  'acyclic': True, 'original_acceptance_preserved': True,
                  'stable_1_1_assignments': len(original_11),
                  'accepted_batch_operations': len(outcomes),
                  'verified_artifacts': manifest_count}, indent=2))
