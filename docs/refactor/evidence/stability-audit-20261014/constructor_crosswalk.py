#!/usr/bin/env python3
"""Generate conservative exact-identity coverage; arguments: OUTPUT_DIRECTORY INVENTORY_JSON_OR_GZ."""
from pathlib import Path
import gzip,json,re,hashlib,collections,subprocess,tarfile,io,sys
ROOT=Path(__file__).resolve().parents[4];OUT=Path(sys.argv[1]);OUT.mkdir(parents=True,exist_ok=True)
IP=Path(sys.argv[2]);raw=gzip.decompress(IP.read_bytes()) if IP.suffix=='.gz' else IP.read_bytes();inv=json.loads(raw)
JP=ROOT/'docs/refactor/evidence/stability-audit-20261014/constructor-judgment.md'; judgment=JP.read_text()
policy_source='bfbcb12190f9291babdc14b38b8a57109467b68d'
policy=subprocess.check_output(['git','show',policy_source+':docs/api-stability.md'],cwd=ROOT)
(OUT/'api-stability-basis.md').write_bytes(policy)
rows=[x for x in judgment.splitlines() if x.startswith('| `')]
rowids={f'J{i+1:02}':{'row':i+1,'markdown':row,'line':judgment.splitlines().index(row)+1} for i,row in enumerate(rows)}
sections={m.group(1):{'heading':m.group(0),'line':judgment[:m.start()].count('\n')+1} for m in re.finditer(r'^### (A[1-6])\..+$',judgment,re.M)}
# All match rules are coverage assignments only. They do not inherit a checked-value assertion.
rules=[]
def add(id,title,pattern,existing=None,boundary='requires exact stable dependency disposition',note=''):
 rules.append(dict(id=id,title=title,pattern=pattern,existingJudgment=existing,boundary=boundary,note=note))
def family(id,title,pkg,names,existing=None,boundary='requires exact stable dependency disposition',note=''):
 add(id,title,r'^storymodel4s\.'+re.escape(pkg)+r'\.(?:'+ '|'.join(re.escape(n) for n in names.split())+r')(?:\.|$)',existing,boundary,note)
family('J01','Executed mapping authority','align','MappingRun StageProvenance.Derived','J01','intended stable facade and result receipts')
family('J02','Reference profiles and outcomes','align','LocalReference','J02','intended stable facade')
family('J03','Reconstruction and structural estimands','align','StructuredReconstruction StructuralWeight StructuralSelection StructuralProfile StructuralMemberEstimate StructuralMemberExclusion StructuralReducer StructuralReductionReceipt','J03','intended stable facade')
family('J05','Checked cost/support quantities','align','Admissibility StructuralCoverage CostBreakdown SupportAssessment CellSupportBasis SupportNotApplicableReason SupportUnestablishedReason','J05','explicit stable structural dependencies')
family('J06','HSMM structure and flow','align','HsmmResult FlowStep TransitionFlow','J06','explicit stable structural result; no general execution authentication')
family('J07a','Source dictionary/composition support','align','SourceRepresentation BundleEntry SourceSupportStatus DeclaredComposition BundleRole PartOrder CoverageUnknownReason UnlocatedReason','J07','stable source contract')
family('J07b','Physical coordinate and source support','core','AxisExtent AxisKind CheckedMapping ClockRepair CompositionSegment EditionCorrespondence EvidenceAnchor EvidenceSupport ExactRational MappingFamily MappingRelation MediaDuration ObservationAuthority PacketTimeFields PlaybackInstant PlaybackInterval PlaybackIntervalSet PlaybackSupport PresentationAxis PrimaryProjection RationalTimebase SourceBundle SourceDerivationReceipt SourceStream StreamKind TimestampField TrackComposition TypedSupport CallerRuntimePacketRecord','J07','stable source contract','Broad source/support row; per-type proof coverage must not be inferred from this grouping.')
family('J08','Recall inventory identity','recall','RecallInventory SegmentationId WordIdPolicy WordMembership UnassignedWordReason DecompositionReason DecompositionStatus MappingSemantics','J08','explicit stable recall accounting')
family('J09','Declared recall clock observations','recall','RecallTiming','J09','intended inspection/observation contract; declaration is not physical synchronization')
family('J10','Estimate and missingness vocabulary','features','Estimate Coverage MissingReason MalformedReason UndefinedReason','J10','explicit stable estimate family')
family('J12','Pipeline request and refusal data','pipeline','MappingPipeline.Config MappingPipeline.Refusal','J12','public command request; Scala facade exposure still needs explicit disposition')
family('J13','Historical candidates/evidence','align','CandidateSet Candidates Nomination LocalEvidence','J13','experimental construction exposed by intended stable facade; decision pending')
family('J14a','Controlled candidate/scoring values','align','StrictCandidates StrictCandidateSet StrictCandidateConfig StrictCostModel StrictBinding StrictSemanticChannel CaptionEvidence CaptionScoring SurfaceEmbedding SurfaceRendering TieBudget TieBudgetRequest TieOverflow UniformSemanticScores CandidateProvenance CandidateTiePolicy','J14','stable targets if exposed; exact producer/receipt distinction needed','Family coverage is not a claim that every nested request/enum is unforgeable.')
family('J14b','Checked ONNX/caption registration','embed.onnx','OnnxMappingRegistration.Registered','J14','stable target receipt if exposed')
family('J14c','Checked caption registration','pipeline','CaptionMapping.Registered','J14','stable target receipt if exposed')
family('J15','Embedding recipe/token budget','embed','EmbeddingSpace TruncationPolicy','J15','experimental dependency with explicit open token-budget finding')
family('J04','Mapping record/accounting vocabulary','align','MappingResult UnitOutcome MappingLink UnitMeasures UnitRoles UnitStageRefs NormalizedScoreMass TransportMass ModelPosterior RawScores CalibratedProbability CalibratedEvent MeasureDerivation MeasureKind FidelityReport FidelityNotAssessedReason Facet FacetVerdict FidelityMode CandidateCoverage CandidatePolicy CandidateSetId TargetUniverseId DeclaredUniverse DecisionBasis DecisionCalibrationUnavailableReason DecisionOrigin DecisionPolicy DecisionRequest DerivationBinding InferencePolicy MappingPolicies ProcessingFailure ProcessingStatus ProviderIdentity ProviderStatus ReferencePrior ScoreDirection Stage StageEntry StageEntryId StageLedger StageProvenance StageReceipt TermSupportNotComputedReason UnknownProvenanceReason LocalizationStatus','J04','stable mapping records and descriptive vocabulary')
# Descriptive error/reason constructors are covered by the existing request/refusal row, not checked authority.
add('J12-errors','Descriptive refusals/errors',r'^storymodel4s\..*(?:Refusal|Error)(?:\.|$)','J12','descriptive value only; containing API stability follows its own boundary','No refusal constructor certifies the event it describes.')
# Broad graph row is matched conservatively, flagged as coarse rather than individually reviewed.
add('J11-proposition','Proposition/chart declarations',r'^storymodel4s\.proposition\.','J11','stable chart/evidence vocabulary; distinguish unchecked proposals','Broad existing row; individual relational products not thereby certified.')
add('J11-recall','Recall graph/unit declarations',r'^storymodel4s\.recall\.','J11','stable recall value vocabulary','Broad existing row; individual relational products not thereby certified.')
add('J11-story','Narrative/model dependencies',r'^storymodel4s\.story\.','J11','experimental narrative implementation exposed by stable signatures; decision pending','Existing row mentions story values, not blanket stable promotion or constructor acceptance.')
# Explicit unresolved groups: do not fill gaps by calling all other classes checked.
family('P-core-identity','Core text/span/identity declarations','core','OpaqueId TextSpan SpanRef SpanSet TokenRange StorySource StoryTitle SourceKind SurfaceAtlas SurfaceUnit SurfaceUnitKind TitleProvenance','PENDING','explicit stable core dependency; construction judgment row missing')
family('P-core-claims','Core claim/credence/provenance','core','ClaimMeta ClaimLedger Evidence EpistemicStatus Credence CredenceBasis Score Provenance ProviderCall BuildReceipt Resolved','PENDING','explicit stable core claim/provenance dependency; mixed construction judgments needed')
add('P-core-atlas','Core atlas/proposal bindings',r'^storymodel4s\.core\.','PENDING','stable core dependencies where exposed; proposal/checked boundary needs explicit judgment')
add('P-feature-descriptors','Feature spaces/sidecars/temporal hypotheses',r'^storymodel4s\.features\.','PENDING','stable only where exposed; open descriptors and checked sidecar factories need separate judgments')
family('P-source-descriptors','Source node and content descriptors','align','SourceView NodeSummary ParticipantSummary ParticipantContent UnitContent TargetContent Members PropositionalScope ScoringPosition SourceNodeRef ContextTag ContentGrain TargetGrain AnalysisGrain RelationLayer SegmentEvidence SemanticOutcomeSummary','PENDING','explicit stable source signature values; current dictionary row is insufficient for every descriptor')
add('P-temporal-producer','Executed temporal query/grid/projection values',r'^storymodel4s\.align\.Temporal','PENDING','reached through existing mapping exchange; distinguish executed producer APIs from later imported inspection','The existing imported Session/Projection row is not a blanket judgment of the 2c executed temporal closure.')
add('P-engine','Inference/cost-model dependencies',r'^storymodel4s\.align\.','PENDING','experimental engine/inference dependency; promote exact exposure or narrow facade')
add('P-codec','Codec contexts, source packages and exchange bundles',r'^storymodel4s\.codec\.','PENDING','stable contextual readers/exchange values where required; permission and relational wire judgments need explicit coverage')
add('P-embedding','Embedding/vector/receipt/privacy/provider dependencies',r'^storymodel4s\.embed\.','PENDING','experimental provider APIs unless exact signature dependency promoted; receipt/value judgments missing')
add('P-pipeline','Orchestration, preparation and disclosure dependencies',r'^storymodel4s\.pipeline\.','PENDING','commands stable, orchestration internal; exact Scala facade exposure needs boundary decision')
add('P-media','Media acquisition/package dependencies',r'^storymodel4s\.media\.','PENDING','experimental adapters; exact exposed source-package/receipt values need disposition')
add('P-corpus','Corpus intake/verified-artifact dependencies',r'^storymodel4s\.corpus\.','PENDING','experimental adapter dependency; distinguish descriptive input from verified artifact')

additional={'P-core-claims':'A1','P-core-identity':'A2','P-core-atlas':'A2','P-source-descriptors':'A3','P-feature-descriptors':'A3','P-codec':'A4','P-temporal-producer':'A5'}
for rule in rules:
 if rule['id'] in additional:
  rule['existingJudgment']='additional:'+additional[rule['id']]
  rule['boundary']='Scoped construction judgment recorded; containing API stability follows its explicit dependency boundary.'
  rule['note']='Scoped family disposition in constructor-judgment.md section '+additional[rule['id']]+'. No blanket per-type checked claim or stability promotion.'

# Parse actual ClassInfo arguments at balanced nesting; do not confuse module classes with nominal classes.
def classinfo(s):
 start=s.index('ClassInfo(')+len('ClassInfo(');level=0;items=[];at=start
 for i in range(start,len(s)):
  c=s[i]
  if c in '([{':level+=1
  elif c in ')]}':
   if level==0:items.append(s[at:i].strip());break
   level-=1
  elif c==',' and level==0:items.append(s[at:i].strip());at=i+1
 return items
parts={e:e.split(' :: ',1) for e in inv['inventory']};infos={e:classinfo(sig) for e,(name,sig) in parts.items() if 'ClassInfo(' in sig}
module_names={parts[e][0] for e,x in infos.items() if x[1].startswith('module class ')}
enum_names={parts[e][0] for e,x in infos.items() if 'object reflect),Enum)' in x[2]}
# Confirmed opaque declarations, explicitly distinguished from abstract generic members and transparent aliases.
opaque={
 'storymodel4s.align.evidence$package.LocalEvidenceId.LocalEvidenceId':('align/src/main/scala/storymodel4s/align/evidence.scala',262),
 'storymodel4s.align.wire$package.AdmissibilityEcho.AdmissibilityEcho':('align/src/main/scala/storymodel4s/align/wire.scala',176),
 'storymodel4s.align.wire$package.ViewFingerprint.ViewFingerprint':('align/src/main/scala/storymodel4s/align/wire.scala',79),
 'storymodel4s.core.OpaqueId.T':('core/src/main/scala/storymodel4s/core/ids.scala',23),
 'storymodel4s.core.atlas$package.LanguageTag.LanguageTag':('core/src/main/scala/storymodel4s/core/atlas.scala',7),
 'storymodel4s.core.hash$package.Checksum.Checksum':('core/src/main/scala/storymodel4s/core/hash.scala',92),
 'storymodel4s.core.ids$package.Probability.Probability':('core/src/main/scala/storymodel4s/core/ids.scala',121),
 'storymodel4s.core.provenance$package.PromptTemplateVersion.PromptTemplateVersion':('core/src/main/scala/storymodel4s/core/provenance.scala',17),
 'storymodel4s.core.surface$package.TokenIndex.TokenIndex':('core/src/main/scala/storymodel4s/core/surface.scala',20),
 'storymodel4s.embed.identity$package.Dimension.Dimension':('embed-core/src/main/scala/storymodel4s/embed/identity.scala',91),
 'storymodel4s.embed.identity$package.PolicyModelIdentity.PolicyModelIdentity':('embed-core/src/main/scala/storymodel4s/embed/identity.scala',40),
 'storymodel4s.features.sidecar$package.SidecarBlockRows':('features/src/main/scala/storymodel4s/features/sidecar.scala',13)
}
def kind(e):
 n,s=parts[e]
 if e in infos:
  tag=infos[e][1]
  return 'module_class' if tag.startswith('module class ') else 'trait_type' if tag.startswith('trait ') else 'class_type'
 if n in opaque:return 'opaque_type'
 if '.Mirrored' in n:return 'generated_mirror_type_member'
 if s.startswith('_ '):
  lo,sep,hi=s.removeprefix('_ >: ').partition(' <: ')
  return 'transparent_type_alias' if sep and lo==hi else 'abstract_or_parameter_type_member'
 if n in module_names:return 'module_term'
 if s.startswith(('(', '[')):return 'method_member'
 if s.split('[',1)[0] in enum_names:return 'enum_singleton_term'
 return 'term_or_member'
# Root paths preserve exact overloaded/type/module entry identity.
idx={e:i for i,e in enumerate(inv['inventory'])};incoming=collections.defaultdict(list);outgoing=collections.defaultdict(list)
for ed in inv['edges']:
 if ed['to'] in idx:incoming[ed['to']].append(ed)
 if ed['from'] in idx and ed['to'] in idx:outgoing[ed['from']].append(ed)
q=collections.deque();prev={}
for e,(n,s) in parts.items():
 if n in inv['roots']:prev[e]=None;q.append(e)
while q:
 e=q.popleft()
 for ed in outgoing[e]:
  if ed['to'] not in prev:prev[ed['to']]=(e,ed);q.append(ed['to'])
def path(e):
 if e not in prev:return None
 xs=[]
 while prev[e] is not None:
  old,ed=prev[e];xs.append({'from':idx[old],'to':idx[e],'kind':ed['kind']});e=old
 return {'rootEntry':idx[e],'edges':list(reversed(xs))}
# Read exact source archive without executing or rebuilding it. Locations are candidate anchors,
# not a Scala visibility parser or proof that a public door exists.
mods=['core','align','codec','corpus','embed-core','embed-onnx','features','media','pipeline','proposition','recall','story']
archive=subprocess.check_output(['git','archive',inv['sourceSha'],'--']+[m+'/src/main' for m in mods],cwd=ROOT)
files=[]
with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
 for m in tar.getmembers():
  if m.isfile() and m.name.endswith('.scala'):
   text=tar.extractfile(m).read().decode();pm=re.search(r'^package\s+([\w.]+)',text,re.M)
   if pm:files.append((m.name,pm.group(1),text.splitlines()))
modifiers=r'(?:(?:final|sealed|abstract|private(?:\[[^]]+\])?|protected(?:\[[^]]+\])?|case|open)\s+)*'
def locations(n,k):
 if n in opaque:return [{'path':opaque[n][0],'line':opaque[n][1],'kind':'opaque type','status':'source-confirmed declaration'}]
 hits=[]
 for fname,pkg,lines in files:
  if not n.startswith(pkg+'.'):continue
  relative=n[len(pkg)+1:].split('.');root,leaf=relative[0],relative[-1]
  if '$package' in root:continue
  rootre=re.compile(r'^'+modifiers+r'(?:class|trait|enum|object)\s+'+re.escape(root)+r'\b')
  if not any(rootre.search(x) for x in lines):continue
  declre=re.compile(r'^\s*'+modifiers+r'(class|trait|enum|object)\s+'+re.escape(leaf)+r'\b')
  for ln,line in enumerate(lines,1):
   ma=declre.search(line)
   enumcase=not ma and '=>' not in line and re.search(r'^\s*case\s+.*\b'+re.escape(leaf)+r'(?=\s*[,\[(]|\s*$|\s+extends)',line)
   if ma and ((k in ['module_class','module_term'] and ma.group(1)!='object') or (k in ['class_type','trait_type'] and ma.group(1)=='object')):continue
   if ma or enumcase:hits.append({'path':fname,'line':ln,'kind':ma.group(1) if ma else 'enum-case-candidate','header':line.strip(),'status':'candidate source anchor; nested owner/access not independently inferred'})
 return hits
entries=[]
for e in inv['inventory']:
 n,s=parts[e];k=kind(e)
 rule=next((r for r in rules if re.search(r['pattern'],n)),None)
 assert rule is not None,n
 relevant=k in ['class_type','trait_type','opaque_type']
 es={'entryId':idx[e],'identity':e,'qualifiedName':n,'entryKind':k,'coverageRule':rule['id'],'existingJudgmentRow':None if rule['existingJudgment']=='PENDING' else rule['existingJudgment'],'coverageDisposition':('pending-explicit-judgment-or-boundary-decision' if rule['existingJudgment']=='PENDING' else 'existing-family-row-only-not-individual-certification'),'stabilityDisposition':rule['boundary']}
 if e in infos:es['compilerDeclaration']=infos[e][1]
 if relevant:
  es['constructionAcceptance']='not established by this coverage crosswalk'
  es['constructionAccess']='trait not directly instantiated' if k=='trait_type' else 'opaque alias has no independent product constructor' if k=='opaque_type' else 'not inferred from ClassInfo; class may be abstract, an enum base, or constructor-restricted'
  es['sourceAnchors']=locations(n,k)
  es['rootWitness']=path(e)
  es['incomingExamples']=[{'fromEntry':idx.get(x['from']),'kind':x['kind'],'detail':x['detail']} for x in incoming[e] if x['from']!=e][:3]
  es['observedConstructorTypeEdges']=sum(x['kind'].startswith('constructor:') for x in outgoing[e])
 entries.append(es)
summary={'entries':len(entries),'kinds':dict(collections.Counter(x['entryKind'] for x in entries)),'classInfo':len(infos),'nominalTypes':sum(x['entryKind'] in ['class_type','trait_type'] for x in entries),'classInfoByPackage':dict(collections.Counter(parts[e][0].split('.')[1] for e in infos)),'nominalByCoverage':dict(collections.Counter(x['coverageDisposition'] for x in entries if x['entryKind'] in ['class_type','trait_type'])),'nominalByRule':dict(collections.Counter(x['coverageRule'] for x in entries if x['entryKind'] in ['class_type','trait_type'])),'nominalWithoutSourceCandidate':sum(not x.get('sourceAnchors') for x in entries if x['entryKind'] in ['class_type','trait_type']),'nominalWithoutRootWitness':sum(x.get('rootWitness') is None for x in entries if x['entryKind'] in ['class_type','trait_type'])}
result={'schema':'constructor-family-coverage/v1','inventorySourceSha':inv['sourceSha'],'inventorySha256':hashlib.sha256(raw).hexdigest(),'reviewedCurrentSourceSha':subprocess.check_output(['git','rev-parse','874f1b08'],cwd=ROOT,text=True).strip(),'judgmentSha256':hashlib.sha256(JP.read_bytes()).hexdigest(),'policyBasisSha':policy_source,'policySha256':hashlib.sha256(policy).hexdigest(),'purpose':'complete coverage/disposition assignment, not a new constructor court, promotion, or freeze','rules':rules,'existingJudgmentRows':rowids,'additionalJudgmentSections':sections,'summary':summary,'entries':entries,'limits':['ClassInfo text does not retain every access/abstract/enum flag; no public constructibility inference is made.','Every nominal type is covered, conservatively including nonconstructible types.','A family assignment imports no checked invariant claim; broad row matches need their own evidence when promoted.','The separate imported-inspection inventory is not a stable promotion and is not classified by this semantic-population crosswalk.','Source anchors use a lexical candidate matcher and must be verified for nested-owner-sensitive decisions.','Type/method/module identities are never deduplicated by qualified name.']}
(OUT/'constructor-family-crosswalk.json').write_text(json.dumps(result,indent=2)+'\n');(OUT/'summary.json').write_text(json.dumps(summary,indent=2)+'\n');(OUT/'rules.json').write_text(json.dumps(rules,indent=2)+'\n')
with (OUT/'nominal-types.tsv').open('w') as f:
 f.write('entry_id\tkind\tname\trule\tdisposition\n')
 for x in entries:
  if x['entryKind'] in ['class_type','trait_type']:f.write('\t'.join(map(str,[x['entryId'],x['entryKind'],x['qualifiedName'],x['coverageRule'],x['coverageDisposition']]))+'\n')
print(json.dumps(summary,indent=2));print(OUT/'constructor-family-crosswalk.json')
