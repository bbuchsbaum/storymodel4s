#!/usr/bin/env python3
"""Copy an explicit private asset selection to an owner-approved development host.

Python 3.9+, POSIX, SSH. Stdout contains counts/digests and committed placement fields only.
The ready marker admits bytes at this machine/root, never a new corpus use or study partition.
"""
import argparse
import contextlib
import fcntl
import hashlib
import json
import os
from pathlib import Path
import shlex
import socket
import stat
import subprocess
import sys
import uuid
import unicodedata

BLOCK = 1024 * 1024
DIR = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
READ = os.O_RDONLY | os.O_NOFOLLOW


class Refusal(Exception):
    pass


class PrivateArgumentParser(argparse.ArgumentParser):
    def error(self, message):
        raise Refusal()


def require(condition):
    if not condition:
        raise Refusal()


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":")).encode("utf-8")


def digest(value):
    return hashlib.sha256(value).hexdigest()


def machine():
    if sys.platform == "darwin":
        return subprocess.check_output(["scutil", "--get", "LocalHostName"], stderr=subprocess.DEVNULL).decode().strip()
    return socket.gethostname()


def parts(path):
    require(isinstance(path, str) and path and not path.startswith("/") and "\\" not in path)
    result = path.split("/")
    require(all(p not in ("", ".", "..") and not any(ord(c) < 32 or ord(c) == 127 for c in p) for p in result))
    return result


def manifest_check(m):
    require(m.get("schema") == 1 and m.get("use") == "development")
    require(isinstance(m.get("restrictions"), list) and len(m["restrictions"]) > 0)
    require(len(m.get("selection_basis_sha256", "")) == 64)
    seen = []
    for a in m["assets"]:
        require(parts(a["path"])[0].casefold() != ".handoff")
        p = unicodedata.normalize("NFC", "/".join(parts(a["path"]))).casefold()
        require(not any(p == x or p.startswith(x + "/") or x.startswith(p + "/") for x in seen))
        seen.append(p)
        require(type(a["size"]) is int and a["size"] >= 0)
        require(len(a["sha256"]) == 64 and all(c in "0123456789abcdef" for c in a["sha256"]))
    require(len(seen) > 0)
    return digest(canonical(m))


@contextlib.contextmanager
def directory(root, create=False, private=True):
    """Open each absolute component without following symlinks; never resolve a link."""
    require(os.path.isabs(root) and os.path.normpath(root) == root and root != "/")
    fd = os.open("/", DIR)
    try:
        for p in root[1:].split("/"):
            if create:
                try:
                    os.mkdir(p, 0o700, dir_fd=fd)
                except FileExistsError:
                    pass
            child = os.open(p, DIR, dir_fd=fd)
            os.close(fd)
            fd = child
        require(not private or stat.S_IMODE(os.fstat(fd).st_mode) == 0o700)
        yield fd
    finally:
        os.close(fd)


@contextlib.contextmanager
def parent(fd, path, create=False, private=True):
    ps = parts(path)
    current = os.dup(fd)
    try:
        for p in ps[:-1]:
            if create:
                try:
                    os.mkdir(p, 0o700, dir_fd=current)
                except FileExistsError:
                    pass
            child = os.open(p, DIR, dir_fd=current)
            os.close(current)
            current = child
            require(not private or stat.S_IMODE(os.fstat(current).st_mode) == 0o700)
        yield current, ps[-1]
    finally:
        os.close(current)


def file_open(fd, path, flags=READ, create_parent=False, private=True):
    with parent(fd, path, create_parent, private) as (p, leaf):
        result = os.open(leaf, flags | os.O_NOFOLLOW, 0o600, dir_fd=p)
    s = os.fstat(result)
    if not stat.S_ISREG(s.st_mode):
        os.close(result)
        raise Refusal()
    return result


def hash_fd(fd, limit=None):
    os.lseek(fd, 0, os.SEEK_SET)
    h = hashlib.sha256()
    n = 0
    while limit is None or n < limit:
        b = os.read(fd, BLOCK if limit is None else min(BLOCK, limit - n))
        if not b:
            break
        h.update(b)
        n += len(b)
    return n, h.hexdigest()


def measure(fd, path):
    try:
        f = file_open(fd, path)
    except FileNotFoundError:
        return None
    try:
        require(stat.S_IMODE(os.fstat(f).st_mode) & 0o077 == 0)
        return hash_fd(f)
    finally:
        os.close(f)


def private_json(fd, path, value):
    """Publish complete metadata create-only; interrupted temporary files are never admitted."""
    data = canonical(value)
    with parent(fd, path, True) as (p, leaf):
        temporary = ".pending-" + uuid.uuid4().hex
        f = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=p)
        try:
            with os.fdopen(f, "wb") as out:
                out.write(data)
                out.flush()
                os.fsync(out.fileno())
            try:
                os.link(temporary, leaf, src_dir_fd=p, dst_dir_fd=p, follow_symlinks=False)
                os.fsync(p)
            except FileExistsError:
                existing = os.open(leaf, READ, dir_fd=p)
                try:
                    require(stat.S_ISREG(os.fstat(existing).st_mode))
                    require(os.read(existing, len(data) + 1) == data)
                finally:
                    os.close(existing)
        finally:
            os.unlink(temporary, dir_fd=p)


class Receiver:
    def __init__(self, root, expected_machine, m):
        require(machine() == expected_machine)
        self.root, self.host, self.m = root, expected_machine, m
        self.id = manifest_check(m)
        self.stage = root + "/.handoff/staging/" + self.id
        self.ready = ".handoff/ready/" + self.id + ".json"
        self.binding = {"schema": 1, "manifest_sha256": self.id, "machine": self.host,
                        "root": root, "assets": len(m["assets"]), "bytes": sum(a["size"] for a in m["assets"])}

    @contextlib.contextmanager
    def locked(self):
        with directory(self.root, True) as root:
            lock = file_open(root, ".handoff/promotion.lock", os.O_RDWR | os.O_CREAT, True)
            try:
                require(stat.S_IMODE(os.fstat(lock).st_mode) == 0o600)
                fcntl.flock(lock, fcntl.LOCK_EX)
                with directory(self.stage, True) as stage:
                    private_json(stage, "manifest.json", {"binding": self.binding, "manifest": self.m})
                    yield root, stage
            finally:
                os.close(lock)

    def status(self):
        result = []
        with self.locked() as (root, stage):
            for i, a in enumerate(self.m["assets"]):
                final = measure(root, a["path"])
                if final is not None:
                    require(final == (a["size"], a["sha256"]))
                    result.append({"index": i, "final": True, "offset": a["size"], "sha256": a["sha256"]})
                else:
                    partial = measure(stage, "assets/" + a["path"]) or (0, digest(b""))
                    require(partial[0] <= a["size"])
                    result.append({"index": i, "final": False, "offset": partial[0], "sha256": partial[1]})
        return result

    def put(self, index, offset, prefix, stream):
        a = self.m["assets"][index]
        require(0 <= offset <= a["size"])
        with self.locked() as (root, stage):
            require(measure(root, a["path"]) is None)
            f = file_open(stage, "assets/" + a["path"], os.O_RDWR | os.O_CREAT, True)
            with os.fdopen(f, "r+b", buffering=0) as out:
                s = os.fstat(out.fileno())
                require(s.st_nlink == 1 and stat.S_IMODE(s.st_mode) == 0o600)
                require(hash_fd(out.fileno()) == (offset, prefix))
                out.seek(offset)
                n = offset
                while n < a["size"]:
                    b = stream.read(min(BLOCK, a["size"] - n))
                    if not b:
                        out.flush()
                        os.fsync(out.fileno())
                        raise Refusal()
                    out.write(b)
                    n += len(b)
                require(not stream.read(1))
                out.flush()
                os.fsync(out.fileno())
                require(hash_fd(out.fileno()) == (a["size"], a["sha256"]))
        return {"verified": 1}

    def finalize(self, after_link=None, before_link=None):
        with self.locked() as (root, stage):
            # Check every byte and conflict before any promotion. No destination overwrite.
            for a in self.m["assets"]:
                final = measure(root, a["path"])
                require((final if final is not None else measure(stage, "assets/" + a["path"])) == (a["size"], a["sha256"]))
            for i, a in enumerate(self.m["assets"]):
                if measure(root, a["path"]) is not None:
                    final = file_open(root, a["path"])
                    try:
                        os.fchmod(final, 0o400)
                        # A crash between link and unlink leaves an owned alias. Never append it.
                        with parent(stage, "assets/" + a["path"]) as (s, sl):
                            try:
                                staged = os.stat(sl, dir_fd=s, follow_symlinks=False)
                                require((staged.st_dev, staged.st_ino) == (os.fstat(final).st_dev, os.fstat(final).st_ino))
                                os.unlink(sl, dir_fd=s)
                            except FileNotFoundError:
                                pass
                    except FileNotFoundError:
                        pass
                    finally:
                        os.close(final)
                    continue
                with parent(stage, "assets/" + a["path"]) as (s, sl), parent(root, a["path"], True) as (d, dl):
                    f = os.open(sl, READ, dir_fd=s)
                    try:
                        require(os.fstat(f).st_nlink == 1)
                        require(hash_fd(f) == (a["size"], a["sha256"]))
                        if before_link is not None:
                            before_link(i)
                        os.link(sl, dl, src_dir_fd=s, dst_dir_fd=d, follow_symlinks=False)
                        os.fchmod(f, 0o400)
                        os.fsync(f)
                        os.unlink(sl, dir_fd=s)
                        os.fsync(d)
                    finally:
                        os.close(f)
                if after_link is not None:
                    after_link(i)
            private_json(root, self.ready, self.binding)
        return self.verify()

    def verify(self):
        with self.locked() as (root, stage):
            f = file_open(root, self.ready)
            try:
                require(os.read(f, len(canonical(self.binding)) + 1) == canonical(self.binding))
            finally:
                os.close(f)
            for a in self.m["assets"]:
                require(measure(root, a["path"]) == (a["size"], a["sha256"]))
        return dict(self.binding, verified=True)


def committed(repo, path):
    return subprocess.check_output(["git", "-C", str(repo), "show", "HEAD:" + path], stderr=subprocess.DEVNULL)


def policy_destination(policy, name):
    require(policy.get("schema") == 1)
    d = policy["destinations"][name]
    require(d.get("approved") is True and d.get("approval_basis"))
    require(d["ssh_target"] and d["host_key_alias"] and d["machine"])
    return d


def ssh_call(d, source, operation, m=None, input_file=None, extra=None):
    args = ["python3", "-c", source, "--receive", operation, "--root", d["root"], "--machine", d["machine"]]
    command = ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=15", "-o", "StrictHostKeyChecking=yes",
               "-o", "HostKeyAlias=" + d["host_key_alias"], d["ssh_target"], shlex.join(args)]
    # Private SSH diagnostics go nowhere near the model or a public build log.
    with subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL) as p:
        try:
            if m is not None:
                p.stdin.write(canonical({"manifest": m, "extra": extra}) + b"\n")
            if input_file is not None:
                while True:
                    b = input_file.read(BLOCK)
                    if not b:
                        break
                    p.stdin.write(b)
            p.stdin.close()
            answer = p.stdout.read()
            require(p.wait() == 0)
            return json.loads(answer)
        except BaseException:
            p.terminate()
            p.wait()
            raise


def client(args):
    repo = Path(__file__).resolve().parents[1]
    policy = json.loads(committed(repo, "docs/data/development-hosts.json"))
    require(machine() in policy["source_machines"])
    d = policy_destination(policy, args.destination)
    source = committed(repo, "tools/data_handoff.py").decode()
    require(Path(__file__).read_bytes() == source.encode())
    source_root = args.root or os.environ.get("STORYMODEL4S_DATA") or str(repo / "data")
    if args.action == "check-local":
        require(machine() == d["machine"] and source_root == d["root"])
        require(args.manifest and len(args.manifest) == 64 and all(c in "0123456789abcdef" for c in args.manifest))
        with directory(source_root) as root:
            f = file_open(root, ".handoff/staging/" + args.manifest + "/manifest.json")
            with os.fdopen(f, "rb") as inp:
                saved = json.load(inp)
        require(manifest_check(saved["manifest"]) == args.manifest)
        return Receiver(source_root, d["machine"], saved["manifest"]).verify()
    if args.action == "plan":
        selection = json.loads(Path(args.selection).read_bytes())
        refs = []
        for ref in selection["restrictions"]:
            require(ref.startswith("docs/data/") or ref.startswith("docs/plans/"))
            parts(ref)
            refs.append({"path": ref, "sha256": digest(committed(repo, ref))})
        m = {"schema": 1, "use": "development", "restrictions": refs,
             "selection_basis_sha256": digest(Path(args.basis).read_bytes()), "assets": []}
        with directory(source_root, private=False) as root:
            for path in selection["assets"]:
                require(parts(path)[0].casefold() != ".handoff")
                f = file_open(root, path, private=False)
                try:
                    before = os.fstat(f)
                    size, sha = hash_fd(f)
                    after = os.fstat(f)
                    require((before.st_size, before.st_mtime_ns, before.st_ctime_ns) == (after.st_size, after.st_mtime_ns, after.st_ctime_ns))
                    m["assets"].append({"path": path, "size": size, "sha256": sha})
                finally:
                    os.close(f)
            ident = manifest_check(m)
            private_json(root, ".handoff/plans/" + ident + ".json", m)
        return {"manifest_sha256": ident, "assets": len(m["assets"]), "bytes": sum(a["size"] for a in m["assets"]), "execute": False}
    require(args.manifest and len(args.manifest) == 64 and all(c in "0123456789abcdef" for c in args.manifest))
    with directory(source_root, private=False) as root:
        f = file_open(root, ".handoff/plans/" + args.manifest + ".json")
        with os.fdopen(f, "rb") as inp:
            m = json.load(inp)
        require(manifest_check(m) == args.manifest)
        # Host identity is checked before sending any private manifest or bytes.
        require(ssh_call(d, source, "identity")["machine"] == d["machine"])
        if args.action == "verify":
            return ssh_call(d, source, "verify", m)
        state = ssh_call(d, source, "status", m)
        if not args.execute:
            return {"manifest_sha256": args.manifest, "assets": len(state), "ready_assets": sum(s["final"] for s in state), "execute": False}
        for s in state:
            if s["final"]:
                continue
            a = m["assets"][s["index"]]
            f = file_open(root, a["path"], private=False)
            with os.fdopen(f, "rb") as inp:
                require(hash_fd(inp.fileno()) == (a["size"], a["sha256"]))
                require(hash_fd(inp.fileno(), s["offset"]) == (s["offset"], s["sha256"]))
                inp.seek(s["offset"])
                ssh_call(d, source, "put", m, inp, s)
        return ssh_call(d, source, "finalize", m)


def main():
    os.umask(0o077)
    p = PrivateArgumentParser(description=__doc__)
    p.add_argument("action", nargs="?", choices=["plan", "transfer", "verify", "check-local"])
    p.add_argument("--destination", default="buc-gw01")
    p.add_argument("--root")
    p.add_argument("--selection", help="private JSON: explicit relative assets and committed restriction references")
    p.add_argument("--basis", help="private selection/partition decision record; its digest is retained")
    p.add_argument("--manifest", help="digest returned by plan; manifest stays in source .handoff/plans")
    p.add_argument("--execute", action="store_true")
    p.add_argument("--receive", choices=["identity", "status", "put", "finalize", "verify"], help=argparse.SUPPRESS)
    p.add_argument("--machine", help=argparse.SUPPRESS)
    try:
        args = p.parse_args()
        if args.receive:
            require(machine() == args.machine)
            if args.receive == "identity":
                result = {"machine": machine()}
            else:
                request = json.loads(sys.stdin.buffer.readline())
                receiver = Receiver(args.root, args.machine, request["manifest"])
                if args.receive == "put":
                    e = request["extra"]
                    result = receiver.put(e["index"], e["offset"], e["sha256"], sys.stdin.buffer)
                else:
                    result = getattr(receiver, args.receive)()
        else:
            require(args.action is not None)
            result = client(args)
        print(json.dumps(result, sort_keys=True))
        return 0
    except (Exception, KeyboardInterrupt):
        print("handoff refused or interrupted; originals preserved; no ready claim", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
