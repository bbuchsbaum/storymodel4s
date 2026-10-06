#!/usr/bin/env python3
"""Synthetic refusal/resume witnesses: never load repository private data."""
import copy
import hashlib
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
import argparse
import contextlib
from unittest.mock import patch

import data_handoff as h


class HandoffTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir="/private/tmp" if os.path.isdir("/private/tmp") else None)
        self.root = str(Path(self.temp.name) / "destination")
        self.payloads = [b"abc", b"second synthetic asset\n"]
        self.m = {"schema": 1, "use": "development", "restrictions": [{"path": "docs/data/synthetic.md", "sha256": "0" * 64}],
                  "selection_basis_sha256": "1" * 64, "assets": [
                      {"path": p, "size": len(b), "sha256": hashlib.sha256(b).hexdigest()}
                      for p, b in zip(["nested/a file.bin", "nested/b.bin"], self.payloads)]}
        self.receiver = h.Receiver(self.root, h.machine(), self.m)

    def tearDown(self):
        self.temp.cleanup()

    def fill(self):
        for i, b in enumerate(self.payloads):
            self.receiver.put(i, 0, h.digest(b""), io.BytesIO(b))

    def test_known_hash_and_roundtrip_permissions(self):
        self.assertEqual(self.m["assets"][0]["sha256"], "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        self.fill()
        r = self.receiver.finalize()
        self.assertTrue(r["verified"])
        self.assertEqual(r["assets"], 2)
        for directory, dirs, files in os.walk(self.root):
            self.assertEqual(os.stat(directory).st_mode & 0o777, 0o700)
            for name in files:
                self.assertIn(os.stat(Path(directory) / name).st_mode & 0o777, [0o600, 0o400])
        self.assertEqual(self.receiver.verify(), r)

    def test_unapproved_destination_refused_before_transport(self):
        with patch.object(h, "ssh_call") as ssh:
            with self.assertRaises(h.Refusal):
                h.policy_destination({"schema": 1, "destinations": {"x": {"approved": False}}}, "x")
            ssh.assert_not_called()

    def test_wrong_actual_machine_refused_before_root_creation(self):
        with self.assertRaises(h.Refusal):
            h.Receiver(self.root, "NOT-THIS-MACHINE", self.m)
        self.assertFalse(Path(self.root).exists())

    def test_unsafe_paths_and_casefold_collisions(self):
        for path in ["../escape", "/absolute", "a/../escape", "a//b", "a\nb", ".handoff/plans/a", ".HANDOFF/plans/a"]:
            m = copy.deepcopy(self.m)
            m["assets"][0]["path"] = path
            with self.subTest(path=path), self.assertRaises(h.Refusal):
                h.manifest_check(m)
        for path in ["NESTED/A FILE.BIN", "nested/a file.bin/subfile"]:
            m = copy.deepcopy(self.m)
            m["assets"][1]["path"] = path
            with self.assertRaises(h.Refusal):
                h.manifest_check(m)
        m = copy.deepcopy(self.m)
        m["assets"][0]["path"] = "caf\u00e9/file"
        m["assets"][1]["path"] = "cafe\u0301/file"
        with self.assertRaises(h.Refusal):
            h.manifest_check(m)

    def test_partial_stream_resume_and_prefix_conflict(self):
        with self.assertRaises(h.Refusal):
            self.receiver.put(0, 0, h.digest(b""), io.BytesIO(b"a"))
        s = self.receiver.status()[0]
        self.assertEqual(s["offset"], 1)
        self.assertEqual(s["sha256"], h.digest(b"a"))
        with self.assertRaises(h.Refusal):
            self.receiver.put(0, 1, h.digest(b"x"), io.BytesIO(b"bc"))
        self.receiver.put(0, 1, h.digest(b"a"), io.BytesIO(b"bc"))
        self.receiver.put(1, 0, h.digest(b""), io.BytesIO(self.payloads[1]))
        self.assertTrue(self.receiver.finalize()["verified"])

    def test_corruption_and_truncation_cannot_admit(self):
        for b in [b"ab", b"abd", b"abcd"]:
            with self.assertRaises(h.Refusal):
                self.receiver.put(0, 0, h.digest(b""), io.BytesIO(b))
            with self.assertRaises((h.Refusal, FileNotFoundError)):
                self.receiver.verify()
            # Preserve failed bytes; test each independently in a fresh destination.
            self.root += "-next"
            self.receiver = h.Receiver(self.root, h.machine(), self.m)

    def test_conflicting_destination_and_unrelated_file_preserved(self):
        self.fill()
        root = Path(self.root)
        (root / "nested").mkdir(mode=0o700)
        conflicting = root / self.m["assets"][1]["path"]
        conflicting.write_bytes(b"preexisting")
        conflicting.chmod(0o600)
        unrelated = root / "unrelated"
        unrelated.write_bytes(b"keep")
        with self.assertRaises(h.Refusal):
            self.receiver.finalize()
        self.assertFalse((root / self.m["assets"][0]["path"]).exists())
        self.assertEqual(conflicting.read_bytes(), b"preexisting")
        self.assertEqual(unrelated.read_bytes(), b"keep")

    def test_symlink_root_and_stage_parent_refused_without_outside_write(self):
        outside = Path(self.temp.name) / "outside"
        outside.mkdir(mode=0o700)
        Path(self.root).symlink_to(outside, target_is_directory=True)
        with self.assertRaises(OSError):
            self.receiver.status()
        self.assertEqual(list(outside.iterdir()), [])
        Path(self.root).unlink()
        self.receiver.status()
        stage = Path(self.receiver.stage)
        (stage / "assets").symlink_to(outside, target_is_directory=True)
        with self.assertRaises(OSError):
            self.receiver.put(0, 0, h.digest(b""), io.BytesIO(b"abc"))
        self.assertEqual(list(outside.iterdir()), [])

    def test_destination_parent_swapped_to_symlink_before_promotion(self):
        self.fill()
        outside = Path(self.temp.name) / "outside"
        outside.mkdir(mode=0o700)
        (Path(self.root) / "nested").symlink_to(outside, target_is_directory=True)
        with self.assertRaises(OSError):
            self.receiver.finalize()
        self.assertEqual(list(outside.iterdir()), [])

    def test_interrupted_promotion_unadmitted_then_resume(self):
        self.fill()
        def interrupt(i):
            raise InterruptedError()
        with self.assertRaises(InterruptedError):
            self.receiver.finalize(interrupt)
        self.assertTrue((Path(self.root) / self.m["assets"][0]["path"]).exists())
        with self.assertRaises(FileNotFoundError):
            self.receiver.verify()
        self.assertTrue(self.receiver.finalize()["verified"])

    def test_readonly_stage_and_interrupt_before_link_resume(self):
        self.fill()
        def interrupt(i):
            raise InterruptedError()
        with self.assertRaises(InterruptedError):
            self.receiver.finalize(before_link=interrupt)
        staged = Path(self.receiver.stage) / "assets" / self.m["assets"][0]["path"]
        staged.chmod(0o400)
        self.assertTrue(self.receiver.finalize()["verified"])

    def test_interrupted_manifest_and_ready_writes_resume(self):
        original_link = os.link
        with patch.object(h.os, "link", side_effect=InterruptedError()):
            with self.assertRaises(InterruptedError):
                self.receiver.status()
        self.assertFalse((Path(self.receiver.stage) / "manifest.json").exists())
        self.fill()
        def interrupt_ready(src, dst, **kwargs):
            if dst == self.receiver.id + ".json":
                raise InterruptedError()
            return original_link(src, dst, **kwargs)
        with patch.object(h.os, "link", side_effect=interrupt_ready):
            with self.assertRaises(InterruptedError):
                self.receiver.finalize()
        with self.assertRaises(FileNotFoundError):
            self.receiver.verify()
        self.assertTrue(self.receiver.finalize()["verified"])

    def client_fixture(self):
        source = str(Path(self.temp.name) / "source")
        m = copy.deepcopy(self.m)
        with h.directory(source, True) as root:
            for a, b in zip(m["assets"], self.payloads):
                f = h.file_open(root, a["path"], os.O_WRONLY | os.O_CREAT | os.O_EXCL, True)
                os.write(f, b)
                os.close(f)
            ident = h.manifest_check(m)
            h.private_json(root, ".handoff/plans/" + ident + ".json", m)
        policy = {"schema": 1, "source_machines": ["SOURCE"], "destinations": {"approved": {
            "approved": True, "approval_basis": "synthetic owner approval", "ssh_target": "synthetic.invalid",
            "host_key_alias": "synthetic", "machine": "DESTINATION", "root": self.root}}}
        args = argparse.Namespace(root=source, action="transfer", destination="approved", manifest=ident, execute=False)
        tool = Path(h.__file__).read_bytes()
        return policy, args, tool

    def test_client_refusals_precede_transport(self):
        policy, args, tool = self.client_fixture()
        for reason in ["source", "destination", "tool"]:
            p = copy.deepcopy(policy)
            if reason == "source":
                p["source_machines"] = []
            if reason == "destination":
                p["destinations"]["approved"]["approved"] = False
            def committed(repo, path):
                return h.canonical(p) if path.endswith(".json") else (b"different" if reason == "tool" else tool)
            with self.subTest(reason=reason), patch.object(h, "committed", side_effect=committed), patch.object(h, "machine", return_value="SOURCE"), patch.object(h, "ssh_call") as ssh:
                with self.assertRaises(h.Refusal):
                    h.client(args)
                ssh.assert_not_called()

    def test_client_identity_before_manifest_and_preview_has_no_bytes(self):
        policy, args, tool = self.client_fixture()
        def committed(repo, path):
            return h.canonical(policy) if path.endswith(".json") else tool
        with patch.object(h, "committed", side_effect=committed), patch.object(h, "machine", return_value="SOURCE"):
            with patch.object(h, "ssh_call", return_value={"machine": "WRONG"}) as ssh:
                with self.assertRaises(h.Refusal):
                    h.client(args)
                self.assertEqual(len(ssh.call_args_list), 1)
                self.assertEqual(ssh.call_args.args[2], "identity")
                self.assertEqual(len(ssh.call_args.args), 3)
            def transport(d, source, operation, m=None, input_file=None, extra=None):
                self.assertEqual(d, policy["destinations"]["approved"])
                self.assertEqual(source.encode(), tool)
                self.assertIsNone(input_file)
                return {"machine": "DESTINATION"} if operation == "identity" else [{"index": 0, "final": False}, {"index": 1, "final": False}]
            with patch.object(h, "ssh_call", side_effect=transport) as ssh:
                self.assertFalse(h.client(args)["execute"])
                self.assertEqual([c.args[2] for c in ssh.call_args_list], ["identity", "status"])

    def test_public_error_contains_no_private_details(self):
        out, err = io.StringIO(), io.StringIO()
        with patch.object(h, "client", side_effect=OSError("identifying filename and secret payload")), patch.object(h.sys, "argv", ["data_handoff.py", "transfer"]), contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            self.assertEqual(h.main(), 2)
        self.assertEqual(out.getvalue(), "")
        self.assertNotIn("identifying", err.getvalue())
        self.assertNotIn("secret", err.getvalue())

    def test_client_execute_streams_selected_bytes_and_verify(self):
        policy, args, tool = self.client_fixture()
        def committed(repo, path):
            return h.canonical(policy) if path.endswith(".json") else tool
        calls = []
        def transport(d, source, operation, m=None, input_file=None, extra=None):
            calls.append(operation)
            if operation == "identity":
                self.assertIsNone(m)
                return {"machine": "DESTINATION"}
            self.assertEqual(h.manifest_check(m), args.manifest)
            if operation == "status":
                return [{"index": i, "final": False, "offset": 0, "sha256": h.digest(b"")} for i in range(2)]
            if operation == "put":
                self.assertEqual(input_file.read(), self.payloads[extra["index"]])
                return {"verified": 1}
            self.assertIsNone(input_file)
            return {"verified": True}
        with patch.object(h, "committed", side_effect=committed), patch.object(h, "machine", return_value="SOURCE"), patch.object(h, "ssh_call", side_effect=transport):
            args.execute = True
            self.assertTrue(h.client(args)["verified"])
            self.assertEqual(calls, ["identity", "status", "put", "put", "finalize"])
            calls.clear()
            args.action = "verify"
            self.assertTrue(h.client(args)["verified"])
            self.assertEqual(calls, ["identity", "verify"])

    def test_local_reader_check_requires_marker_bound_to_actual_root(self):
        self.fill()
        policy, args, tool = self.client_fixture()
        policy["source_machines"] = [h.machine()]
        policy["destinations"]["approved"]["machine"] = h.machine()
        args.root = self.root
        args.action = "check-local"
        args.manifest = self.receiver.id
        def committed(repo, path):
            return h.canonical(policy) if path.endswith(".json") else tool
        with patch.object(h, "committed", side_effect=committed):
            with self.assertRaises(FileNotFoundError):
                h.client(args)
            self.receiver.finalize()
            self.assertTrue(h.client(args)["verified"])
            policy["destinations"]["approved"]["root"] = self.root + "-wrong"
            with self.assertRaises(h.Refusal):
                h.client(args)

    def test_foreign_stage_marker_and_forged_ready_marker_refused(self):
        self.receiver.status()
        marker = Path(self.receiver.stage) / "manifest.json"
        marker.write_text("{}")
        with self.assertRaises(h.Refusal):
            self.receiver.status()
        marker.unlink()
        self.fill()
        self.receiver.finalize()
        marker = Path(self.root) / self.receiver.ready
        marker.write_text("{}")
        with self.assertRaises(h.Refusal):
            self.receiver.verify()

    def test_ready_bytes_rehashed_and_staging_cannot_modify_copy(self):
        self.fill()
        self.receiver.finalize()
        a = Path(self.root) / self.m["assets"][0]["path"]
        with self.assertRaises(h.Refusal):
            self.receiver.put(0, 0, h.digest(b""), io.BytesIO(b"abd"))
        self.assertEqual(a.read_bytes(), b"abc")
        self.assertFalse((Path(self.receiver.stage) / "assets" / self.m["assets"][0]["path"]).exists())
        a.chmod(0o600)
        a.write_bytes(b"abd")
        with self.assertRaises(h.Refusal):
            self.receiver.verify()


if __name__ == "__main__":
    unittest.main()
