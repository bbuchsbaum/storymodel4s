"""S4A exact-candidate mutation/release runner. Logs are append-only by refusal."""
from pathlib import Path
import hashlib,json,os,re,subprocess,sys,time,xml.etree.ElementTree as ET

root=Path(os.environ.get('D1A_S4A_ROOT','/Users/bbuchsbaum/code/scala/storymodel4s'))
repo=Path(os.environ.get('D1A_S4A_REPO','/private/tmp/storymodel4s-d1a-s4a-20260919'))
out=Path(os.environ.get('D1A_S4A_OUTPUT',str(root/'data/study/d1a-s4a-20260919')))
out.mkdir(parents=True,exist_ok=True)
pin=os.environ.get('D1A_S4A_GRAKERN','/private/tmp/storymodel4s-film-consumer-20260919-9zqdg50z/grakern')
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

core='core/src/main/scala/storymodel4s/core/support.scala'
graph='story/src/main/scala/storymodel4s/story/graph.scala'
model='story/src/main/scala/storymodel4s/story/model.scala'
trajectory='story/src/main/scala/storymodel4s/story/trajectory.scala'
codec='codec/src/main/scala/storymodel4s/codec/core.scala'
projection_suite='storymodel4s.core.D1aProjectionSuite'
boundary_suite='storymodel4s.story.D1aTextBoundarySuite'
order_suite='storymodel4s.story.D1aOrderingSuite'
graph_probe='storymodel4s.probes.D1aGraphBoundarySuite'
model_probe='storymodel4s.probes.StoryModelUnforgeableSuite'
constructor_probe='storymodel4s.story.probes.D1aModelConstructorSuite'
codec_suite='storymodel4s.codec.D1aTypedSupportCodecSuite'
projection_control='zero-length and out-of-extent spans remain deferred text validator concerns'
model_control='accepting control: ordinary text draft and internal copy remain constructible'
order_control='empty graph still refuses non-text ordering axis'
probe_control='accepting control: typed model consumer and tuple Product/Mirror compile'
codec_text='accepting control: Text support has exact historical bytes and references'
codec_anchor='Anchored component encodes full support and text decode refuses it'
groups={
 'core':('coreJVM',[projection_suite]),
 'boundary':('storyJVM',[boundary_suite]),
 'order':('storyJVM',[order_suite]),
 'graph-probe':('storyJVM',[graph_probe]),
 'model-probe':('storyJVM',[model_probe]),
 'constructor':('storyJVM',[constructor_probe]),
 'codec':('codecJVM',[codec_suite])}
cases=[]
def add(label,path,change,group,reject,control,cleanCompile=False):
    suite=groups[group][1][0]
    cases.append(dict(id=label,path=path,change=change,group=group,rejectSuite=suite,rejectingTest=reject,
        controlSuite=suite,acceptingControl=control,cleanRecompile=cleanCompile))
def c(label,old,new,reject,control=projection_control):
    add(label,core,replace(old,new),'core',reject,control)
projection_exact='accepting control: text projection retains exact UTF-16 references and hull'
c('projection-primary-kind','if bundle.primaryAxis.kind != AxisKind.TextCharacter then',
  'if bundle.primaryAxis.kind != AxisKind.TextCharacter && false then',
  'text projection refuses a non-text primary even with Text support')
c('projection-anchored-refusal','Left(SourceCanon.inv("projection/support-kind", "text projection requires Text support"))',
  'Right(PrimaryProjection.TextSpans(bundle.primaryAxis.id, SpanSet.one(TextSpan.unsafe(0, 1))))',
  'text projection refuses anchored support')
c('projection-ref-retention','Right(PrimaryProjection.TextSpans(bundle.primaryAxis.id, spans))',
  'Right(PrimaryProjection.TextSpans(bundle.primaryAxis.id, SpanSet.one(spans.minSpan)))',projection_exact)
c('projection-axis-binding','Right(PrimaryProjection.TextSpans(bundle.primaryAxis.id, spans))',
  'Right(PrimaryProjection.TextSpans(PresentationAxisId.unsafe("foreign-axis"), spans))',projection_exact,
  'text projection refuses anchored support')
c('projection-hull-end','spans.minSpan.endExclusive.toLong','spans.refs.head.span.endExclusive.toLong',projection_exact)
c('text-accessor-ref-retention','case Text(spans) => Some(spans)',
  'case Text(spans) => Some(SpanSet.one(spans.minSpan))',projection_exact)
c('anchored-accessor-no-cast','case Anchored(_) => None',
  'case Anchored(_) => Some(SpanSet.one(TextSpan.unsafe(0, 1)))',projection_exact)
add('graph-primary-kind',graph,replace('if bundle.primaryAxis.kind != AxisKind.TextCharacter then',
    'if bundle.primaryAxis.kind != AxisKind.TextCharacter && false then'),'order',order_control,
    'equal projected hulls use id independent of map insertion order')
add('order-start-precedence',graph,replace('(start, end, id)','(start * 0L, end, id)'),
    'order','projected start precedes end when their orders disagree',order_control)
add('order-end-precedence',graph,replace('(start, end, id)','(start, end * 0L, id)'),
    'order','accepting control: projection order uses start then full hull end then id',order_control)
add('order-id-tie',graph,replace('(start, end, id)',
    '(start, end, id.value.map(c => (Char.MaxValue - c).toChar))'),
    'order','equal projected hulls use id independent of map insertion order',order_control)
add('model-all-text-guard',model,replace('if wrongSupport.nonEmpty then','if wrongSupport.nonEmpty && false then'),
    'boundary','text draft and copy refuse anchored entity support',model_control)
for kind in ['entities','segments','contexts']:
    add('model-enumerate-'+kind,graph,replace(kind+'.values.toVector.map(n => s"'+kind+'/${n.id.value}" -> n.support)',
        kind+'.values.toVector.filter(_ => false).map(n => s"'+kind+'/${n.id.value}" -> n.support)'),
        'boundary','text draft and copy refuse anchored '+{'entities':'entity','segments':'segment','contexts':'context'}[kind]+' support',model_control)
add('model-enumerate-circumstances',graph,replace('relations.circumstances.zipWithIndex.map',
    'relations.circumstances.filter(_ => false).zipWithIndex.map'),'boundary',
    'text draft and copy refuse anchored circumstance support',model_control)
add('model-claim-evidence',model,replace('else if allClaims.exists(_.evidence.exists(_.anchors.nonEmpty)) then',
    'else if allClaims.exists(_.evidence.exists(_.anchors.nonEmpty)) && false then'),
    'boundary','text draft and copy refuse anchored ordinary claim evidence before export',model_control)
add('model-hierarchy-boundary',model,replace('(hierarchy.boundaryBeliefs ++ trajectory.steps.flatMap(_.boundaryBeliefs))',
    '(hierarchy.boundaryBeliefs.filter(_ => false) ++ trajectory.steps.flatMap(_.boundaryBeliefs))'),
    'boundary','text draft and copy refuse anchored hierarchy boundary evidence',model_control)
add('model-step-boundary',model,replace('(hierarchy.boundaryBeliefs ++ trajectory.steps.flatMap(_.boundaryBeliefs))',
    '(hierarchy.boundaryBeliefs ++ trajectory.steps.flatMap(_.boundaryBeliefs).filter(_ => false))'),
    'boundary','text draft and copy refuse step-only anchored and twin boundary evidence',
    'accepting control: text boundary evidence may live only on a trajectory step')
constructor_args='schemaVersion, source, atlas, graph, hierarchy, trajectory, featureSpaces, sidecars, featureRefs, descriptors, hypotheses, sensoryProfiles, receipt, discourseOrder'
def bypass_copy(s):
    start=s.index('    StoryModel.checked[T](');end=s.index('\n\n  private[story] def withStatus',start)
    return s[:start]+'    Right(new StoryModel[T]('+constructor_args+'))'+s[end:]
add('copy-shared-admission',model,bypass_copy,'boundary',
    'text draft and copy refuse anchored ordinary claim evidence before export',model_control)
add('copy-stale-order',model,region('private[story] def copy','private[story] def withStatus',
    '      receipt\n    )','      receipt\n    ).map(rebuilt => new StoryModel[T]('+', '.join('rebuilt.'+v for v in constructor_args.split(', ')[:-1])+', discourseOrder))'),
    'order','internal graph copy recomputes every ordered model query',order_control)
add('trajectory-propagates-refusal',trajectory,replace('order <- graph.discourseOrderOn(bundle)',
    'order <- graph.discourseOrderOn(bundle).orElse(Right(graph.situations.keys.toVector.sorted))'),
    'boundary','checked trajectory derivation refuses anchored event and state support',
    'accepting control: checked trajectory derivation retains text transitions')
for method,reject in [
 ('discourseOrder','unbound graph discourse order is internal'),
 ('discoursePosition','unbound graph discourse position is internal'),
 ('situationsByEntity','unbound graph entity ordering is internal'),
 ('situationsByContext','unbound graph context ordering is internal'),
 ('situationsWithin','unbound graph within ordering is internal'),
 ('situationsCovering','unbound graph covering ordering is internal')]:
    add('visibility-'+method,graph,replace('private[story] def '+method+'(', 'def '+method+'('),
        'graph-probe',reject,'accepting control: external checked pre-model ordering and model reads compile',True)
add('visibility-model-constructor',model,replace('final class StoryModel[S <: ModelStatus] private (',
    'final class StoryModel[S <: ModelStatus] private[story] ('),'constructor',
    'direct model constructor is private even inside the story package',
    'accepting control: model factory is available inside the story package',True)
add('visibility-model-copy',model,replace('private[story] def copy[T', 'def copy[T'),
    'model-probe','external callers cannot invoke internal copy',probe_control,True)
add('model-product',model,replace('    val discourseOrder: Vector[SituationId]\n):',
    '    val discourseOrder: Vector[SituationId]\n) extends Product:\n  def canEqual(that: Any): Boolean = that.isInstanceOf[StoryModel[?]]\n  def productArity: Int = 0\n  def productElement(index: Int): Any = throw new IndexOutOfBoundsException(index.toString)'),
    'model-probe','model is not a Product',probe_control,True)
mirror='''object StoryModel:
  given mutantMirror[S <: ModelStatus]: scala.deriving.Mirror.ProductOf[StoryModel[S]] =
    new scala.deriving.Mirror.Product:
      type MirroredType = StoryModel[S]
      type MirroredMonoType = StoryModel[S]
      type MirroredElemTypes = EmptyTuple
      type MirroredElemLabels = EmptyTuple
      type MirroredLabel = "StoryModel"
      def fromProduct(value: Product): StoryModel[S] =
        throw new IllegalArgumentException(value.productArity.toString)
'''
add('model-mirror',model,replace('object StoryModel:',mirror),'model-probe',
    'model has no synthesizable Product Mirror',probe_control,True)
add('codec-text-ref-retention',codec,replace('case TypedSupport.Text(spans)       => spans.asJson',
    'case TypedSupport.Text(spans) => SpanSet.one(spans.minSpan).asJson'),
    'codec',codec_text,codec_anchor)
add('codec-anchored-payload',codec,replace('case TypedSupport.Anchored(support) => support.asJson',
    'case TypedSupport.Anchored(support) => support.asJson.mapObject(_.remove("anchors"))'),
    'codec',codec_anchor,codec_text)
add('codec-anchored-refusal',codec,replace('summon[Decoder[EvidenceSupport]].apply(cursor).map(TypedSupport.Anchored(_))',
    'Right(TypedSupport.Text(SpanSet.one(TextSpan.unsafe(0, 1))))'),
    'codec',codec_anchor,codec_text)
add('codec-text-decode-retention',codec,replace('else summon[Decoder[SpanSet]].apply(cursor).map(TypedSupport.Text(_))',
    'else summon[Decoder[SpanSet]].apply(cursor).map(spans => TypedSupport.Text(SpanSet.one(spans.minSpan)))'),
    'codec',codec_text,codec_anchor)

def inventory():
    for c in cases:c['change']((repo/c['path']).read_text())
    value=dict(codeRevision=revision,plannedMutationCount=len(cases),inventoryStatus='settled before mutation and full release gates',
        cases=[{k:v for k,v in c.items() if k!='change'} for c in cases],
        boundaries=[
          'Every visibility/Product/Mirror probe mutant receives its own storyJVM/Test/clean before testOnly.',
          'Support enumeration for situations is deliberately redundant with checked projection; both event/state refusals run, and the projection refusal mutant separately falsifies that guard.',
          'Text extent remains a validator law, with an accepting Draft plus violation witness. No new numeric validation is introduced.',
          'Trajectory-only boundary evidence was already omitted at pre-S4a 5ddd9039. The new shared enumeration repairs it without changing evidence licensing.',
          'The graph pre-sort also provides stable ID ties; reverse-ID mutation targets the observable ID ordering contract rather than claiming deletion of a redundant key changes behavior.',
          'Full text bytes, fixture rendering and numerical trajectory behavior are additionally governed by unchanged S0/WOG/full-suite courts.'])
    (out/'guard-witness-inventory.json').write_text(json.dumps(value,indent=2)+'\n');return value

def report_path(project,suite):
    return repo/{'coreJVM':'core','storyJVM':'story','codecJVM':'codec'}[project]/'.jvm/target/test-reports'/('TEST-'+suite+'.xml')
def command(group):
    project,suites=groups[group];return project+'/testOnly '+' '.join(suites)
def control_tasks():
    return [command('core'),'storyJVM/testOnly '+ ' '.join([boundary_suite,order_suite,graph_probe,model_probe,constructor_probe]),command('codec')]
def mutations():
    assert clean();inventory()
    if not (out/'isolated-control.json').exists():run('isolated-control',prefix+control_tasks())
    results=json.loads((out/'mutation-progress.json').read_text()) if (out/'mutation-progress.json').exists() else []
    completed={r['id'] for r in results}
    for c in cases:
        if c['id'] in completed:continue
        path=repo/c['path'];saved=path.read_bytes();mutant=c['change'](saved.decode())
        project,suites=groups[c['group']]
        for suite in suites:report_path(project,suite).unlink(missing_ok=True)
        attempt=1
        while (out/(c['id']+'-attempt-'+str(attempt)+'.log')).exists():attempt+=1
        label=c['id']+'-attempt-'+str(attempt)
        try:
            path.write_text(mutant)
            tasks=([project+'/Test/clean'] if c['cleanRecompile'] else [])+[command(c['group'])]
            receipt=run(label,prefix+tasks,False)
            assert receipt['exitCode']==1 and receipt['aggregateTestCounts']['Failed']>0 and receipt['aggregateTestCounts']['Errors']==0,(c['id'],'not an executed failing mutant')
            outcomes={};xml_hashes={}
            for suite in suites:
                report=report_path(project,suite);assert report.exists(),report
                xml=report.read_bytes();(out/(label+'--'+suite+'.xml')).write_bytes(xml);xml_hashes[suite]=sha(xml)
                for t in ET.fromstring(xml).findall('testcase'):outcomes[(suite,t.attrib['name'])]=t
            rejecting=outcomes[(c['rejectSuite'],c['rejectingTest'])];control=outcomes[(c['controlSuite'],c['acceptingControl'])]
            assert rejecting.find('failure') is not None,(c['id'],'survived')
            assert all(control.find(k) is None for k in ['failure','error','skipped']),(c['id'],'control failed')
            if c['cleanRecompile']:
                assert re.search(r'compiling \d+ Scala sources? to .*story/\.jvm/target/scala-[^/]+/test-classes', (out/(label+'.log')).read_text()),'missing actual probe compilation'
            receipt.update({k:v for k,v in c.items() if k!='change'})
            receipt.update(compiled=True,acceptingControlPassed=True,originalSha256=sha(saved),mutantSha256=sha(mutant.encode()),junitSha256=xml_hashes,
                failedTests=[dict(suite=s,name=n) for (s,n),t in outcomes.items() if t.find('failure') is not None])
            results.append(receipt);(out/'mutation-progress.json').write_text(json.dumps(results,indent=2)+'\n')
            print(c['id'],'KILLED; named control passed',flush=True)
        finally:path.write_bytes(saved)
        assert clean()
    restored=run('restored-control-clean',prefix+['coreJVM/Test/clean','storyJVM/Test/clean','codecJVM/Test/clean']+control_tasks())
    for group,(project,suites) in groups.items():
        for suite in suites:
            report=report_path(project,suite)
            (out/('restored-control-clean--'+suite+'.xml')).write_bytes(report.read_bytes())
    (out/'mutations.json').write_text(json.dumps(dict(codeRevision=revision,mutations=results,restoredControl=restored),indent=2)+'\n')

def release():
    assert clean()
    run('scope',['bash','tools/reference-scope.sh','5ddd9039',revision])
    receipt=run('full-gate',prefix+['clean','compileAll','testAll'])
    build=(repo/'build.sbt').read_text()
    def declared(name):return re.findall(r'"(\w+)"',re.search(r'val '+name+r' = List\((.*?)\)',build,re.S).group(1))
    tasks=[m+p+'/test' for m in declared('allModules') for p in declared('allPlatforms')]+[m+'/test' for m in declared('jvmOnlyModules')]
    assert len(tasks)==len(receipt['testTotals'])
    receipt['taskTotalsInAliasOrder']=dict(zip(tasks,receipt['testTotals']))
    (out/'full-gate.json').write_text(json.dumps(receipt,indent=2)+'\n')
    run('format-last',prefix+['scalafmtCheckAll','scalafmtSbtCheck'])
    run('docs-examples',['env','STORYMODEL4S_GRAKERN_BUILD='+pin,'npm','--prefix','docs-site','run','verify:examples'])

if __name__=='__main__':{'inventory':inventory,'mutations':mutations,'release':release}[sys.argv[1]]()
