import json,pathlib,subprocess
repo=pathlib.Path('/private/tmp/storymodel4s-organization-20261013')
root=pathlib.Path('/Users/bbuchsbaum/code/scala/storymodel4s/.agent-work/organization-challenge-20261014')
p=repo/'pipeline/src/test/scala/storymodel4s/pipeline/OrganizationChallengeWitness.scala'
original=p.read_bytes();text=original.decode();assert text.count('if candidates.size == 1 then')==1
try:
 p.write_text(text.replace('if candidates.size == 1 then','if candidates.nonEmpty then'))
 command=['python3','/Users/bbuchsbaum/.agents/skills/lean-logs/scripts/run_logged.py','--log',str(root/'scala-mutation.log'),'--cwd',str(repo),'--timeout','900','--head-bytes','100','--tail-bytes','2200','--','sbt','-batch','set ThisBuild / tlFatalWarnings := true','pipeline/testOnly *OrganizationChallengeWitnessSuite']
 result=subprocess.run(command,cwd=repo)
finally:p.write_bytes(original)
log=(root/'scala-mutation.log').read_text()
assert result.returncode==1 and 'Failed: Total 4, Failed 1, Errors 0, Passed 3' in log
assert 'exact names survive, a unique typo repairs, and ambiguous candidates remain unchanged' in log
assert 'done compiling' in log
(root/'scala-mutation-receipt.json').write_text(json.dumps({'mutant':'choose first candidate when multiple names match','compiled':True,'exit':result.returncode,'passed':3,'failed':1,'errors':0,'named_test':'exact names survive, a unique typo repairs, and ambiguous candidates remain unchanged','restored_exact_bytes':p.read_bytes()==original},indent=2)+'\n')
