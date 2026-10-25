"""Exercise witness admission without starting builds or reading study data."""
from pathlib import Path
import ast,json,re,hashlib,sys
root=Path(__file__).parent
path=root/'run-atlas-consumer.py'
tree=ast.parse(path.read_text())
function=next(node for node in tree.body if isinstance(node,ast.FunctionDef) and node.name=='validate_inputs')
namespace={}
exec(compile(ast.Module(body=[function],type_ignores=[]),str(path),'exec'),namespace)
validate=namespace['validate_inputs']
expected='c69a5d754a21fa455c64901334d9509278401091'
good={'status':'source-cli-qualified','source':expected}
validate(good,expected,{})
for value,environment in [({**good,'source':'0'*40},{}),({**good,'status':'running'},{}),(good,{'STORYATLAS4S_INTAGLIO_BUILD':'/tmp/foreign'}),(good,{'STORYATLAS4S_STORYMODEL4S_BUILD':'/tmp/foreign'})]:
    try:validate(value,expected,environment)
    except ValueError:pass
    else:raise AssertionError('Invalid witness input accepted')
path=root/'run-timezone-witness.py'
tree=ast.parse(path.read_text())
pattern=next(node.args[0].value for node in ast.walk(tree) if isinstance(node,ast.Call) and isinstance(node.func,ast.Attribute) and node.func.attr=='findall')
assert re.findall(pattern,'[info] ans: String = America/Toronto\n')==['America/Toronto']
assert re.findall(pattern,'request America/Toronto\n[info] ans: String = Pacific/Honolulu\n')!=['America/Toronto']
for path in root.glob('*.py'):
    ast.parse(path.read_text())
    assert not any(line.rstrip()!=line for line in path.read_text().splitlines())
print(json.dumps({'status':'pass','courts':7,'buildsStarted':0,'studyDataRead':False,'drivers':{path.name:hashlib.sha256(path.read_bytes()).hexdigest() for path in sorted(root.glob('run-*.py'))},'python':sys.version},indent=2))
