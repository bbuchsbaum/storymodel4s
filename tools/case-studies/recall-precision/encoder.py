"""Local sentence encoder (all-MiniLM-L6-v2 ONNX, pinned in tools/model-weights.json).

Mean pooling over the attention mask, then L2 normalization, as in sentence-transformers.
Embeddings are cached by content hash so reruns make no new encoder calls.
"""

from __future__ import annotations

import hashlib
from pathlib import Path

import numpy as np

from data import DATA, OUT

MODEL = DATA / "models" / "onnx" / "model.onnx"
TOKENIZER = DATA / "models" / "onnx" / "tokenizer.json"


class Encoder:
    def __init__(self, max_len: int = 128):
        import onnxruntime as ort
        from tokenizers import Tokenizer

        self.tok = Tokenizer.from_file(str(TOKENIZER))
        self.tok.enable_truncation(max_len)
        self.tok.enable_padding()
        self.sess = ort.InferenceSession(str(MODEL), providers=["CPUExecutionProvider"])
        self.cache_dir = OUT / "embedding-cache"
        self.cache_dir.mkdir(parents=True, exist_ok=True)

    def _raw(self, texts: list[str]) -> np.ndarray:
        enc = self.tok.encode_batch(texts)
        ids = np.array([e.ids for e in enc], dtype=np.int64)
        mask = np.array([e.attention_mask for e in enc], dtype=np.int64)
        tt = np.zeros_like(ids)
        (h,) = self.sess.run(
            None, {"input_ids": ids, "attention_mask": mask, "token_type_ids": tt}
        )
        m = mask[..., None].astype(np.float32)
        v = (h * m).sum(1) / np.maximum(m.sum(1), 1e-9)
        return v / np.linalg.norm(v, axis=1, keepdims=True)

    def encode(self, texts: list[str], batch: int = 64) -> np.ndarray:
        key = hashlib.sha256("\x1f".join(texts).encode()).hexdigest()[:24]
        path = self.cache_dir / f"{key}.npy"
        if path.exists():
            return np.load(path)
        order = sorted(range(len(texts)), key=lambda i: len(texts[i]))
        srt = [texts[i] for i in order]
        parts = [self._raw(srt[i : i + batch]) for i in range(0, len(srt), batch)]
        out = np.empty((len(texts), parts[0].shape[1]), dtype=np.float32)
        out[order] = np.vstack(parts)
        np.save(path, out)
        return out
