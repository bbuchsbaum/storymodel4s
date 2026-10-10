"""Two independent-reader guards, each killed with a passing unchanged-publication control."""
import hashlib,json,subprocess,sys,tempfile,unittest,importlib.util
from pathlib import Path
root=Path('/Users/bbuchsbaum/code/scala/storymodel4s');sha='efba130cfc867c566ea83ca19ebded0d4914c240'
work=root/'.agent-work/workflow-five/reader-mutations';work.mkdir(parents=True,exist_ok=True)
log=root/'.agent-work/workflow-five/exchange-dev-03.log'
reader=subprocess.check_output(['git','show',sha+':examples/mapping/read_mapping.py'],cwd=root,text=True)
tests=subprocess.check_output(['git','show',sha+':examples/mapping/test_paired_mapping.py'],cwd=root,text=True)
spec=[('unit-status','summary[name] == decision["processing_status"]["status"].capitalize()','True','test_completed_unit_summary_must_match_member_tables'),('failed-denominator','outcome["units"] == expected_units','expected_units is not None','test_prepared_but_failed_attempt_preserves_every_unit')]
results=[]
for name,old,new,test in spec:
 assert reader.count(old)==1
 with tempfile.TemporaryDirectory(prefix='paired-reader-mutant-') as d:
  p=Path(d);mutant=reader.replace(old,new);(p/'read_mapping.py').write_text(mutant);(p/'test_paired_mapping.py').write_text(tests)
  script='''import json,sys,unittest
from pathlib import Path
from test_paired_mapping import PairedReaderTests
for line in Path(sys.argv[1]).read_text().splitlines():
 if line.startswith("PAIRED_EXCHANGE_FIXTURE\\t"):
  _,name,content=line.split("\\t",2);PairedReaderTests.fixtures[name]=json.loads(content)
suite=unittest.TestSuite([PairedReaderTests(sys.argv[2]),PairedReaderTests("test_publication_accounts_for_two_recalls_and_one_failed_attempt")])
result=unittest.TextTestRunner(verbosity=2).run(suite)
print(json.dumps({"ran":result.testsRun,"failed":len(result.failures),"errors":len(result.errors),"named":[t._testMethodName for t,_ in result.failures]}))
sys.exit(0 if result.wasSuccessful() else 1)
'''
  (p/'run.py').write_text(script)
  r=subprocess.run([sys.executable,str(p/'run.py'),str(log),test],capture_output=True,text=True,cwd=p)
  (work/(name+'.log')).write_text(r.stdout+r.stderr)
  totals=json.loads(r.stdout.splitlines()[-1]);qualified=r.returncode==1 and totals=={'ran':2,'failed':1,'errors':0,'named':[test]}
  results.append(dict(name=name,candidate_sha=sha,qualified=qualified,exit=r.returncode,totals=totals,old=old,new=new,mutant_sha256=hashlib.sha256(mutant.encode()).hexdigest(),fixture_log_sha256=hashlib.sha256(log.read_bytes()).hexdigest()))
  assert qualified,(name,r.stdout,r.stderr)
(work/'results.json').write_text(json.dumps(results,indent=2)+'\n')
print('Two reader guard mutations killed; each has one named failure and one passing positive control')
