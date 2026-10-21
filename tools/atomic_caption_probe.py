#!/usr/bin/env python3
"""Question-independent frame-local descriptions for a frozen local diagnostic.

The freeze contains only frame locations, model pins and a generic prompt. Recall,
claims, ASR, prior captions and reference labels are absent from the model input.
"""
import argparse
import hashlib
import importlib.util
import json
import mmap
import os
from pathlib import Path
import platform
import time

from claim_assessment_probe import read, sha, write

PROMPT = """Describe only visible content in these three images, in their supplied order.
Write at most nine short factual sentences, grouped as Image 1, Image 2, Image 3.
Use a separate local person handle in each image (for example Image 1 person A).
For each visible person, describe appearance and clothing, posture, contact with surfaces,
location, visible actions and handled objects. Include other salient objects and interactions.
State ambiguity when a feature cannot be seen. Distinguish observed appearance from inference.
Do not name characters, infer their thoughts or medical state, invent speech, infer motion
from a still image, or assert that people in different images are the same person.
Use plain sentences, no story summary. Do not add information that is not visible."""


def run(root):
    freeze = read(root / "freeze.json")
    if freeze["harnessSha256"] != sha(__file__):
        raise ValueError("harness changed")
    if freeze["prompt"] != PROMPT:
        raise ValueError("prompt changed")
    for path, digest in freeze["inputs"].items():
        if sha(path) != digest:
            raise ValueError("frozen input changed")
    rows = freeze["targets"]
    if not rows or len({r["row"] for r in rows}) != len(rows):
        raise ValueError("duplicate or empty frame population")
    output = root / "outputs.jsonl"
    if output.exists():
        raise ValueError("execution already attempted")
    os.environ.update(HF_HUB_OFFLINE="1", TRANSFORMERS_OFFLINE="1", HF_HUB_DISABLE_TELEMETRY="1")
    spec = importlib.util.spec_from_file_location("frame_worker", freeze["worker"])
    worker = importlib.util.module_from_spec(spec); spec.loader.exec_module(worker)
    worker.verify_model_dir(freeze["model"])
    import mlx.core as mx
    import mlx_vlm
    import numpy as np
    from mlx_vlm import load, generate
    from mlx_vlm.prompt_utils import apply_chat_template
    from mlx_vlm.utils import load_config
    model, processor = load(freeze["model"]["localPath"], trust_remote_code=False)
    config = load_config(freeze["model"]["localPath"])
    recipe = freeze["recipe"]
    ip = processor.image_processor
    ip.min_pixels, ip.max_pixels = recipe["minPixels"], recipe["maxPixels"]
    if isinstance(getattr(ip, "size", None), dict):
        ip.size = dict(shortest_edge=ip.min_pixels, longest_edge=ip.max_pixels)
    runtime = dict(python=platform.python_version(), mlx=mx.__version__, mlx_vlm=mlx_vlm.__version__)
    handles, data = [], {}
    try:
        for part, frames in freeze["frames"].items():
            handle = open(frames["file"], "rb"); handles.append(handle)
            mm = mmap.mmap(handle.fileno(), 0, access=mmap.ACCESS_READ); handles.append(mm)
            if len(mm) != frames["count"] * frames["width"] * frames["height"] * 3:
                raise ValueError("frame size mismatch")
            data[part] = mm
        with output.open("x") as stream:
            for index, row in enumerate(rows):
                frame = freeze["frames"][row["part"]]
                if any(not 0 <= n < frame["count"] for n in row["sampleOrdinals"]):
                    raise ValueError("foreign frame ordinal")
                images = [worker.frame_image(data[row["part"]], n, frame["width"], frame["height"])
                          for n in row["sampleOrdinals"]]
                prompt = apply_chat_template(processor, config, PROMPT, num_images=len(images))
                grid = np.asarray(processor(text=[prompt], images=images, return_tensors="np")["image_grid_thw"]).tolist()
                mx.random.seed(recipe["seed"])
                start = time.monotonic()
                generated = generate(model, processor, prompt, images,
                                     max_tokens=recipe["maxNewTokens"], temperature=0.0, verbose=False)
                valid = (generated.generation_tokens is not None and
                         generated.generation_tokens < recipe["maxNewTokens"] and bool(generated.text.strip()))
                value = dict(row=row["row"], sampleOrdinals=row["sampleOrdinals"],
                             raw=generated.text, processing="observed" if valid else "output_limit_or_empty",
                             promptTokens=generated.prompt_tokens, generatedTokens=generated.generation_tokens,
                             imageGridThw=grid, seconds=time.monotonic()-start)
                stream.write(json.dumps(value, ensure_ascii=False) + "\n"); stream.flush()
                print(f"Completed {index+1}/{len(rows)}: {value['processing']}", flush=True)
        write(root / "execution.json", dict(freezeSha256=sha(root / "freeze.json"),
              outputsSha256=sha(output), requested=len(rows), observed=index+1, runtime=runtime))
    finally:
        for handle in reversed(handles):
            handle.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("experiment", type=Path)
    run(parser.parse_args().experiment)
