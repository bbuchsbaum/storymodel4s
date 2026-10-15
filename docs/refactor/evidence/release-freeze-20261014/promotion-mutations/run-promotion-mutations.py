from pathlib import Path
import hashlib,json,re,subprocess,sys
root=Path(__file__).resolve().parents[2]
# Script lives under the ignored evidence root; paths are explicit to avoid mutating the primary.
repo=Path('/Users/bbuchsbaum/code/scala/storymodel4s')
clone=repo/'.agent-work/release-workflow-20261014/producer-check'
out=repo/'.agent-work/release-freeze-20261014/promotion-mutations'
out.mkdir(exist_ok=False)
records=[]

def case(label,module,source,tests,replacements,required,allowed=None):
 return dict(label=label,module=module,source=source,tests=tests,replacements=replacements,required=required,allowed=allowed or required)
num='align/src/test/scala/storymodel4s/probes/PromotedNumericBoundarySuite.scala'
media='media/src/test/scala/storymodel4s/probe/PromotedMediaBoundarySuite.scala'
receipt='embed-core/src/test/scala/storymodel4s/embedprobe/ReceiptDigestBoundarySuite.scala'
identity='embed-core/src/test/scala/storymodel4s/embed/SemanticViewIdentitySuite.scala'
cases=[
case('layer-finite','alignJVM','align/src/main/scala/storymodel4s/align/hsmm.scala',[num],[('if model.theta.values.exists(v => v.isNaN || v.isInfinite) then','if false then')],['standalone layer use refuses every nonfinite transition weight']),
case('row-total','alignJVM','align/src/main/scala/storymodel4s/align/matrix.scala',[num],[('&& {\n      val sum = total\n      sum >= 0.0 && !sum.isInfinite\n    }','&& true')],['finite masses whose aggregate overflows are refused']),
case('custom-identity','embedCoreJVM','embed-core/src/main/scala/storymodel4s/embed/identity.scala',[identity],[('s"custom/v2:${ns.length}:$ns:${n.length}:$n"','s"custom:$ns:$n"')],['the old namespace/name collision has distinct versioned renderings and geometry ids','empty, Unicode and separator-bearing component pairs retain separate identities']),
case('receipt-consistency','embedCoreJVM','embed-core/src/main/scala/storymodel4s/embed/receipt.scala',['embed-core/src/test/scala/storymodel4s/embed/ReceiptDigestSuite.scala'],[('i.digest.kind != DigestKind.Plain || ReceiptDigest.plainAdmissible.contains(i.sensitivity)','(i.digest.kind == DigestKind.Plain) == ReceiptDigest.plainAdmissible.contains(i.sensitivity)')],['keyed public items satisfy receipt consistency while all-public attempt policy stays strict','every admitted item/output sensitivity combination yields a consistent embedding receipt']),
case('receipt-construction','embedCoreJVM','embed-core/src/main/scala/storymodel4s/embed/receipt.scala',[receipt],[(f'final class {name} private (',f'final class {name} (') for name in ['Plain','Keyed','Withheld']],[f'{name} refuses external new with a same-shape positive control' for name in ['Plain','Keyed','Withheld']],[f'{name} refuses external {door} with a same-shape positive control' for name in ['Plain','Keyed','Withheld'] for door in ['new','apply']]),
case('media-caption-construction','media','media/src/main/scala/storymodel4s/media/caption.scala',[media],[(f'final class {name} private (',f'final class {name} (') for name in ['ModelPin','CaptionRecipe']],[f'{name} direct constructor is inaccessible' for name in ['ModelPin','CaptionRecipe']],[f'{name} {door}' for name in ['ModelPin','CaptionRecipe'] for door in ['direct constructor is inaccessible','apply is absent']]),
case('geometry-construction','media','media/src/main/scala/storymodel4s/media/frames.scala',[media],[('final class PictureGeometry private (','final class PictureGeometry (')],['PictureGeometry direct constructor is inaccessible'],['PictureGeometry direct constructor is inaccessible','PictureGeometry apply is absent']),
case('recipe-prompt','media','media/src/main/scala/storymodel4s/media/caption.scala',[media],[('prompt.trim.nonEmpty && minPixels > 0','minPixels > 0')],['CaptionRecipe rejects a blank prompt']),
case('recipe-minimum','media','media/src/main/scala/storymodel4s/media/caption.scala',[media],[(' && minPixels > 0 &&',' &&')],['CaptionRecipe rejects non-positive minimum pixels']),
case('recipe-order','media','media/src/main/scala/storymodel4s/media/caption.scala',[media],[(' && maxPixels >= minPixels &&',' &&')],['CaptionRecipe rejects maximum pixels below the minimum']),
case('recipe-token-limit','media','media/src/main/scala/storymodel4s/media/caption.scala',[media],[(' && maxNewTokens > 0 then',' then')],['CaptionRecipe rejects non-positive token limit']),
case('geometry-overflow','media','media/src/main/scala/storymodel4s/media/frames.scala',[media],[('bytes > BigInt(Long.MaxValue)','bytes > BigInt(Long.MaxValue) * 4')],['PictureGeometry rejects a frame byte count above Long maximum']),
case('pts-overflow','media','media/src/main/scala/storymodel4s/media/manifest.scala',[media],[('BigInt(nominalTicksPerFrame) > BigInt(Long.MaxValue)','BigInt(nominalTicksPerFrame) > BigInt(Long.MaxValue) * 2')],['DeclaredFrames rejects an unrepresentable last generated PTS'])
]

def save():
 (out/'receipt.json').write_text(json.dumps(records,indent=2)+'\n')

for c in cases:
 p=clone/c['source'];original=p.read_text();changed=original
 for before,after in c['replacements']:
  assert changed.count(before)==1,(c['label'],before,changed.count(before))
  changed=changed.replace(before,after)
 record={k:v for k,v in c.items() if k!='replacements'}
 record['sourceSha256']=hashlib.sha256(original.encode()).hexdigest()
 record['mutation']=c['replacements'];records.append(record);save()
 try:
  p.write_text(changed)
  sources=', '.join(f'file({json.dumps(str(clone/test))})' for test in c['tests'])
  commands=['sbt','-batch',f'set Seq(ThisBuild / tlFatalWarnings := true, LocalProject("{c["module"]}") / Test / unmanagedSources := Seq({sources}))',f'{c["module"]}/Test/clean',f'{c["module"]}/test']
  record['command']=commands
  log=out/(c['label']+'.log')
  with log.open('w') as stream:
   try:
    r=subprocess.run(commands,cwd=clone,stdout=stream,stderr=subprocess.STDOUT,timeout=900)
    record['exit']=r.returncode
   except Exception as error:
    record['exit']=None;record['error']=str(error)
  text=log.read_text();record['logSha256']=hashlib.sha256(log.read_bytes()).hexdigest()
  failures=re.findall(r'^==> X [A-Za-z0-9_.]+Suite\.(.*?) \d+\.\d+s ',text,re.M)
  record['failures']=failures
  totals=re.findall(r'\[(?:info|error)\] (?:Passed|Failed): Total (\d+), Failed (\d+), Errors (\d+), Passed (\d+)(?:, Skipped (\d+))?',text)
  record['totals']=totals
  assert isinstance(record['exit'],int) and record['exit']!=0,record
  assert 'Compilation failed' not in text and 'Type error in expression' not in text,record
  assert totals and all(int(t[2])==0 for t in totals),record
  assert set(c['required'])<=set(failures)<=set(c['allowed']),record
  assert int(totals[-1][1])==len(failures),record
  record['status']='killed-by-named-assertions';save();print(c['label'],'killed',len(failures),flush=True)
 finally:
  p.write_text(original);save()
print('all mutations killed; sources restored',flush=True)
