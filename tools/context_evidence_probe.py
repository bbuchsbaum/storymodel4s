#!/usr/bin/env python3
"""Build fixed-candidate text evidence conditions without expanding focal scope.

Selection uses time and part only. Manual annotations are optional attributed data.
No recall, reference labels, or question wording participate in context selection.
"""
import copy
import hashlib
import json
import math

import claim_assessment_probe as v1

CONDITIONS = ('focal_human', 'context_auto', 'context_human')


def coordinate(record):
    part, start, end = record['part'], record['start'], record['end']
    if (not isinstance(part, str) or not part or
            any(isinstance(x, bool) or not isinstance(x, (int, float)) or not math.isfinite(x)
                for x in (start, end)) or start < 0 or end <= start):
        raise ValueError('invalid evidence coordinate')
    return part, start, end


def source_item(kind, target, text, **metadata):
    """Retain the earlier renderer's content identity for the existing source atoms."""
    # Earlier preparation used json.dumps default separators for this identity.
    key = hashlib.sha256(json.dumps([kind, target['part'], metadata, text], sort_keys=True).encode()).hexdigest()
    return dict(id=key, kind=kind, text=text, part=target['part'], **metadata)


def target_items(target, manual=False):
    coordinate(target)
    result = [source_item('asr-segment', target, segment['text'],
                         start=segment['start_seconds'], end=segment['end_seconds'],
                         segmentIndex=segment['index'], suspect=segment['suspect'],
                         diagnostics=segment['diagnostics'],
                         ownership='complete decoder segment; words outside target interval are context')
              for segment in target['audio']['segments'] if segment['text'].strip()]
    if target['visual'].strip():
        result.append(source_item('caption', target, target['visual'], start=target['start'],
                                  end=target['end'],
                                  ownership='machine description of sampled frames; not original image evidence'))
    if manual and target['human'].strip():
        result.append(source_item('manual-annotation', target, target['human'],
                                  start=target['start'], end=target['end'], sourceRow=target['row'],
                                  ownership='fallible human annotation of the interval; not verified footage or an authority override'))
    for item in result:
        coordinate(item)
    return result


def checked_targets(targets):
    by_row = {}
    for target in targets:
        coordinate(target)
        row = target['row']
        if isinstance(row, bool) or not isinstance(row, int) or row < 0 or row in by_row:
            raise ValueError('invalid or duplicate source row')
        by_row[row] = target
    if not by_row:
        raise ValueError('empty source cut')
    return by_row


def windows(intervals, targets, radius):
    if isinstance(radius, bool) or not isinstance(radius, (int, float)) or not math.isfinite(radius) or radius < 0:
        raise ValueError('invalid context radius')
    bounds = {}
    for target in targets.values():
        part, start, end = coordinate(target)
        old = bounds.get(part, (start, end))
        bounds[part] = min(old[0], start), max(old[1], end)
    result = []
    for interval in intervals:
        part, start, end = coordinate(interval)
        lower, upper = bounds[part]
        if start < lower or end > upper:
            raise ValueError('focal interval outside declared source cut')
        result.append(dict(part=part, start=max(lower, start-radius), end=min(upper, end+radius)))
    merged = []
    for window in sorted(result, key=lambda w: coordinate(w)):
        if merged and window['part'] == merged[-1]['part'] and window['start'] <= merged[-1]['end']:
            merged[-1]['end'] = max(window['end'], merged[-1]['end'])
        else:
            merged.append(dict(window))
    return merged


def overlaps(record, selected):
    part, start, end = coordinate(record)
    return any(part == window['part'] and min(end, window['end']) > max(start, window['start'])
               for window in selected)


def context_copy(item):
    result = dict(item, sourceEvidenceId=item['id'], evidenceRole='surrounding_context')
    result['ownership'] = item['ownership'] + '; surrounding evidence only, not direct depiction in the focal intervals'
    result.pop('id')
    result['id'] = v1.identity(result)
    return result


def augment(request, targets, condition, radius=90):
    """Preserve the exact target intervals and every original item; add complete source atoms."""
    if condition not in CONDITIONS or request['arm'] != 'audio_visual':
        raise ValueError('unknown condition or noncombined request')
    if v1.identity({k: v for k, v in request.items() if k != 'identity'}) != request['identity']:
        raise ValueError('request identity mismatch')
    by_row = checked_targets(targets)
    focal_rows = {interval['row'] for interval in request['intervals']}
    for interval in request['intervals']:
        target = by_row[interval['row']]
        if coordinate(interval) != coordinate(target) or interval['centralWords'] != target['audio']['centralWords']:
            raise ValueError('focal coordinate/timing differs from source')
    focal_auto = {}
    for row in focal_rows:
        for item in target_items(by_row[row]):
            if item['id'] in focal_auto and focal_auto[item['id']] != item:
                raise ValueError('inconsistent focal evidence identity')
            focal_auto[item['id']] = item
    if request['items'] != [focal_auto[k] for k in sorted(focal_auto)]:
        raise ValueError('baseline evidence differs from original source rendering')
    selected = windows(request['intervals'], by_row, radius if condition.startswith('context') else 0)
    manual = condition.endswith('human')
    items = dict(focal_auto)
    origins = set(items)
    if manual:
        for row in focal_rows:
            for item in target_items(by_row[row], manual=True):
                if item['kind'] == 'manual-annotation':
                    items[item['id']] = item
                    origins.add(item['id'])
    if condition.startswith('context'):
        for target in sorted(by_row.values(), key=lambda t: (t['part'], t['start'], t['row'])):
            # ASR extents can straddle source rows, so select each atom by its own extent.
            for item in target_items(target, manual=manual):
                if item['id'] not in origins and overlaps(item, selected):
                    origins.add(item['id'])
                    contextual = context_copy(item)
                    items[contextual['id']] = contextual
    result = copy.deepcopy(request)
    result['items'] = [items[k] for k in sorted(items)]
    result.pop('identity')
    result['identity'] = v1.identity(result)
    return result, dict(windows=selected, originalItems=len(focal_auto), totalItems=len(items),
                        addedItems=sorted(set(items)-set(focal_auto)),
                        selection='positive overlap, same part, clipped to the retained source cut; full records; no claim-conditioned filtering')
