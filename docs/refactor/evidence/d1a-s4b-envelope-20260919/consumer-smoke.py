"""Pinned consumer edition build and owned-project-browser smoke, after consumer gates."""
from pathlib import Path
import hashlib,json,os,re,subprocess,time

root=Path(os.environ.get('D1A_S4B_ROOT','/Users/bbuchsbaum/code/scala/storymodel4s'))
out=Path(os.environ.get('D1A_S4B_OUTPUT',str(root/'data/study/d1a-s4b-20260919')))
clones=Path(os.environ.get('D1A_S4B_CONSUMER_ROOT','/private/tmp/storymodel4s-film-consumer-20260919-9zqdg50z'))
repos={name:clones/name for name in ['storyatlas4s','storymodel4s','intaglio','grakern']}
consumer=repos['storyatlas4s']
guard=Path.home()/'.local/share/agent-policy/browser-automation-guard.mjs'
browser_path=clones/'playwright-browsers'
env=dict(os.environ,PLAYWRIGHT_BROWSERS_PATH=str(browser_path),STORYATLAS4S_SMOKE_DIAGNOSTICS_DIR=str(out/'smoke-diagnostics'),DEBUG='pw:browser')
for key in ['STORYATLAS4S_SMOKE_MUTATION','STORYATLAS4S_SMOKE_FAIL_AFTER_LAUNCH']:env.pop(key,None)
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def state():
    return {name:dict(revision=subprocess.check_output(['git','rev-parse','HEAD'],cwd=path,text=True).strip(),
        status=subprocess.check_output(['git','status','--porcelain'],cwd=path,text=True).strip()) for name,path in repos.items()}
def run(label,argv):
    path=out/(label+'.log');assert not path.exists(),path
    started=time.time()
    with path.open('w') as stream:
        stream.write('COMMAND '+json.dumps(argv)+'\n');stream.flush()
        result=subprocess.run(argv,cwd=consumer,env=env,stdout=stream,stderr=subprocess.STDOUT)
        stream.write('\nCOMMAND_EXIT='+str(result.returncode)+'\n')
    return dict(command=argv,exitCode=result.returncode,startedEpochSeconds=started,endedEpochSeconds=time.time(),
        logPath=str(path),logSha256=sha(path))
def owned_processes(label,launched_pids=()):
    # The global guard does not recognize Playwright's headless_shell name. Keep
    # unmodified ps rows for this cache and the exact launched PID independently.
    command=['ps','-axo','pid=,ppid=,command=']
    observed=time.time()
    raw=subprocess.check_output(command,text=True)
    rows=[]
    for line in raw.splitlines():
        parts=line.split(None,2)
        if len(parts)==3 and (str(browser_path) in parts[2] or int(parts[0]) in launched_pids):
            rows.append(line)
    path=out/(label+'.log');assert not path.exists(),path
    path.write_text(''.join(line+'\n' for line in rows))
    return dict(command=command,observedEpochSeconds=observed,cachePath=str(browser_path),
        exactLaunchedPids=list(launched_pids),rawScopedRows=rows,logPath=str(path),logSha256=sha(path))

assert not (out/'consumer-smoke.json').exists()
before=state();assert all(not value['status'] for value in before.values())
provider=before['storymodel4s']['revision']
assert 'lazy val storymodel4sRevision = "'+provider+'"' in (consumer/'build.sbt').read_text()
prefix=['sbt','-batch','-Dstoryatlas4s.storymodel4s.build='+str(repos['storymodel4s']),
    '-Dstoryatlas4s.intaglio.build='+str(repos['intaglio']),'-Dstorymodel4s.grakern.build='+str(repos['grakern'])]
receipt=dict(schema='d1a-consumer-smoke/v1',before=before,environmentOverrides={
    'PLAYWRIGHT_BROWSERS_PATH':str(browser_path),
    'STORYATLAS4S_SMOKE_DIAGNOSTICS_DIR':str(out/'smoke-diagnostics'),'DEBUG':'pw:browser'},
    clearedMutationEnvironment=['STORYATLAS4S_SMOKE_MUTATION','STORYATLAS4S_SMOKE_FAIL_AFTER_LAUNCH'],
    scriptSha256=sha(consumer/'app/smoke/smoke.cjs'),lockfileSha256=sha(consumer/'e2e/static/package-lock.json'))
try:
    assert not (consumer/'target/edition').exists(), 'edition output must be absent before this run'
    receipt['freshEditionDirectory']=True
    receipt['editionBuild']=run('consumer-edition-build',prefix+['cli/run edition --out target/edition','app/editionBundle'])
    assert receipt['editionBuild']['exitCode']==0,'edition build failed'
    files={}
    for name in ['index.html','app.js','receipt.json']:
        p=consumer/'target/edition'/name
        assert p.exists() and p.stat().st_ctime >= receipt['editionBuild']['startedEpochSeconds'],p
        files[name]=dict(bytes=p.stat().st_size,sha256=sha(p),mtime=p.stat().st_mtime,ctime=p.stat().st_ctime)
    assert files['index.html']['sha256']==sha(consumer/'app/index.html')
    linked=consumer/'app/target/scala-3.7.4/storyatlas4s-app-fastopt/main.js'
    assert files['app.js']['sha256']==sha(linked)
    receipt['linkedProductionMain']=dict(path=str(linked),sha256=sha(linked))
    receipt['editionFiles']=files
    receipt['browser']=json.loads(subprocess.check_output(['node','-e',
        'const p=require("./e2e/static/node_modules/playwright"); console.log(JSON.stringify({version:require("./e2e/static/node_modules/playwright/package.json").version,executable:p.chromium.executablePath()}))'],cwd=consumer,env=env,text=True))
    assert receipt['browser']['version']=='1.55.1'
    assert Path(receipt['browser']['executable']).is_relative_to(browser_path)
    receipt['browser']['executableSha256']=sha(Path(receipt['browser']['executable']))
    receipt['nodeRuntime']=json.loads(subprocess.check_output(['node','-e',
        'console.log(JSON.stringify({version:process.version,executable:process.execPath}))'],env=env,text=True))
    receipt['nodeRuntime']['executableSha256']=sha(Path(receipt['nodeRuntime']['executable']))
    receipt['guardBefore']=run('browser-guard-before',['node',str(guard),'--audit'])
    assert receipt['guardBefore']['exitCode'] in [0,1], 'browser audit could not inspect processes'
    assert str(browser_path) not in Path(receipt['guardBefore']['logPath']).read_text(), 'owned browser unexpectedly already running'
    receipt['ownedProcessesBefore']=owned_processes('browser-owned-processes-before')
    assert not receipt['ownedProcessesBefore']['rawScopedRows'], 'owned cache already in use'
    try:
        receipt['smoke']=run('consumer-browser-smoke',['node','app/smoke/smoke.cjs','target/edition/index.html'])
    finally:
        receipt['guardAfter']=run('browser-guard-after',['node',str(guard),'--audit'])
        log=(out/'consumer-browser-smoke.log').read_text()
        launched_pids=[int(value) for value in re.findall(r'<launched> pid=(\d+)',log)]
        receipt['ownedProcessesAfter']=owned_processes('browser-owned-processes-after',launched_pids)
    assert receipt['guardAfter']['exitCode'] in [0,1], 'browser audit could not inspect processes'
    assert str(browser_path) not in Path(receipt['guardAfter']['logPath']).read_text(), 'owned browser remains after smoke'
    log=Path(receipt['smoke']['logPath']).read_text()
    launches=re.findall(r'<launching> (.*?) --',log)
    assert len(launches)==len(launched_pids)==1,'expected exactly one actual browser launch'
    executable=Path(launches[0])
    assert executable.is_relative_to(browser_path)
    receipt['browser']['actualLaunchedExecutable']=str(executable)
    receipt['browser']['actualLaunchedExecutableSha256']=sha(executable)
    receipt['browser']['actualLaunchedPid']=launched_pids[0]
    assert not receipt['ownedProcessesAfter']['rawScopedRows'], 'owned browser or child remains after smoke'
    receipt['smoke']['passedChecks']=sum(line.startswith('ok  ') for line in log.splitlines())
    receipt['smoke']['failedChecks']=sum(line.startswith('FAIL') for line in log.splitlines())
    receipt['smoke']['terminalPassed']='\nsmoke passed\n' in log
    assert receipt['smoke']['exitCode']==0 and receipt['smoke']['terminalPassed']
    assert receipt['smoke']['passedChecks']>0 and receipt['smoke']['failedChecks']==0
finally:
    receipt['after']=state()
    (out/'consumer-smoke.json').write_text(json.dumps(receipt,indent=2)+'\n')
assert receipt['after']==before
print(json.dumps(receipt,indent=2))
