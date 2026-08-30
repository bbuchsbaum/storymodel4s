"""Generate independent MiniLM tokenizer/vector goldens with Python runtimes.

The Scala acceptance test uses DJL tokenizers plus ONNX Runtime Java. This script intentionally
uses Hugging Face tokenizers plus ONNX Runtime Python so the committed values cross implementations.
"""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer


CASES = {
    "river": "The warriors went down the river.",
    "negation": "He did not feel sick.",
    "war-cries": "They heard war cries behind them.",
}


def generate(model: Path, tokenizer_path: Path, output: Path) -> None:
    tokenizer = Tokenizer.from_file(str(tokenizer_path))
    tokenizer.no_padding()
    tokenizer.no_truncation()
    encodings = [tokenizer.encode(text, add_special_tokens=True) for text in CASES.values()]
    width = max(len(encoding.ids) for encoding in encodings)

    def padded(values: list[int], fill: int) -> list[int]:
        return values + [fill] * (width - len(values))

    ids = np.asarray([padded(e.ids, 0) for e in encodings], dtype=np.int64)
    masks = np.asarray([padded(e.attention_mask, 0) for e in encodings], dtype=np.int64)
    types = np.asarray([padded(e.type_ids, 0) for e in encodings], dtype=np.int64)
    session = ort.InferenceSession(str(model), providers=["CPUExecutionProvider"])
    hidden = session.run(
        ["last_hidden_state"],
        {"input_ids": ids, "attention_mask": masks, "token_type_ids": types},
    )[0]

    lines = [
        "# format: case-id<TAB>token-ids<TAB>384-vector",
        "# model: sentence-transformers/all-MiniLM-L6-v2@1110a243fdf4706b3f48f1d95db1a4f5529b4d41",
        "# model-sha256: 6fd5d72fe4589f189f8ebc006442dbb529bb7ce38f8082112682524616046452",
        "# tokenizer-sha256: be50c3628f2bf5bb5e3a7f17b1f74611b2561a3a27eeab05e5aa30f411572037",
        f"# reference: onnxruntime={ort.__version__} tokenizers=0.21.4 numpy={np.__version__}",
        "# pooling: attention-mask mean in float64, then L2 normalize",
    ]
    for row, (case_id, _) in enumerate(CASES.items()):
        active = masks[row].astype(bool)
        pooled = hidden[row, active, :].astype(np.float64).mean(axis=0)
        pooled /= np.linalg.norm(pooled)
        token_values = ",".join(str(value) for value in encodings[row].ids)
        vector_values = ",".join(format(value, ".17g") for value in pooled)
        lines.append(f"{case_id}\t{token_values}\t{vector_values}")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text("\n".join(lines) + "\n", encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--tokenizer", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    generate(args.model, args.tokenizer, args.output)
