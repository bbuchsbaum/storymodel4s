import io,json,pathlib,sys,types,unittest
base=pathlib.Path('/private/tmp/storymodel4s-organization-20261013/tools/recall-study')
sys.path.insert(0,str(base))
source=(base/'organization_challenge.py').read_text()
mutants={
 'fit-commit-guard':('committed(fit_commit, FIT_PATH) != fit_bytes or','False or'),
 'development-split-guard':("any(item['split'] != 'development' for item in items)",'False'),
 'partial-row-verification':("available = [(r, c) for r, c in zip(item['result']['rows'], item['costs']) if r['status'] == 'Available']", "available = [] if any(r['status'] != 'Available' for r in item['result']['rows']) else [(r, c) for r, c in zip(item['result']['rows'], item['costs'])]")}
results=[]
for name,(old,new) in mutants.items():
 assert source.count(old)==1
 module=types.ModuleType('organization_challenge');module.__file__=str(base/'organization_challenge.py')
 sys.modules[module.__name__]=module
 exec(compile(source.replace(old,new),module.__file__,'exec'),module.__dict__)
 tests=types.ModuleType('mutation_courts');tests.__file__=str(base/'tests/test_organization_challenge.py')
 exec(compile(pathlib.Path(tests.__file__).read_text(),tests.__file__,'exec'),tests.__dict__)
 stream=io.StringIO()
 result=unittest.TextTestRunner(stream=stream,verbosity=2).run(unittest.defaultTestLoader.loadTestsFromModule(tests))
 receipt={'mutant':name,'compiled':True,'run':result.testsRun,'failures':[str(t) for t,_ in result.failures],'errors':[str(t) for t,_ in result.errors],'passed':result.testsRun-len(result.failures)-len(result.errors)}
 assert len(result.failures)==1 and not result.errors,stream.getvalue()
 results.append(receipt)
 print(json.dumps(receipt))
pathlib.Path('/Users/bbuchsbaum/code/scala/storymodel4s/.agent-work/organization-challenge-20261014/mutations.json').write_text(json.dumps(results,indent=2)+'\n')
