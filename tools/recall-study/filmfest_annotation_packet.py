#!/usr/bin/env python3
"""Blinded local diagnostic packet, independent answer intake and uncertainty-aware scoring.

Never send or commit packet/source/recall prose. Sample strata are model-selected development
diagnostics, not a prevalence sample. The annotator receives no model decisions, scores, old gold
or sampling stratum. An independent human must supply judgments; blank answers never count.
"""
import argparse
import collections
import json
from pathlib import Path

import filmfest_diagnostics as diag
import filmfest_gold_film as gold

PHENOMENA = {'localization','ambiguity','merge','revisit','external','insufficient_evidence'}
EXTERNAL = {'Association','Commentary','SourceConsistentInference','Intrusion','Uninterpretable'}


def prepare(diagnostics, reports, annotation, output):
    receipt = json.loads((diagnostics/'receipt.json').read_text())
    if receipt.get('schema')!='storymodel4s.filmfestival.diagnostics/v1':
        raise ValueError('unsupported diagnostic receipt')
    for name in ('jl-all.units.json','comparisons.json'):
        if receipt.get('artifacts',{}).get(name)!=gold.digest(diagnostics/name):
            raise ValueError('diagnostic artifact changed after receipt')
    expected_inputs = receipt['arms']['jl-all']['inputs']
    if expected_inputs.get(str(annotation.resolve()))!=gold.digest(annotation):
        raise ValueError('packet source differs from diagnostic input')
    rows = json.loads((diagnostics/'jl-all.units.json').read_text())
    diagnosed = {(r['participant'],r['unit']):r for r in rows}
    if len(diagnosed)!=len(rows): raise ValueError('duplicate diagnostic unit')
    comparisons = json.loads((diagnostics/'comparisons.json').read_text())
    if len(comparisons)!=1: raise ValueError('packet requires the fixed two-arm comparison')
    sample = next(iter(comparisons.values()))['sample']
    chosen = {(r['participant'],r['unit']):r['kind'] for r in sample}
    # Additional cases expose uncertainty/external decisions, without calling them human labels.
    for name, ordered in [
        ('external-mass',sorted(rows,key=lambda r:(-r['externalMass'],diag.digest_value([r['participant'],r['unit']])))),
        ('diffuse-film-mass',sorted(rows,key=lambda r:(max(r['filmMass'].values(),default=0.),diag.digest_value([r['participant'],r['unit']]))))]:
        for r in ordered[:6]: chosen.setdefault((r['participant'],r['unit']),name)
    index = diag.source_index(annotation)
    source_rows = diag.read_tsv(annotation)
    universe = []
    for r in source_rows:
        universe.append({'ref':f"sit:filmfest:seg:{int(r['segment']):04d}", 'film':r['film'],
            'scene':r['scene_number'] or None, 'description':r['description'], 'part':r['part_id'],
            'annotationStart':r['start_s'],'annotationEnd':r['end_s'] or None})
    for scene in sorted({int(r['scene_number']) for r in source_rows if r['scene_number']}):
        members = [u for u in universe if u['scene']==str(scene)]
        universe.append({'ref':f'seg:filmfest:scene:{scene:03d}', 'film':members[0]['film'],
                         'scene':str(scene), 'description':' '.join(u['description'] for u in members)})
    if {u['ref'] for u in universe} != set(index): raise ValueError('incomplete source universe')
    cases, private = [], []
    for sub in sorted({s for s,_ in chosen}):
        report = reports/f'recall-map-{sub}.tsv'
        if expected_inputs.get(str(report.resolve()))!=gold.digest(report):
            raise ValueError('packet report differs from diagnostic input')
        rs = diag.read_tsv(report)
        diag.unique(rs,'unit')
        by = {int(r['unit']):(i,r) for i,r in enumerate(rs)}
        for participant,ordinal in sorted(chosen):
            if participant != sub: continue
            i,r = by[ordinal]
            signature = gold.hashlib.sha256(json.dumps([r['recallText'],gold.finite(r['recallOnsetSeconds']),
                gold.finite(r['recallLastWordOnsetSeconds'])]).encode()).hexdigest()
            if diagnosed[(sub,ordinal)]['signature']!=signature:
                raise ValueError('packet recall signature differs from diagnostic unit')
            case_id = diag.digest_value(['filmfest-blind-v1',sub,ordinal])[:20]
            cases.append({'caseId':case_id,'recall':r['recallText'],
                'previousRecall':rs[i-1]['recallText'] if i else None,
                'nextRecall':rs[i+1]['recallText'] if i+1<len(rs) else None,
                'recallContext':[x['recallText'] for x in rs],'centralContextIndex':i})
            private.append({'caseId':case_id,'participant':sub,'unit':ordinal,'stratum':chosen[(sub,ordinal)],
                            'reportSha256':gold.digest(report)})
    cases.sort(key=lambda c:c['caseId'])
    packet = {'schema':'storymodel4s.filmfestival.annotation-packet/v1',
        'instructions':[
            'Judge the central recall using the source evidence; adjacent recall supplies context only.',
            'For each plausible interpretation give the source refs that must jointly hold, or one external state.',
            'Several interpretations express ambiguity; several source refs within one interpretation express a merge.',
            'Use insufficient_evidence when the available source cannot support a judgment. Do not force an anchor.',
            'Mark revisit only if return to an earlier source event is supported by the recall context.',
            'Annotation coordinates are proxies. Source-description judgments do not establish video timing.',
            'Do not consult model outputs or existing gold. Record the evidence basis and your independence attestation.'
        ], 'cases':cases,'sourceEvidence':universe,
        'sourceSha256':gold.digest(annotation),'availableBasis':'source-descriptions',
        'phenomena':sorted(PHENOMENA),'externalStates':sorted(EXTERNAL)}
    output.mkdir(parents=True,exist_ok=False)
    packet_path = output/'packet.json'
    packet_path.write_text(json.dumps(packet,indent=2,ensure_ascii=False)+'\n')
    template = {'schema':'storymodel4s.filmfestival.annotation-answers/v1','packetSha256':gold.digest(packet_path),
        'annotator':None,'independenceAttested':False,'basis':None,
        'answers':[{'caseId':c['caseId'],'phenomena':[],'interpretations':[],'evidenceNote':None} for c in cases]}
    (output/'answers-template.json').write_text(json.dumps(template,indent=2)+'\n')
    (output/'PRIVATE-selection.json').write_text(json.dumps({'schema':'filmfest-private-selection/v1',
        'packetSha256':gold.digest(packet_path),'diagnosticsReceiptSha256':gold.digest(diagnostics/'receipt.json'),
        'selection':private,'diagnosticRowsSha256':gold.digest(diagnostics/'jl-all.units.json')},indent=2)+'\n')
    return {'cases':len(cases),'sourceRefs':len(universe),'packetSha256':gold.digest(packet_path)}


def validate_answers(packet_path, answers_path):
    packet, answers = json.loads(packet_path.read_text()),json.loads(answers_path.read_text())
    if packet.get('schema')!='storymodel4s.filmfestival.annotation-packet/v1' or answers.get('schema')!='storymodel4s.filmfestival.annotation-answers/v1':
        raise ValueError('unsupported annotation schema')
    if answers.get('packetSha256')!=gold.digest(packet_path): raise ValueError('answer packet checksum differs')
    if not str(answers.get('annotator') or '').strip() or answers.get('independenceAttested') is not True:
        raise ValueError('named independent annotator attestation required')
    if answers.get('basis')!='source-descriptions':
        raise ValueError('this packet only establishes a source-description evidence basis')
    expected = {c['caseId'] for c in packet['cases']}
    actual = diag.unique(answers['answers'],'caseId')
    if set(actual)!=expected: raise ValueError('answers must account for every packet case')
    refs = {r['ref'] for r in packet['sourceEvidence']}
    for answer in actual.values():
        phenomena = answer['phenomena']
        if not phenomena or len(set(phenomena))!=len(phenomena) or not set(phenomena)<=PHENOMENA:
            raise ValueError('blank or unknown diagnostic phenomena')
        if not str(answer.get('evidenceNote') or '').strip(): raise ValueError('evidence note required')
        interpretations = answer['interpretations']
        if 'insufficient_evidence' in phenomena:
            if phenomena!=['insufficient_evidence'] or interpretations: raise ValueError('insufficient evidence cannot carry accepted anchors')
            continue
        if not interpretations: raise ValueError('supported judgment requires an interpretation')
        if ('ambiguity' in phenomena) != (len(interpretations)>1): raise ValueError('ambiguity must preserve alternative interpretations')
        has_merge = False
        has_external = False
        identities = []
        for interpretation in interpretations:
            anchors, external = interpretation['anchors'], interpretation['external']
            if len(set(anchors))!=len(anchors) or not set(anchors)<=refs: raise ValueError('duplicate or unknown interpretation anchor')
            if bool(anchors)==(external is not None): raise ValueError('interpretation must choose anchors or external')
            if external is not None and external not in EXTERNAL: raise ValueError('unknown external judgment')
            identities.append(diag.digest_value([sorted(anchors),external]))
            has_merge |= len(anchors)>1
            has_external |= external is not None
        if len(set(identities))!=len(identities): raise ValueError('duplicate interpretations are not ambiguity')
        if ('merge' in phenomena)!=has_merge or ('external' in phenomena)!=has_external:
            raise ValueError('phenomena disagree with interpretations')
    return packet,answers


def score_answers(packet_path, answers_path, selection_path, diagnostics):
    packet,answers = validate_answers(packet_path,answers_path)
    selection = json.loads(selection_path.read_text())
    if selection['packetSha256']!=gold.digest(packet_path): raise ValueError('private selection packet differs')
    if selection['diagnosticsReceiptSha256']!=gold.digest(diagnostics/'receipt.json'):
        raise ValueError('diagnostic receipt changed after packet preparation')
    if selection['diagnosticRowsSha256']!=gold.digest(diagnostics/'jl-all.units.json'):
        raise ValueError('diagnostic rows changed after packet selection')
    rows = {(r['participant'],r['unit']):r for r in json.loads((diagnostics/'jl-all.units.json').read_text())}
    selected = diag.unique(selection['selection'],'caseId')
    if set(selected)!={c['caseId'] for c in packet['cases']}: raise ValueError('private selection case population differs')
    cases = []
    for a in answers['answers']:
        key = selected[a['caseId']]
        r = rows[(key['participant'],key['unit'])]
        if 'insufficient_evidence' in a['phenomena']:
            cases.append({'caseId':a['caseId'],'status':'insufficient_evidence'}); continue
        anchor_sets = [set(i['anchors']) for i in a['interpretations'] if i['anchors']]
        external = {i['external'] for i in a['interpretations'] if i['external']}
        accepted = set().union(*anchor_sets) if anchor_sets else set()
        # Film-level mass is deliberately too coarse to grade within-film localization or a merge.
        # Exact final-ref membership is a diagnostic, not whole-interpretation correctness.
        cases.append({'caseId':a['caseId'],'status':'observed','phenomena':a['phenomena'],
            'finalRefInAcceptedUnion':any(r['finalRef'] in s for s in anchor_sets),
            'acceptedSourceUnionMass':sum(r['anchorMass'].get(ref,0.) for ref in accepted),
            'requiredAnchorSupportByInterpretation':[all(r['anchorMass'].get(ref,0.)>0 for ref in s) for s in anchor_sets],
            'acceptedExternalMass':sum(r['externalStates'].get(e,0.) for e in external),
            'mergeFullRecovery':None if any(len(s)>1 for s in anchor_sets) else 'not_applicable',
            'localizationBasis':'source-description reference; no video clock claim'})
    return {'schema':'storymodel4s.filmfestival.annotation-score/v1','packetSha256':gold.digest(packet_path),
        'answersSha256':gold.digest(answers_path),'annotator':answers['annotator'],'basis':answers['basis'],
        'cases':cases,'counts':dict(collections.Counter(c['status'] for c in cases)),
        'claim':'independent judgments on model-selected development cases; no prevalence or confirmation estimate'}


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    sub=p.add_subparsers(dest='command',required=True)
    prep=sub.add_parser('prepare')
    for name in ('diagnostics','reports','annotation','output'): prep.add_argument(name,type=Path)
    score=sub.add_parser('score')
    for name in ('packet','answers','selection','diagnostics','output'): score.add_argument(name,type=Path)
    a=p.parse_args()
    if a.command=='prepare': print(json.dumps(prepare(a.diagnostics,a.reports,a.annotation,a.output),indent=2))
    else:
        result=score_answers(a.packet,a.answers,a.selection,a.diagnostics)
        with a.output.open('x') as f: json.dump(result,f,indent=2,sort_keys=True)
