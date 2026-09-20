"""S4B exact-candidate mutation/release runner. Logs are append-only by refusal."""
from pathlib import Path
import hashlib,json,os,re,subprocess,sys,time,xml.etree.ElementTree as ET

root=Path(os.environ.get('D1A_S4B_ROOT','/Users/bbuchsbaum/code/scala/storymodel4s'))
repo=Path(os.environ.get('D1A_S4B_REPO','/private/tmp/storymodel4s-d1a-s4b-20260919'))
out=Path(os.environ.get('D1A_S4B_OUTPUT',str(root/'data/study/d1a-s4b-20260919')))
out.mkdir(parents=True,exist_ok=True)
pin=os.environ.get('D1A_S4B_GRAKERN','/private/tmp/storymodel4s-film-consumer-20260919-9zqdg50z/grakern')
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
identity='core/src/main/scala/storymodel4s/core/sourceIdentity.scala'
graph='story/src/main/scala/storymodel4s/story/graph.scala'
model='story/src/main/scala/storymodel4s/story/model.scala'
validator='story/src/main/scala/storymodel4s/story/validate.scala'
codec='codec/src/main/scala/storymodel4s/codec/story.scala'
envelope='storymodel4s.story.D1aEnvelopeSuite'
admission='storymodel4s.story.D1aEnvelopeAdmissionSuite'
ordering='storymodel4s.story.D1aOrderingSuite'
witness='storymodel4s.probes.D1aTextWitnessSuite'
constructor='storymodel4s.story.probes.D1aModelConstructorSuite'
codec_witness='storymodel4s.probes.D1aCodecWitnessSuite'
groups={
 'envelope':('storyJVM',[envelope]), 'admission':('storyJVM',[admission]),
 'order':('storyJVM',[ordering]), 'witness':('storyJVM',[witness]),
 'constructor':('storyJVM',[constructor]), 'codec':('codecJVM',[codec_witness])}
controls={
 'envelope':'accepting control: anchored draft validates and has no canonical text witness',
 'admission':'accepting control: fully anchored graph, hierarchy, trajectory and claims validate',
 'order':'empty graph admits both supported primary kinds',
 'witness':'accepting control: checked text factories, promotions and text reads compile',
 'constructor':'accepting control: model factory is available inside the story package',
 'codec':'accepting control: canonical model codecs accept and return text witnesses'}
cases=[]
def add(label,path,change,group,reject,control=None,cleanCompile=False):
    suite=groups[group][1][0]
    cases.append(dict(id=label,path=path,change=change,group=group,rejectSuite=suite,rejectingTest=reject,
        controlSuite=suite,acceptingControl=control or controls[group],cleanRecompile=cleanCompile))
def m(label,old,new,group,reject,control=None):
    add(label,model,replace(old,new),group,reject,control)
axis_test='bundle-only copy recomputes primary selection and reverses order while retaining native evidence'
add('identity-primary-axis-fingerprint',identity,
    replace('Vector(value.primaryAxis.fingerprint.hex, streams.size.toString)', 'Vector(streams.size.toString)'),
    'envelope',axis_test)
m('identity-full-bundle','anchored.bundle.identity.hex','anchored.bundle.id.value','envelope',
    'non-primary coordinate metadata and mapping payload reach both model identity fields')
m('identity-bound-surface','Vector("surface:some", s.identity.hex)',
    'Vector("surface:some", s.surface.source.canonicalChecksum.hex)','envelope',
    'non-text identity binds bound surface content, units and receipt association')
for label,old,new in [
 ('receipt-story-id','r.storyId != storyId || r.sourceChecksum != checksum','r.sourceChecksum != checksum'),
 ('receipt-source-checksum','r.storyId != storyId || r.sourceChecksum != checksum','r.storyId != storyId')]:
    m(label,old,new,'envelope','receipt joins check story id and full source checksum for draft and copy')
m('canonical-text-evidence','bundle.primaryAxis.kind == AxisKind.TextCharacter && e.anchors.nonEmpty',
    'bundle.primaryAxis.kind == AxisKind.TextCharacter && e.anchors.nonEmpty && false','admission',
    'text evidence refuses anchors that otherwise belong to its own bundle')
m('canonical-film-evidence','bundle.primaryAxis.kind == AxisKind.EditionPlayback && e.spans.nonEmpty',
    'bundle.primaryAxis.kind == AxisKind.EditionPlayback && e.spans.nonEmpty && false','admission',
    'graph evidence is checked on both draft and copy')
m('claim-anchor-membership','EvidenceSupport.of(bundle, support.anchors.toVector).map(_ => ())',
    'Right(())','admission','graph evidence is checked on both draft and copy')
for label,old in [('graph','graph.allMeta'),('hierarchy','hierarchy.allMeta'),
                  ('trajectory','trajectory.allMeta'),('descriptor','descriptors.map(_.meta)'),
                  ('hypothesis','hypotheses.map(_.meta)')]:
    add('collect-'+label,model,region('  private def checked[','  private def identityOf',old,
        old+'.filter(_ => false)'),'admission',label+' evidence is checked on both draft and copy')
for label,old in [('hierarchy boundary','hierarchy.boundaryBeliefs'),
                  ('step boundary','trajectory.steps.flatMap(_.boundaryBeliefs)')]:
    add('collect-'+label.replace(' ','-'),model,region('  private def checked[','  private def identityOf',old,
        old+'.filter(_ => false)'),'admission',label+' evidence is checked on both draft and copy')
add('projection-rechecks-membership',core,
    replace('checked <- EvidenceSupport.of(bundle, anchors.anchors.toVector)','checked <- Right(anchors)'),
    'envelope','node membership is rechecked independently of an available primary interval')
add('projection-direct-primary-refusal',core,
    replace('intervals <- checked.intervalsOn(bundle.primaryAxis.id)',
        'intervals <- checked.intervalsOn(bundle.primaryAxis.id).orElse(Right(PlaybackIntervalSet.one(PlaybackInterval.on(bundle.primaryAxis, 1L, 3L).toOption.get)))'),
    'envelope','native-only support is refused even when an explicit map exists')
add('projection-refuses-film-text',core,
    replace('      case (AxisKind.TextCharacter | AxisKind.EditionPlayback, _) =>',
      '''      case (AxisKind.EditionPlayback, TypedSupport.Text(spans)) =>
        PlaybackInterval.on(bundle.primaryAxis, spans.minSpan.start.toLong, spans.minSpan.endExclusive.toLong)
          .map(i => PrimaryProjection.Playback(bundle.primaryAxis.id, PlaybackIntervalSet.one(i)))
      case (AxisKind.TextCharacter | AxisKind.EditionPlayback, _) =>'''),
    'admission','anchored entity support cannot be replaced with bare text')
add('empty-order-primary-kind',graph,replace(
    'if bundle.primaryAxis.kind != AxisKind.TextCharacter && bundle.primaryAxis.kind != AxisKind.EditionPlayback',
    'if bundle.primaryAxis.kind != AxisKind.TextCharacter && bundle.primaryAxis.kind != AxisKind.EditionPlayback && false'),
    'order','empty ordering refuses an unsupported annotation primary axis')

fields='schemaVersion, atlas, graph, hierarchy, trajectory, featureSpaces, sidecars, featureRefs, descriptors, hypotheses, sensoryProfiles, receipt'.split(', ')
def copy_change(stale=None):
    def change(s):
        a=s.index('    StoryModel.checked[T](');b=s.index('\n\n  private[story] def withStatus',a)
        if stale is None: body='    Right(new StoryModel[T]('+', '.join(fields+['discourseOrder','supportProjections'])+'))'
        else:
            args=['rebuilt.'+f for f in fields]+[
                'discourseOrder' if stale=='order' else 'rebuilt.discourseOrder',
                'supportProjections' if stale=='projection' else 'rebuilt.supportProjections']
            body=s[a:b]+'.map(rebuilt => new StoryModel[T]('+', '.join(args)+'))'
        return s[:a]+body+s[b:]
    return change
add('copy-shared-admission',model,copy_change(),'admission','graph evidence is checked on both draft and copy')
add('copy-recomputes-order',model,copy_change('order'),'envelope',axis_test)
add('copy-recomputes-projection',model,copy_change('projection'),'envelope',axis_test)
add('containment-preserves-gaps',validator,
    replace('''enclosing.intervals.toVector.exists(p =>
                  p.start <= c.start && p.endExclusive >= c.endExclusive
                )''',
    '''enclosing.intervals.toVector.map(_.start).min <= c.start &&
                  enclosing.intervals.toVector.map(_.endExclusive).max >= c.endExclusive'''),
    'admission','playback containment checks the interval union rather than its hull')
add('containment-covers-every-member',validator,
    replace('!child.intervals.toVector.forall(c =>','!child.intervals.toVector.exists(c =>'),
    'admission','playback containment checks the interval union rather than its hull')
add('context-circumstance-text-extent',validator,
    replace('if spans.minSpan.endExclusive > length then','if spans.minSpan.endExclusive > length && false then'),
    'admission','text context and circumstance bounds remain validation laws with exact paths')
add('generic-consistency-rules',validator,
    replace('case None       => NarrativeConsistency.checkGeneral(model)', 'case None       => Vector.empty'),
    'envelope','generic anchored validation retains root Reported and retrospective duplicate rules')
m('promotion-retains-text-witness','new TextModel(model.withStatus[T], text)',
    'TextModel.fromModel(model.withStatus[T]).get','envelope',
    'text identity and witness survive validation and adjudication without replacement')
add('text-validation-blocking',validator,
    replace('TextValidationOutcome(result, if blocked(result, policy) then None else Some(draft.promoted))',
        'TextValidationOutcome(result, if blocked(result, policy) && false then None else Some(draft.promoted))'),
    'envelope','failed text validation retains the same report and no promoted witness')
m('text-wrapper-value-equality','case that: TextModel[?] => model == that.model',
    'case that: TextModel[?] => this eq that','envelope',
    'text identity and witness survive validation and adjudication without replacement')
m('as-text-requires-canonical-atlas','case _: AnchoredNarrativeAtlas => None',
    '''case a: AnchoredNarrativeAtlas => a.surface.map(s =>
        new TextModel(model, StoryText.fromAtlas(TextNarrativeAtlas.of(s.surface).toOption.get)))''',
    'admission','bound surface unit ids are checked as surface ids without granting text access',
    'text receipt joins check id and checksum independently on draft and copy')

for typ in ['StoryText','TextModel']:
    generic='[S <: ModelStatus]' if typ=='TextModel' else ''
    params='[S]' if typ=='TextModel' else ''
    add('constructor-'+typ,model,replace('final class '+typ+generic+' private (',
        'final class '+typ+generic+' private[story] ('),'constructor',
        typ+' constructor is private even inside the story package',cleanCompile=True)
    ctor='new StoryText(text.source, text.surface, text.stream)' if typ=='StoryText' else 'new TextModel(model, text)'
    method=('  def apply(source: StorySource, surface: SurfaceAtlas, stream: StreamId): StoryText =\n'
            '    new StoryText(source, surface, stream)\n') if typ=='StoryText' else (
            '  def apply[S <: ModelStatus](model: StoryModel[S], text: StoryText): TextModel[S] =\n'
            '    new TextModel(model, text)\n')
    add('apply-'+typ,model,replace('object '+typ+':','object '+typ+':\n'+method),'witness',
        typ+' has no public apply or copy',cleanCompile=True)
    if typ=='StoryText':
        declaration='final class StoryText private (\n    val source: StorySource,\n    val surface: SurfaceAtlas,\n    val stream: StreamId\n)'
        add('copy-'+typ,model,replace(declaration,declaration+':\n  def copy(): StoryText = this'),
            'witness',typ+' has no public apply or copy',cleanCompile=True)
        product=declaration+' extends Product:\n'
    else:
        declaration='final class TextModel[S <: ModelStatus] private (val model: StoryModel[S], val text: StoryText):'
        add('copy-'+typ,model,replace(declaration,declaration+'\n  def copy(): TextModel[S] = this'),
            'witness',typ+' has no public apply or copy',cleanCompile=True)
        product=declaration[:-1]+' extends Product:\n'
    product+='  def canEqual(that: Any): Boolean = that.isInstanceOf['+typ+('[?]' if generic else '')+']\n'
    product+='  def productArity: Int = 0\n  def productElement(index: Int): Any = throw new IndexOutOfBoundsException(index.toString)\n'
    add('product-'+typ,model,replace(declaration,product),'witness',
        typ+' is not a Product and has no Product Mirror',cleanCompile=True)
    mirror='object '+typ+':\n  given mutantMirror'+generic+': scala.deriving.Mirror.ProductOf['+typ+params+'] =\n'
    mirror+='    new scala.deriving.Mirror.Product:\n      type MirroredType = '+typ+params+'\n      type MirroredMonoType = '+typ+params+'\n'
    mirror+='      type MirroredElemTypes = EmptyTuple\n      type MirroredElemLabels = EmptyTuple\n      type MirroredLabel = "'+typ+'"\n'
    mirror+='      def fromProduct(value: Product): '+typ+params+' = throw new IllegalArgumentException(value.productArity.toString)\n'
    add('mirror-'+typ,model,replace('object '+typ+':',mirror),'witness',
        typ+' is not a Product and has no Product Mirror',cleanCompile=True)

add('private-text-promotion',model,replace('private[story] def promoted[','def promoted['),
    'witness','text witness does not forward internal copy or promotion',cleanCompile=True)
add('text-status-forwarding',model,
    replace('  private[story] def promoted[T <: ModelStatus]: TextModel[T] =',
        '  def withStatus[T <: ModelStatus]: TextModel[T] = promoted[T]\n  private[story] def promoted[T <: ModelStatus]: TextModel[T] ='),
    'witness','text witness does not forward internal copy or promotion',cleanCompile=True)
add('generic-model-source-access',model,
    replace('  val bundle: SourceBundle = atlas.bundle',
        '  def source: StorySource = StoryModel.asText(this).get.source\n  val bundle: SourceBundle = atlas.bundle'),
    'witness','generic model has no source or public text support queries',cleanCompile=True)
add('text-factory-independent-source',model,
    region('  def draftText(', '  def asText[',
        'schemaVersion: String = SchemaVersion',
        'schemaVersion: String = SchemaVersion, source: StorySource = StorySource.fromText("unused").toOption.get'),
    'witness','text factory accepts no independent source',cleanCompile=True)
for path,methods in [(model,['supporting','covering','situationsCovering']),(graph,['supporting','covering'])]:
    for name in methods:
        add('visibility-'+('model' if path==model else 'graph')+'-'+name,path,
            replace('private[story] def '+name+'(', 'def '+name+'('),'witness',
            'generic model has no source or public text support queries' if path==model else 'unbound graph has no public text support queries',
            cleanCompile=True)
bridge='''object StoryModel:
  given mutantTextConversion[S <: ModelStatus]: Conversion[StoryModel[S], TextModel[S]] with
    def apply(model: StoryModel[S]): TextModel[S] = StoryModel.asText(model).get
'''
add('generic-text-consumer-bridge',model,replace('object StoryModel:',bridge),'witness',
    'generic model cannot stand in for a text model at a text consumer',cleanCompile=True)
add('generic-codec-method-bridge',model,replace('object StoryModel:',bridge),'codec',
    'generic models cannot enter text encode and checksum methods',cleanCompile=True)
add('generic-codec-encoder',codec,replace('object StoryModelCodec:',
    '''object StoryModelCodec:
  given mutantEncoder[S <: ModelStatus]: Encoder[StoryModel[S]] =
    Encoder.instance(m => modelEncoder[S].apply(StoryModel.asText(m).get))
'''),'codec','generic models have no canonical Encoder',cleanCompile=True)
add('generic-codec-decoder',codec,replace('object StoryModelCodec:',
    '''object StoryModelCodec:
  given mutantDecoder: Decoder[StoryModel[ModelStatus.Draft]] = draftDecoder.map(_.model)
'''),'codec','generic models have no canonical Decoder',cleanCompile=True)
add('private-model-constructor',model,
    replace('final class StoryModel[S <: ModelStatus] private (','final class StoryModel[S <: ModelStatus] private[story] ('),
    'constructor','direct model constructor is private even inside the story package',cleanCompile=True)

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
            'Every new constructor/apply/copy/Product/Mirror/visibility/codec/implicit-bridge mutant receives its own affected-project Test/clean; actual compilation is required in its log.',
            'Same-bundle text anchors isolate canonical refusal from the otherwise redundant membership check.',
            'All seven evidence collectors have independent lawful controls and bare/twin/foreign refusals at both draft and copy.',
            'The primary-axis-fingerprint mutation uses a primary-only switch with unchanged stream inventory and legacy bundle id; both model identity fields must differ.',
            'Validator anchor-membership checks are redundant on publicly constructible models: the shared draft/copy join already refuses those invalid inputs. Their source trace is reviewed, not claimed as independently killed deletion mutants.',
            'Unsupported AnnotationTimeline is refused at checked pre-model ordering and the existing anchored-atlas constructor; no fake playback interval is minted.',
            'Existing S4a supportEntries enumeration, text projection/order and graph order visibility tests remain in the full provider gate; this inventory targets new or changed S4b boundaries.',
            'Film SurfaceExplicit remains unconstructible under the text-span license; general hypothesis-subject rule is retained with its existing text court, while film refusal is the explicit limit.',
            'Full text bytes and model/validator/rendering behavior additionally retain frozen S0 and codec/WOG gates.'])
    (out/'guard-witness-inventory.json').write_text(json.dumps(value,indent=2)+'\n');return value

def report_path(project,suite):
    return repo/{'coreJVM':'core','storyJVM':'story','codecJVM':'codec'}[project]/'.jvm/target/test-reports'/('TEST-'+suite+'.xml')
def command(group):
    project,suites=groups[group];return project+'/testOnly '+' '.join(suites)
def control_tasks():
    return ['storyJVM/testOnly '+ ' '.join([envelope,admission,ordering,witness,constructor]),command('codec')]
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
                assert re.search(r'compiling \d+ Scala sources? to .*/'+('codec' if project=='codecJVM' else 'story')+r'/\.jvm/target/scala-[^/]+/test-classes', (out/(label+'.log')).read_text()),'missing actual probe compilation'
            receipt.update({k:v for k,v in c.items() if k!='change'})
            receipt.update(compiled=True,acceptingControlPassed=True,originalSha256=sha(saved),mutantSha256=sha(mutant.encode()),junitSha256=xml_hashes,
                failedTests=[dict(suite=s,name=n) for (s,n),t in outcomes.items() if t.find('failure') is not None])
            results.append(receipt);(out/'mutation-progress.json').write_text(json.dumps(results,indent=2)+'\n')
            print(c['id'],'KILLED; named control passed',flush=True)
        finally:path.write_bytes(saved)
        assert clean()
    restored=run('restored-control-clean',prefix+['storyJVM/Test/clean','codecJVM/Test/clean']+control_tasks())
    for group,(project,suites) in groups.items():
        for suite in suites:
            report=report_path(project,suite)
            (out/('restored-control-clean--'+suite+'.xml')).write_bytes(report.read_bytes())
    (out/'mutations.json').write_text(json.dumps(dict(codeRevision=revision,mutations=results,restoredControl=restored),indent=2)+'\n')

def release():
    assert clean()
    run('scope',['bash','tools/reference-scope.sh','57ee3595',revision])
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
