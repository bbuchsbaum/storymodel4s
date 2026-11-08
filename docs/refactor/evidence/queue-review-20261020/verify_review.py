#!/usr/bin/env python3
"""Check coverage, preservation, graph invariants and applied Mote projection."""
import argparse
import hashlib
import json
import re
import subprocess
from concurrent.futures import ThreadPoolExecutor
from collections import Counter
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]


def read(name):
    return json.loads((HERE / name).read_text())


def require(ok, message):
    if not ok:
        raise RuntimeError(message)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--after', action='store_true')
    parser.add_argument('--capture-after', action='store_true',
                        help='Read back full affected records through Mote CLI; requires --after')
    args = parser.parse_args()
    before = {r['id']: r for r in read('before.json')}
    assessment = read('assessments.json')
    rows = {r['id']: r for r in assessment['records']}
    sets = read('planned-sets.json')
    batch = [json.loads(line) for line in (HERE / 'planned-batch.jsonl').read_text().splitlines()]
    tranche = read('tranche.json')
    require(len(before) == len(rows) == len(assessment['records']) == 108 and before.keys() == rows.keys(), 'Incomplete/duplicate baseline coverage')
    require(all(r['status'] != 'closed' for r in rows.values()), 'Unjustified closure')
    require(sum(r['priority'] != r['before_priority'] for r in rows.values()) == 6, 'Priority delta')
    require(sum(r['status'] != r['before_status'] for r in rows.values()) == 8, 'Status delta')
    require(Counter(r['status'] for r in rows.values()) == {'open': 86, 'blocked': 7, 'review': 15}, 'Status totals')
    require(len({s['id'] for s in sets}) == len(sets) == 29, 'Duplicate set command')
    original = json.loads((ROOT / '.agent-work/queue-review-20261020/before-full.json').read_text())
    require(hashlib.sha256((ROOT / '.agent-work/queue-review-20261020/before-full.json').read_bytes()).hexdigest() == assessment['full_snapshot_sha256'], 'Full snapshot changed')
    for operation in sets:
        old = original[operation['id']]
        require(operation['fields']['body'].endswith(old['body']), 'Historical body lost')
        require(hashlib.sha256(old['body'].encode()).hexdigest() == rows[operation['id']]['body_sha256'], 'Wrong body source')
        require(set(operation['fields']) <= {'body', 'title', 'priority', 'status'}, 'Scope expansion')
    graph = {key: {e['parent'] for e in d['deps'] if e['parent'] in rows}
             for key, d in original.items()}
    edges = [op for op in batch if op['action'] == 'dep_add']
    require(edges == [dict(action='dep_add', child='bd-01M50KBFT26DRB77VFHPRHYNA2', parent='bd-01M2TA0X79Y4BQZP0B6AT919C9')], 'Unexpected prerequisite change')
    for edge in edges:
        graph[edge['child']].add(edge['parent'])
    done = set()
    active = set()

    def visit(key):
        require(key not in active, 'Introduced dependency cycle')
        if key in done:
            return
        active.add(key)
        for parent in graph.get(key, set()):
            visit(parent)
        active.remove(key)
        done.add(key)
    for key in graph:
        visit(key)

    def closure(key):
        found = set()
        work = [key]
        while work:
            current = work.pop()
            for parent in graph.get(current, set()):
                if parent not in found:
                    found.add(parent)
                    work.append(parent)
        return found
    research = {'bd-01M50KBFT26DRB77VFHPRHYNA2', 'bd-01M50KC744836VNJ8F8A4AHNWH',
                'bd-01M50KD0NHVPCEHGZND9H18M6E', 'bd-01M2TA9KKVFWPQ74HN90P0SM68'}
    for key in ['bd-01M2TAMJ95K9D7H248A8387YW4', 'bd-01M2TAKTB61XNXV4Y6A9J3CB0C']:
        require(not closure(key) & research, 'Research gates software release')
    selected = {t['id'] for t in tranche['selected']}
    require(len(selected) == 5, 'Tranche size')
    require('bd-01M4EA3TVCGSVSA114DQX9588N' not in closure('bd-01M2TAMJ95K9D7H248A8387YW4'), 'StageTrace became a release blocker')
    proc = subprocess.run(['git', 'merge-base', '--is-ancestor', 'c69a5d754a21fa455c64901334d9509278401091', '37f659277199d9d1922b772f13b0b260b1e028d9'], cwd=ROOT)
    require(proc.returncode == 0, 'S1 not in the stated integration range')
    for name in ['README.md', 'dispositions.md']:
        text = (HERE / name).read_text()
        for match in re.finditer(r'\]\(([^)]+)\)', text):
            target = match.group(1).split('#')[0]
            if target and '://' not in target:
                require((HERE / target).exists(), 'Broken local evidence link: ' + target)
    require(sum(line.startswith('| bd-') for line in (HERE / 'dispositions.md').read_text().splitlines()) == 108, 'Rendered crosswalk incomplete')
    result = dict(baseline=108, assessed=108, priority_changes=6, status_changes=8,
                  preserved_prior_bodies=len(sets), selected=5, dependency_cycles=0,
                  research_to_release_paths=0, baseline_closures=0,
                  planning_integrity='pass', after_state_checked=False)
    if args.after:
        applied = read('applied.json')
        require(len(applied['commands']) == 30 and all(c['exit'] == 0 for c in applied['commands']), 'Incomplete mutation commands')
        for field, name in [('sets_sha256', 'planned-sets.json'), ('batch_sha256', 'planned-batch.jsonl')]:
            require(hashlib.sha256((HERE / name).read_bytes()).hexdigest() == applied[field], 'Applied plan identity differs')
        after = {d['id']: d for d in read('after.json')}
        snapshot_path = ROOT / '.agent-work/queue-review-20261020/after-full.json'
        if args.capture_after:
            affected = {s['id'] for s in sets} | {tranche['selected'][0]['id']}
            def capture(key):
                process = subprocess.run(['mote', '--actor', 'codex-queue-review', '--json', 'show', key],
                                         cwd=ROOT, capture_output=True, text=True, check=True)
                return key, json.loads(process.stdout)
            with ThreadPoolExecutor(max_workers=4) as pool:
                captured = dict(pool.map(capture, sorted(affected)))
            snapshot_path.write_text(json.dumps(captured, indent=2) + '\n')
        actual = json.loads(snapshot_path.read_text())
        require(set(actual) == {s['id'] for s in sets} | {tranche['selected'][0]['id']}, 'Incomplete full after projection')
        for key, expected in rows.items():
            for field in ['status', 'priority']:
                require(after[key][field] == expected[field], 'After projection mismatch: ' + key + '/' + field)
        for operation in sets:
            for field, value in operation['fields'].items():
                require(actual[operation['id']][field] == value, 'Full projected field differs: ' + operation['id'] + '/' + field)
        e1 = actual['bd-01M50KBFT26DRB77VFHPRHYNA2']
        require(dict(kind='blocks', parent='bd-01M2TA0X79Y4BQZP0B6AT919C9') in e1['deps'], 'Actual E1 resource edge missing')
        integration = actual[tranche['selected'][0]['id']]
        require(dict(kind='parent', parent='bd-01M2TA01EHVRF6MQ1N00XTVK1K') in integration['relations'], 'Actual integration containment missing')
        require(tranche['tag'] in integration['tags'], 'Actual integration tranche tag missing')
        require({key for key, d in after.items() if tranche['tag'] in d['tags']} == selected, 'Tranche tag membership differs')
        # Re-evaluate graph properties on the captured actual dependency projection.
        for key, d in actual.items():
            graph[key] = {e['parent'] for e in d['deps'] if e['parent'] in rows or e['parent'] in actual}
        done.clear()
        active.clear()
        for key in graph:
            visit(key)
        for key in ['bd-01M2TAMJ95K9D7H248A8387YW4', 'bd-01M2TAKTB61XNXV4Y6A9J3CB0C']:
            require(not closure(key) & research, 'Actual research-to-release path')
        public_projection = dict(full_snapshot_sha256=hashlib.sha256(snapshot_path.read_bytes()).hexdigest(),
            records={key: dict(body_sha256=hashlib.sha256(d['body'].encode()).hexdigest(),
                               title=d['title'], status=d['status'], priority=d['priority'],
                               deps=d['deps'], relations=d['relations'], tags=d['tags'])
                     for key, d in actual.items()})
        (HERE / 'after-projection.json').write_text(json.dumps(public_projection, indent=2) + '\n')
        result['after_state_checked'] = True
        result['exact_applied_bodies_checked'] = len(sets)
        result['actual_resource_and_parent_edges_checked'] = True
        result['commands_exit_zero'] = len(applied['commands'])
        result['unfinished_including_audit'] = len(after)
        result['after_status'] = dict(Counter(d['status'] for d in after.values()))
        result['after_priority'] = dict(Counter(d['priority'] for d in after.values()))
    print(json.dumps(result))


if __name__ == '__main__':
    main()
