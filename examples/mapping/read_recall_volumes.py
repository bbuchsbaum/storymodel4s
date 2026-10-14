#!/usr/bin/env python3
"""Independently reconstruct non-additive onset attachments from a pinned CSV.

Unit measures are returned once, in their original paired mapping exchange.
This reader checks the declared release join, not physical trigger accuracy.
"""
import argparse
from collections import Counter, defaultdict
import csv
from fractions import Fraction
import hashlib
import io
import json
from pathlib import Path
from read_mapping import envelope, parse, read_publication, require


def rational(value):
    require(set(value) == {'numerator', 'denominator'}, 'rational fields')
    n, d = value['numerator'], value['denominator']
    require(type(n) is str and type(d) is str, 'rational text')
    result = Fraction(int(n), int(d))
    require(str(result.numerator) == n and str(result.denominator) == d, 'rational canonicality')
    return result


def read(files, csv_bytes):
    meta = envelope(files, 'recall-volume-export/v1')
    prior = parse(files['mapping-exchange-manifest.json'])
    paired = {e['path']: files[e['path']] for e in prior['files']}
    paired['manifest.json'] = files['mapping-exchange-manifest.json']
    exchange = read_publication(paired)
    require(len(exchange['pairs']) == 1, 'single observation')
    require(exchange['metadata']['plan'] == meta['mapping_plan'], 'mapping plan')
    outcome = exchange['outcomes'][0]
    require((outcome['participant'], outcome['session']) == (meta['participant'], meta['session']), 'observation identity')
    inventory = exchange['pairs'][0]['reference'][0]['inventory']
    timing = parse(files['recall-timing.json'])
    run = parse(files['scanner-run.json'])
    binding = parse(files['scanner-binding.json'])
    actual = parse(files['onset-attachments.json'])
    require(timing['schemaVersion'] == 'recall-timing/v0.1', 'timing schema')
    require(run['schemaVersion'] == 'scanner-run/v0.1', 'run schema')
    require(binding['schemaVersion'] == 'scanner-binding/v0.1', 'binding schema')
    require(timing['inventory_digest'] == actual['inventory_digest'] == inventory['digest'], 'inventory identity')
    require(actual['timing_digest'] == timing['digest'], 'timing identity')
    require(actual['run_digest'] == binding['run_digest'] == run['digest'], 'run identity')
    authority = 'declared-not-independently-verified'
    require(meta['authority'] == run['authority'] == binding['authority'] == authority, 'authority promotion')
    require(meta['encoding_binding'] == 'unavailable' and meta['crop'] == meta['hrf_shift'] == 'not-applied', 'unexpected transform')
    require(meta['recipe'] == 'external-clock-single-lexical-word-replay/v1', 'recipe')
    require(timing['provenance']['correspondence'] == meta['correspondence_digest'], 'correspondence identity')
    csv_hash = hashlib.sha256(csv_bytes).hexdigest()
    require(csv_hash == meta['csv_sha256'] == timing['clock']['artifact'], 'CSV identity')
    columns = meta['columns']
    require(timing['clock']['key'] == columns['clock'], 'clock selection')
    declaration = meta['recording']
    require(declaration['csv_sha256'] == csv_hash and declaration['clock'] == columns['clock'], 'recording clock')
    require(declaration['run_key'] == run['declaration']['key'], 'recording run')
    require((declaration['participant'], declaration['session']) == (meta['participant'], meta['session']), 'recording observation')
    require(binding['evidence']['supporting_artifacts'] == [v['sha256'] for v in declaration['evidence']], 'evidence pins')
    require(binding['reference_kind'] == 'RecallClock' and binding['reference_artifact'] == timing['clock']['digest'], 'binding reference')
    period = rational(actual['period'])
    count = run['declaration']['sample_count']
    require(period > 0 and type(count) is int and count > 0, 'sampling')
    require(run['declaration']['origin'] == {'status': 'first-stored-sample'}, 'sample origin')
    require([rational(t) for t in run['declaration']['sample_seconds']] == [period*i for i in range(count)], 'sample grid')
    require(rational(binding['scale']) == 1 and rational(binding['offset']) == 0, 'undeclared shift')
    require(rational(binding['domain']['start']) == 0 and rational(binding['domain']['end_exclusive']) == count*period, 'domain')
    rows = list(csv.reader(io.StringIO(csv_bytes.decode('utf-8-sig'), newline=''), strict=True))
    require(rows[0] == columns['header'], 'CSV header')
    require(all(len(r) == len(rows[0]) for r in rows[1:]), 'CSV width')
    words = inventory['words']
    entries = timing['entries']
    require([e['word'] for e in entries] == [w['id'] for w in words], 'complete word timing')
    require(all(e['clock'] == timing['clock']['digest'] for e in entries), 'word clock')
    records, cursor, word_index = [], 0, 0
    for number, row in enumerate(rows[1:], 1):
        # Java String.trim removes only U+0000-U+0020.
        text = row[columns['word']].strip(''.join(map(chr, range(33))))
        value = row[columns['onset']].strip()
        onset = Fraction(value) if value else None
        word, unit = None, None
        if text:
            require(word_index < len(words), 'extra source record')
            w, entry = words[word_index], entries[word_index]
            length = len(text.encode('utf-16-le', errors='surrogatepass')) // 2
            require(cursor <= w['start'] < w['end_exclusive'] <= cursor+length, 'positional word correspondence')
            cursor += length+1
            word = w['id']
            unit = w['membership'].get('unit')
            observed = entry['observation']
            if onset is None:
                require(observed == {'status': 'missing', 'reason': 'BlankSourceCell'}, 'missing onset')
            else:
                require(observed['status'] == 'onset-only' and rational(observed['seconds']) == onset, 'observed onset')
                require(observed['basis'] == {'status': 'source-reported', 'evidence': csv_hash}, 'onset evidence')
            word_index += 1
        volume = None if onset is None or onset < 0 or onset >= count*period else int(onset // period)
        records.append({'record': number, 'word': word, 'unit': unit,
                        'correspondence': 'matched' if word else 'excluded-blank-word',
                        'onset': None if onset is None else {'numerator': str(onset.numerator), 'denominator': str(onset.denominator)},
                        'timing_status': 'missing-onset' if onset is None else 'assigned' if volume is not None else 'outside-stored-coverage',
                        'volume': volume})
    require(word_index == len(words), 'missing source records')
    records = [{k: v for k, v in row.items() if v is not None} for row in records]
    links = defaultdict(list)
    for row in records:
        if row.get('volume') is not None and row.get('unit') is not None:
            links[row['volume'], row['unit']].append(row['word'])
    units = []
    for unit in inventory['units']:
        totals = Counter(r['timing_status'] for r in records if r.get('unit') == unit['id'])
        assigned, size = totals['assigned'], len(unit['words'])
        units.append({'unit': unit['id'], 'words': size, 'assigned': assigned,
                      'outside': totals['outside-stored-coverage'], 'missing': totals['missing-onset'],
                      'attachment': 'none' if assigned == 0 else 'complete' if assigned == size else 'partial'})
    expected = {'schemaVersion': 'recall-onset-attachments/v1', 'inventory_digest': inventory['digest'],
                'timing_digest': timing['digest'], 'run_digest': run['digest'], 'period': actual['period'],
                'sample_count': count, 'index_base': 0, 'semantics': 'non-additive-original-unit-onset-attachments',
                'temporal_mass_allocation': 'unavailable-onset-only', 'records': records,
                'volume_units': [{'volume': v, 'unit': u, 'words': ws} for (v, u), ws in sorted(links.items())],
                'units': units}
    require(actual == expected, 'attachment reconstruction')
    statuses = Counter(r['timing_status'] for r in records)
    return {'exchange': exchange, 'attachments': expected,
            'summary': {'records': len(records), 'canonicalWords': len(words), 'originalUnits': len(units),
                        'volumeUnitLinks': len(links), 'occupiedVolumes': len({v for v, _ in links}),
                        'timingDispositions': dict(statuses), 'mappingMembers': 2,
                        'semantics': expected['semantics']}}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('directory', type=Path)
    p.add_argument('--csv', type=Path, required=True)
    args = p.parse_args()
    files = {f.name: f.read_text(encoding='utf-8') for f in args.directory.iterdir()}
    result = read(files, args.csv.read_bytes())
    print(json.dumps(result['summary'], sort_keys=True))


if __name__ == '__main__':
    main()
