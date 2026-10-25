#!/usr/bin/env python3
"""Pinned local ONNX cosine diagnostic, independent of production mapping admission.

No input truncation, query expansion or inferred event links. Model-specific pooling
and length limits are explicit. MiniLM results must agree with the existing Scala
provider on the unchanged baseline before using this backend for comparisons.
"""
import argparse
import importlib.metadata
import json
from pathlib import Path
import platform

from claim_assessment_probe import read, sha, write


def pool(hidden, mask, method):
    import numpy as np
    if hidden.ndim != 3 or mask.shape != hidden.shape[:2] or not np.isfinite(hidden).all():
        raise ValueError("invalid hidden states")
    if method == "cls":
        value = hidden[:, 0, :]
    elif method == "mean":
        count = mask.sum(axis=1, keepdims=True)
        if (count <= 0).any():
            raise ValueError("empty mask")
        value = (hidden * mask[:, :, None]).sum(axis=1) / count
    else:
        raise ValueError("unknown pooling")
    norm = np.linalg.norm(value, axis=1, keepdims=True)
    if not np.isfinite(norm).all() or (norm <= 0).any():
        raise ValueError("invalid vector norm")
    return value / norm


def run(freeze_path, output):
    import numpy as np
    import onnxruntime as ort
    from tokenizers import Tokenizer
    freeze = read(freeze_path)
    if freeze["harnessSha256"] != sha(__file__):
        raise ValueError("harness changed")
    for path, digest in freeze["files"].items():
        if sha(path) != digest:
            raise ValueError("frozen artifact changed")
    for key in ("input", "model", "tokenizer"):
        if str(Path(freeze[key]).absolute()) not in freeze["files"]:
            raise ValueError("consumed artifact not frozen")
    if output.exists():
        raise ValueError("execution already exists")
    records = read(freeze["input"])["records"]
    if not records or len({r["id"] for r in records}) != len(records):
        raise ValueError("empty or duplicate input")
    tokenizer = Tokenizer.from_file(freeze["tokenizer"])
    tokenizer.no_truncation(); tokenizer.no_padding()
    options = ort.SessionOptions(); options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    session = ort.InferenceSession(freeze["model"], sess_options=options,
                                   providers=["CPUExecutionProvider"])
    names = {i.name for i in session.get_inputs()}
    if not names <= {"input_ids", "attention_mask", "token_type_ids"}:
        raise ValueError("unknown model input")
    outputs = []
    for row in records:
        if row["role"] not in ("query", "document") or not row["text"].strip():
            raise ValueError("invalid input")
        encoding = tokenizer.encode(row["text"])
        tokens = len(encoding.ids)
        if tokens > freeze["maxTokens"]:
            result = dict(id=row["id"], status="too-long", vector=None, tokens=tokens)
        else:
            values = dict(input_ids=encoding.ids, attention_mask=encoding.attention_mask,
                          token_type_ids=encoding.type_ids)
            inputs = {k: np.asarray([values[k]], dtype=np.int64) for k in names}
            hidden = session.run([freeze["outputName"]], inputs)[0]
            vector = pool(hidden, np.asarray([encoding.attention_mask]), freeze["pooling"])[0]
            if vector.shape != (freeze["dimension"],):
                raise ValueError("wrong embedding dimension")
            result = dict(id=row["id"], status="observed", vector=vector.tolist(), tokens=tokens)
        outputs.append(result)
    write(output, dict(inputSha256=sha(freeze["input"]), freezeSha256=sha(freeze_path),
          model=freeze["coordinate"], runtime=dict(python=platform.python_version(),
          packages={n:importlib.metadata.version(n) for n in ("onnxruntime", "numpy", "tokenizers")}),
          records=outputs))
    print(f"Recorded {len(outputs)} local embedding attempts")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("freeze", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    run(args.freeze, args.output)
