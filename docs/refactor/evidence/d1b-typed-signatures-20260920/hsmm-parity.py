"""Exact per-backend preservation against immutable pre-migration bytes; never updates expectations."""
from pathlib import Path
import hashlib,importlib.util,json,os,platform,re,subprocess,time,xml.etree.ElementTree as ET
spec=importlib.util.spec_from_file_location('qualification',Path(__file__).with_name('qualify.py'))
q=importlib.util.module_from_spec(spec);spec.loader.exec_module(q)
q.out=q.out/'hsmm-parity';q.out.mkdir(parents=True,exist_ok=True)
baseline=q.repo/'docs/refactor/evidence/wog-hsmm-baseline-20260919'
def sha(b):return hashlib.sha256(b).hexdigest()
# Pin from landed evidence commit 5c3ea026, not from mutable current expectations.
frozen_manifest_sha='1b6d0023340bd76d24f6b4bdc24935b587a9b9feffb564342cd9e4c04f816153'
assert sha((baseline/'manifest.json').read_bytes())==frozen_manifest_sha
expected_manifest=json.loads((baseline/'manifest.json').read_text())
for name,entry in expected_manifest['platforms'].items():
    raw=(baseline/(name.lower()+'.json')).read_bytes()
    assert sha(raw)==entry['sha256'] and len(raw)==entry['bytes'],name
assert sha((baseline/'WogHsmmBaselineCaptureSuite.scala').read_bytes())==expected_manifest['instrumentationSha256']

def probe(argv):
    r=subprocess.run(argv,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
    return dict(command=argv,exitCode=r.returncode,output=r.stdout.strip())
def require_equal(actual,expected):
    if actual!=expected:raise AssertionError('inferred HSMM bytes differ from the frozen backend baseline')
manifest=dict(schema='d1b-hsmm-preservation/v1',candidateRevision=q.revision,
    baselineProductionRevision='75c8df52adb2ffc54a5cd3586528deddabf4a531',
    baselineCaptureRevision='f3c6edb41621c43de599ef76f66790ae52b5af1e',
    baselineManifestSha256=sha((baseline/'manifest.json').read_bytes()),
    platform=dict(system=platform.system(),release=platform.release(),machine=platform.machine(),
                  macOS=probe(['sw_vers','-productVersion']),node=probe(['node','--version']),clang=probe(['clang','--version'])),
    qualification='Host-bound exact per-backend preservation. Existing JVM resource checksum remains a separate test. Native expected bytes are not a universal cross-OS guarantee; no tolerance or numerical policy changes.',backends={})
harness='fixtures/src/test/scala/storymodel4s/fixtures/wog/WogHsmmBaselineCaptureSuite.scala'
assert (q.repo/harness).read_bytes()==(baseline/'WogHsmmBaselineCaptureSuite.scala').read_bytes()
manifest['unchangedCaptureHarnessSha256']=sha((q.repo/harness).read_bytes())
for backend in ['JVM','JS','Native']:
    suites=['storymodel4s.fixtures.wog.WogHsmmBaselineCaptureSuite','storymodel4s.fixtures.wog.WarOfTheGhostsCodecGoldenSuite']
    if backend=='JVM':suites.append('storymodel4s.fixtures.wog.WarOfTheGhostsCodecGoldenResourceSuite')
    reportdir=q.repo/'fixtures'/('.'+backend.lower())/'target/test-reports'
    for suite in suites:(reportdir/('TEST-'+suite+'.xml')).unlink(missing_ok=True)
    receipt=q.run(backend.lower(),q.prefix+['fixtures'+backend+'/testOnly '+' '.join(suites)])
    assert receipt['cleanBefore'] and receipt['cleanAfter']
    log=(q.out/(backend.lower()+'.log')).read_text()
    marker='WOG_HSMM_BASELINE_JSON='
    outputs=[line[len(marker):] for line in log.splitlines() if line.startswith(marker)]
    assert len(outputs)==1
    actual=outputs[0].encode();expected=(baseline/(backend.lower()+'.json')).read_bytes()
    artifact=q.out/(backend.lower()+'-actual.json');assert not artifact.exists();artifact.write_bytes(actual)
    cases=[];reports={}
    for suite in suites:
        report=reportdir/('TEST-'+suite+'.xml');assert report.stat().st_mtime>=receipt['startedEpochSeconds']
        data=report.read_bytes();target=q.out/(backend.lower()+'--'+suite+'.xml');target.write_bytes(data)
        reports[suite]=dict(file=target.name,sha256=sha(data))
        cases.extend(ET.fromstring(data).findall('testcase'))
    assert len(cases)==(4 if backend=='JVM' else 3)
    assert all(all(c.find(tag) is None for tag in ['failure','error','skipped']) for c in cases)
    entry=dict(commandReceipt=receipt,baselineSha256=sha(expected),actualSha256=sha(actual),
        baselineBytes=len(expected),actualBytes=len(actual),exactEqual=actual==expected,
        schemaVersion=json.loads(actual)['schemaVersion'],reports=reports,
        namedPassedTests=[c.attrib['name'] for c in cases],
        sbtRuntimeFromLog=next(l for l in log.splitlines() if 'welcome to sbt' in l))
    manifest['backends'][backend]=entry
    (q.out/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    require_equal(actual,expected)
    mutated=actual[:-1]+bytes([actual[-1]^1]);killed=False
    try:require_equal(mutated,expected)
    except AssertionError:killed=True
    assert killed
    entry['byteComparatorFalsifier']=dict(originalAccepted=True,changedLastByteRejected=killed,mutantSha256=sha(mutated),changedByteOffset=len(actual)-1)
    (q.out/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    print('PARITY',backend,entry['actualSha256'],flush=True)
manifest['allBackendsExact']=True
(q.out/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
