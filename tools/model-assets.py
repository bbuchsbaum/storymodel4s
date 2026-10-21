#!/usr/bin/env python3
"""Fetch pinned model assets or relocate verified weights while preserving legacy paths."""
import argparse
import hashlib
import json
import os
import plistlib
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.request
from pathlib import Path, PurePosixPath

HERE = Path(__file__).resolve().parent
MANIFEST = HERE / 'model-assets/manifest.json'


def require(ok, message):
    if not ok:
        raise ValueError(message)


def sha(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def relative(value):
    require(isinstance(value, str), 'Unsafe relative path')
    p = PurePosixPath(value)
    require(isinstance(value, str) and value and not p.is_absolute() and
            all(part not in ('', '.', '..') for part in value.split('/')) and
            '\\' not in value and not any(ord(c) < 32 for c in value), 'Unsafe relative path')
    return Path(*p.parts)


def read_manifest(path):
    manifest = json.loads(Path(path).read_text())
    require(manifest['schema_version'] == 1, 'Unsupported manifest version')
    ids, aliases, coordinates = set(), set(), set()
    for model in manifest['models']:
        require(re.fullmatch(r'[a-z0-9][a-z0-9-]*', model['id']) and model['id'] not in ids,
                'Duplicate/invalid model id')
        ids.add(model['id'])
        require(re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', model['repo']) and
                '..' not in model['repo'].split('/'), 'Invalid model repository')
        require(re.fullmatch(r'[0-9a-f]{40}', model['revision']), 'Model revision must be immutable')
        coordinate = (model['repo'], model['revision'])
        require(coordinate not in coordinates and model['files'], 'Duplicate/empty model')
        coordinates.add(coordinate)
        for name, record in model['files'].items():
            relative(name)
            expected = 'https://huggingface.co/' + model['repo'] + '/resolve/' + model['revision'] + '/' + name
            require(record['url'] == expected, 'Unpinned/foreign asset URL')
            require(type(record['bytes']) is int and record['bytes'] > 0 and
                    re.fullmatch(r'[0-9a-f]{64}', record['sha256']), 'Invalid asset identity')
            require(type(record['weight']) is bool, 'Missing weight classification')
            for alias in record['legacy_paths']:
                p = relative(alias)
                require(p.parts[0] in ('data', 'tmp', '.agent-work') and alias not in aliases,
                        'Protected/duplicate legacy path')
                aliases.add(alias)
    return manifest


def checkout():
    common = subprocess.check_output(['git', '-C', str(HERE.parent), 'rev-parse',
                                      '--path-format=absolute', '--git-common-dir'], text=True).strip()
    return Path(common).parent.resolve()


def legacy_path(repo, alias):
    path = relative(alias)
    if path.parts[0] == 'data':
        base = Path(os.environ.get('STORYMODEL4S_DATA', str(repo / 'data'))).absolute()
        tail = Path(*path.parts[1:])
    else:
        base, tail = repo, path
    dest = base / tail
    require(dest.parent.resolve().is_relative_to(base.resolve()), 'Legacy parent escapes its root')
    return dest


def cache_path(root, model, name):
    path = root / relative(model['repo']) / model['revision'] / relative(name)
    for parent in [path, *path.parents]:
        if parent == root:
            break
        require(not parent.is_symlink(), 'Cache symlink substitution: ' + str(parent))
    return path


def prepare_root(value, repo):
    root = Path(value).expanduser().absolute()
    require(not root.resolve().is_relative_to(repo.resolve()), 'Model cache must be outside the checkout')
    if root.parts[:2] == ('/', 'Volumes'):
        require(len(root.parts) > 2 and Path(*root.parts[:3]).is_mount(), 'External volume is not mounted')
    return root


def verify(path, record):
    require(path.is_file() and path.stat().st_size == record['bytes'] and sha(path) == record['sha256'],
            'Missing or corrupt asset: ' + str(path))


def publish_stream(target, record, source):
    """Never overwrite a conflicting asset; clean only this invocation's partial/lock."""
    if target.exists() or target.is_symlink():
        require(not target.is_symlink(), 'Cache file is a symlink')
        verify(target, record)
        return False
    target.parent.mkdir(parents=True, exist_ok=True)
    lock = target.with_name(target.name + '.model-assets.lock')
    lock_fd = os.open(lock, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    temporary = None
    try:
        require(not target.exists() and not target.is_symlink(), 'Concurrent cache publication')
        with tempfile.NamedTemporaryFile(prefix=target.name + '.partial-', dir=target.parent,
                                         delete=False) as output:
            temporary = Path(output.name)
            total = 0
            with source() as stream:
                for chunk in iter(lambda: stream.read(1024 * 1024), b''):
                    total += len(chunk)
                    require(total <= record['bytes'], 'Asset exceeds declared size')
                    output.write(chunk)
            output.flush()
            os.fsync(output.fileno())
        verify(temporary, record)  # Independent complete-file readback before publication.
        require(not target.exists() and not target.is_symlink(), 'Concurrent cache publication')
        temporary.replace(target)
        return True
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)
        os.close(lock_fd)
        lock.unlink()


def fetch_asset(target, record):
    def source():
        response = urllib.request.urlopen(record['url'], timeout=60)
        if not response.geturl().startswith('https://'):
            response.close()
            raise ValueError('Download redirected outside HTTPS')
        return response
    return publish_stream(target, record, source)


def signature(path):
    s = path.lstat()
    return s.st_dev, s.st_ino, s.st_size, s.st_mtime_ns


def replace_with_link(path, target, record, *, evict=False, relink=False, before_replace=None):
    if path.is_symlink():
        if path.resolve() == target.resolve():
            verify(path, record)
            return 'linked'
        require(relink, 'Existing link points elsewhere; use --relink after verifying the new cache')
    elif path.exists():
        verify(path, record)
        if not evict:
            verify(target, record)
            return 'retained_small_or_existing_file'
    path.parent.mkdir(parents=True, exist_ok=True)
    previous = signature(path) if path.exists() or path.is_symlink() else None
    temporary = path.with_name(path.name + '.model-assets-link-' + str(os.getpid()))
    require(not temporary.exists() and not temporary.is_symlink(), 'Link staging collision')
    temporary.symlink_to(target)
    try:
        require((signature(path) if path.exists() or path.is_symlink() else None) == previous,
                'Legacy file changed before replacement')
        if previous is not None and not path.is_symlink():
            verify(path, record)
        verify(target, record)  # Custody refreshed immediately before removing local bytes.
        if before_replace is not None:
            before_replace()
        require((signature(path) if path.exists() or path.is_symlink() else None) == previous,
                'Legacy file changed during custody verification')
        os.replace(temporary, path)
        verify(path, record)
    finally:
        temporary.unlink(missing_ok=True)
    return 'relocated' if previous is not None and evict else 'linked'


def open_paths(paths):
    if not paths:
        return set()
    require(shutil.which('lsof') is not None, 'lsof is required before local weight replacement')
    result = subprocess.run(['lsof', '-Fn', '--', *map(str, paths)], capture_output=True, text=True)
    require(result.returncode in (0, 1) and not result.stderr.strip(), 'Open-file inspection failed')
    # lsof may exit1 when some requested files are closed even while others appear in stdout.
    return {line[1:] for line in result.stdout.splitlines() if line.startswith('n')}


def volume_identity(root, expected):
    require(root.parts[:2] == ('/', 'Volumes') and len(root.parts) > 2,
            'Migration requires an explicit mounted external volume')
    mount = Path(*root.parts[:3])
    require(mount.is_mount(), 'External volume is not mounted')
    result = subprocess.run(['diskutil', 'info', '-plist', str(mount)], capture_output=True, check=True)
    info = plistlib.loads(result.stdout)
    require(info.get('VolumeUUID') == expected and info.get('Internal') is False and
            info.get('MountPoint') == str(mount), 'Wrong or internal migration volume')
    return {key: info.get(key) for key in ('VolumeUUID', 'MountPoint', 'VolumeName', 'Internal')}


def save_receipt(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    staging = path.with_name(path.name + '.pending')
    with staging.open('x') as output:
        json.dump(value, output, indent=2)
        output.write('\n')
        output.flush()
        os.fsync(output.fileno())
    staging.replace(path)


def migrate(models, root, repo, receipt_path, expected_uuid, copy_only=False):
    require(not receipt_path.exists() and not receipt_path.is_symlink(),
            'Migration receipt already exists; inspect it rather than overwrite')
    receipt = dict(schema_version=1, manifest_sha256=sha(MANIFEST), script_sha256=sha(Path(__file__)),
                   volume=volume_identity(root, expected_uuid), cache_root=str(root),
                   selected=[m['id'] for m in models], phase='copying', copies=[], aliases=[])
    save_receipt(receipt_path, receipt)
    for model in models:
        for name, record in model['files'].items():
            target = cache_path(root, model, name)
            if target.exists():
                verify(target, record)
                continue
            sources = [legacy_path(repo, p) for p in record['legacy_paths']]
            source = next((p for p in sources if p.is_file()), None)
            require(source is not None, 'No local source; run fetch first: ' + model['id'] + '/' + name)
            verify(source, record)
            if publish_stream(target, record, lambda p=source: p.open('rb')):
                receipt['copies'].append(dict(model=model['id'], file=name, bytes=record['bytes'],
                                              sha256=record['sha256'], target=str(target)))
                save_receipt(receipt_path, receipt)
                print('COPIED', model['id'], name, flush=True)
    receipt['phase'] = 'copy_verified'
    save_receipt(receipt_path, receipt)
    if copy_only:
        return receipt
    regular_weights = [(legacy_path(repo, p), cache_path(root, model, name), record)
                       for model in models for name, record in model['files'].items() if record['weight']
                       for p in record['legacy_paths']
                       if legacy_path(repo, p).exists() and not legacy_path(repo, p).is_symlink()]
    for path, _, record in regular_weights:
        verify(path, record)
    busy = open_paths([p for p, _, _ in regular_weights])
    if busy:
        receipt.update(phase='pending_open_files', open_files=sorted(busy))
        save_receipt(receipt_path, receipt)
        raise ValueError('Weights are open; copied bytes retained, local weights untouched: ' + ', '.join(sorted(busy)))
    volume_identity(root, expected_uuid)
    for path, target, record in regular_weights:
        def pre_replace(path=path):
            volume_identity(root, expected_uuid)
            require(not open_paths([path]), 'Weight became open before replacement: ' + str(path))
        previous = signature(path)
        result = replace_with_link(path, target, record, evict=True, before_replace=pre_replace)
        receipt['aliases'].append(dict(path=str(path.relative_to(repo)) if path.is_relative_to(repo) else str(path),
                                       target=str(target), bytes=record['bytes'], sha256=record['sha256'],
                                       previous_stat=previous, outcome=result))
        save_receipt(receipt_path, receipt)
        print('RELOCATED', path, flush=True)
    receipt['phase'] = 'complete'
    receipt['relocated_bytes'] = sum(a['bytes'] for a in receipt['aliases'] if a['outcome'] == 'relocated')
    save_receipt(receipt_path, receipt)
    return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', help='Cache outside checkout; default STORYMODEL4S_MODEL_ROOT or Passport')
    sub = parser.add_subparsers(dest='command', required=True)
    for command in ('list', 'verify', 'fetch', 'link', 'migrate'):
        child = sub.add_parser(command)
        child.add_argument('models', nargs='*', help='Manifest IDs; omitted or all selects all six')
        if command in ('fetch', 'link'):
            child.add_argument('--relink', action='store_true', help='Replace only existing symlinks with verified new targets')
        if command == 'fetch':
            child.add_argument('--link', action='store_true', help='Also establish missing legacy aliases')
        if command == 'verify':
            child.add_argument('--legacy', action='store_true', help='Verify every legacy alias too')
        if command == 'migrate':
            child.add_argument('--receipt', type=Path, required=True)
            child.add_argument('--expected-volume-uuid', required=True)
            child.add_argument('--copy-only', action='store_true', help='Verify custody without replacing local files')
    args = parser.parse_args()
    manifest = read_manifest(MANIFEST)
    repo = checkout()
    root = prepare_root(args.root or os.environ.get('STORYMODEL4S_MODEL_ROOT') or manifest['default_cache_root'], repo)
    ids = {m['id'] for m in manifest['models']}
    selected = ids if not args.models or args.models == ['all'] else set(args.models)
    require(selected <= ids, 'Unknown model IDs: ' + ', '.join(sorted(selected - ids)))
    models = [m for m in manifest['models'] if m['id'] in selected]
    if args.command == 'list':
        for model in models:
            print(model['id'], sum(r['bytes'] for r in model['files'].values()),
                  root / model['repo'] / model['revision'])
        return
    if args.command == 'migrate':
        receipt = migrate(models, root, repo, args.receipt.absolute(), args.expected_volume_uuid, args.copy_only)
        print(json.dumps(dict(phase=receipt['phase'], copies=len(receipt['copies']),
                              relocated_bytes=receipt.get('relocated_bytes', 0))))
        return
    # Finish verifying/downloading the full selected bundle before creating any aliases.
    for model in models:
        for name, record in model['files'].items():
            target = cache_path(root, model, name)
            if args.command == 'fetch':
                fetch_asset(target, record)
            else:
                verify(target, record)
            if args.command == 'verify' and args.legacy:
                for alias in record['legacy_paths']:
                    verify(legacy_path(repo, alias), record)
        print('VERIFIED', model['id'], flush=True)
    if args.command == 'link' or (args.command == 'fetch' and args.link):
        for model in models:
            for name, record in model['files'].items():
                for alias in record['legacy_paths']:
                    replace_with_link(legacy_path(repo, alias), cache_path(root, model, name), record,
                                      relink=args.relink)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        print('ERROR:', error, file=sys.stderr)
        raise SystemExit(1)
