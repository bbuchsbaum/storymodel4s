"""Generate the tiny deterministic ONNX/tokenizer pair used by offline adapter tests.

This fixture proves tensor names, batching, attention-mask pooling, and L2 normalization. It is not
a learned model and must never be used as benchmark evidence.
"""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper
from tokenizers import Tokenizer
from tokenizers.models import WordLevel
from tokenizers.pre_tokenizers import Whitespace
from tokenizers.processors import TemplateProcessing


def generate(output: Path) -> None:
    output.mkdir(parents=True, exist_ok=True)
    weights = np.asarray(
        [
            [0.0, 0.0, 0.0, 0.0],
            [1.0, 1.0, 1.0, 1.0],
            [1.0, 0.0, 0.0, 0.0],
            [0.0, 1.0, 0.0, 0.0],
            [0.0, 0.0, 1.0, 0.0],
            [0.0, 0.0, 0.0, 1.0],
            [1.0, 1.0, 0.0, 0.0],
            [0.0, 1.0, 1.0, 0.0],
        ],
        dtype=np.float32,
    )
    graph = helper.make_graph(
        [helper.make_node("Gather", ["weights", "input_ids"], ["last_hidden_state"])],
        "storymodel4s-test-sentence-encoder",
        [
            helper.make_tensor_value_info("input_ids", TensorProto.INT64, ["batch", "tokens"]),
            helper.make_tensor_value_info(
                "attention_mask", TensorProto.INT64, ["batch", "tokens"]
            ),
            helper.make_tensor_value_info(
                "token_type_ids", TensorProto.INT64, ["batch", "tokens"]
            ),
        ],
        [
            helper.make_tensor_value_info(
                "last_hidden_state", TensorProto.FLOAT, ["batch", "tokens", 4]
            )
        ],
        [numpy_helper.from_array(weights, "weights")],
    )
    model = helper.make_model(
        graph,
        producer_name="storymodel4s-test-fixture",
        opset_imports=[helper.make_opsetid("", 17)],
    )
    model.ir_version = 9
    onnx.checker.check_model(model)
    onnx.save(model, output / "model.onnx")

    vocabulary = {
        "[PAD]": 0,
        "[UNK]": 1,
        "[CLS]": 2,
        "[SEP]": 3,
        "hello": 4,
        "world": 5,
        "ghost": 6,
        "warrior": 7,
    }
    tokenizer = Tokenizer(WordLevel(vocabulary, unk_token="[UNK]"))
    tokenizer.pre_tokenizer = Whitespace()
    tokenizer.post_processor = TemplateProcessing(
        single="[CLS] $A [SEP]",
        pair="[CLS] $A [SEP] $B:1 [SEP]:1",
        special_tokens=[("[CLS]", 2), ("[SEP]", 3)],
    )
    tokenizer.save(str(output / "tokenizer.json"), pretty=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("output", type=Path)
    generate(parser.parse_args().output)
