from pathlib import Path
import difflib, hashlib, json, re, subprocess, sys
ROOT=Path('/private/tmp/storymodel4s-s2b2-build')
LOGS=Path('/private/tmp/storymodel4s-s2b2-logs')
CAND=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
assert CAND.startswith('b06b72a1')
FILES=['align/src/main/scala/storymodel4s/align/surfacerender.scala','align/src/main/scala/storymodel4s/align/embeddingmetric.scala']
ORIGINAL={p:subprocess.check_output(['git','show',f'{CAND}:{p}'],cwd=ROOT).decode() for p in FILES}
RUNTIME='storymodel4s.align.SurfaceRenderingSuite'
BOUNDARY='surfaceprobe.SurfaceRenderingBoundarySuite'
M=[]
def add(name,path,old,new,failure,suite=RUNTIME,pattern='.*',clean=False):
    assert ORIGINAL[path].count(old)==1,(name,old)
    M.append(dict(name=name,path=path,old=old,new=new,failure=failure,suite=suite,pattern=pattern,clean=clean))
surf,metric=FILES
add('source-identity',surf,'if t.canonicalText == sourceAtlas.source.canonicalChecksum =>','if t.canonicalText == t.canonicalText =>','source declaration refuses different bytes even at identical text length')
add('physical-support',surf,'if p.support == node.support =>','if p.support == p.support =>','representation physical support must equal node support')
add('view-binding',surf,'representation.viewFingerprint == ViewFingerprint.of(snapshot)','representation.viewFingerprint == representation.viewFingerprint','source representation must bind the frozen view')
add('target-alias',surf,'(unit, target, grain, unitText, targetText, policy)','(unit, target, grain, unitText, policy)','same semantic target with reordered surface strings has distinct rendered identity')
add('unicode',surf,'if !unicode(text) then','if false && !unicode(text) then','unpaired source Unicode is refused before rendering')
add('anchors',surf,'r.unit.exists(id => !atlas.byId.get(id).exists(_.span.contains(r.span)))','r.unit.exists(id => false && !atlas.byId.get(id).exists(_.span.contains(r.span)))','foreign source anchors are refused before merging spans',pattern='^(?!known anchors|unit anchor).*')
add('cycle',surf,'if acyclic(snapshot.nodes) then','if acyclic(snapshot.nodes) || snapshot.nodes.nonEmpty then','self-cycle and disconnected two-node cycle return typed hierarchy refusal')
add('half-scale',metric,'case HalfCosineDistance => distance / 2.0','case HalfCosineDistance => distance','raw and half cosine retain exact declared units',pattern='^(?!cosine conversion preserves).*')
add('constructor',surf,'final class Pair private[SurfaceRendering] (','final class Pair (','rendered pair constructor is closed',suite=BOUNDARY,pattern='^(?!rendered pair apply).*',clean=True)
add('product-mirror',metric,'final class CosineDistanceValue private[EmbeddingMetric] (','final case class CosineDistanceValue private[EmbeddingMetric] (','cosine value Mirror is closed',suite=BOUNDARY,pattern='^(cosine value Mirror is closed|rendered pair .*|direct .*text reads compile)$',clean=True)
results=[]
try:
    for m in M:
        for p,s in ORIGINAL.items(): ROOT.joinpath(p).write_text(s)
        changed=ORIGINAL[m['path']].replace(m['old'],m['new'])
        ROOT.joinpath(m['path']).write_text(changed)
        patch=''.join(difflib.unified_diff(ORIGINAL[m['path']].splitlines(True),changed.splitlines(True),fromfile='a/'+m['path'],tofile='b/'+m['path']))
        LOGS.joinpath(m['name']+'.patch').write_text(patch)
        cmd=['sbt','-batch','-Dstorymodel4s.grakern.build=/Users/bbuchsbaum/code/scala/grakern','set ThisBuild / tlFatalWarnings := true']
        if m['clean']:cmd.append('alignJVM/Test/clean')
        cmd.append(f'alignJVM/testOnly {m["suite"]} -- "--tests={m["pattern"]}"')
        wrapper=['python3','/Users/bbuchsbaum/.agents/skills/lean-logs/scripts/run_logged.py','--log',str(LOGS/(m['name']+'.log')),'--cwd',str(ROOT),'--timeout','600','--head-bytes','0','--tail-bytes','0','--']+cmd
        result=subprocess.run(wrapper,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
        log=LOGS.joinpath(m['name']+'.log').read_text()
        plain=re.sub(r'\x1b\[[0-9;]*[A-Za-z]','',log)
        totals=[list(map(int,x)) for x in re.findall(r'(?:Passed|Failed): Total (\d+), Failed (\d+), Errors (\d+), Passed (\d+)',plain)]
        failures=[l for l in plain.splitlines() if l.startswith('==> X')]
        compiled=bool(re.search(r'compiling [12] Scala sources? to .*align/\.jvm/target/[^\n]*/classes',plain))
        r=dict(mutation=m['name'],candidate_sha=CAND,path=m['path'],argv=cmd,exit=result.returncode,compiled=compiled,totals=totals,named_failure=m['failure'],failures=failures,source_sha256=hashlib.sha256(changed.encode()).hexdigest(),patch_sha256=hashlib.sha256(patch.encode()).hexdigest(),log_sha256=hashlib.sha256(LOGS.joinpath(m['name']+'.log').read_bytes()).hexdigest(),clean_test_recompile=m['clean'])
        results.append(r)
        LOGS.joinpath('mutations.json').write_text(json.dumps(results,indent=2)+'\n')
        assert result.returncode==1 and compiled and len(totals)==1 and totals[0][1:3]==[1,0] and totals[0][3]>0,(m['name'],r,result.stdout)
        assert len(failures)==1 and m['failure'] in failures[0] and 'munit.' in failures[0],r
        assert 'done compiling' in plain and '[error] --' not in plain,r
        print(f'{m["name"]}: compiled; named assertion failed; {totals[0][3]} sibling controls passed',flush=True)
finally:
    for p,s in ORIGINAL.items():ROOT.joinpath(p).write_text(s)
    assert subprocess.run(['git','diff','--exit-code'],cwd=ROOT,stdout=subprocess.PIPE).returncode==0
