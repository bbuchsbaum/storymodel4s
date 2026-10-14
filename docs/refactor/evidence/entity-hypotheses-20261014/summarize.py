#!/usr/bin/env python3
"""Aggregate frozen development diagnostics without choosing an interpretation."""
import argparse
import json
import math
from pathlib import Path


def mean(values):
    values=list(values)
    return math.fsum(values)/len(values) if values else None


def summarize(report):
    answer=[]
    for temperature in [0.5,0.15]:
        for condition in sorted({c['condition'] for c in report['cases']}):
            cases=[c for c in report['cases'] if c['temperature']==temperature and c['condition']==condition]
            rows=[row for c in cases for row in c['rows']]
            point=[row for row in rows if row['label']['kind']=='point']
            sets=[row for row in rows if row['label']['kind']=='set']
            single=[row for row in point if len(row['hypotheses'])==1]
            output={'condition':condition,'temperature':temperature,'cases':len(cases),'rows':len(rows),
                    'point_label_rows':len(point),'set_label_rows':len(sets),
                    'baseline_unique_correct_point_rows':sum(row['baseline_evaluation'].get('unique_correct',False) for row in point),
                    'robust_correct_point_rows':sum(row['robust_winner']==str(row['label']['target']) for row in point),
                    'robust_wrong_point_rows':sum(row['robust_winner'] not in [None,str(row['label']['target'])] for row in point),
                    'robust_inside_label_set':sum(row['robust_winner'] in [str(t) for t in row['label']['targets']] for row in sets),
                    'robust_outside_label_set':sum(row['robust_winner'] is not None and row['robust_winner'] not in [str(t) for t in row['label']['targets']] for row in sets),
                    'no_robust_winner_rows':sum(row['robust_winner'] is None for row in rows),
                    'single_hypothesis_point_rows':len(single),
                    'mean_external_mass_range_width':mean(row['mass_ranges']['external'][1]-row['mass_ranges']['external'][0] for row in rows),
                    'mean_organization_range_width':{k:mean(c['conditional_organization']['ranges'][k][1]-c['conditional_organization']['ranges'][k][0] for c in cases) for k in ['Backward','Forward','SameTarget','External']},
                    'assignment_count_range':[min(c['conditional_organization']['assignment_count'] for c in cases),max(c['conditional_organization']['assignment_count'] for c in cases)]}
            # Point comparisons only where the declared identity inventory has one member.
            # No best-branch or mixture score is assigned to ambiguous observations.
            if single:
                output['single_hypothesis_scores']={key:{
                    'baseline_mean':mean(row['baseline_evaluation'][key] for row in single),
                    'conditional_mean':mean(row['hypotheses'][0]['evaluation'][key] for row in single)} for key in ['nll','brier']}
            determined=[c for c in cases if c['point_source_path_truth'] is not None and all(len(row['hypotheses'])==1 for row in c['rows'])]
            if determined:
                output['single_assignment_organization_L1']={
                    'cases':len(determined),'definition':'Sum of absolute errors across four expected-count categories, then mean across cases; three actual adjacencies per case',
                    'baseline_mean':mean(math.fsum(abs(c['baseline_counts']['counts'][k]-v) for k,v in c['point_source_path_truth'].items()) for c in determined),
                    'conditional_mean':mean(math.fsum(abs(c['conditional_organization']['ranges'][k][0]-v) for k,v in c['point_source_path_truth'].items()) for c in determined)}
            answer.append(output)
    return {'scope':'Development-only diagnostics under declared fixture identity assumptions; no branch weights or selection',
            'validation':report['validation'],'summaries':answer}


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('report',type=Path);p.add_argument('output',type=Path);a=p.parse_args()
    with a.output.open('x') as f:
        json.dump(summarize(json.loads(a.report.read_text())),f,indent=2,allow_nan=False);f.write('\n')
