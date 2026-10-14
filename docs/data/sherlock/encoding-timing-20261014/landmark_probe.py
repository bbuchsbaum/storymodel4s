#!/usr/bin/env python3
"""Reproduce the fixed nine media-cut probes; retain misses without adjusting offsets."""
import argparse,hashlib,json,re,subprocess
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('plan',type=Path);p.add_argument('media_root',type=Path);p.add_argument('output',type=Path);a=p.parse_args()
a.output.mkdir(exist_ok=False)
plan=json.loads(a.plan.read_text());rows=[]
for part,entry in plan['parts'].items():
 media=a.media_root/f'Sherlock_part{part}_imovie.m4v'
 assert hashlib.file_digest(media.open('rb'),'sha256').hexdigest()==entry['expected_sha256']
 for t in entry['landmarks_seconds']:
  command=['ffmpeg','-hide_banner','-ss',str(t-3),'-i',str(media),'-t','6','-vf',"select='gt(scene,0.3)',showinfo",'-an','-f','null','-']
  run=subprocess.run(command,capture_output=True,text=True)
  (a.output/f'landmark-part{part}-{t}.log').write_text(run.stderr)
  candidates=[float(x)+t-3 for x in re.findall(r'pts_time:([0-9.]+)',run.stderr)]
  candidates=[x for x in candidates if t-3<=x<=t+3]
  nearest=min(candidates,key=lambda x:abs(x-t)) if candidates else None
  rows.append(dict(part=int(part),annotation_seconds=t,cut_candidates_seconds=candidates,nearest_seconds=nearest,residual_seconds=None if nearest is None else nearest-t,exit=run.returncode,log_sha256=hashlib.sha256(run.stderr.encode()).hexdigest()))
result={'plan_sha256':hashlib.file_digest(a.plan.open('rb'),'sha256').hexdigest(),'landmarks':rows}
(a.output/'landmarks.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps({'landmarks':len(rows),'cuts_found':sum(r['nearest_seconds'] is not None for r in rows),'failures':sum(r['exit']!=0 for r in rows)}))
