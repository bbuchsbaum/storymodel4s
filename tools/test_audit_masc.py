"""Falsifiers for the MASC audit's parsing and compatibility claims."""
import importlib.util
from pathlib import Path
import unittest
import io
import json
import sys


def load(source=None):
    path = Path(__file__).with_name("audit-masc.py")
    spec = importlib.util.spec_from_file_location("audit_masc", path)
    module = importlib.util.module_from_spec(spec)
    if source is None:
        spec.loader.exec_module(module)
    else:
        exec(compile(source, str(path), "exec"), module.__dict__)
    return module


audit = load()


class AuditCourts(unittest.TestCase):
    def test_outer_wrapper_is_valid_but_inner_unlabeled_node_is_not(self):
        self.assertEqual(audit.ptb_leaves(b"( (S (NN token)))"), [[("NN", "token")]])
        with self.assertRaises(ValueError):
            audit.ptb_leaves(b"( (S ((NN token))))")

    def test_malformed_empty_subject_cannot_disappear_from_leaf_count(self):
        with self.assertRaises(ValueError):
            audit.ptb_leaves(b"( (S (NP-SBJ -NONE- *-1)))")

    def test_merged_placeholder_column_is_retained_as_a_failure(self):
        rows, issues = audit.skeleton_rows(b"doc 0 0 [WORD]NP-SBJ * - - *\n")
        self.assertEqual(len(rows), 1)
        self.assertEqual(len(issues), 1)
        self.assertEqual(issues[0]["token_index"], 0)

    def test_malformed_sentence_cannot_vanish_from_join_verdict(self):
        rows, issues = audit.skeleton_rows(b"doc 0 0 [WORD]NP-SBJ * - - *\n")
        self.assertFalse(audit.compare_tree(b"( (S (NN token)))", rows, issues)["pos_and_count_join_passes"])
        valid, issues = audit.skeleton_rows(b"doc 0 0 [WORD] NN * - - *\n")
        self.assertTrue(audit.compare_tree(b"( (S (NN token)))", valid, issues)["pos_and_count_join_passes"])

    def test_numbered_arguments_do_not_include_modifiers_or_rel_links(self):
        row = b"0 1 gold run-v run.01 ----- 0:1-ARG0 1:0-rel 2:1-ARGM-TMP\n"
        self.assertEqual(audit.prop_counts(row, False)["numbered_argument_records"], 1)
        with self.assertRaises(ValueError):
            audit.prop_counts(b"", False)


def mutations():
    global audit
    source = Path(__file__).with_name("audit-masc.py").read_text()
    strict = '''        require(all(isinstance(child, list) for child in node[1:]),
                "PTB branch mixes label with multiple atom children or atom and subtree")
        return [leaf for child in node[1:] for leaf in leaves(child)]'''
    variants = {
        "silently_drop_malformed_tree_atoms": (
            strict,
            "        return [leaf for child in node[1:] if isinstance(child, list) for leaf in leaves(child)]"),
        "ignore_merged_skeleton_column": ('if fields[3] != "[WORD]":', "if False:"),
        "omit_malformed_sentence_from_verdict": (
            "not count_bad and not pos_bad and not skeleton_issues",
            "not count_bad and not pos_bad"),
    }
    result_rows = []
    for name, (before, after) in variants.items():
        if source.count(before) != 1:
            raise ValueError("mutation target is not unique: " + name)
        audit = load(source.replace(before, after))
        suite = unittest.defaultTestLoader.loadTestsFromTestCase(AuditCourts)
        result = unittest.TextTestRunner(stream=io.StringIO()).run(suite)
        if result.errors or not result.failures or result.testsRun != 5:
            raise ValueError("mutation was not killed by an assertion: " + name)
        result_rows.append({"mutant": name, "tests_run": result.testsRun,
                            "failed_courts": [test.id() for test, _ in result.failures],
                            "errors": len(result.errors)})
    audit = load()
    print(json.dumps({"compiling_mutants_killed": len(result_rows), "results": result_rows}, indent=2))


if __name__ == "__main__":
    if sys.argv[1:] == ["--mutations"]:
        mutations()
    else:
        unittest.main()
