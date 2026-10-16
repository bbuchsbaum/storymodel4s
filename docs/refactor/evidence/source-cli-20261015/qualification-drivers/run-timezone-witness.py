from pathlib import Path
import importlib.util,json,os,re,shutil,subprocess,tempfile,time
parent=Path('/Volumes/My Passport for Mac/agent-work/storymodel4s')
producer_root=parent/'reality-check-20261015-s1-candidate-65811d01'
root=parent/'reality-check-20261015-s1-timezones-65811d01'
checkout=producer_root/'checkout'
expected='65811d0137ab49dde114dce5450e3998fa7b96c6'
qualification=json.loads((producer_root/'receipt.json').read_text())
assert qualification['status']=='source-cli-qualified', 'Producer qualification incomplete; do not run concurrent gates'
assert qualification['source']==expected
assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=checkout,text=True).strip()==expected
assert not subprocess.check_output(['git','status','--porcelain'],cwd=checkout)
assert not root.exists(), 'Preserve prior timezone evidence'
root.mkdir()
spec=importlib.util.spec_from_file_location('qualified_witness',checkout/'tools/release/qualify-source-cli.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
module.validate_environment(os.environ)
witness=module.Witness(root)
witness.receipt.update(schema='storymodel4s-cli-timezone-reproducibility/v1',source=expected,producerReceiptSha256=module.sha(producer_root/'receipt.json'),archives={})
ipc=tempfile.TemporaryDirectory(prefix='sm4s-tz-ipc-',dir='/private/tmp')
try:
    artifact=list((checkout/'pipeline/target/cli-distribution').glob('*.zip'))
    assert len(artifact)==1
    original=root/'qualification.zip';shutil.copyfile(artifact[0],original)
    assert module.sha(original)==qualification['artifact']['archiveSha256']
    witness.receipt['archives']['qualification']={'sha256':module.sha(original),'bytes':original.stat().st_size}
    directories={key:Path(value) for key,value in qualification['isolation'].items()}
    java='/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home'
    env=dict(os.environ,JAVA_HOME=java,TMPDIR=str(directories['tmp']),XDG_RUNTIME_DIR=ipc.name,COURSIER_CACHE=str(directories['coursier']),COURSIER_CONFIG_DIR=str(directories['config']),COURSIER_CREDENTIALS='',GIT_TERMINAL_PROMPT='0',ORT_DISABLE_TELEMETRY='1')
    base=['sbt','-batch','-J-Xmx8G','-J-XX:ActiveProcessorCount=4','-J-Djava.io.tmpdir='+str(directories['tmp']),'-Dsbt.boot.directory='+str(directories['boot']),'-Dsbt.global.base='+str(directories['global']),'-Dsbt.global.staging='+str(directories['staging']),'-Dsbt.ivy.home='+str(directories['ivy']),'-Dsbt.repository.config='+str(producer_root/'repositories'),'-Dsbt.override.build.repos=true']
    for label,zone in [('toronto','America/Toronto'),('honolulu','Pacific/Honolulu')]:
        log=witness.run(base+['-J-Duser.timezone='+zone,'eval java.time.ZoneId.systemDefault.getId','pipeline/cliDistribution'],checkout,dict(env,TZ=zone))
        observations=re.findall(r'(?m)^\[info\] (?:ans|res\d+): String = "?([A-Za-z_/]+)"?\s*$',log.read_text())
        assert observations==[zone], 'Actual JVM timezone observation differs or is absent: '+str(observations)
        archives=list((checkout/'pipeline/target/cli-distribution').glob('*.zip'));assert len(archives)==1
        copy=root/(label+'.zip');shutil.copyfile(archives[0],copy)
        witness.receipt['archives'][label]={'timezone':zone,'sha256':module.sha(copy),'bytes':copy.stat().st_size}
        witness.run([os.sys.executable,str(checkout/'tools/test_cli_distribution.py'),'--compare-archives',str(original),str(copy)],root,env)
    assert not subprocess.check_output(['git','status','--porcelain'],cwd=checkout)
    witness.receipt.update(status='qualified-timezone-reproducibility',archiveBytesEqual=True)
except Exception as error:
    witness.receipt.update(status='failed',error=str(error))
finally:
    witness.receipt['finishedUnix']=time.time();witness.save();ipc.cleanup()
print(witness.receipt['status'],root/'receipt.json')
raise SystemExit(0 if witness.receipt['status']=='qualified-timezone-reproducibility' else 1)
