"""Integrity courts using admitted, original synthetic packets; no corpus access."""
import copy
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location(
    "baseline", os.environ.get("BASELINE_MODULE", str(HERE / "freeze_baseline.py")))
baseline = importlib.util.module_from_spec(spec)
spec.loader.exec_module(baseline)


class BaselineSuite(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.fixture = baseline.load(HERE / "fixtures/baseline-miniatures.json")
        f = self.fixture
        events = f["textSource"]["events"]
        packets = f["recallPackets"]
        self.inv = {"schema": "storymodel4s.bench.baseline-inventory/v1",
                    "transcriptSha256": baseline.digest(" ".join(p["text"] for p in packets).encode()),
                    "sourceFingerprint": baseline.digest(baseline.canonical(events)),
                    "units": [], "words": [], "targets": [{"id": "seg:" + e["id"]} for e in events],
                    "rowLoci": []}
        cursor = 0
        for ordinal, p in enumerate(packets):
            start, indices = cursor, []
            for word in p["text"].split():
                index = len(self.inv["words"])
                indices.append(index)
                self.inv["words"].append({"id": f"synthetic-word-{index}", "index": index,
                                           "startUtf16": cursor, "endUtf16": cursor + len(word),
                                           "onsetSeconds": p["onsetSeconds"]})
                cursor += len(word) + 1
            self.inv["units"].append({"id": p["id"], "ordinal": ordinal, "startUtf16": start,
                                       "endUtf16": cursor - 1, "wordIndices": indices,
                                       "textSha256": baseline.digest(p["text"].encode()),
                                       "reportTextSha256": baseline.digest(p["text"].encode())})
        # Seven admitted coordinate rows; e4 intentionally lacks media support.
        annotation = "row\tstart\tend\n"
        for locus in f["annotatedVideoSource"]["loci"]:
            if locus["part"] is None:
                continue
            number = len(self.inv["rowLoci"]) + 1
            row = {"row": number, **{k: locus[k] for k in ("part", "startTick", "endTick")},
                   "kind": "instant" if locus["startTick"] == locus["endTick"] else "extent"}
            self.inv["rowLoci"].append(row)
            annotation += f"{number}\t{int(row['startTick']) // 10}\t{int(row['endTick']) // 10}\n"
        repair = {"presentationEditionIdentity": {"parts": [
            {"partId": p["part"], "video": {"ticksPerSecond": int(p["ticksPerSecond"])}}
            for p in f["annotatedVideoSource"]["parts"]]},
            "annotationToPlaybackCrosswalk": {"runs": [
                {"mapping": "identity", "formula": "playbackTicks = rawAnnotationSeconds * ticksPerSecond",
                 "annotationStartSeconds": 0, "playbackStartTicks": 0,
                 "partId": part, "annotationRows": rows}
                for part, rows in [("part-a", "1-3"), ("part-b", "4-7")]]}}
        values = {"annotation": annotation.encode(), "recall": baseline.canonical(packets),
                  "partition": baseline.canonical({"development": ["synthetic"], "untouchedTest": ["sealed"]}),
                  "model": b"synthetic model, not ONNX", "tokenizer": b"synthetic tokenizer",
                  "repairRecord": baseline.canonical(repair)}
        values["recallLineage"] = baseline.canonical({"sources": [
            {"localCsvSha256": baseline.digest(values["recall"])}]})
        self.inputs = {}
        for key, data in values.items():
            path = self.root / (key + ".fixture")
            path.write_bytes(data)
            self.inputs[key] = baseline.file_record(path, "data", self.root)
        self.inv["annotationSha256"] = self.inputs["annotation"]["sha256"]
        self.inv["recallSha256"] = self.inputs["recall"]["sha256"]
        # Synthetic pin substitution is explicit and scoped to this integrity court.
        for name, key in [("MODEL", "model"), ("TOKENIZER", "tokenizer")]:
            pin = patch.object(baseline, name, self.inputs[key]["sha256"])
            pin.start()
            self.addCleanup(pin.stop)
        self.paths = []
        for number in (1, 2):
            directory = self.root / f"run-{number}"
            directory.mkdir()
            paths = {k: directory / v for k, v in baseline.ARTIFACTS.items()}
            baseline.write_json(paths["inventory"], self.inv)
            chosen = ["e2", "e7", "e7", "e8", None, None, "e3", "e2"]
            chosen = ["seg:" + target if target else None for target in chosen]
            report = "unit\trecallText\tmapAnchor\n"
            for i, (p, target) in enumerate(zip(packets, chosen)):
                report += f"{i}\t{p['text']}\t{target or 'none'}\n"
            paths["report"].write_text(report)
            baseline.write_json(paths["posterior"], {
                "schema": "storymodel4s.bench.recall-to-video.posterior", "schemaVersion": 1,
                "units": [{"unit": i, "argmax": {"ref": "seg:" + p["admissible"][0]}
                           if p["admissible"][0] != "external" else None,
                           "decoded": {"ref": target} if target else None}
                          for i, (p, target) in enumerate(zip(packets, chosen))]})
            baseline.write_json(paths["stages"], {
                "schema": "storymodel4s.bench.stage-trace/v1",
                "reportSha256": baseline.file_hash(paths["report"]),
                "recallChecksum": self.inv["transcriptSha256"],
                "sourceFingerprint": self.inv["sourceFingerprint"],
                "units": [{"unit": i, "unitId": p["id"], "finalAnchor": target}
                          for i, (p, target) in enumerate(zip(packets, chosen))]})
            # Minimal wire documents test identity joins, not Scala domain admission.
            baseline.write_json(paths["voyage"], {
                "schema": "storymodel4s.view.recall-voyage", "schemaVersion": 1, "coding": None,
                "provenance": {"sourceChecksum": self.inv["sourceFingerprint"]},
                "units": [{"id": p["id"], "ordinal": i, "text": p["text"]}
                          for i, p in enumerate(packets)],
                "rows": [{"unit": p["id"]} for p in packets],
                "decisions": [{"unit": p["id"], "anchor": {"type": "Segment", "id": target[4:]} if target else None}
                              for p, target in zip(packets, chosen)],
                "timeline": {"nodes": [{"ref": {"type": "Segment", "id": e["id"]}} for e in events]}})
            (directory / "run.log").write_text("Synthetic command receipt\nCAPTURE_EXIT=0\n")
            self.paths.append(paths)
        self.manifest = {"schema": baseline.SCHEMA, "participant": "synthetic", "inputs": self.inputs,
                         "configuration": {"environment": copy.deepcopy(baseline.SETTINGS),
                                           "unset": baseline.UNSET.copy(), "provider": "pinned-local-onnx",
                                           "responseCache": "none; local model recomputed"},
                         "inventory": self.inv, "summary": baseline.inspect_run(self.paths[0]), "runs": []}
        for paths in self.paths:
            self.manifest["runs"].append({
                "exitCode": 0, "command": baseline.runner_commands(
                    self.root / "annotation.fixture", self.root / "recall.fixture", paths),
                "log": baseline.file_record(paths["report"].parent / "run.log", "data", self.root),
                "artifacts": {k: baseline.file_record(p, "data", self.root) for k, p in paths.items()}})

    def verify(self):
        return baseline.verify(self.manifest, self.root, self.root)

    def change_artifact(self, key, value, run=0):
        """Refresh the byte receipt, so semantic identity checks must catch the defect."""
        baseline.write_json(self.paths[run][key], value)
        self.manifest["runs"][run]["artifacts"][key] = baseline.file_record(
            self.paths[run][key], "data", self.root)

    def test_complete_synthetic_replay_preserves_distinct_outcomes(self):
        summary = self.verify()
        self.assertEqual(summary["units"], 8)
        self.assertEqual(summary["outcomes"], {"reportRows": 8, "sourceChosen": 6, "withoutSourceChoice": 2})
        self.assertIsNone(self.inv["words"][self.inv["units"][2]["wordIndices"][0]]["onsetSeconds"])
        post = baseline.load(self.paths[0]["posterior"])["units"][2]
        self.assertNotEqual(post["argmax"]["ref"], post["decoded"]["ref"])

    def test_independent_fixture_has_reversal_revisit_and_partial_support(self):
        f = self.fixture
        ranks = {e["id"]: e["order"] for e in f["textSource"]["events"]}
        path = [ranks[e] for e in f["expectedCases"]["clearPath"]]
        self.assertEqual(sum(b < a for a, b in zip(path, path[1:])), 1)
        self.assertEqual(sum(b > a for a, b in zip(path, path[1:])), 2)
        self.assertEqual(f["recallPackets"][0]["admissible"], f["recallPackets"][-1]["admissible"])
        video = f["annotatedVideoSource"]
        members = video["partialGroup"]["members"]
        support = [l["part"] is not None for l in video["loci"] if l["event"] in members]
        self.assertTrue(any(support))
        self.assertFalse(all(support))
        self.assertFalse(video["partialGroup"]["completePlaybackSupport"])
        self.assertEqual(len(f["recallPackets"][6]["admissible"]), 2)

    def test_dropped_duplicate_reordered_posterior_units_refused(self):
        original = baseline.load(self.paths[0]["posterior"])
        for indices in [list(range(7)), [0, 0, *range(2, 8)], [1, 0, *range(2, 8)]]:
            with self.subTest(indices=indices):
                post = copy.deepcopy(original)
                post["units"] = [post["units"][i] for i in indices]
                self.change_artifact("posterior", post)
                with self.assertRaisesRegex(ValueError, "posterior unit accounting mismatch"):
                    self.verify()

    def test_foreign_unit_source_report_receipts_refused(self):
        original = baseline.load(self.paths[0]["stages"])
        for field in ["sourceFingerprint", "recallChecksum", "reportSha256"]:
            with self.subTest(field=field):
                stage = copy.deepcopy(original)
                stage[field] = "foreign"
                self.change_artifact("stages", stage)
                with self.assertRaisesRegex(ValueError, "foreign .* receipt"):
                    self.verify()
        stage = copy.deepcopy(original)
        stage["units"][0]["unitId"] = "p2"
        self.change_artifact("stages", stage)
        with self.assertRaisesRegex(ValueError, "stage unit identity mismatch"):
            self.verify()

    def test_changed_input_and_configuration_refused(self):
        self.manifest["configuration"]["environment"]["STORYMODEL4S_PRIOR_SCALE"] = "0"
        with self.assertRaisesRegex(ValueError, "changed baseline configuration"):
            self.verify()
        self.manifest["configuration"]["environment"] = copy.deepcopy(baseline.SETTINGS)
        (self.root / "recall.fixture").write_bytes(b"changed")
        with self.assertRaisesRegex(ValueError, "changed artifact"):
            self.verify()

    def test_unused_source_row_change_refused_even_with_refreshed_hashes(self):
        # e1 is not selected by any packet. Full row coverage must still bind it.
        self.inv["rowLoci"][0]["endTick"] = "11"
        for number in (0, 1):
            self.change_artifact("inventory", self.inv, number)
        self.manifest["summary"] = baseline.inspect_run(self.paths[0])
        with self.assertRaisesRegex(ValueError, "complete row-locus oracle mismatch"):
            self.verify()

    def test_two_run_byte_difference_refused(self):
        # A changed score does not change units or chosen anchors: parity must catch it.
        post = baseline.load(self.paths[1]["posterior"])
        post["units"][0]["argmax"]["mass"] = 0.123
        self.change_artifact("posterior", post, 1)
        with self.assertRaisesRegex(ValueError, "repeat run artifact bytes differ"):
            self.verify()

    def test_complete_word_membership_required(self):
        self.inv["units"][0]["wordIndices"].pop()
        self.change_artifact("inventory", self.inv)
        with self.assertRaisesRegex(ValueError, "unresolved word membership"):
            baseline.inspect_run(self.paths[0])

    def test_missing_artifact_failed_exit_and_foreign_command_refused(self):
        run = self.manifest["runs"][0]
        run["exitCode"] = 1
        with self.assertRaisesRegex(ValueError, "incomplete/failed run"):
            self.verify()
        run["exitCode"] = 0
        run["command"][-1] += " altered"
        with self.assertRaisesRegex(ValueError, "foreign command receipt"):
            self.verify()
        self.paths[0]["voyage"].unlink()
        with self.assertRaisesRegex(ValueError, "missing artifact"):
            self.verify()

    def test_test_partition_refused(self):
        self.manifest["participant"] = "sealed"
        with self.assertRaisesRegex(ValueError, "non-development participant"):
            self.verify()

    def test_duplicate_run_receipt_refused(self):
        self.manifest["runs"][1] = copy.deepcopy(self.manifest["runs"][0])
        with self.assertRaisesRegex(ValueError, "distinct run artifact and log paths required"):
            self.verify()

    def test_empty_foreign_or_gold_voyage_refused(self):
        original = baseline.load(self.paths[0]["voyage"])
        for value in [{}, 42, {"coding": {"name": "unexpected"}}]:
            with self.subTest(value=value):
                self.change_artifact("voyage", value)
                with self.assertRaisesRegex(ValueError, "unknown voyage schema"):
                    self.verify()
        original["coding"] = {"name": "unexpected"}
        self.change_artifact("voyage", original)
        with self.assertRaisesRegex(ValueError, "unexpected independent coding"):
            self.verify()
        original["coding"] = None
        original["rows"].pop()
        self.change_artifact("voyage", original)
        with self.assertRaisesRegex(ValueError, "voyage unit accounting mismatch"):
            self.verify()

    def test_path_escape_refused(self):
        record = copy.deepcopy(self.inputs["recall"])
        record["path"] = "../outside"
        with self.assertRaisesRegex(ValueError, "unsafe artifact path"):
            baseline.check_file(record, {"data": self.root})

    def test_oracle_refuses_unimplemented_offset(self):
        repair = baseline.load(self.root / "repairRecord.fixture")
        repair["annotationToPlaybackCrosswalk"]["runs"][1]["playbackStartTicks"] = 3
        with self.assertRaisesRegex(ValueError, "oracle refuses unsupported formula"):
            baseline.annotation_oracle(self.root / "annotation.fixture", repair)


if __name__ == "__main__":
    unittest.main()
