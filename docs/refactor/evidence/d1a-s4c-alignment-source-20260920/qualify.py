"""S4C exact-candidate mutation/release runner. Logs are append-only by refusal."""
from pathlib import Path
import hashlib,json,os,re,subprocess,sys,time,xml.etree.ElementTree as ET

root=Path(os.environ.get('D1A_S4C_ROOT','/Users/bbuchsbaum/code/scala/storymodel4s'))
repo=Path(os.environ.get('D1A_S4C_REPO','/private/tmp/storymodel4s-d1a-s4c-20260920'))
out=Path(os.environ.get('D1A_S4C_OUTPUT',str(root/'data/study/d1a-s4c-20260920')))
out.mkdir(parents=True,exist_ok=True)
pin=os.environ.get('D1A_S4C_GRAKERN','/private/tmp/storymodel4s-film-consumer-20260919-9zqdg50z/grakern')
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

alignment='story/src/main/scala/storymodel4s/story/alignment.scala'
view='align/src/main/scala/storymodel4s/align/bridge/StorySourceView.scala'
runtime='storymodel4s.story.D1aAlignmentSourceSuite'
boundary='storymodel4s.probes.D1aAlignmentBoundarySuite'
constructor='storymodel4s.story.probes.D1aAlignmentConstructorSuite'
bridge='storymodel4s.probes.D1aStorySourceBoundarySuite'
golden='storymodel4s.fixtures.wog.WarOfTheGhostsCodecGoldenResourceSuite'
capture='storymodel4s.fixtures.wog.WogHsmmBaselineCaptureSuite'
groups={'runtime':('storyJVM',[runtime]),'boundary':('storyJVM',[boundary]),
        'constructor':('storyJVM',[constructor]),'bridge':('alignJVM',[bridge]),
        'score':('fixturesJVM',[golden,capture,'storymodel4s.fixtures.wog.WarOfTheGhostsCodecGoldenSuite'])}
controls={'runtime':'accepting control: validated and adjudicated sources preserve ordered nodes and relations',
          'boundary':'accepting control: validated and adjudicated factories preserve source capabilities',
          'constructor':'accepting control: checked source factories remain available inside story',
          'bridge':'accepting control: text sources and status checked text models construct a view',
          'score':'capture the existing canonical WOG HSMM output'}
cases=[]
def add(label,path,change,group,reject,cleanCompile=False):
    cases.append(dict(id=label,path=path,change=change,group=group,rejectSuite=groups[group][1][0],
        rejectingTest=reject,controlSuite=capture if group=='score' else groups[group][1][0],
        acceptingControl=controls[group],cleanRecompile=cleanCompile))
def a(label,old,new,group,reject,probe=False):add(label,alignment,replace(old,new),group,reject,probe)
for typ,label in [('AlignmentSource','general'),('TextAlignmentSource','text')]:
    a('seal-'+label,'sealed trait '+typ,'trait '+typ,'boundary',label+' alignment source cannot be externally implemented',True)
for typ,label in [('StoryModel','general'),('TextModel','text')]:
    for method,status,kind in [('apply','Validated','validated'),('adjudicated','Adjudicated','adjudicated')]:
        a('status-'+label+'-'+kind,'def '+method+'(model: '+typ+'[ModelStatus.'+status+'])',
          'def '+method+'(model: '+typ+'[?])','boundary',label+' '+kind+' factory refuses Draft',True)
a('constructor-general','private class StoryAlignmentSource','private[story] class StoryAlignmentSource','constructor',
  'general implementation constructor is private even inside story',True)
def expose_text_constructor(s):
    s=replace('private class StoryAlignmentSource','private[story] class StoryAlignmentSource')(s)
    return replace('private final class StoryTextAlignmentSource','private[story] final class StoryTextAlignmentSource')(s)
add('constructor-text',alignment,expose_text_constructor,'constructor',
  'text implementation constructor is private even inside story',True)
def expose_spans(s):
    s=replace('sealed trait AlignmentSource:', 'sealed trait AlignmentSource:\n  def sourceSupport(target: NarrativeNodeId): Option[SpanSet]')(s)
    s=replace('    private val g = model.graph', '    def sourceSupport(target: NarrativeNodeId): Option[SpanSet] = evidenceOf(target).flatMap(_.textSpans)\n    private val g = model.graph')(s)
    return replace('    def sourceSupport(target: NarrativeNodeId): Option[SpanSet] =\n      evidenceOf(target).flatMap(_.textSpans)',
                   '    override def sourceSupport(target: NarrativeNodeId): Option[SpanSet] =\n      evidenceOf(target).flatMap(_.textSpans)')(s)
add('general-text-span-door',alignment,expose_spans,'boundary','general source has no text span accessor',True)
def expose_text(s):
    s=replace('sealed trait AlignmentSource:', 'sealed trait AlignmentSource:\n  def text: StoryText')(s)
    s=replace('    private val g = model.graph', '    def text: StoryText = StoryModel.asText(model).get.text\n    private val g = model.graph')(s)
    return replace('    val text: StoryText = model.text','    override val text: StoryText = model.text')(s)
add('general-text-witness-door',alignment,expose_text,'boundary','general source has no canonical text witness',True)
for label,old,new in [
    ('situation-evidence','g.situations.get(id).map(_.support)','g.situations.get(id).flatMap(_ => None)'),
    ('segment-evidence','g.segments.get(id).map(_.support)','g.segments.get(id).flatMap(_ => None)')]:
    a(label,old,new,'runtime','general text source returns complete typed evidence and primary projection')
for label,collection in [('situation','situations'),('segment','segments')]:
    a('missing-'+label,'g.'+collection+'.get(id).map(_.support)',
        'g.'+collection+'.get(id).orElse(g.'+collection+'.values.headOption).map(_.support)',
        'runtime','missing situation and segment have no evidence projection or text support')
a('primary-presence','evidenceOf(target).map(model.projectionOf)','evidenceOf(target).flatMap(_ => None)',
  'runtime','general text source returns complete typed evidence and primary projection')
a('primary-complete-union','evidenceOf(target).map(model.projectionOf)',
  '''evidenceOf(target).map(model.projectionOf).map {
        case PrimaryProjection.Playback(axis, intervals) => PrimaryProjection.Playback(axis, PlaybackIntervalSet.one(intervals.intervals.head))
        case other => other
      }''','runtime','general film source preserves native evidence and the complete gapped primary union')
a('native-evidence-retention','g.situations.get(id).map(_.support)',
  '''g.situations.get(id).map(_.support).map {
        case TypedSupport.Anchored(s) => TypedSupport.Anchored(EvidenceSupport.of(model.bundle, Vector(s.anchors.head)).toOption.get)
        case other => other
      }''','runtime','general film source preserves native evidence and the complete gapped primary union')
a('text-span-exactness','evidenceOf(target).flatMap(_.textSpans)',
  'evidenceOf(target).flatMap(_.textSpans).map(s => SpanSet.one(SpanRef(None, s.minSpan)))',
  'runtime','text source keeps its own witness and exact span references')
a('text-witness-retention','val text: StoryText = model.text',
  'val text: StoryText = StoryModel.asText(model.model).get.text',
  'runtime','text source keeps its own witness and exact span references')
add('bridge-general-source',view,replace('object StorySourceView:',
    '''object StorySourceView:
  def apply(source: AlignmentSource): StorySourceView = throw new IllegalArgumentException(source.allNodes.size.toString)
'''),'bridge','general source cannot construct a text view',True)
add('bridge-independent-model',view,replace('object StorySourceView:',
    '''object StorySourceView:
  def apply(source: TextAlignmentSource, unrelated: TextModel[ModelStatus.Validated]): StorySourceView =
    if source.allNodes.isEmpty then validated(unrelated) else apply(source)
'''),'bridge','source cannot be paired with an independent text model',True)
for method,status in [('validated','Validated'),('adjudicated','Adjudicated')]:
    end='  def adjudicated(' if method=='validated' else '  def sketchRole('
    def change(s,method=method,status=status,end=end):
        lo=s.index('  def '+method+'(');hi=s.index(end,lo);part=s[lo:hi]
        part=replace('model: TextModel[ModelStatus.'+status+']','model: TextModel[?]')(part)
        call='AlignmentSource(model)' if method=='validated' else 'AlignmentSource.adjudicated(model)'
        replacement=('AlignmentSource' if method=='validated' else 'AlignmentSource.adjudicated')+'(model.asInstanceOf[TextModel[ModelStatus.'+status+']])'
        part=replace(call,replacement)(part)
        return s[:lo]+part+s[hi:]
    add('bridge-status-'+method,view,change,'bridge',method+' view factory refuses Draft',True)
add('score-text-length',view,replace('val textLength: Int = text.length','val textLength: Int = text.length + 1'),
    'score','the committed hsmm/v3 resource matches the inferred artifact and fixed checksum')
def inventory():
    import difflib
    rows=[]
    for c in cases:
        original=(repo/c['path']).read_text();mutant=c['change'](original)
        row={k:v for k,v in c.items() if k!='change'}
        row.update(originalSha256=sha(original.encode()),mutantSha256=sha(mutant.encode()),
            diff=''.join(difflib.unified_diff(original.splitlines(True),mutant.splitlines(True),fromfile=c['path'],tofile=c['path'])))
        rows.append(row)
    value=dict(codeRevision=revision,plannedMutationCount=len(cases),inventoryStatus='proposed before execution',cases=rows,
        boundaries=[
            'General and text sealed traits, private implementations, four status factories, and text-only bridge doors each have Compile/clean plus Test/clean, with both main and test compilation required in the log.',
            'Exact evidence and projection, missing nodes, text witness identity, and complete native/gapped support have runtime counterexamples.',
            'The existing JVM resource checksum is distinct from the new platform-labelled pre/post capture comparison.',
            'The text implementation constructor mutant also exposes its base class, required for a compilable Scala subclass signature; the narrow first attempt was invalid and remains retained.',
            'No relation/scoring algorithm or numerical tolerance is changed. The text-length mutant perturbs a real score input and must fail the fixed JVM inferred-artifact court.'])
    (out/'guard-witness-inventory.json').write_text(json.dumps(value,indent=2)+'\n');return value

def report_path(project,suite):
    return repo/{'storyJVM':'story','alignJVM':'align','fixturesJVM':'fixtures'}[project]/'.jvm/target/test-reports'/('TEST-'+suite+'.xml')
def command(group):
    project,suites=groups[group];return project+'/testOnly '+' '.join(suites)
def control_tasks():
    return ['storyJVM/testOnly '+ ' '.join([runtime,boundary,constructor]),command('bridge'),command('score')]
def mutations():
    assert clean();inventory()
    if not (out/'isolated-control.json').exists():run('isolated-control',prefix+control_tasks())
    results=json.loads((out/'mutation-progress.json').read_text()) if (out/'mutation-progress.json').exists() else []
    completed={r['id'] for r in results}
    for c in cases:
        if c['id'] in completed:continue
        assert clean()
        path=repo/c['path'];saved=path.read_bytes();mutant=c['change'](saved.decode())
        project,suites=groups[c['group']]
        for suite in suites:report_path(project,suite).unlink(missing_ok=True)
        attempt=1
        while (out/(c['id']+'-attempt-'+str(attempt)+'.log')).exists():attempt+=1
        label=c['id']+'-attempt-'+str(attempt)
        try:
            path.write_text(mutant)
            tasks=([project+'/Compile/clean',project+'/Test/clean'] if c['cleanRecompile'] else [])+[command(c['group'])]
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
                assert re.search(r'compiling \d+ Scala sources? to .*/'+{'storyJVM':'story','alignJVM':'align','fixturesJVM':'fixtures'}[project]+r'/\.jvm/target/scala-[^/]+/test-classes', (out/(label+'.log')).read_text()),'missing actual probe compilation'
                assert re.search(r'compiling \d+ Scala sources? to .*/'+{'storyJVM':'story','alignJVM':'align','fixturesJVM':'fixtures'}[project]+r'/\.jvm/target/scala-[^/]+/classes', (out/(label+'.log')).read_text()),'missing actual production compilation'
            receipt.update({k:v for k,v in c.items() if k!='change'})
            receipt.update(compiled=True,acceptingControlPassed=True,originalSha256=sha(saved),mutantSha256=sha(mutant.encode()),junitSha256=xml_hashes,
                failedTests=[dict(suite=s,name=n) for (s,n),t in outcomes.items() if t.find('failure') is not None])
            results.append(receipt);(out/'mutation-progress.json').write_text(json.dumps(results,indent=2)+'\n')
            print(c['id'],'KILLED; named control passed',flush=True)
        finally:path.write_bytes(saved)
        assert clean()
        receipt.update(baselineCleanBeforeMutation=True,restoredSha256=sha(path.read_bytes()),restoredCleanAfterMutation=clean())
        (out/'mutation-progress.json').write_text(json.dumps(results,indent=2)+'\n')
    restored=run('restored-control-clean',prefix+['storyJVM/Test/clean','alignJVM/Test/clean','fixturesJVM/Test/clean']+control_tasks())
    for group,(project,suites) in groups.items():
        for suite in suites:
            report=report_path(project,suite)
            (out/('restored-control-clean--'+suite+'.xml')).write_bytes(report.read_bytes())
    (out/'mutations.json').write_text(json.dumps(dict(codeRevision=revision,mutations=results,restoredControl=restored),indent=2)+'\n')

def release():
    assert clean()
    run('scope',['bash','tools/reference-scope.sh','7358eb7c',revision])
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
