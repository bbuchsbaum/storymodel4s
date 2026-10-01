#!/usr/bin/env python3
"""Reproduce the 2026-09-04 MASC inventory from external, pinned audit inputs.

Reads archives without extracting them. Emits metadata/counts only, never story
text or judgments. See docs/calibration/2026-09-04-masc-audit.md for acquisition.
This is an inventory, not a PropBank importer or semantic-role adjudicator.
"""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import tarfile
import xml.etree.ElementTree as ET

NS = {"g": "http://www.xces.org/ns/GrAF/1.0/"}
ARCHIVES = {
    "masc.tgz": "206d87511fb27cdbf82cc87982e08351ae84fd98e86d518c15a7461573589d0c",
    "original-propbank.tgz": "685b954342a1095fa6c6cab3202b327323554535f1014b204ce3ecab31e94a63",
    "treebank.tgz": "65ae26e4106cc576ad1291cf86a44048db7ff76fffa052658c45e8b1bb9f6230",
}
PROP_REVISION = "4abade0b53ce4a181e1d98b3518101c1a44d395a"


def sha(data):
    return hashlib.sha256(data).hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def archive(path):
    require(sha(path.read_bytes()) == ARCHIVES[path.name], f"archive checksum: {path.name}")
    with tarfile.open(path) as src:
        members = [m for m in src if m.isfile() and not any(
            p.startswith("._") or p in {".svn", "__MACOSX"} for p in Path(m.name).parts)]
        require(len({m.name for m in members}) == len(members), "duplicate tar member")
        return {m.name: src.extractfile(m).read() for m in members}


def prop_counts(data, unified):
    rows = [s.split() for s in data.decode().splitlines() if s.strip()]
    offset = 1 if unified else 0
    require(bool(rows), "empty PropBank file")
    require(all(len(row) >= 7 + offset for row in rows), "short PropBank row")
    roles = Counter()
    for row in rows:
        for arg in row[6 + offset:]:
            require("-" in arg, "unparsed role field")
            roles[arg.split("-", 1)[1]] += 1
    return {
        "predicate_rows": len(rows),
        "annotated_sentence_indices": len({int(row[offset]) for row in rows}),
        "numbered_argument_records": sum(n for k, n in roles.items() if re.fullmatch(r"ARG[0-9]+", k)),
        "labels": dict(sorted(roles.items())),
        "row_statuses": dict(sorted(Counter(row[2 + offset] for row in rows).items())),
    }


def ptb_leaves(data):
    """Count explicit -NONE- leaves separately; do not guess malformed traces."""
    stack, trees = [], []
    for token in re.findall(r"\(|\)|[^\s()]+", data.decode()):
        if token == "(":
            stack.append([])
        elif token == ")":
            require(bool(stack), "unbalanced PTB close")
            node = stack.pop()
            (stack[-1] if stack else trees).append(node)
        else:
            require(bool(stack), "PTB token outside tree")
            stack[-1].append(token)
    require(not stack and bool(trees), "unclosed or empty PTB")

    def leaves(node):
        if len(node) == 2 and all(isinstance(x, str) for x in node):
            return [tuple(node)]
        require(bool(node) and isinstance(node[0], str), "PTB node lacks label")
        require(all(isinstance(child, list) for child in node[1:]),
                "PTB branch mixes label with multiple atom children or atom and subtree")
        return [leaf for child in node[1:] for leaf in leaves(child)]

    # Penn exports permit one unlabeled outer wrapper, not unlabeled inner nodes.
    return [leaves(tree[0] if len(tree) == 1 and isinstance(tree[0], list) else tree)
            for tree in trees]


def skeleton_rows(data):
    rows, issues = [], []
    sentence = []
    for line in data.decode().splitlines() + [""]:
        if line.strip():
            fields = line.split()
            require(len(fields) >= 8, "short skeleton row")
            require(int(fields[2]) == len(sentence), "noncontiguous skeleton token index")
            if fields[3] != "[WORD]":
                issues.append({"sentence_index": len(rows), "token_index": len(sentence),
                               "reason": "word placeholder is not a separate column"})
            sentence.append(fields)
        elif sentence:
            rows.append(sentence)
            sentence = []
    require(bool(rows), "empty token skeleton")
    return rows, issues


def compare_tree(data, skeleton, skeleton_issues):
    try:
        trees = ptb_leaves(data)
    except ValueError as error:
        return {"sha256": sha(data), "parse_status": "refused",
                "reason": str(error), "pos_and_count_join_passes": False}
    visible = [[leaf for leaf in tree if leaf[0] != "-NONE-"] for tree in trees]
    require(len(visible) == len(skeleton), "tree/skeleton sentence count differs")
    count_bad, pos_bad = [], []
    invalid_sentences = {issue["sentence_index"] for issue in skeleton_issues}
    for index, (leaves, rows) in enumerate(zip(visible, skeleton)):
        if index in invalid_sentences:
            continue
        if len(leaves) != len(rows):
            count_bad.append({"sentence_index": index, "tree_tokens": len(leaves), "skeleton_tokens": len(rows)})
        elif [leaf[0] for leaf in leaves] != [row[4] for row in rows]:
            pos_bad.append(index)
    return {
        "sha256": sha(data), "parse_status": "balanced_shape_only",
        "sentences": len(trees),
        "leaves_including_empty": sum(map(len, trees)),
        "leaves_without_explicit_NONE": sum(map(len, visible)),
        "skeleton_token_count_mismatches": count_bad,
        "skeleton_pos_mismatch_sentences_with_equal_counts": pos_bad,
        "uncompared_malformed_skeleton_sentences": sorted(invalid_sentences),
        "pos_and_count_join_passes": not count_bad and not pos_bad and not skeleton_issues,
    }


def audit(root, manifest):
    masc = archive(root / "masc.tgz")
    original = archive(root / "original-propbank.tgz")
    treebank = archive(root / "treebank.tgz")
    require(manifest["propbank_revision"] == PROP_REVISION, "wrong PropBank revision")
    pb = {}
    for entry in manifest["propbank_files"]:
        path = Path(entry["path"])
        require(not path.is_absolute() and ".." not in path.parts, "unsafe input path")
        data = (root / "propbank" / path).read_bytes()
        require(sha(data) == entry["sha256"], f"PropBank checksum: {path}")
        if path.suffix == ".prop":
            require(path.stem not in pb, "duplicate PropBank document")
            pb[path.stem] = {"path": str(path), "sha256": sha(data), **prop_counts(data, True)}
    require(bool(pb), "no PropBank documents")
    original_pb = {}
    for name, data in original.items():
        if name.endswith(".prop"):
            key = Path(name).stem
            require(key not in original_pb, "duplicate original PropBank document")
            try:
                counts = {"parse_status": "parsed", **prop_counts(data, False)}
            except ValueError as error:
                counts = {"parse_status": "refused", "reason": str(error)}
            original_pb[key] = {"path": name, "sha256": sha(data), **counts}
    docs = {}
    for path, data in masc.items():
        if not path.startswith("MASC-3.0.0/data/") or not path.endswith(".hdr"):
            continue
        p = Path(path)
        key = p.stem
        require(key not in docs, "duplicate MASC document")
        hdr = ET.fromstring(data)
        extent = hdr.find(".//g:extent", NS)
        text_path = str(p.with_suffix(".txt"))
        require(text_path in masc, f"no source for {path}")
        fn_path = str(p.with_name(key + "-fn.xml"))
        fn = ET.fromstring(masc[fn_path]) if fn_path in masc else None
        row = {
            "id": key, "genre": p.parts[3], "header_path": path,
            "header_sha256": sha(data), "source_sha256": sha(masc[text_path]),
            "header_word_count": int(extent.attrib["count"]) if extent is not None else None,
            "propbank_unified": pb.get(key),
            "propbank_original_exact_basename_match": original_pb.get(key),
            "framenet_graf_path": fn_path if fn is not None else None,
            "framenet_graf_annotation_records": len(fn.findall("g:a", NS)) if fn is not None else None,
        }
        if row["genre"] in {"fiction", "ficlets"}:
            source = hdr.find(".//g:sourceDesc", NS)
            row["source_metadata"] = [
                {"field": x.tag.split("}")[-1], "text": x.text, "attributes": x.attrib}
                for x in source
            ]
        docs[key] = row
    require(bool(docs), "no MASC documents")
    ficlet_ids, ficlet_refs, ficlet_files = [], [], []
    for path, data in masc.items():
        if "/data/written/ficlets/" in path and path.endswith(".txt"):
            text = data.decode()
            ids = re.findall(r"^ID: (\d+)\s*$", text, re.M)
            refs = [n for field in re.findall(r"^(?:Prequels|Sequels): ([^\n]+)", text, re.M)
                    for n in re.findall(r"\d+", field)]
            ficlet_ids.extend(ids)
            ficlet_refs.extend(refs)
            ficlet_files.append({"file": path, "id_records": len(ids)})
    narrative = [d for d in docs.values() if d["genre"] in {"fiction", "ficlets"}]
    prefix = "MASC-3.0.0/data/written/fiction/lw1"
    skel, skel_issues = skeleton_rows((root / "propbank/data/oanc/masc/written/00/lw1.gold_skel").read_bytes())
    bundled = masc["MASC-3.0.0/original-annotations/Penn_Treebank/lw1.mrg"]
    separate = treebank["data/written/fiction/lw1.mrg"]
    require(bundled == original["Propbank/Penn_Treebank-orig/data/written/lw1.mrg"], "original/bundled tree changed")
    return {
        "audit_date": "2026-09-04", "purpose": "external corpus inventory; no calibration judgments",
        "input_manifest_canonical_json_sha256": sha(json.dumps(manifest, sort_keys=True).encode()),
        "counts": {
            "masc_documents": len(docs), "genres": dict(sorted(Counter(d["genre"] for d in docs.values()).items())),
            "unified_propbank_documents": len(pb), "original_propbank_documents": len(original_pb),
            "unified_exact_basename_matches": len(set(pb) & set(docs)),
            "unified_exact_match_genres": dict(sorted(Counter(docs[k]["genre"] for k in pb if k in docs).items())),
            "framenet_graf_documents": sum(d["framenet_graf_path"] is not None for d in docs.values()),
            "fiction_and_ficlets_files": len(narrative),
            "fiction_and_ficlets_with_unified_propbank": sum(d["propbank_unified"] is not None for d in narrative),
            "fiction_and_ficlets_with_framenet_graf": sum(d["framenet_graf_path"] is not None for d in narrative),
        },
        "unified_unmatched_basenames": sorted(set(pb) - set(docs)),
        "original_unmatched_basenames": sorted(set(original_pb) - set(docs)),
        "original_propbank_refusals": {k: v for k, v in original_pb.items() if v["parse_status"] == "refused"},
        "ficlet_structure": {
            "files": sorted(ficlet_files, key=lambda x: x["file"]),
            "id_records": len(ficlet_ids), "unique_ids": len(set(ficlet_ids)),
            "prequel_sequel_numeric_references": len(ficlet_refs),
            "references_inside_sample": sum(ref in set(ficlet_ids) for ref in ficlet_refs),
            "references_outside_sample": sum(ref not in set(ficlet_ids) for ref in ficlet_refs),
            "independent_narratives": None,
        },
        "lw1_coordinates": {
            "anc_sentence_regions": len(ET.fromstring(masc[prefix + "-s.xml"]).findall("g:region", NS)),
            "graf_ptb_token_annotations": len(ET.fromstring(masc[prefix + "-ptbtok.xml"]).findall("g:a", NS)),
            "skeleton_sentences": len(skel), "skeleton_physical_token_rows": sum(map(len, skel)),
            "skeleton_malformed_rows": skel_issues,
            "bundled_tree": compare_tree(bundled, skel, skel_issues),
            "separate_tree": compare_tree(separate, skel, skel_issues),
        },
        "documents": sorted(docs.values(), key=lambda d: d["id"]),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("external_inputs", type=Path)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    result = audit(args.external_inputs, json.loads(args.manifest.read_text()))
    args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n")
    print(json.dumps(result["counts"], sort_keys=True))
    print("Audit completed; counts do not establish semantic correctness or story independence.")


if __name__ == "__main__":
    main()
