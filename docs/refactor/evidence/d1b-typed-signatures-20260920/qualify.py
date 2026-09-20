"""D1B exact-candidate mutation/release runner. Logs are append-only by refusal."""
from pathlib import Path
import hashlib,json,os,re,subprocess,sys,time,xml.etree.ElementTree as ET

root=Path(os.environ.get('D1B_ROOT','/Users/bbuchsbaum/code/scala/storymodel4s'))
repo=Path(os.environ.get('D1B_REPO','/private/tmp/storymodel4s-d1b-20260920'))
out=Path(os.environ.get('D1B_OUTPUT',str(root/'data/study/d1b-20260920')))
out.mkdir(parents=True,exist_ok=True)
pin=os.environ.get('D1B_GRAKERN','/private/tmp/storymodel4s-film-consumer-20260919-9zqdg50z/grakern')
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
    counts=dict.fromkeys(['Total','Failed','Errors','Passed','Skipped','Ignored'],0)
    for line in totals:
        for key,value in re.findall(r'(Total|Failed|Errors|Passed|Skipped|Ignored) (\d+)',line): counts[key]+=int(value)
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

support='core/src/main/scala/storymodel4s/core/support.scala'
source='core/src/main/scala/storymodel4s/core/source.scala'
identity='core/src/main/scala/storymodel4s/core/sourceIdentity.scala'
align='align/src/main/scala/storymodel4s/align/source.scala'
hsmm='align/src/main/scala/storymodel4s/align/hsmm.scala'
wire='align/src/main/scala/storymodel4s/align/wire.scala'
codec='codec/src/main/scala/storymodel4s/codec/align.scala'
corecodec='codec/src/main/scala/storymodel4s/codec/core.scala'
corpus='corpus-intake/src/main/scala/storymodel4s/corpus/intake/SherlockSourceAtlas.scala'
metrics='embed-bench/src/main/scala/storymodel4s/bench/metrics.scala'
point='storymodel4s.core.D1bPointSupportSuite'
playprobe='storymodel4s.core.probes.D1bPlaybackSupportBoundarySuite'
position='storymodel4s.align.D1bScoringPositionSuite'
resultprobe='storymodel4s.align.probes.D1bResultBoundarySuite'
aligncodec='storymodel4s.codec.AlignCodecSuite'
pointcodec='storymodel4s.codec.D1bPointCodecSuite'
annotation='storymodel4s.corpus.intake.SherlockAnnotationsSuite'
metric='storymodel4s.bench.WogDiagnosticSuite'
groups={
 'point':('coreJVM',[point]), 'playprobe':('coreJVM',[playprobe]),
 'position':('alignJVM',[position]), 'resultprobe':('alignJVM',[resultprobe]),
 'codec':('codecJVM',[aligncodec,pointcodec]),
 'corpus':('corpusIntake',[annotation]), 'metrics':('embedBench',[metric]), 'laws':('lawsJVM',['storymodel4s.laws.LawsSuite'])}
controls={
 'point':'accepting control: an interval-only projection keeps its exact checked coordinates',
 'playprobe':'accepting control: checked playback union permits explicit intervals and points',
 'position':'accepting control: physical point and declared scoring feature retain separate exact values',
 'resultprobe':'accepting control: result validation derives its own exact support map',
 'codec':'a real inferred result has a canonical contextual round trip',
 'corpus':'accepting control: composed atlas retains an ordinary interval on both declared axes',
 'metrics':'the route metric actually fires on the multi-unit case, and abstains elsewhere',
 'laws':'SourceViewLaws: align.sourceView.InMemorySourceView: scoring-feature presence is retained'}
modules={'coreJVM':'core/.jvm','alignJVM':'align/.jvm','codecJVM':'codec/.jvm',
 'corpusIntake':'corpus-intake','embedBench':'embed-bench','lawsJVM':'laws/.jvm'}
cases=[]
def add(label,path,change,group,reject,probe=False,suite=None):
 cases.append(dict(id=label,path=path,change=change,group=group,
  rejectSuite=suite or groups[group][1][0],rejectingTest=reject,
  controlSuite=groups[group][1][0],acceptingControl=controls[group],cleanRecompile=probe))
def edit(label,path,old,new,group,reject,probe=False,suite=None):
 add(label,path,replace(old,new),group,reject,probe,suite)
empty='complete support requires a member and one common axis'
mixed='mixed support retains interval gaps and explicit inside-interval points'
union='complete union merges overlapping and adjacent intervals and bounds outer points'
contain='containment distinguishes points, gaps and positive duration'
binding='full bundle binding refuses same-id metadata drift until explicit checked rebuild'
clock='mapped point obeys exact clock image with excluded end and ambiguous mapping refusal'
composition='composition points preserve gaps, next segment start and source bounds'
member='point membership independently checks selected stream extent and kind'
pid='point identity independently binds the selected stream and axis within one bundle'
edit('playback-nonempty',support,'if intervals.isEmpty && points.isEmpty then','if false then','point',empty)
edit('playback-interval-axis',support,'intervals.exists(_.axis != axis) || points.exists(_.axis != axis)','points.exists(_.axis != axis)','point',empty)
edit('playback-point-axis',support,'intervals.exists(_.axis != axis) || points.exists(_.axis != axis)','intervals.exists(_.axis != axis)','point',empty)
edit('playback-retain-points',support,'points.distinct.sortBy(_.at)','Vector.empty','point',mixed)
edit('playback-retain-inside-points',support,'points.distinct.sortBy(_.at)','points.distinct.sortBy(_.at).filterNot(p => merged.exists(_.contains(p.at)))','point',mixed)
edit('playback-deduplicate-points',support,'points.distinct.sortBy(_.at)','points.sortBy(_.at)','point',mixed)
edit('playback-sort-points',support,'points.distinct.sortBy(_.at)','points.distinct','point',union)
edit('playback-merge-overlap',support,'case Some(last) if interval.start <= last.endExclusive =>','case Some(last) if interval.start == last.endExclusive =>','point',union)
edit('playback-preserve-gap',support,'case Some(last) if interval.start <= last.endExclusive =>','case Some(last) =>','point',mixed)
edit('playback-point-lower-bound',support,'(intervals.map(_.start) ++ points.map(_.at)).min','intervals.map(_.start).min','point',union)
edit('playback-point-upper-bound',support,'(intervals.map(_.endExclusive) ++ points.map(_.at)).max','intervals.map(_.endExclusive).max','point',union)
edit('containment-axis',support,'axis == other.axis && other.intervals.forall(c =>','other.intervals.forall(c =>','point',union)
edit('containment-points',support,'points.contains(p) || intervals.exists(_.contains(p.at))','points.contains(p) || intervals.exists(i => i.start <= p.at && p.at <= i.endExclusive)','point',contain)
edit('containment-positive-duration',support,'intervals.exists(p => p.start <= c.start && p.endExclusive >= c.endExclusive)','intervals.exists(p => p.start <= c.start && p.endExclusive >= c.endExclusive) || points.exists(_.at == c.start)','point',contain)
add('point-native-end',support,region('    if axis == stream.nativeAxis then','    else if axis != bundle.primaryAxis.id then','p.at < extent.endExclusive','p.at <= extent.endExclusive'),'point',member)
edit('point-kind',support,'if !kindMatches then','if !kindMatches && !anchor.isInstanceOf[EvidenceAnchor.MediaPoint] then','point',member)
edit('point-clock-end',support,'end.greaterThan(p.at)','end.atLeast(p.at)','point',clock)
edit('point-clock-start',support,'points.forall(p => start.atMost(p.at) && end.greaterThan(p.at))','points.forall(p => end.greaterThan(p.at))','point',clock)
edit('point-composition-gap',support,'points.forall(p => segments.exists(_.target.contains(p.at)))','points.forall(p => p.at >= segments.map(_.target.start).min && p.at < segments.map(_.target.endExclusive).max)','point',composition)
edit('point-composition-source-extent',support,'segments.forall(segment => within(segment.source, extent)) &&','segments.nonEmpty &&','point',composition)
edit('full-support-binding',source,'if bundle.identity != bundleIdentity then','if false then','point',binding)
edit('projection-full-binding',support,'checked <- anchors.checkedOn(bundle)','checked <- Right(anchors)','point',binding)
edit('point-hull-refusal',source,'case EvidenceAnchor.MediaPoint(_, _, at) => at.axis == axis','case EvidenceAnchor.MediaPoint(_, _, _) => false','point','point support has no interval-only hull and no invented interval')
edit('point-axis-selection',source,'case EvidenceAnchor.MediaPoint(_, _, at) if at.axis == axis => at','case EvidenceAnchor.MediaPoint(_, _, at) => at','point','point identity independently binds the selected stream and axis within one bundle')
edit('identity-full-binding',identity,'value.bundleIdentity.hex,','"omitted-binding",','point',binding)
for label,old in [('tick','at.at.toString'),('axis','at.axis.value'),('stream','stream.value')]:
 add('identity-point-'+label,identity,region('case EvidenceAnchor.MediaPoint(bundle, stream, at) =>','case EvidenceAnchor.Shot',old,'"omitted-'+label+'"'),'point',pid if label!='tick' else 'physical identity distinguishes point tick axis stream and interval shape')
for typ,path,scope,group in [('PlaybackSupport',support,'core','playprobe'),('HsmmResult',hsmm,'align','resultprobe')]:
 name='playback union' if typ=='PlaybackSupport' else 'result'
 edit('constructor-'+typ,path,'final class '+typ+' private (','final class '+typ+' private['+scope+'] (',group,name+' constructor '+('stays private even inside core' if typ=='PlaybackSupport' else 'is inaccessible even inside align'),True)
 marker='    val points: Vector[PlaybackInstant]\n):' if typ=='PlaybackSupport' else '    val textWireCompatible: Boolean\n):'
 product=marker[:-1]+' extends Product:\n  def canEqual(that: Any): Boolean = that.isInstanceOf['+typ+']\n  def productArity: Int = 0\n  def productElement(index: Int): Any = throw new IndexOutOfBoundsException(index.toString)'
 edit('product-'+typ,path,marker,product,group,name+' has no Product or Mirror construction door',True)
 mirror='object '+typ+':\n  given mutantMirror: scala.deriving.Mirror.ProductOf['+typ+'] =\n    new scala.deriving.Mirror.Product:\n      type MirroredType = '+typ+'\n      type MirroredMonoType = '+typ+'\n      type MirroredElemTypes = EmptyTuple\n      type MirroredElemLabels = EmptyTuple\n      type MirroredLabel = "'+typ+'"\n      def fromProduct(value: Product): '+typ+' = throw new IllegalArgumentException(value.productArity.toString)\n'
 edit('mirror-'+typ,path,'object '+typ+':',mirror,group,name+' has no Product or Mirror construction door',True)
edit('position-missing-as-zero',align,'relativeSpan(ref).map((a, b) => (a + b) / 2.0)','relativeSpan(ref).map((a, b) => (a + b) / 2.0).orElse(Some(0.0))','position','a present film node without a scoring feature remains absent through transition features')
edit('position-denominator',align,'if length > 0 then','if length >= 0 then','position','measured zero differs from missing and an unmeasured denominator remains absent')
edit('position-total-accessor',align,'trait SourceView:','trait SourceView:\n  def relativePosition(ref: SourceNodeRef): Double = measuredPosition(ref).getOrElse(0.0)','position','public missing-as-zero accessor is absent with a measured-position accepting control',True)
fp='physical evidence and scoring coordinates independently affect fingerprint'
edit('fingerprint-physical',wire,'field("physicalSupport", n.support.identity.hex)','field("physicalSupport", "omitted")','position',fp)
edit('fingerprint-feature-spans',wire,'list("scoringSpans", position.spans.refs.toVector.sorted.map(Render.spanRef))','list("scoringSpans", Vector.empty)','position',fp)
edit('fingerprint-feature-kind',wire,'case _: ScoringPosition.CanonicalText        => "canonical-text"','case _: ScoringPosition.CanonicalText        => "legacy-annotation-text"','position',fp)
edit('fingerprint-denominator',wire,'field("textLength", view.scoringLength.toString)','field("textLength", "omitted")','position',fp)
noncanonical='text support with absent or noncanonical scoring cannot enter the v3 wire'
edit('wire-legacy-feature',align,'case (TypedSupport.Text(spans), Some(ScoringPosition.CanonicalText(position))) =>\n        spans == position','case (TypedSupport.Text(spans), Some(position)) =>\n        spans == position.spans','codec',noncanonical)
edit('wire-feature-equality',align,'spans == position','spans.nonEmpty || position.nonEmpty','codec',noncanonical)
# Either support branch already has a SpanSet: return true without dropping names or producing warnings.
cases[-1]['change']=replace('spans == position','spans.minSpan.start >= 0 && position.minSpan.start >= 0')
inv='result refuses duplicate inventory lookup disagreement and lookup-only nominations'
edit('result-duplicate-inventory',hsmm,'sourceIndex.size != sourceNodes.size || sourceNodes.exists(n =>\n          view.node(n.ref) != Some(n)\n        )','sourceNodes.exists(n => view.node(n.ref) != Some(n))','codec',inv)
edit('result-lookup-inventory',hsmm,'sourceIndex.size != sourceNodes.size || sourceNodes.exists(n =>\n          view.node(n.ref) != Some(n)\n        )','sourceIndex.size != sourceNodes.size','codec',inv)
edit('result-nomination-inventory',hsmm,'anchors.find(ref => !sourceIndex.contains(ref))','anchors.find(ref => view.node(ref).isEmpty)','codec',inv)
refusal='generic HSMM encoding and both contextual decode doors refuse non-text support'
edit('result-retain-unused',hsmm,'sourceIndex.view.mapValues(_.support).toMap','sourceIndex.filter((ref, _) => candidateAnchors.valuesIterator.flatten.contains(ref)).view.mapValues(_.support).toMap','codec',refusal)
edit('wire-toJson-refusal',codec,'if !result.textWireCompatible then Left(HsmmCodecError.UnsupportedSupport)','if false then Left(HsmmCodecError.UnsupportedSupport)','codec',refusal)
edit('wire-encode-refusal',codec,'toJson(result).map(Canonical.print)','Right(Canonical.print(summon[Encoder[Wire]].apply(Wire.from(result))))','codec',refusal)
for method,end in [('decode','  /** Decode a JSON value'),('decodeJson','  private[codec] given')]:
 add('wire-'+method+'-refusal',codec,region('  def '+method+'(',end,'if !view.textWireCompatible then','if false then'),'codec',refusal)
edit('wire-generic-encoder',codec,'object HsmmResultCodec:', 'object HsmmResultCodec:\n  given Encoder[HsmmResult] = Encoder.instance(r => toJson(r).toOption.get)','codec','checked encoding remains available but no generic Encoder HsmmResult exists',True)
edit('point-component-binding',corecodec,'component.mapObject(_.add("bundleIdentity", support.bundleIdentity.hex.asJson))','component','codec','same legacy id with changed full binding has distinct point component bytes',suite=pointcodec)
edit('point-component-tick',corecodec,'"tick" -> at.at.toString.asJson','"tick" -> "0".asJson','codec','accepting control: point component records exact point and full binding',suite=pointcodec)
metric_missing='present inferred anchor without scoring feature stays eligible missing'
add('direction-missing-feature',metrics,region('    val route:', '    def positionOf', 'case _ => MetricObservation.Missing(MissingReason.AllMissing)','case _ => MetricObservation.Ineligible'),'metrics',metric_missing)
add('direction-unranked',metrics,region('    val route:', '    def positionOf','MetricObservation.Missing(MissingReason.ProviderAbstained)','MetricObservation.Missing(MissingReason.AllMissing)'),'metrics',metric_missing)
add('displacement-missing-feature',metrics,region('    val displacement =','    val structuralCoverage', 'case _ => MetricObservation.Missing(MissingReason.AllMissing)\n                  case _', 'case _ => MetricObservation.Ineligible\n                  case _'),'metrics',metric_missing)
add('displacement-unranked',metrics,region('    val displacement =','    val structuralCoverage','MetricObservation.Missing(MissingReason.ProviderAbstained)','MetricObservation.Missing(MissingReason.AllMissing)'),'metrics',metric_missing)
rate='different admitted part rates use exact LCM ticks for both native and primary support'
edit('composition-rate',corpus,'parts.map(p => BigInt(p._1.ticksPerSecond)).reduce((a, b) => a / a.gcd(b) * b)','BigInt(parts.head._1.ticksPerSecond)','corpus',rate)
edit('composition-project-scale',corpus,'BigInt(target.start) + numerator / denominator','BigInt(target.start) + (BigInt(tick) - source.start) + (numerator / denominator) * 0','corpus',rate)
receipt='composition receipt binds the exact admitted record annotation and ordered part identities'
for label,old,new in [('record','input.repairRecord.checksum','input.manifest.annotationSha256'),('annotation','input.manifest.annotationSha256','input.repairRecord.checksum')]:
 add('composition-receipt-'+label,corpus,region('        receipt <-','        segments <-',old,new),'corpus',receipt)
add('composition-receipt-parts',corpus,region('        receipt <-','        segments <-','_._2.identity','p => p._2.streams.head.checksum'),'corpus',receipt)
# Keep enough native support for construction to remain lawful when primary is dropped? The atlas must refuse;
# the ordinary interval control still proves the adapter works for the unmutated path.
edit('composition-native-point',corpus,'EvidenceAnchor.MediaPoint(bundle.id, stream.id, at),','EvidenceAnchor.MediaPoint(bundle.id, stream.id, projected),','corpus','composed source atlas retains exact native and primary row and scene support')
edit('composition-primary-point',corpus,'projected <- PlaybackInstant.on(primary, tick)','projected <- PlaybackInstant.on(primary, tick + 1L)','corpus','a point-only scene remains a point-only checked proposal unit')
edit('composition-scene-points',corpus,'.flatMap(r => rows(r.row).support.anchors.toVector)', '.flatMap(r => rows(r.row).support.anchors.toVector).filterNot(_.isInstanceOf[EvidenceAnchor.MediaPoint])','corpus','a point-only scene remains a point-only checked proposal unit')

edit('law-optional-feature','laws/src/main/scala/storymodel4s/laws/Laws.scala','node.scoringPosition.nonEmpty && v.scoringLength > 0','node.support != null && v.scoringLength > 0','laws','source-view laws accept exact film support without a scoring-position feature')

expected_counts={point:14,playprobe:3,position:6,resultprobe:3,aligncodec:14,pointcodec:2,
 annotation:23,metric:22,'storymodel4s.laws.LawsSuite':3}
def json_bytes(value):return (json.dumps(value,indent=2)+'\n').encode()
def immutable(path,value):
 raw=json_bytes(value)
 if path.exists():assert path.read_bytes()==raw,('refuse changed frozen record',path)
 else:path.write_bytes(raw)
 return sha(raw)
def report_path(project,suite):return repo/modules[project]/'target/test-reports'/('TEST-'+suite+'.xml')
def command(group):
 project,suites=groups[group]
 return project+'/testOnly '+' '.join(suites)+(' -- --tests *scoring*' if group=='laws' else '')
def control_tasks():return [command(g) for g in groups]
def mutant_tasks(c):
 project=groups[c['group']][0]
 return ([project+'/Compile/clean',project+'/Test/clean'] if c['cleanRecompile'] else [])+[command(c['group'])]
def inventory():
 import difflib
 assert clean()
 runner_path=Path(__file__).resolve().relative_to(root).as_posix()
 runner=Path(__file__).read_bytes()
 assert runner==subprocess.check_output(['git','show',revision+':'+runner_path],cwd=repo),'runner is not the candidate runner'
 rows=[]
 for c in cases:
  original=(repo/c['path']).read_text();mutant=c['change'](original)
  assert original!=mutant,c['id']
  row={k:v for k,v in c.items() if k!='change'}
  row.update(originalSha256=sha(original.encode()),mutantSha256=sha(mutant.encode()),command=prefix+mutant_tasks(c),
   diff=''.join(difflib.unified_diff(original.splitlines(True),mutant.splitlines(True),fromfile=c['path'],tofile=c['path'])))
  rows.append(row)
 tests={}
 for project,suites in groups.values():
  for suite in suites:
   path=modules[project].removesuffix('/.jvm')+'/src/test/scala/'+suite.replace('.','/')+'.scala'
   tests[path]=sha((repo/path).read_bytes())
 value=dict(codeRevision=revision,runnerSha256=sha(runner),testSourceSha256=tests,
  expectedControlCounts=expected_counts,controlCommand=prefix+control_tasks(),plannedMutationCount=len(cases),
  inventoryStatus='frozen before execution',cases=rows,
  boundaries=[
   'Compile-door mutants clean both Compile and Test and require actual main/test recompilation.',
   'A kill requires a compiled named failing test plus an independent named passing control. Invalid, surviving and interrupted attempts remain retained.',
   'Adapter malformed metadata guards are defensive beneath the sealed checked intake Atlas. Reachable tests cover differing valid rates, exact part coordinates, point retention, scene gaps and receipt inputs.',
   'Historical text JSON, backend WOG bytes, all1000 source loci and all17 inference anchors are separate preservation courts.',
   'Resume refuses any changed revision, runner, inventory, test/source/mutant hash, command, receipt, log, XML or incomplete restoration.'])
 immutable(out/'guard-witness-inventory.json',value)
 return value

def outcomes(raw):
 tree=ET.fromstring(raw);rows=tree.findall('testcase');assert rows
 names=[r.attrib['name'] for r in rows];assert len(set(names))==len(names)
 return {r.attrib['name']:('failed' if r.find('failure') is not None else 'error' if r.find('error') is not None else 'skipped' if r.find('skipped') is not None else 'passed') for r in rows}
def total_outcomes(values):
 flattened=[v for row in values.values() for v in row.values()]
 return dict(Total=len(flattened),Passed=flattened.count('passed'),Failed=flattened.count('failed'),
  Errors=flattened.count('error'),Skipped=flattened.count('skipped'),Ignored=0)
def verify_log(receipt):
 assert receipt['codeRevision']==revision
 log=Path(receipt['logPath']);assert sha(log.read_bytes())==receipt['logSha256']
 return log.read_text()
def capture_reports(label,receipt,selected):
 records={};values={}
 for project,suites in selected:
  for suite in suites:
   path=report_path(project,suite)
   if not path.exists():continue
   assert receipt['startedEpochSeconds']<=path.stat().st_mtime<=receipt['startedEpochSeconds']+receipt['seconds']+2,('stale XML',path)
   raw=path.read_bytes();target=out/(label+'--'+suite+'.xml');assert not target.exists();target.write_bytes(raw)
   records[suite]=dict(path=target.name,sha256=sha(raw));values[suite]=outcomes(raw)
 return records,values
def verify_reports(records):
 values={}
 for suite,r in records.items():
  raw=(out/r['path']).read_bytes();assert sha(raw)==r['sha256'];values[suite]=outcomes(raw)
 return values
def clear_reports(selected):
 for project,suites in selected:
  for suite in suites:report_path(project,suite).unlink(missing_ok=True)
def checked_control(label,binding,expected_names=None,clean_tests=False):
 cert=out/(label+'-verified.json')
 tasks=([p+'/Test/clean' for p in modules] if clean_tests else [])+control_tasks()
 argv=prefix+tasks
 if (out/(label+'.json')).exists():
  assert cert.exists(),('incomplete control cannot be reused',label)
  value=json.loads(cert.read_text());assert value['bindingSha256']==binding
  receipt=json.loads((out/(label+'.json')).read_text())
  assert value['receiptSha256']==sha((out/(label+'.json')).read_bytes())
  records=value['reports'];values=verify_reports(records)
 else:
  clear_reports(groups.values());receipt=run(label,argv)
  records,values=capture_reports(label,receipt,groups.values())
  value=dict(bindingSha256=binding,receiptSha256=sha((out/(label+'.json')).read_bytes()),reports=records)
 assert receipt['command']==argv and receipt['cleanBefore'] and receipt['cleanAfter'] and receipt['exitCode']==0
 verify_log(receipt)
 assert {k:len(v) for k,v in values.items()}==expected_counts
 assert all(v=='passed' for row in values.values() for v in row.values()),(label,'nonpassing control')
 assert total_outcomes(values)==receipt['aggregateTestCounts'],(label,'log/XML totals differ')
 names={k:sorted(v) for k,v in values.items()}
 if expected_names is not None:assert names==expected_names
 for c in cases:
  assert c['rejectingTest'] in values[c['rejectSuite']]
  assert values[c['controlSuite']][c['acceptingControl']]=='passed'
 value['testNames']=names;immutable(cert,value)
 assert clean()
 return receipt,names

def verify_completed(row,planned,binding,control_names):
 assert row['bindingSha256']==binding and row['codeRevision']==revision
 for key in planned:
  if key=='diff':continue
  assert row[key]==planned[key],(row['id'],key)
 assert row['restoredSha256']==planned['originalSha256'] and row['restoredCleanAfterMutation'] and row['baselineCleanBeforeMutation']
 assert row['compiled'] and row['acceptingControlPassed'] and row['exitCode']==1
 log=verify_log(row);label=Path(row['logPath']).stem
 raw=(out/(label+'.json')).read_bytes();assert sha(raw)==row['rawReceiptSha256']
 original=json.loads(raw)
 for key in original:assert row[key]==original[key],(label,key)
 restore=(out/(label+'-restoration.json')).read_bytes();assert sha(restore)==row['restorationSha256']
 restoration=json.loads(restore)
 assert restoration['bindingSha256']==binding and restoration['restoredCleanAfterMutation'] and restoration['baselineCleanBeforeMutation']
 assert restoration['id']==row['id'] and restoration['path']==row['path']
 assert restoration['originalSha256']==restoration['restoredSha256']==planned['originalSha256']
 assert restoration['mutantSha256']==planned['mutantSha256'] and restoration['codeRevision']==revision
 values=verify_reports(row['reports']);project,suites=groups[row['group']]
 assert {s:sorted(v) for s,v in values.items()}=={s:control_names[s] for s in suites}
 assert total_outcomes(values)==row['aggregateTestCounts']
 assert values[row['rejectSuite']][row['rejectingTest']]=='failed'
 assert values[row['controlSuite']][row['acceptingControl']]=='passed'
 assert row['failedTests']==[dict(suite=s,name=n) for s,rs in values.items() for n,v in rs.items() if v=='failed']
 if row['cleanRecompile']:
  for leaf in ['classes','test-classes']:
   assert re.search(r'compiling \d+ Scala sources? to .*/'+re.escape(modules[project])+r'/target/scala-[^/]+/'+leaf,log),('missing main/probe compilation',row['id'],leaf)

def mutations():
 assert clean();frozen=inventory();binding=sha((out/'guard-witness-inventory.json').read_bytes())
 _,control_names=checked_control('isolated-control',binding)
 planned={c['id']:c for c in frozen['cases']}
 results=json.loads((out/'mutation-progress.json').read_text()) if (out/'mutation-progress.json').exists() else []
 assert len({r['id'] for r in results})==len(results)
 for r in results:verify_completed(r,planned[r['id']],binding,control_names)
 completed={r['id'] for r in results}
 for c in cases:
  assert clean()
  if c['id'] in completed:continue
  expected=planned[c['id']];path=repo/c['path'];saved=path.read_bytes();mutant=c['change'](saved.decode()).encode()
  assert sha(saved)==expected['originalSha256'] and sha(mutant)==expected['mutantSha256']
  project,suites=groups[c['group']];clear_reports([(project,suites)])
  attempt=1
  while (out/(c['id']+'-attempt-'+str(attempt)+'.log')).exists():attempt+=1
  label=c['id']+'-attempt-'+str(attempt)
  try:
   path.write_bytes(mutant)
   receipt=run(label,expected['command'],False)
   records,values=capture_reports(label,receipt,[(project,suites)])
   assert receipt['exitCode']==1 and receipt['aggregateTestCounts']['Failed']>0 and receipt['aggregateTestCounts']['Errors']==0,(c['id'],'not an executed failing mutant')
   assert {s:sorted(v) for s,v in values.items()}=={s:control_names[s] for s in suites}
   assert total_outcomes(values)==receipt['aggregateTestCounts']
   assert values[c['rejectSuite']][c['rejectingTest']]=='failed',(c['id'],'survived')
   assert values[c['controlSuite']][c['acceptingControl']]=='passed',(c['id'],'control failed')
   receipt.update({k:v for k,v in c.items() if k!='change'})
   receipt.update(bindingSha256=binding,compiled=True,acceptingControlPassed=True,
    originalSha256=sha(saved),mutantSha256=sha(mutant),reports=records,
    rawReceiptSha256=sha((out/(label+'.json')).read_bytes()),
    failedTests=[dict(suite=s,name=n) for s,row in values.items() for n,v in row.items() if v=='failed'])
  finally:
   path.write_bytes(saved)
   restoration=dict(bindingSha256=binding,codeRevision=revision,id=c['id'],path=c['path'],baselineCleanBeforeMutation=True,
    originalSha256=sha(saved),mutantSha256=sha(mutant),restoredSha256=sha(path.read_bytes()),restoredCleanAfterMutation=clean())
   immutable(out/(label+'-restoration.json'),restoration)
  assert clean()
  receipt.update(baselineCleanBeforeMutation=True,restoredSha256=sha(path.read_bytes()),restoredCleanAfterMutation=True,
   restorationSha256=sha((out/(label+'-restoration.json')).read_bytes()))
  verify_completed(receipt,expected,binding,control_names)
  results.append(receipt);(out/'mutation-progress.json').write_bytes(json_bytes(results))
  print(c['id'],'KILLED; named control passed; source restored clean',flush=True)
 restored,_=checked_control('restored-control-clean',binding,control_names,True)
 assert clean() and len(results)==len(cases)
 for r in results:verify_completed(r,planned[r['id']],binding,control_names)
 immutable(out/'mutations.json',dict(codeRevision=revision,bindingSha256=binding,mutations=results,restoredControl=restored))

if __name__=='__main__':{'inventory':inventory,'mutations':mutations}[sys.argv[1]]()
