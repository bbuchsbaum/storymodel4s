"""Independent, content-free D1B coordinate/scoring verifier; never rewrites expectations."""
from pathlib import Path
import copy,csv,hashlib,json,math,sys

FROZEN_MANIFEST='42b9ec1558272487192829fc6f381965cff858764cb83aed96d35559a0ceadca'
ROW_DIGEST='59e8118472c91e07506910ab27b4cbf4a30ea9f0ce8703a6f44f66b4531dd8dc'
ANNOTATION='8c205826dcea8c58db24d7a17c71a3f99a9054e9379435e60b1dd0b987c2b296'
REPAIR='b64d7ab2fa6457ebb39e7d43be48daf0fad910faa77c494c40979cd7f5f66146'
def sha(b):return hashlib.sha256(b).hexdigest()
def address(kind,*fields):return kind+':'+sha('\0'.join((kind,)+fields).encode())[:12]
def safe_digest(fields):return sha('\0'.join(sha(str(x).encode()) for x in fields).encode())
def axis_binding(bundle,edition,end,rate):
 fields=['axis',bundle,edition,'EditionPlayback','FilmEdition',f'PlaybackTicks([0,{end}), RationalTimebase(1/{rate}))',f'1/{rate}']
 fingerprint=sha('\0'.join(fields).encode())
 return address('axis',fingerprint),fingerprint
def full_bundle(bundle,edition,fingerprint,streams,authority,mappings):
 return safe_digest(['source-bundle/v1',bundle,'some',edition,'FilmEdition',fingerprint,len(streams)]+
  [digest for _,digest in sorted(streams)]+[len(authority)]+authority+[len(mappings)]+sorted(mappings))
def stream_binding(stream,checksum,axis,end,rate):
 return safe_digest(['stream',stream,checksum,axis,'Picture','ticks','0',end,'1',rate,'some',f'1/{rate}','0'])
def load(path):return json.loads(path.read_bytes())
def need(ok,reason):
 if not ok:raise AssertionError(reason)
def union(intervals):
 out=[]
 for start,end in sorted(intervals):
  if out and start<=out[-1][1]:out[-1]=(out[-1][0],max(out[-1][1],end))
  else:out.append((start,end))
 return out

def expected(repo,annotation):
 frozen_path=repo/'docs/refactor/evidence/sherlock-baseline-20260919/manifest.json'
 need(sha(frozen_path.read_bytes())==FROZEN_MANIFEST,'frozen baseline manifest changed')
 frozen=load(frozen_path)
 repair_path=repo/'docs/data/sherlock/timebase-repair.json'
 need(sha(repair_path.read_bytes())==REPAIR,'repair changed')
 repair=load(repair_path)
 need(sha(annotation.read_bytes())==ANNOTATION,'annotation changed')
 parts=sorted(repair['presentationEditionIdentity']['parts'],key=lambda p:p['presentationOrdinal'])
 rate=math.lcm(*(p['video']['ticksPerSecond'] for p in parts))
 axes={a['part']:a['axis'] for a in frozen['inventory']['axes']}
 offsets={};total=0
 for part in parts:
  offsets[part['partId']]=total
  total+=part['video']['durationTicks']*rate//part['video']['ticksPerSecond']
 part_by_row={}
 for run in repair['annotationToPlaybackCrosswalk']['runs']:
  need(run['mapping']=='identity' and run['formula']=='playbackTicks = rawAnnotationSeconds * ticksPerSecond','unsupported coordinate formula')
  first,last=map(int,run['annotationRows'].split('-'))
  for i in range(first,last+1):
   need(i not in part_by_row,'overlapping run ranges');part_by_row[i]=run['partId']
 rates={p['partId']:p['video']['ticksPerSecond'] for p in parts}
 streams={p['partId']:address('stream',p['sha256']) for p in parts}
 stream_records=sorted((streams[p['partId']],p['sha256']) for p in parts)
 bundle=address('bundle','bundle','sherlock-nn2017-composed','FilmEdition','EditionPlayback',
  *[field for stream,checksum in stream_records for field in (stream,'Picture',checksum)],
  *[streams[p['partId']] for p in parts])
 # Independently reconstruct the declared identity preimages from the pinned media inventory.
 primary,primary_fingerprint=axis_binding(bundle,'sherlock-nn2017-composed',total,rate)
 stream_digests=[];part_bindings=[]
 for part in parts:
  key=part['partId'];stream=streams[key];end=part['video']['durationTicks'];part_rate=rates[key]
  edition='sherlock-nn2017-'+key
  part_bundle=address('bundle','bundle',edition,'FilmEdition','EditionPlayback',stream,'Picture',part['sha256'],stream)
  axis,fingerprint=axis_binding(part_bundle,edition,end,part_rate)
  need(axis==axes[key],'independent native identity differs from frozen axis')
  stream_digest=stream_binding(stream,part['sha256'],axis,end,part_rate)
  stream_digests.append((stream,stream_digest))
  part_bindings.append(full_bundle(part_bundle,edition,fingerprint,[(stream,stream_digest)],[stream],[]))
 receipt=safe_digest(['source-receipt-binding/v1','sherlock/presentation-composition/v1',
  'manifest ordinal concatenation; exact integral target ticks; native loci retained','4',REPAIR,ANNOTATION]+part_bindings)
 mappings=[]
 for part in parts:
  key=part['partId'];start=offsets[key];end=start+part['video']['durationTicks']*rate//rates[key]
  segment=safe_digest(['segment','sherlock-part-'+str(part['presentationOrdinal']),axes[key],'0',
   part['video']['durationTicks'],primary,start,end])
  mappings.append(safe_digest(['checked-mapping/v1','track-composition',axes[key],primary,'1',segment,receipt]))
 binding=full_bundle(bundle,'sherlock-nn2017-composed',primary_fingerprint,stream_digests,
  [streams[p['partId']] for p in parts],mappings)
 rows={};scenes=[]
 with annotation.open(encoding='utf-8',newline='') as f:
  table=list(csv.reader(f,delimiter='\t',quoting=csv.QUOTE_NONE))[1:]
 for cells in table:
  if not cells:continue
  number,start,end=map(int,cells[:3]);part=part_by_row[number]
  if cells[5].strip():scenes.append([])
  need(bool(scenes),'row before first scene');scenes[-1].append(number)
  rows[number]=dict(row=number,part=part,startTick=str(start*rates[part]),endTick=str(end*rates[part]),
   kind='instant' if start==end else 'extent',axis=axes[part],primaryStart=offsets[part]+start*rate,
   primaryEnd=offsets[part]+end*rate)
 need(list(rows)==list(range(1,1001)) and len(scenes)==50,'wrong independent row/scene inventory')
 projection=('row\tpart\tstartTick\tendTick\n'+''.join(f"{i}\t{r['part']}\t{r['startTick']}\t{r['endTick']}\n" for i,r in rows.items())).encode()
 need(sha(projection)==ROW_DIGEST,'independent all-row oracle differs from fixed digest')
 need([{k:r[k] for k in ['row','part','startTick','endTick','kind']} for r in rows.values()]==frozen['inventory']['rowLoci'],'independent rows differ from frozen row oracle')
 return dict(rows=rows,scenes=scenes,rate=rate,parts=parts,axes=axes,total=total,streams=streams,bundle=bundle,binding=binding,primary=primary,
  targets={t['id']:t for t in frozen['inventory']['targets']},projection=projection)

def anchors(unit,binding,bundle):
 support=unit['physicalSupport'];result=[]
 need(unit['supportBundleIdentity']==binding,'support full bundle binding')
 need(support['schema']==('evidence-support/v2' if any(a['type']=='MediaPoint' for a in support['anchors']) else 'evidence-support/v1'),'component schema')
 if support['schema']=='evidence-support/v2':need(support['bundleIdentity']==binding,'point full binding')
 for a in support['anchors']:
  need(a['bundle']==bundle,'foreign anchor bundle')
  if a['type']=='MediaPoint':result.append((a['axis'],'instant',int(a['tick']),int(a['tick']),a['stream']))
  else:
   need(a['type']=='MediaTime','unexpected physical anchor family')
   for interval in a['intervals']:
    need(interval['axis']==a['axis'],'interval axis mismatch')
    result.append((a['axis'],'extent',int(interval['startTick']),int(interval['endExclusiveTick']),a['stream']))
 need(len(result)>0,'empty physical support')
 return result

def verify(actual,e):
 need(actual['schema']=='d1b/sherlock-support-inventory/v1','capture schema')
 need(actual['annotationSha256']==ANNOTATION and actual['repairRecordSha256']==REPAIR,'capture input binding')
 need([r['row'] for r in actual['rows']]==list(range(1,1001)),'capture row population')
 need([s['scene'] for s in actual['scenes']]==list(range(1,51)),'capture scene population')
 need(actual['resultInventorySize']==1050,'complete retained result inventory')
 need(int(actual['primaryTicksPerSecond'])==e['rate'],'composed timebase')
 for axis,part in zip(actual['nativeAxes'],e['parts'],strict=True):
  need(axis==dict(part=part['partId'],ordinal=part['presentationOrdinal'],axis=e['axes'][part['partId']],
   stream=e['streams'][part['partId']],ticksPerSecond=str(part['video']['ticksPerSecond']),durationTicks=str(part['video']['durationTicks'])),'native axis binding')
 primary=actual['primaryAxis'];binding=actual['bundleIdentity']
 need(actual['bundleId']==e['bundle'],'composed legacy bundle identity')
 need(primary==e['primary'] and binding==e['binding'],'composed primary/full bundle identity')
 expected_anchors={};refs=set();legacy_length=max(t['supportEndUtf16'] for t in e['targets'].values())
 need(actual['scoringLength']==legacy_length,'legacy scoring denominator')
 def feature(unit):
  ref=unit['ref'];need(ref not in refs,'duplicate node ref');refs.add(ref)
  old=e['targets'][ref];a=old['supportStartUtf16'];b=old['supportEndUtf16']
  need(unit['scoringFeature']==dict(kind='LegacyAnnotationText',refs=[dict(unit=None,start=a,endExclusive=b)]),'legacy scoring spans')
  need(unit['relativeSpan']==[a/legacy_length,b/legacy_length],'relative scoring span')
  need(unit['measuredPosition']==(a/legacy_length+b/legacy_length)/2,'measured scoring position')
  need(unit['retainedInResult'] is True,'retained support join')
  need(len(unit['physicalIdentity'])==64,'physical identity missing')
 def projection(unit,wanted):
  intervals=union([(a,b) for axis,kind,a,b,stream in wanted if axis==primary and kind=='extent'])
  points=sorted({a for axis,kind,a,b,stream in wanted if axis==primary and kind=='instant'})
  need(unit['primaryIntervals']==[[str(a),str(b)] for a,b in intervals],'primary union changed')
  need(unit['primaryPoints']==list(map(str,points)),'primary points changed')
 for row in actual['rows']:
  number=row['row'];r=e['rows'][number]
  need(row['ref']==f'sit:sherlock:row:{number:04d}' and row['unit']==f'sherlock:row:{number:04d}','row ordinal/ref/unit association')
  wanted=[(r['axis'],r['kind'],int(r['startTick']),int(r['endTick']),e['streams'][r['part']]),
   (primary,r['kind'],r['primaryStart'],r['primaryEnd'],e['streams'][r['part']])]
  need(anchors(row,binding,e['bundle'])==wanted,'native/primary row coordinates changed')
  expected_anchors[number]=wanted;projection(row,wanted);feature(row)
  need(row['nominated']==(number==1),'retention control nomination changed')
 for scene,members in zip(actual['scenes'],e['scenes'],strict=True):
  ordinal=scene['scene']
  need(scene['ref']==f'seg:sherlock:scene:{ordinal:02d}' and scene['unit']==f'sherlock:scene:{ordinal:03d}','scene ordinal/ref/unit association')
  wanted=[anchor for number in members for anchor in expected_anchors[number]]
  need(anchors(scene,binding,e['bundle'])==wanted,'scene members/point inventory changed')
  projection(scene,wanted);feature(scene);need(scene['nominated'] is False,'scene unexpectedly nominated')
 need(refs==set(e['targets']),'node inventory differs from frozen inventory')
 row13=actual['rows'][12]
 need(e['rows'][13]['kind']=='instant' and e['rows'][13]['startTick']=='112500','row13 fixed native point')
 need(row13['nominated'] is False and row13['retainedInResult'] is True,'unused row13 was lost')
 return dict(rows=1000,scenes=50,resultInventory=1050,rowLociSha256=ROW_DIGEST,
  row13NativeTick='112500',row13UnusedPointRetained=True,exactPhysicalAndScoringComparison=True)

def main():
 repo,annotation,capture,output=(Path(x).resolve() for x in sys.argv[1:])
 need(not output.exists(),'refuse overwrite')
 e=expected(repo,annotation);actual=load(capture);summary=verify(actual,e)
 falsifiers=[]
 def swap_associations(x):
  for key in ['ref','unit','scoringFeature','relativeSpan','measuredPosition']:
   x['rows'][1][key],x['rows'][2][key]=x['rows'][2][key],x['rows'][1][key]
 for name,change in [
  ('row13-tick',lambda x:x['rows'][12]['physicalSupport']['anchors'][0].update(tick='112501')),
  ('row13-omitted',lambda x:x['rows'].pop(12)),
  ('foreign-stream',lambda x:x['rows'][12]['physicalSupport']['anchors'][0].update(stream='stream:foreign')),
  ('foreign-bundle',lambda x:x['rows'][12]['physicalSupport']['anchors'][0].update(bundle='bundle:foreign')),
  ('changed-full-binding',lambda x:x['rows'][12].update(supportBundleIdentity='0'*64)),
  ('changed-composed-binding',lambda x:x.update(bundleIdentity='0'*64)),
  ('row-association-swap',swap_associations),
  ('scene-point-omitted',lambda x:next(s for s in x['scenes'] if s['primaryPoints'])['primaryPoints'].clear()),
  ('scoring-coordinate-changed',lambda x:x['rows'][0]['scoringFeature']['refs'][0].update(endExclusive=0))]:
  mutated=copy.deepcopy(actual);change(mutated);refused=False
  try:verify(mutated,e)
  except (AssertionError,KeyError):refused=True
  need(refused,'comparator falsifier survived: '+name);falsifiers.append(dict(name=name,rejected=True))
 output.write_text(json.dumps(dict(schema='d1b/support-parity/v1',captureSha256=sha(capture.read_bytes()),
  frozenManifestSha256=FROZEN_MANIFEST,annotationSha256=ANNOTATION,repairSha256=REPAIR,
  summary=summary,comparatorFalsifiers=falsifiers,
  limitation='Independent exact coordinate/legacy scoring comparison and synthetic retention control; not participant replay or scientific validation.'),indent=2)+'\n')
 print(json.dumps(summary))
if __name__=='__main__':main()
