"""Read back the existing Qwen Sherlock samples; never run ASR or alter source outputs."""
import hashlib, json, wave
from pathlib import Path

ROOT = Path(__file__).resolve()
while not (ROOT / 'AGENTS.md').exists():
    ROOT = ROOT.parent
ORIGINAL = ROOT / 'data/transcription/asr-comparison-qwen-20261020/run-2'
EXPECTED = {
    'comparison.json': '11bc2a19e06f3c3c199da4b7b23b2f2f5fe3ba0b9161dc666782800c02fdf54b',
    'method.json': '3660c1e0d31c0b271175f0f16ec79744861abf080bae32dd747cbcc8d2b5dbf8',
    'manifest.json': '1fc1c14ca32ae2aacddc4dfa99e7db4e58a6416708684444754d73175005624f',
    'comparison.html': '28b3f6dc6d1700e2b9f32d3801d9421fc9ad41d1806aefef4c78719391704690',
}
KEYS = {'sherlock1_boundary': (20, 145), 'sherlock2_boundary': (20, 120),
        'sherlock1_dialogue': (860, 60)}

def sha(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as f:
        for block in iter(lambda: f.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()

def load(path):
    return json.loads(Path(path).read_text())

def verify():
    for name, digest in EXPECTED.items():
        assert sha(ORIGINAL / name) == digest, ('reviewed artifact changed', name)
    comparison, method, manifest = (load(ORIGINAL / name) for name in
                                    ('comparison.json', 'method.json', 'manifest.json'))
    assert comparison['completed'] == manifest['completed'] == 15
    assert comparison['failures'] == [] and manifest['failed'] == 0
    assert comparison['method'] == method['identity'] == manifest['method']
    spec = method['specification']
    assert spec['model']['repo'] == 'mlx-community/Qwen3-ASR-1.7B-8bit'
    assert spec['model']['revision'] == 'a8379a2e2f9e313c9292cdf1af4055ab56d50d55'
    assert spec['settings']['temperature'] == 0 and spec['settings']['language'] is None
    assert spec['settings']['max_tokens'] == 2048
    baseline_path = Path(spec['baselinePins']['baselineComparison'])
    assert sha(baseline_path) == comparison['baselineSha256']
    baseline = {r['input']['key']: r for r in load(baseline_path)['specimens']}
    rows = [r for r in comparison['specimens'] if r['input']['key'].startswith('sherlock')]
    assert len(rows) == 3 and {r['input']['key'] for r in rows} == set(KEYS)
    seen_sources, results = {}, []
    for row in rows:
        item = row['input']; key = item['key']; start, duration = KEYS[key]
        assert item['sourceStartSeconds'] == start and item['durationSeconds'] == duration
        assert row['qwenState'] == 'complete' and row['wordTiming'] is None
        assert item == baseline[key]['input']
        assert row['baselineText'] == {name: ' '.join(w['word'] for w in words)
                                       for name, words in baseline[key]['outputs'].items()}
        folder = ORIGINAL / key; completion_path = folder / 'completion.json'
        assert sha(completion_path) == row['completionSha256']
        completion, transcript, raw = (load(folder / name) for name in
                                      ('completion.json', 'transcript.json', 'raw.json'))
        assert completion['exit'] == 0 and completion['method'] == method['identity']
        assert set(completion['outputs']) == {'attempt.json', 'raw.json', 'transcript.json'}
        for name, digest in completion['outputs'].items():
            assert sha(folder / name) == digest, (key, name)
        assert completion['inputSha256'] == item['sha256'] == sha(item['path'])
        assert transcript['method'] == method['identity'] and transcript['input'] == item
        assert transcript['text'] == row['qwenText'] == raw['returned']['text'].strip()
        assert transcript['wordTiming'] is None
        assert transcript['sourceClockOffsetSeconds'] == start
        assert transcript['nativeCoarseChunks'] == raw['returned']['segments']
        assert transcript['nativeCoarseChunksClock'] == row['coarseChunkClock']
        assert len(raw['streams']) == 1
        token_count = len(raw['streams'][0]['ids'])
        assert token_count == raw['returned']['generation_tokens'] == transcript['summary']['generationTokens']
        assert token_count < spec['settings']['max_tokens']
        source = item['sourceInput']; source_path = Path(source['path'])
        if str(source_path) not in seen_sources:
            assert source_path.stat().st_size == source['bytes']
            assert sha(source_path) == source['sha256']
            seen_sources[str(source_path)] = source['sha256']
        production = ROOT / 'data/transcription/whisper-large-v3-mlx-20261020/sherlock' / item['sourceKey']
        source_wav = production / (item['sourceKey'] + '.wav')
        assert sha(production / 'completion.json') == item['sourceCompletionSha256']
        assert sha(source_wav) == item['sourceWavSha256']
        with wave.open(str(source_wav), 'rb') as full, wave.open(item['path'], 'rb') as excerpt:
            assert (full.getnchannels(), full.getsampwidth(), full.getframerate()) == (1, 2, 16000)
            assert (excerpt.getnchannels(), excerpt.getsampwidth(), excerpt.getframerate()) == (1, 2, 16000)
            assert excerpt.getnframes() == duration * 16000
            full.setpos(start * 16000)
            assert full.readframes(duration * 16000) == excerpt.readframes(excerpt.getnframes()), key
        results.append({'key': key, 'sourcePart': item['sourceKey'], 'sourceStartSeconds': start,
                        'durationSeconds': duration, 'sourceSHA256': source['sha256'],
                        'excerptSHA256': item['sha256'], 'completionSHA256': sha(completion_path),
                        'completedExit': 0, 'generatedTokens': token_count,
                        'wordTiming': None, 'exactSourceAudioSlice': True})
    return {'model': spec['model']['repo'], 'modelRevision': spec['model']['revision'],
            'methodIdentity': method['identity'], 'reviewedArtifactSHA256': EXPECTED,
            'baselineSHA256': comparison['baselineSha256'], 'verified': results,
            'selectedSeconds': sum(r['durationSeconds'] for r in results),
            'newInferenceExecuted': False, 'humanAudioAdjudication': False,
            'claim': 'Retained source/excerpt/output integrity and exact excerpt clock verified; text differences are not accuracy or mapping efficacy.'}

if __name__ == '__main__':
    print(json.dumps(verify(), indent=2))
