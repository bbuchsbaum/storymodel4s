import argparse, json, re, subprocess, sys
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--clone',type=Path,required=True);p.add_argument('--sha',required=True);p.add_argument('--log',type=Path,required=True);p.add_argument('--scope',type=Path,required=True);a=p.parse_args()
assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=a.clone,text=True).strip()==a.sha
assert not subprocess.check_output(['git','status','--porcelain'],cwd=a.clone,text=True).strip()
r=subprocess.run(['bash','tools/reference-scope.sh','c4010ed8f89d1e4e0ad94c58115fc4e8ebfa2650',a.sha],cwd=a.clone,text=True,capture_output=True)
a.scope.write_text(r.stdout+r.stderr);assert r.returncode==0
jvm=re.search(r'sbt -batch "([^"\n]+)"',r.stdout).group(1)
modules=r.stdout.split('modules that reference them (the gate must cover all of these):\n',1)[1].split('\ngate command',1)[0].split()
build=(a.clone/'build.sbt').read_text()
cross={module:(alias,platforms) for alias,platforms,module in re.findall(r'lazy val (\w+) = crossProject\(([^)]*)\).*?\.in\(file\("([^"]+)"\)\)',build,re.S)}
platform_tasks=[]
for tag in ['JS','Native']:
 for module in modules:
  if module in cross:
   alias,platforms=cross[module]
   if ('JSPlatform' if tag=='JS' else 'NativePlatform') in platforms:platform_tasks.append(alias+tag+'/test')
command=['sbt','-batch','-Dstorymodel4s.grakern.build=/Users/bbuchsbaum/code/scala/grakern','set ThisBuild / tlFatalWarnings := true','embedBench/Test/clean',jvm,*platform_tasks,'scalafmtCheckAll','scalafmtSbtCheck']
plan={'candidate_sha':a.sha,'modules':modules,'jvm_tasks':jvm.split('; '),'portable_tasks':platform_tasks,'command':command}
a.scope.with_suffix('.json').write_text(json.dumps(plan,indent=2)+'\n')
print('Serial scoped gate:',len(modules),'modules,',len(plan['jvm_tasks'])+len(platform_tasks),'module/platform test tasks',flush=True)
status=subprocess.run([sys.executable,'/Users/bbuchsbaum/.agents/skills/lean-logs/scripts/run_logged.py','--log',str(a.log),'--cwd',str(a.clone),'--timeout','7200','--',*command])
sys.exit(status.returncode)
