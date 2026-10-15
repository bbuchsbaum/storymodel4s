import argparse, json, collections, pathlib
parser = argparse.ArgumentParser(description='Apply the reviewed 14 October exact-member stability decisions.')
parser.add_argument('inventory', type=pathlib.Path)
parser.add_argument('output', type=pathlib.Path)
args = parser.parse_args()
out = args.output
out.mkdir(parents=True, exist_ok=True)
j = json.loads(args.inventory.read_text())
nom=set(j['nominals']); members={m['id']:m for m in j['members']}; links=collections.defaultdict(set); deps=collections.defaultdict(set)
for e in j['edges']:
 if e['kind'] in ['member-exposure','companion-factory']:links[e['from']].add(e['to'])
 else:deps[e['from']].add(e['to'])
name=lambda x:x.split(' :: ')[0]
exact_cuts={'storymodel4s.align.LocalEvidence.compute','storymodel4s.pipeline.StudyPreparation.Source.narrativeModel','storymodel4s.pipeline.StudyPreparation.Prepared.config','storymodel4s.pipeline.StudyPreparation.Prepared.apply','storymodel4s.pipeline.StudyPreparation.Prepared.<init>','storymodel4s.embed.PseudonymizedText.checked'}
exact_cuts.update(['storymodel4s.embed.onnx.OnnxMappingRegistration.Registered.<init>', 'storymodel4s.embed.onnx.OnnxMappingRegistration.Registered.apply', 'storymodel4s.embed.onnx.OnnxMappingRegistration.Registered.replay', 'storymodel4s.embed.onnx.OnnxMappingRegistration.Registered.score', 'storymodel4s.embed.onnx.OnnxMappingRegistration.Registered.pricing', 'storymodel4s.embed.onnx.OnnxMappingRegistration.Registered.config', 'storymodel4s.embed.onnx.OnnxMappingRegistration.Registered.session', 'storymodel4s.embed.onnx.OnnxSentenceEmbedder.RecordedBatch.replay', 'storymodel4s.embed.AttemptReceipt.addResultDecisions', 'storymodel4s.embed.AttemptReceipt.enforceWithholding', 'storymodel4s.align.StrictCandidateSet.generated', 'storymodel4s.align.StrictCandidateSet.canonicalGenerated', 'storymodel4s.align.StrictCandidates.canonical', 'storymodel4s.align.StrictCandidates.generated', 'storymodel4s.align.StrictCandidates.captionSession', 'storymodel4s.align.StrictCandidates.semanticChannel', 'storymodel4s.align.StrictCandidates.surfaceContext', 'storymodel4s.pipeline.CaptionMapping.Registered.<init>', 'storymodel4s.pipeline.CaptionMapping.Registered.apply', 'storymodel4s.pipeline.CaptionMapping.Registered.produced'])
exact_cuts.update({'storymodel4s.pipeline.CaptionMapping.PackageRegistered.' + x for x in ['<init>', 'apply', 'produced']})
exact_cuts.update({'storymodel4s.align.AlignmentRow.' + x for x in ['entropy', 'sourceEntropy', 'localizability', 'normalized']})
exact_cuts.update({'storymodel4s.embed.ValidatedVector.' + x for x in ['norm', 'dot', 'truncated']})
exact_cuts.update({'storymodel4s.align.AlignmentMatrix.' + x for x in ['columnMass', 'distortedColumnMass', 'visitation']})
exact_cuts.update({'storymodel4s.align.TransitionModel.' + x for x in ['pExternalIn', 'pExternalStay']})
exact_cuts.update({'storymodel4s.codec.SurfaceWorkspaceCodecs.' + x for x in ['create', 'Investigation.narrativeModel', 'Investigation.exportBytes']})
exact_cuts.update({'storymodel4s.codec.TemporalInspectionCodecs.' + x for x in ['checked', 'encode', 'saveView', 'Session.compile', 'Projection.query', 'Row.durationWeighted', 'Row.durationWeightedBounds']})
exact_cuts.update({'storymodel4s.codec.RecallTimingCodecs.' + x for x in ['encode', 'toJson']})
exact_cuts.update({'storymodel4s.codec.TemporalProjectionCodecs.' + x for x in ['encodeRequest', 'encode', 'toJson']})
def cut(m):
 n=name(m); origins=members[m]['origins']
 if n in exact_cuts or any(n.startswith(c+'$default$') for c in exact_cuts):return 'experimental construction or preparation extension'
 if 'protected' in origins or 'qualified-private' in origins:return 'restricted implementation member'
 if 'companion-factory' in origins and n.startswith(('storymodel4s.embed.','storymodel4s.media.','storymodel4s.pipeline.StudyPreparation.')):return 'provider or preparation factory'
 return None
rootids={x for x in j['inventory'] if name(x) in j['roots']}
stable=set(rootids); reasons={x:'explicit root' for x in rootids}; queue=list(stable)
while queue:
 x=queue.pop()
 for y in sorted(deps[x]|{m for m in links[x] if not cut(m)}):
  if y not in stable: stable.add(y);queue.append(y);reasons[y]=x
manifest={'schema':'storymodel4s-stable-api-disposition/v1','nominals':{n:('stable' if n in stable else 'experimental') for n in sorted(nom)},'members':{m:('stable' if m in stable else 'experimental') for m in sorted(members)}}
(out/'dispositions-draft.json').write_text(json.dumps(manifest,indent=2)+'\n')
(out/'disposition-reasons.json').write_text(json.dumps(reasons,indent=2)+'\n')
print('stable nominals',len(stable&nom),'stable members',len(stable&set(members)))
print('nominal packages',dict(collections.Counter(name(n).split('.')[1] for n in stable&nom)))
print('experimental narrative reaches:')
for n in sorted(stable&nom):
 if n.startswith(('storymodel4s.story.','storymodel4s.document.','storymodel4s.acquire.')):
  print(name(n),'via',name(reasons[n]))
