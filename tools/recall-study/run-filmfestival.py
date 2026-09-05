#!/usr/bin/env python3
"""Run the four declared Film Festival development arms, refusing output reuse or silent fallback.

One sbt process runs all participants of each arm. Explicit controls replace inherited experiment
knobs; receipts bind the command, Git revision, source, recalls, encoder artifacts and reports.
Source and recall prose remain in the ignored data root. No remote provider is called.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time

ARMS = ('jl-all','jl-no-cartoons','jl-six-film-identity','crowd-six-film-identity')
CONFIG = {'ORT_DISABLE_TELEMETRY':'1','STORYMODEL4S_LEXICAL_BLEND':'0.8',
          'STORYMODEL4S_CANDIDATES_PER_LEVEL':'8','STORYMODEL4S_CANDIDATES_LEXICAL_OVERLAP':'false',
          'STORYMODEL4S_PRIOR_SCALE':'1.0','STORYMODEL4S_MONOTONE_SCENE':'off',
          'STORYMODEL4S_MONOTONE_FILL':'off','STORYMODEL4S_BACKWARD_PENALTY':'hard',
          'STORYMODEL4S_FORWARD_PENALTY':'0.0'}


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('data',type=Path)
    p.add_argument('inputs',type=Path)
    p.add_argument('output',type=Path)
    p.add_argument('--grakern',type=Path,required=True)
    a=p.parse_args()
    for key in ("data", "inputs", "output", "grakern"):
        setattr(a, key, getattr(a, key).resolve())
    root=Path(__file__).resolve().parents[2]
    # Fail before a run if any tracked implementation changed after the recorded commit.
    if subprocess.check_output(['git','status','--porcelain','--untracked-files=no'],cwd=root,text=True).strip():
        raise ValueError('commit the implementation before running an arm')
    revision=subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip()
    recalls=sorted((a.data/'filmfestival/derived/recall').glob('sub-*_run-01.csv'))
    if len(recalls)!=20:
        raise ValueError(f'expected 20 first-run recalls, got {len(recalls)}')
    model=a.data/'models/onnx/model.onnx'
    tokenizer=a.data/'models/onnx/tokenizer.json'
    for f in (model,tokenizer,*recalls):
        if not f.is_file(): raise ValueError(f'missing input {f}')
    a.output.mkdir(parents=True,exist_ok=False)
    env={k:v for k,v in os.environ.items() if not k.startswith('STORYMODEL4S_')}
    env.update(CONFIG,STORYMODEL4S_DATA=str(a.data),STORYMODEL4S_ONNX_MODEL=str(model),STORYMODEL4S_ONNX_TOKENIZER=str(tokenizer))
    receipt={'schema':'storymodel4s.filmfestival.run/v1','gitRevision':revision,'configuration':CONFIG,
             'modelSha256':sha(model),'tokenizerSha256':sha(tokenizer),'recallSha256':{f.name:sha(f) for f in recalls},'arms':{}}
    receipt_path=a.output/'receipt.json'
    def save(): receipt_path.write_text(json.dumps(receipt,indent=2,sort_keys=True)+'\n')
    save()
    for arm in ARMS:
        dest=a.output/arm
        dest.mkdir()
        (dest/'parts.json').write_text('{"run-01":0.0,"run-02":1490.0}\n')
        annotation=a.inputs/f'{arm}.tsv'
        commands=[]
        for recall in recalls:
            sub=recall.stem.removesuffix('_run-01')
            report=dest/f'recall-map-{sub}.tsv'
            for f in (annotation,recall,report):
                if any(c.isspace() for c in str(f)):
                    raise ValueError('sbt main argument paths must not contain whitespace')
            commands.append(f'embedBench/runMain storymodel4s.bench.filmfestival.filmFestivalRecallMap {annotation} {recall} {report} onnx')
        cmd=['sbt','-batch',f'-Dstorymodel4s.grakern.build={a.grakern}',*commands]
        started=time.time()
        entry={'annotationSha256':sha(annotation),'command':cmd,'startedEpoch':started}
        receipt['arms'][arm]=entry;save()
        print(f'starting {arm}',flush=True)
        with (dest/'run.log').open('w') as log:
            result=subprocess.run(cmd,cwd=root,env=env,stdout=log,stderr=subprocess.STDOUT)
        entry.update(exitCode=result.returncode,elapsedSeconds=time.time()-started)
        reports=sorted(dest.glob('recall-map-*.tsv'))
        entry['reports']={f.name:sha(f) for f in reports}
        entry['verifiedNeuralRuns']=(dest/'run.log').read_text().count('semantic channel: neural:onnx-sentence-encoder')
        save()
        if result.returncode or len(reports)!=20 or entry['verifiedNeuralRuns']!=20:
            raise RuntimeError(f'{arm}: incomplete run or incorrect encoder; see {dest}/run.log')
        print(f'completed {arm}: {entry["elapsedSeconds"]:.1f}s',flush=True)


if __name__=='__main__': main()
