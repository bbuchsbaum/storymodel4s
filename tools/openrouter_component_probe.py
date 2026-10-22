#!/usr/bin/env python3
"""Serial, one-attempt component experiment; stop on rate limits and retain denominator."""
import argparse
import json
import math
import os
from pathlib import Path
import time
import urllib.error
import urllib.request
import claim_assessment_probe as base
import component_assessment_probe as protocol
import openrouter_claim_probe as remote


def body(request):
    return dict(model=remote.MODEL, temperature=0, max_tokens=8192,
                reasoning=dict(effort='low', exclude=True), stream=False,
                provider=dict(remote.PROVIDER,max_price=dict(prompt=.30,completion=1.20,request=0)),
                messages=[dict(role='user',content=protocol.PROMPT+'\nINPUT:\n'+
                               json.dumps(protocol.model_input(request),ensure_ascii=False))])


def interpret(request,response):
    invalid = dict(processing='invalid_response',raw=None,proposal=None)
    if not isinstance(response,dict):
        return invalid
    if response.get('model') != remote.MODEL or response.get('provider') != 'Fireworks':
        return dict(invalid,processing='foreign_provider_or_model')
    choices = response.get('choices')
    if not isinstance(choices,list) or len(choices)!=1 or not isinstance(choices[0],dict):
        return invalid
    choice=choices[0]; message=choice.get('message')
    if not isinstance(message,dict):
        return invalid
    raw=message.get('content')
    if choice.get('finish_reason')!='stop' or message.get('tool_calls') or not isinstance(raw,str):
        return dict(invalid,processing='unfinished_or_nontext_response',raw=raw if isinstance(raw,str) else None)
    try:
        return dict(processing='valid',raw=raw,proposal=protocol.validate_proposal(request,raw))
    except (ValueError,KeyError,TypeError,AttributeError) as error:
        return dict(processing='invalid_proposal',raw=raw,proposal=None,errorClass=type(error).__name__)


def execute(record,key):
    request=record['request']; payload=body(request)
    encoded=json.dumps(payload,ensure_ascii=False).encode()
    out=dict(condition=record['condition'],question=request['question'],requestIdentity=request['identity'],
             bodySha256=base.hashlib.sha256(encoded).hexdigest(),attempts=1,raw=None,proposal=None)
    start=time.monotonic()
    call=urllib.request.Request(remote.ENDPOINT,data=encoded,method='POST',headers={
        'Authorization':'Bearer '+key,'Content-Type':'application/json'})
    try:
        with urllib.request.urlopen(call,timeout=120) as stream:
            response=json.load(stream)
        out.update(interpret(request,response))
        if isinstance(response,dict):
            out.update(usage=response.get('usage'),generationId=response.get('id'),model=response.get('model'),
                       provider=response.get('provider'))
    except urllib.error.HTTPError as error:
        out.update(processing='http_failure',httpStatus=error.code)
        # Numeric retry delay only; neither headers nor potentially echoed request bodies are logged.
        retry=error.headers.get('Retry-After','')
        if retry.isdigit():
            out['retryAfterSeconds']=int(retry)
    except (OSError,ValueError,KeyError,TypeError) as error:
        out.update(processing='transport_failure',errorClass=type(error).__name__)
    out['seconds']=time.monotonic()-start
    return out


def run(root):
    freeze=base.read(root/'freeze.json')
    for path,digest in freeze['files'].items():
        if base.sha(path)!=digest:
            raise ValueError('frozen artifact changed')
    for path in (Path(__file__),Path(protocol.__file__),Path(base.__file__),Path(remote.__file__),root/'requests.json'):
        if freeze['files'].get(str(path.absolute()))!=base.sha(path):
            raise ValueError('consumed code/input not frozen')
    records=base.read(root/'requests.json')['executions']
    keys=[(r['condition'],r['request']['question']) for r in records]
    if not records or len(keys)!=len(set(keys)):
        raise ValueError('empty or duplicate executions')
    for r in records:
        req=r['request']
        if req['identity']!=base.identity({k:v for k,v in req.items() if k!='identity'}):
            raise ValueError('request identity mismatch')
    bound=sum(remote.cost_bound(body(r['request'])) for r in records)
    budget=freeze['budgetUsd']; prior=freeze['priorCostBoundUsd']
    if not all(isinstance(n,(int,float)) and not isinstance(n,bool) and math.isfinite(n) for n in (budget,prior)):
        raise ValueError('invalid budget')
    if not 0<budget<=5 or prior<0 or bound+prior>budget:
        raise ValueError('whole-round conservative budget exceeded')
    key=os.environ.get('OPENROUTER_API_KEY')
    if not key:
        raise ValueError('OPENROUTER_API_KEY absent')
    output=root/'outputs.jsonl'; results=[]; stopped=False
    with output.open('x') as stream:
        for i,r in enumerate(records):
            if stopped:
                out=dict(condition=r['condition'],question=r['request']['question'],
                         requestIdentity=r['request']['identity'],attempts=0,processing='unattempted_after_rate_limit',
                         raw=None,proposal=None)
            else:
                out=execute(r,key)
                stopped=out.get('httpStatus')==429
            results.append(out); stream.write(json.dumps(out,ensure_ascii=False)+'\n'); stream.flush()
            print(f"Completed {i+1}/{len(records)}: {out['processing']}",flush=True)
    costs=[r['usage']['cost'] for r in results if isinstance(r.get('usage'),dict)
           and isinstance(r['usage'].get('cost'),(int,float))]
    attempted_bound=sum(remote.cost_bound(body(r['request'])) for r,o in zip(records,results) if o['attempts'])
    base.write(root/'execution.json',dict(freezeSha256=base.sha(root/'freeze.json'),
        outputsSha256=base.sha(output),requested=len(records),attempted=sum(o['attempts'] for o in results),
        valid=sum(o['processing']=='valid' for o in results),conservativeCostBoundUsd=bound,
        attemptedCostBoundUsd=attempted_bound,reportedCostUsd=sum(costs),reportedCostCount=len(costs)))


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__); parser.add_argument('experiment',type=Path)
    run(parser.parse_args().experiment)
