"""S3 exact-candidate mutation/release runner. Logs are append-only by refusal."""
from pathlib import Path
import hashlib,json,os,re,subprocess,sys,time,xml.etree.ElementTree as ET

root=Path(os.environ.get('D1A_S3_ROOT','/Users/bbuchsbaum/code/scala/storymodel4s'))
repo=Path(os.environ.get('D1A_S3_REPO','/private/tmp/storymodel4s-d1a-s3-20260919'))
out=Path(os.environ.get('D1A_S3_OUTPUT',str(root/'data/study/d1a-s3-20260919')))
out.mkdir(parents=True,exist_ok=True)
pin=os.environ.get('D1A_S3_GRAKERN','/private/tmp/storymodel4s-film-consumer-20260919-9zqdg50z/grakern')
revision=subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip()
prefix=['sbt','-Dstorymodel4s.grakern.build='+pin,'-batch']
def sha(b): return hashlib.sha256(b).hexdigest()
def clean(): return not subprocess.check_output(['git','status','--porcelain'],cwd=repo)
def run(label,args,success=True):
    log=out/(label+'.log');assert not log.exists(),log
    before=clean();started=time.time()
    with log.open('w') as stream:
        result=subprocess.run(args,cwd=repo,stdout=stream,stderr=subprocess.STDOUT)
    text=log.read_text();totals=[l for l in text.splitlines() if l.startswith(('[info] Passed: Total','[info] Failed: Total','[error] Failed: Total'))]
    counts=dict.fromkeys(['Total','Failed','Errors','Passed','Skipped'],0)
    for line in totals:
        for key,value in re.findall(r'(Total|Failed|Errors|Passed|Skipped) (\d+)',line): counts[key]+=int(value)
    receipt=dict(codeRevision=revision,command=args,exitCode=result.returncode,cleanBefore=before,cleanAfter=clean(),startedEpochSeconds=started,seconds=time.time()-started,logPath=str(log),logSha256=sha(log.read_bytes()),testTotals=totals,aggregateTestCounts=counts)
    (out/(label+'.json')).write_text(json.dumps(receipt,indent=2)+'\n')
    print(label,result.returncode,counts,flush=True)
    if success and result.returncode: raise RuntimeError(label)
    return receipt
def replace(old,new):
    def change(s):
        assert s.count(old)==1,(old,s.count(old));return s.replace(old,new)
    return change
def region(start,end,old,new):
    def change(s):
        a=s.index(start);b=s.index(end,a);part=s[a:b]
        assert part.count(old)==1,(old,part.count(old))
        return s[:a]+part.replace(old,new)+s[b:]
    return change

proposal='acquire/src/main/scala/storymodel4s/acquire/proposal.scala'
resolve='acquire/src/main/scala/storymodel4s/acquire/resolve.scala'
task='acquire/src/main/scala/storymodel4s/acquire/task.scala'
compiler='document/src/main/scala/storymodel4s/document/compiler.scala'
codec='codec/src/main/scala/storymodel4s/codec/derivation.scala'
support_suite='storymodel4s.acquire.D1aAcquireSupportSuite'
parity_suite='storymodel4s.acquire.D1aTextResolverParitySuite'
task_suite='storymodel4s.acquire.D1aTaskReferencesSuite'
compiler_suite='storymodel4s.document.CompilerSuite'
wire_suite='storymodel4s.codec.D1aGapWireSuite'
codec_control='storymodel4s.codec.EvidenceSupportCodecSuite'
text_control='accepting control: existing text support retains spans and acceptance'
anchor_control='anchored bundle support alone admits determined and calibrated winners'
task_control='accepting control: anchored references use bound parser surface units'
wire_test='historical direct-support failure and gap names keep literal wire values'
groups={
 'support':('acquireJVM',[support_suite]),
 'parity':('acquireJVM',[parity_suite,support_suite]),
 'task':('acquireJVM',[task_suite]),
 'compiler':('documentJVM',[compiler_suite]),
 'wire':('codecJVM',[wire_suite,codec_control])}
cases=[]
def add(label,path,change,group,reject_suite,reject,control_suite,control):
    cases.append(dict(id=label,path=path,change=change,group=group,rejectSuite=reject_suite,rejectingTest=reject,controlSuite=control_suite,acceptingControl=control))
def support_case(label,path,change,reject,control=text_control):
    add(label,path,change,'support',support_suite,reject,support_suite,control)
support_case('inline-text-loss',proposal,replace('Some(TypedSupport.Text(spans))','Some(TypedSupport.Text(spans)).filter(_ => false)'),
    'inline singular support covers neither, text, anchors and twin form without selecting a winner')
support_case('inline-anchor-loss',proposal,replace('Some(TypedSupport.Anchored(anchors))','Some(TypedSupport.Anchored(anchors)).filter(_ => false)'),
    'winning inline anchored support alone admits determined and calibrated winners')
support_case('twin-form-preference',proposal,replace('case _                     => None','case (Some(_), Some(anchors)) => Some(TypedSupport.Anchored(anchors))\n        case _ => None'),
    'twin-form inline evidence alone is ambiguous but does not erase another valid witness')
support_case('by-id-invention',proposal,region('def support:', '/** What a proposal conflicts', 'case ById(_)   => None',
    'case ById(_) => Some(TypedSupport.Text(SpanSet.one(TextSpan.unsafe(0, 1))))'),
    'ById cannot invent direct support without a ledger')
support_case('bundle-anchor-loss',resolve,replace('bundle.sourceSupport.support.nonEmpty','bundle.sourceSupport.spans.nonEmpty'),anchor_control)
support_case('winning-anchor-loss',resolve,replace('c.evidence.exists(_.support.nonEmpty)','c.evidence.exists(_.spans.nonEmpty)'),
    'winning inline anchored support alone admits determined and calibrated winners')
support_case('losing-evidence-counts',resolve,replace('c.evidence.exists(_.support.nonEmpty)',
    '(c.evidence ++ bundle.proposals.flatMap(_.evidence)).exists(_.support.nonEmpty)'),
    'losing inline anchors do not support the winner')
support_case('derived-text-span-loss',resolve,replace('support.collect { case TypedSupport.Text(spans) => spans }','support.flatMap(_ => None)'),
    text_control,anchor_control)
support_case('text-constructor-loss',resolve,replace('spans.map(TypedSupport.Text(_))','spans.filter(_ => false).map(TypedSupport.Text(_))'),
    text_control,anchor_control)
add('current-resolver-text-loss',resolve,replace(
    'bundle.sourceSupport.support.nonEmpty || c.evidence.exists(_.support.nonEmpty)',
    'bundle.sourceSupport.support.exists { case TypedSupport.Anchored(_) => true; case _ => false } || c.evidence.exists(_.support.exists { case TypedSupport.Anchored(_) => true; case _ => false })'),
    'parity',parity_suite,'all 8640 declared text-policy cases preserve complete resolution states',support_suite,anchor_control)
for label,old,new,reject in [
 ('task-missing-unit','.surface.byId.get(id).exists(_.kind == kind)','.surface.byId.get(id).forall(_.kind == kind)',
  'anchored references refuse missing and wrong-kind parser units'),
 ('task-wrong-kind','.surface.byId.get(id).exists(_.kind == kind)','.surface.byId.get(id).exists(unit => unit.kind == kind || true)',
  'anchored references refuse missing and wrong-kind parser units'),
 ('task-missing-surface','anchored.surface.exists','anchored.surface.forall',
  'absence of a bound proposal surface refuses all parser references'),
 ('task-text-refusal','validateAgainst(text.atlas)','validateAgainst(text.atlas).orElse(this.validNec)',
  'text atlas overload preserves complete validation and error order')]:
    add(label,task,replace(old,new),'task',task_suite,reject,task_suite,task_control)
add('text-source-support-guard',compiler,region('unitInterval(bundle.sourceSupport.score,','unitInterval(bundle.agreementScore,',
    'case TypedSupport.Anchored(_) => true','case TypedSupport.Anchored(_) => false'),
    'compiler',compiler_suite,'text compiler refuses anchored source support with text and absent controls',
    compiler_suite,'accepted evidence compiles into a validated story model')
for label,path,old,new in [
 ('wire-resolution-name',codec,'case Some("NoSpanEvidence")','case Some("NoSupportEvidence")'),
 ('wire-gap-name',codec,'case Some("MissingSpanEvidence")','case Some("MissingSupportEvidence")'),
 ('wire-gap-render',compiler,'"missing-span-evidence"','"missing-support-evidence"')]:
    add(label,path,replace(old,new),'wire',wire_suite,wire_test,codec_control,
        'accepting control: ordinary text evidence keeps its omitted-anchor wire and round trip')

def inventory():
    for c in cases: c['change']((repo/c['path']).read_text())
    value=dict(codeRevision=revision,plannedMutationCount=len(cases),inventoryStatus='settled before mutation and full release gates',
        cases=[{k:v for k,v in c.items() if k!='change'} for c in cases],
        boundaries=['Pure resolver support presence is not membership/projectability or SurfaceExplicit licensing.',
          'No new checked constructor or compile-time construction boundary is introduced; no clean construction-probe mutant is required.',
          'Current-Resolver-only text mutant leaves the historical oracle and shared conversion helpers unchanged; direct helper mutants separately test text conversion/access.',
          'All numerical score, basis, ranking, provider-agreement and structural/critic policies remain unchanged.'])
    (out/'guard-witness-inventory.json').write_text(json.dumps(value,indent=2)+'\n')
    return value
def report_path(project,suite):
    module={'acquireJVM':'acquire','documentJVM':'document','codecJVM':'codec'}[project]
    return repo/module/'.jvm/target/test-reports'/('TEST-'+suite+'.xml')
def command(group):
    project,suites=groups[group]
    return project+'/testOnly '+' '.join(suites)
def mutations():
    assert clean();inventory()
    control_tasks=['acquireJVM/testOnly '+support_suite+' '+parity_suite+' '+task_suite,
        command('compiler'),command('wire')]
    if not (out/'isolated-control.json').exists(): run('isolated-control',prefix+control_tasks)
    results=json.loads((out/'mutation-progress.json').read_text()) if (out/'mutation-progress.json').exists() else []
    completed={r['id'] for r in results}
    for c in cases:
        if c['id'] in completed: continue
        path=repo/c['path'];saved=path.read_bytes();mutant=c['change'](saved.decode())
        project,suites=groups[c['group']]
        for suite in suites: report_path(project,suite).unlink(missing_ok=True)
        try:
            path.write_text(mutant)
            receipt=run(c['id'],prefix+[command(c['group'])],False)
            assert receipt['exitCode']==1 and receipt['aggregateTestCounts']['Failed']>0 and receipt['aggregateTestCounts']['Errors']==0,(c['id'],'not an executed failing mutant')
            outcomes={};xml_hashes={}
            for suite in suites:
                report=report_path(project,suite);assert report.exists(),report
                xml=report.read_bytes();(out/(c['id']+'--'+suite+'.xml')).write_bytes(xml)
                xml_hashes[suite]=sha(xml)
                for t in ET.fromstring(xml).findall('testcase'):
                    outcomes[(suite,t.attrib['name'])]=t
            rejecting=outcomes[(c['rejectSuite'],c['rejectingTest'])]
            control=outcomes[(c['controlSuite'],c['acceptingControl'])]
            assert rejecting.find('failure') is not None,(c['id'],'survived')
            assert all(control.find(k) is None for k in ['failure','error','skipped']),(c['id'],'control failed')
            receipt.update({k:v for k,v in c.items() if k!='change'})
            receipt.update(compiled=True,acceptingControlPassed=True,originalSha256=sha(saved),mutantSha256=sha(mutant.encode()),junitSha256=xml_hashes,
                failedTests=[dict(suite=s,name=n) for (s,n),t in outcomes.items() if t.find('failure') is not None])
            results.append(receipt)
            (out/'mutation-progress.json').write_text(json.dumps(results,indent=2)+'\n')
            print(c['id'],'KILLED; named control passed',flush=True)
        finally: path.write_bytes(saved)
        assert clean()
    restored=run('restored-control',prefix+control_tasks)
    (out/'mutations.json').write_text(json.dumps(dict(codeRevision=revision,mutations=results,restoredControl=restored),indent=2)+'\n')
def release():
    assert clean()
    run('scope',['bash','tools/reference-scope.sh','e94ef776',revision])
    receipt=run('full-gate',prefix+['clean','compileAll','testAll'])
    build=(repo/'build.sbt').read_text()
    def declared(name):
        return re.findall(r'"(\w+)"',re.search(r'val '+name+r' = List\((.*?)\)',build,re.S).group(1))
    tasks=[m+p+'/test' for m in declared('allModules') for p in declared('allPlatforms')]+[m+'/test' for m in declared('jvmOnlyModules')]
    assert len(tasks)==len(receipt['testTotals'])
    receipt['taskTotalsInAliasOrder']=dict(zip(tasks,receipt['testTotals']))
    (out/'full-gate.json').write_text(json.dumps(receipt,indent=2)+'\n')
    run('format-last',prefix+['scalafmtCheckAll','scalafmtSbtCheck'])
    run('docs-examples',['env','STORYMODEL4S_GRAKERN_BUILD='+pin,'npm','--prefix','docs-site','run','verify:examples'])

if __name__=='__main__': {'inventory':inventory,'mutations':mutations,'release':release}[sys.argv[1]]()
