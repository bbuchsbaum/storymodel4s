from pathlib import Path
import hashlib,json,os,re,subprocess,sys,time,xml.etree.ElementTree as ET
root=Path(os.environ.get('D1A_S2_ROOT','/Users/bbuchsbaum/code/scala/storymodel4s'))
repo=Path(os.environ.get('D1A_S2_REPO','/private/tmp/storymodel4s-d1a-s2-20260919'))
out=Path(os.environ.get('D1A_S2_OUTPUT',str(root/'data/study/d1a-s2-20260919')))
out.mkdir(parents=True,exist_ok=True)
pin=os.environ.get('D1A_S2_GRAKERN','/private/tmp/storymodel4s-film-consumer-20260919-9zqdg50z/grakern')
revision=subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip()
prefix=['sbt','-Dstorymodel4s.grakern.build='+pin,'-batch']

def sha(value): return hashlib.sha256(value).hexdigest()
def clean(): return not subprocess.check_output(['git','status','--porcelain'],cwd=repo)
def run(label,args,success=True):
    log=out/(label+'.log'); assert not log.exists(),log
    before=clean(); started=time.time()
    with log.open('w') as stream: result=subprocess.run(args,cwd=repo,stdout=stream,stderr=subprocess.STDOUT)
    text=log.read_text(); totals=[x for x in text.splitlines() if x.startswith(('[info] Passed: Total','[info] Failed: Total','[error] Failed: Total'))]
    record=dict(codeRevision=revision,command=args,exitCode=result.returncode,cleanBefore=before,cleanAfter=clean(),seconds=round(time.time()-started,3),logPath=str(log),logSha256=sha(log.read_bytes()),testTotals=totals)
    counts=dict.fromkeys(['Total','Failed','Errors','Passed','Skipped'],0)
    for line in totals:
        for key,value in re.findall(r'(Total|Failed|Errors|Passed|Skipped) (\d+)',line): counts[key]+=int(value)
    record['aggregateTestCounts']=counts
    (out/(label+'.json')).write_text(json.dumps(record,indent=2)+'\n')
    print(label,result.returncode,counts,flush=True)
    if success and result.returncode:
        print(text[-7000:],flush=True);raise RuntimeError(label)
    return record

def replace(old,new):
    def change(text):
        assert text.count(old)==1,(old,text.count(old))
        return text.replace(old,new)
    return change

def region(start,end,old,new):
    def change(text):
        a=text.index(start);b=text.index(end,a)
        section=text[a:b];assert section.count(old)==1,(start,old,section.count(old))
        return text[:a]+section.replace(old,new)+text[b:]
    return change

support='core/src/main/scala/storymodel4s/core/support.scala'
source='core/src/main/scala/storymodel4s/core/source.scala'
atlas='core/src/main/scala/storymodel4s/core/atlas.scala'
identity='core/src/main/scala/storymodel4s/core/sourceIdentity.scala'
codec='codec/src/main/scala/storymodel4s/codec/core.scala'
model='story/src/main/scala/storymodel4s/story/model.scala'
compiler='document/src/main/scala/storymodel4s/document/compiler.scala'
suites={
 'support':('coreJVM','storymodel4s.core.D1aSupportSuite','accepting control: all four lawful anchor kinds survive'),
 'atlas':('coreJVM','storymodel4s.core.D1aAtlasSuite','accepting control: unique units and bound sentences have exact lookup'),
 'probe':('coreJVM','storymodel4s.coreprobe.D1aConstructionBoundarySuite','accepting control: same-module value carriers retain construction doors'),
 'codec':('codecJVM','storymodel4s.codec.EvidenceSupportCodecSuite','accepting control: ordinary text evidence keeps its omitted-anchor wire and round trip'),
 'model':('storyJVM','storymodel4s.story.D1aTextBoundarySuite','accepting control: ordinary text draft and internal copy remain constructible'),
 'compiler':('documentJVM','storymodel4s.document.CompilerSuite','accepted evidence compiles into a validated story model')}
cases=[]
def add(label,path,change,suite,test,clean_compile=False):
    if suite=='atlas': label+='-v2'
    cases.append((label,path,change,suite,test,clean_compile))
add('foreign-bundle',support,replace('if anchor.anchorBundle != bundle.id then','if anchor.anchorBundle != bundle.id && false then'),'support','refuse foreign bundle even with a local stream')
add('foreign-stream',support,replace('invalid("stream", "anchor stream is foreign to the bundle")','Right(())'),'support','refuse foreign stream even with a local bundle')
add('stream-kind',support,replace('if !kindMatches then','if !kindMatches && false then'),'support','refuse anchor stream-kind mismatch')
add('interval-axis',support,replace('if axis != intervals.axis =>','if axis != intervals.axis && false =>'),'support','refuse MediaTime axis different from its interval axis')
add('text-extent',support,replace('if spans.spans.forall(_.endExclusive <= extent.length) =>','if spans.spans.forall(_.endExclusive <= extent.length) || true =>'),'support','refuse text spans outside the selected extent')
add('stream-axis',support,replace('invalid("stream-axis", "anchor axis does not belong to the selected stream")','Right(())'),'support',"right bundle does not license another stream's axis")
add('native-extent',support,replace('interval.start >= extent.start && interval.endExclusive <= extent.endExclusive','interval.start >= extent.start'),'support','native support obeys the selected stream extent')
add('mapping-image',support,replace('if imageCovers(mapping, stream, intervals) then','if imageCovers(mapping, stream, intervals) || true then'),'support','clock mapping admits its exact image and refuses partial or absent coverage')
add('composition-source',support,replace('segments.forall(segment => within(segment.source, extent)) &&','(segments.forall(segment => within(segment.source, extent)) || true) &&'),'support','composition image preserves gaps and refuses source segments outside stream extent')
add('composition-gap',support,replace('if part.start <= covered && part.endExclusive > covered then','if part.endExclusive > covered then'),'support','composition image preserves gaps and refuses source segments outside stream extent')
add('clock-timebase',support,replace('fraction(extent.timebase.scale) * fraction(repair.scale)','fraction(ExactRational.One) * fraction(repair.scale)'),'support','clock image uses native timebase and exact fractional boundaries')
add('text-stream-selection',source,replace('case EvidenceAnchor.Text(_, s, spans) if s == stream => spans','case EvidenceAnchor.Text(_, s, spans) if s == stream || true => spans'),'support','text spans are selected per stream')
add('union-overlap',source,replace('last.endExclusive.max(interval.endExclusive)','last.endExclusive'),'support','interval union merges overlap and adjacency while preserving gaps')
add('union-adjacency',source,replace('if interval.start <= last.endExclusive =>','if interval.start < last.endExclusive =>'),'support','interval union merges overlap and adjacency while preserving gaps')
add('duplicate-unit',atlas,replace('if ids.distinct.size != ids.size then','if ids.distinct.size != ids.size && false then'),'atlas','refuse duplicate proposal unit IDs before map construction')
add('duplicate-surface',atlas,replace('if surfaces.distinct.size != surfaces.size then','if surfaces.distinct.size != surfaces.size && false then'),'atlas','refuse duplicated bound sentence')
add('atlas-primary',atlas,replace('if bundle.primaryAxis.kind != AxisKind.EditionPlayback then','if bundle.primaryAxis.kind != AxisKind.EditionPlayback && false then'),'atlas','refuse text and annotation timeline primaries explicitly')
add('atlas-surface',atlas,replace('!surface.exists(_.surface.byId.get(id).exists(_.kind == SurfaceUnitKind.Sentence))','!surface.exists(_.surface.byId.get(id).exists(_.kind == SurfaceUnitKind.Sentence)) && false'),'atlas','refuse missing, foreign and nonsentence proposal surfaces')
add('missing-map',support,replace('invalid(\n            "stream-axis",\n            "support requires one unambiguous coordinate mapping from its stream"\n          )','Right(())'),'support',"right bundle does not license another stream's axis")
add('mapping-cardinality',support,replace('maps match','maps.take(1) match'),'support','clock mapping admits its exact image and refuses partial or absent coverage')
for label,guard in [('media','a == axis'),('shot','interval.axis == axis'),('track','set.axis == axis')]:
    add('interval-axis-'+label,source,region('def intervalsOn(', '/** Text coordinates', 'if '+guard+' =>','if '+guard+' || true =>'),'support','single-interval selection refuses a foreign axis for every playback anchor')
add('mapping-equivalence',support,replace('.distinctBy(_.identity)','.distinct'),'support','equivalent mapping order has identical identity and support admission')
add('atlas-support-attempt2',atlas,replace('EvidenceSupport.of(bundle, unit.support.anchors.toVector).map(_ => ())','EvidenceSupport.of(bundle, unit.support.anchors.toVector).orElse(Right(unit.support)).map(_ => ())'),'atlas','refuse support belonging to another bundle')
add('receipt-safe-binding',source,replace('lazy val bindingIdentity: Checksum = SourceIdentity.receipt(this)','lazy val bindingIdentity: Checksum = identity'),'support','safe receipt binding distinguishes legacy delimiter collision')
add('mapping-offset',identity,replace('rational(repair.scale) ++ rational(repair.offset)','rational(repair.scale) ++ rational(ExactRational.Zero)'),'support','full mapping and bundle identity distinguish equal relation IDs')
add('mapping-scale',identity,replace('rational(repair.scale) ++ rational(repair.offset)','rational(ExactRational.One) ++ rational(repair.offset)'),'support','mapping identity binds scale, receipt, composition and correspondence payloads')
add('bundle-mapping',identity,replace('value.mappings.map(_.identity.hex).sorted','value.mappings.map(_.family.toString).sorted'),'support','full mapping and bundle identity distinguish equal relation IDs')
add('bundle-stream-extent',identity,replace('streamKind ++ extent(stream.extent) ++','streamKind ++ Vector("omitted") ++'),'support','full mapping and bundle identity distinguish equal relation IDs')
add('bundle-primary',identity,replace('Vector(value.primaryAxis.fingerprint.hex, streams.size.toString)','Vector("omitted", streams.size.toString)'),'support','full bundle identity independently binds its primary fingerprint')
for field in ['unit.id.value','unit.kind.toString','unit.span.start.toString','unit.ordinal.toString']:
    add('surface-'+field.split('.')[1],identity,region('def surface(', '\n    digest(\n      Vector(\n        "proposal-surface',field,'"omitted"'),'atlas','surface digest binds each unit field independently')
add('surface-endExclusive',identity,replace('unit.span.endExclusive.toString','"omitted"'),'atlas','surface digest binds each unit field independently')
add('surface-parent',identity,replace('optional(unit.parent.map(_.value))','Vector("omitted")'),'atlas','surface digest binds each unit field independently')
add('surface-source',identity,region('def surface(', '\n    )\n', 'value.source.canonicalChecksum.hex','"omitted"'),'atlas','surface binding distinguishes canonical bytes, unit structure and receipt')
# Explicit payload fields, one mutant for each public anchor case.
for kind,next_kind,field in [('Text','MediaTime','"stream" -> stream.value.asJson,'),('MediaTime','Shot','"axis" -> axis.value.asJson,'),('Shot','Track','"shot" -> shot.value.asJson,'),('Track',None,'"track" -> track.value.asJson,')]:
    end='case EvidenceAnchor.'+next_kind if next_kind else '\n    Json.obj("schema"'
    add('codec-'+kind.lower(),codec,region('case EvidenceAnchor.'+kind,end,field,''),'codec',kind+' payload is complete and its anchored encoding is refused on decode')
add('evidence-decode-refusal',codec,replace('if c.downField("anchors").focus.isEmpty then','if c.downField("anchors").focus.isEmpty || true then'),'codec','Text payload is complete and its anchored encoding is refused on decode')
add('support-decode-refusal',codec,region('given Decoder[EvidenceSupport]', '// ---- source and atlas', 'Left(\n      DecodingFailure(\n        "evidence-support/v1 is unsupported by the 0.7.0 text decoder",\n        cursor.history\n      )\n    )','SourceBundle.writtenText(StorySource.fromText("Alpha.").toOption.get).flatMap(bundle => EvidenceSupport.text(bundle, bundle.streams.head.id, SpanSet.one(TextSpan.unsafe(0, 3)))).left.map(error => DecodingFailure(error.message, cursor.history))'),'codec','Text payload is complete and its anchored encoding is refused on decode')
add('text-model-claims',model,replace('claims.forall(_.evidence.forall(_.anchors.isEmpty)) &&','(claims.forall(_.evidence.forall(_.anchors.isEmpty)) || true) &&'),'model','text draft refuses anchored ordinary claim evidence before export')
add('text-model-boundary',model,replace('hierarchy.boundaryBeliefs.forall(_.evidence.forall(_.anchors.isEmpty)),','(hierarchy.boundaryBeliefs.forall(_.evidence.forall(_.anchors.isEmpty)) || true),'),'model','text draft refuses anchored boundary evidence outside the claim ledger')
add('compiler-ledger',compiler,replace('if evidence.anchors.nonEmpty then','if evidence.anchors.nonEmpty && false then'),'compiler','text compiler refuses anchored ledger evidence with an accepting text control')
add('compiler-inline',compiler,replace('if ev.anchors.nonEmpty then','if ev.anchors.nonEmpty && false then'),'compiler','text compiler refuses anchored inline evidence with an accepting text control')
for name,path in [('NarrativeProposalUnit',source),('AnchoredNarrativeAtlas',atlas),('BoundProposalSurface',atlas)]:
    add('probe-'+name,path,replace('final class '+name+' private','final case class '+name+' private'),'probe',name+' has no unchecked constructor, copy or Product doors',True)
add('probe-sealed-atlas',atlas,replace('sealed trait NarrativeSourceAtlas:','trait NarrativeSourceAtlas:'),'probe','NarrativeSourceAtlas cannot be extended outside core',True)

def report_path(project,suite):
    module=project.removesuffix('JVM')
    directory={'core':'core','story':'story','document':'document','codec':'codec'}[module]
    return repo/directory/'.jvm/target/test-reports'/('TEST-'+suite+'.xml')

def mutations():
    assert clean()
    # Validate edits before spending build time.
    for label,path,change,_,_,_ in cases: change((repo/path).read_text())
    control_tasks=[p+'/testOnly '+s for p,s,_ in suites.values()]
    if not (out/'isolated-control.json').exists(): run('isolated-control',prefix+control_tasks)
    results=json.loads((out/'mutation-progress.json').read_text()) if (out/'mutation-progress.json').exists() else []
    completed={Path(r['logPath']).stem for r in results}
    for label,name,change,key,rejecting,clean_compile in cases:
        if label in completed: continue
        path=repo/name;saved=path.read_bytes();mutant=change(saved.decode())
        project,suite,accepting=suites[key];report=report_path(project,suite);report.unlink(missing_ok=True)
        try:
            path.write_text(mutant)
            args=prefix+([project+'/clean'] if clean_compile else [])+[project+'/testOnly '+suite]
            receipt=run(label,args,False)
            assert receipt['exitCode']==1 and report.exists(),(label,'not an executed mutant')
            xml=report.read_bytes()
            (out/(label+'.xml')).write_bytes(xml)
            tests={t.attrib['name']:t for t in ET.fromstring(xml).findall('testcase')}
            assert rejecting in tests and tests[rejecting].find('failure') is not None,(label,'survived',rejecting)
            assert accepting in tests and all(tests[accepting].find(t) is None for t in ['failure','error','skipped']),(label,'control failed')
            assert receipt['aggregateTestCounts']['Errors']==0
            receipt.update(path=name,compiled=True,cleanRecompile=clean_compile,originalSha256=sha(saved),mutantSha256=sha(mutant.encode()),junitSha256=sha(xml),namedRejectingTest=rejecting,acceptingControl=accepting,acceptingControlPassed=True,failedTests=[n for n,t in tests.items() if t.find('failure') is not None])
            results.append(receipt)
            (out/'mutation-progress.json').write_text(json.dumps(results,indent=2)+'\n')
            print(label,'KILLED; control passed',flush=True)
        finally: path.write_bytes(saved)
        assert clean()
    restored=run('restored-control-final',prefix+['coreJVM/clean']+control_tasks)
    (out/'mutations.json').write_text(json.dumps(dict(codeRevision=revision,mutations=results,restoredControl=restored),indent=2)+'\n')

def release():
    assert clean()
    run('scope',['bash','tools/reference-scope.sh','75c8df52',revision])
    receipt=run('full-gate',prefix+['clean','compileAll','testAll'])
    build=(repo/'build.sbt').read_text()
    modules=re.findall(r'"(\w+)"',re.search(r'val allModules = List\((.*?)\n\)',build,re.S).group(1))
    platforms=re.findall(r'"(\w+)"',re.search(r'val allPlatforms = List\((.*?)\)',build,re.S).group(1))
    jvm=re.findall(r'"(\w+)"',re.search(r'val jvmOnlyModules = List\((.*?)\n\)',build,re.S).group(1))
    tasks=[m+p+'/test' for m in modules for p in platforms]+[m+'/test' for m in jvm]
    assert len(tasks)==len(receipt['testTotals']),(len(tasks),len(receipt['testTotals']))
    receipt['taskTotalsInAliasOrder']=dict(zip(tasks,receipt['testTotals']))
    (out/'full-gate.json').write_text(json.dumps(receipt,indent=2)+'\n')
    run('format-last',prefix+['scalafmtCheckAll','scalafmtSbtCheck'])
    run('docs-examples',['env','STORYMODEL4S_GRAKERN_BUILD='+pin,'npm','--prefix','docs-site','run','verify:examples'])

if __name__=='__main__': {'mutations':mutations,'release':release}[sys.argv[1]]()
