from pathlib import Path
import importlib.util,json,os,subprocess,tempfile,time
def validate_inputs(qualification, expected, environment):
    if qualification.get('status') != 'source-cli-qualified' or qualification.get('source') != expected:
        raise ValueError('Producer qualification is incomplete or bound to another revision')
    present=[key for key in ('STORYATLAS4S_STORYMODEL4S_BUILD','STORYATLAS4S_INTAGLIO_BUILD') if environment.get(key)]
    if present:
        raise ValueError('Ambient Atlas source overrides: '+', '.join(present))

root=Path('/Volumes/My Passport for Mac/agent-work/storymodel4s/reality-check-20261015-atlas-consumer-286b5234')
producer_root=root.parent/'reality-check-20261015-s1-candidate-c69a5d75'
producer=producer_root/'checkout';checkout=root/'checkout'
expected_producer='c69a5d754a21fa455c64901334d9509278401091'
expected_consumer='50a160168daa03c51fcab60a4eaaed66674da83a'
qualification=json.loads((producer_root/'receipt.json').read_text())
validate_inputs(qualification,expected_producer,os.environ)
assert not (root/'receipt.json').exists(), 'Preserve existing result'
for path,expected in [(producer,expected_producer),(checkout,expected_consumer)]:
    assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=path,text=True).strip()==expected
    assert not subprocess.check_output(['git','status','--porcelain'],cwd=path)
spec=importlib.util.spec_from_file_location('qualified_witness',producer/'tools/release/qualify-source-cli.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
module.validate_environment(os.environ)
witness=module.Witness(root);witness.receipt.update(schema='storyatlas4s-producer-compatibility/v1',producer=expected_producer,consumer=expected_consumer,producerReceiptSha256=module.sha(producer_root/'receipt.json'),heapGiB=6,producerRoute='explicit isolated source override; consumer default pin remains eb48a5622bf02d08c175d2956811fd35d4aaeb92',intaglioPin='2fa5c682f4a95b8e73daad78dd9302d7aff46e11',uiQualification='not performed; unchanged consumer compile/tests/bundle only')
ipc=tempfile.TemporaryDirectory(prefix='sm4s-atlas-ipc-',dir='/private/tmp')
try:
    directories={name:root/name for name in ['boot','global','staging','ivy','coursier','config','tmp']}
    for directory in directories.values():directory.mkdir()
    repositories=root/'repositories';repositories.write_text('[repositories]\n  maven-central: https://repo.maven.apache.org/maven2\n')
    java='/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home'
    env=dict(os.environ,JAVA_HOME=java,TMPDIR=str(directories['tmp']),XDG_RUNTIME_DIR=ipc.name,COURSIER_CACHE=str(directories['coursier']),COURSIER_CONFIG_DIR=str(directories['config']),COURSIER_CREDENTIALS='',GIT_TERMINAL_PROMPT='0',ORT_DISABLE_TELEMETRY='1')
    witness.receipt['isolation']={key:str(value) for key,value in directories.items()};witness.save()
    witness.run([java+'/bin/java','-version'],root,env)
    base=['sbt','-batch','-J-Xmx6G','-J-XX:ActiveProcessorCount=4','-J-Djava.io.tmpdir='+str(directories['tmp']),'-Dsbt.boot.directory='+str(directories['boot']),'-Dsbt.global.base='+str(directories['global']),'-Dsbt.global.staging='+str(directories['staging']),'-Dsbt.ivy.home='+str(directories['ivy']),'-Dsbt.repository.config='+str(repositories),'-Dsbt.override.build.repos=true','-Dstoryatlas4s.storymodel4s.build='+str(producer)]
    log=witness.run(base+['set ThisBuild / tlFatalWarnings := true','compileAll','testAll','app/fastLinkJS','scalafmtCheckAll','scalafmtSbtCheck'],checkout,env)
    witness.receipt['tests']=module.test_totals(log.read_text(errors='replace'))
    fetched=[];declarations={'storymodel4s':(producer/'build.sbt').read_text()}
    for gitdir in directories['staging'].rglob('.git'):
        tree=gitdir.parent
        url=subprocess.check_output(['git','config','--get','remote.origin.url'],cwd=tree,text=True).strip()
        row={'url':url,'revision':subprocess.check_output(['git','rev-parse','HEAD'],cwd=tree,text=True).strip(),'clean':not subprocess.check_output(['git','status','--porcelain'],cwd=tree)}
        fetched.append(row)
        if url=='https://github.com/canardlapin/grakern.git':declarations['grakern']=(tree/'build.sbt').read_text()
    pins=module.load_pins(producer/'tools/release/source-pins.json')
    module.validate_fetches(pins,[row for row in fetched if row['url'] in {pin['url'] for pin in pins}],declarations)
    intaglio=[row for row in fetched if row['url']=='https://github.com/canardlapin/intaglio.git']
    assert len(intaglio)==1 and intaglio[0]['clean'] and intaglio[0]['revision']=='2fa5c682f4a95b8e73daad78dd9302d7aff46e11', 'Fetched Intaglio differs from declared pin'
    witness.receipt['fetchedSources']=fetched
    assert not subprocess.check_output(['git','status','--porcelain'],cwd=checkout)
    assert not subprocess.check_output(['git','status','--porcelain'],cwd=producer)
    witness.receipt['status']='qualified-compatibility'
except Exception as error:
    witness.receipt.update(status='failed',error=str(error))
finally:
    witness.receipt['finishedUnix']=time.time();witness.save();ipc.cleanup()
print(witness.receipt['status'],root/'receipt.json')
raise SystemExit(0 if witness.receipt['status']=='qualified-compatibility' else 1)
