#!/usr/bin/env python3
"""Untrusted captioning worker for the storymodel4s media court (captioning admission record §4–§7).

Reads a request JSON naming a raw BGR24 frame file that the JVM adapter produced and identified,
a list of extents (frame ordinals) to describe, a pinned local model directory, and a recipe; runs
the model once per extent with the frames presented as an image sequence; writes an outcome JSON
with the text, token counts, and what the library actually applied (prompt digest, pixel limits,
processor grid, normalization), read back from the processor rather than echoed.

What this worker owns: nothing. It never opens a media container, never computes a timestamp, and
reports frame ordinals only; the JVM adapter re-anchors extents to PTS through its own packet
index. It runs offline: the model directory is verified against the request's declared shard
digests before anything is loaded, and no network access is attempted.

Usage: caption_worker.py REQUEST_JSON OUTCOME_JSON
"""

import hashlib
import json
import os
import platform
import sys

os.environ.setdefault("HF_HUB_OFFLINE", "1")
os.environ.setdefault("TRANSFORMERS_OFFLINE", "1")

import numpy as np
from PIL import Image

SCHEMA = "storymodel4s.media.caption-outcome"
REQUEST_SCHEMA = "storymodel4s.media.caption-request"
PIXEL_FORMAT = "bgr24"
BYTES_PER_PIXEL = 3


def fail(reason):
    sys.stderr.write(reason + "\n")
    sys.exit(2)


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 24), b""):
            h.update(chunk)
    return h.hexdigest()


def sha256_text(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def verify_model_dir(model):
    """The request declares the shard digests; the directory must match before it is loaded."""
    path = model["localPath"]
    for name, expected in model["shardsSha256"].items():
        p = os.path.join(path, name)
        if not os.path.isfile(p):
            fail(f"model shard missing: {name}")
        got = sha256_file(p)
        if got != expected:
            fail(
                f"model shard {name} hashes to {got[:16]}, request declares {expected[:16]}"
            )
    with open(os.path.join(path, "config.json"), "r", encoding="utf-8") as f:
        config_text = f.read()
    return sha256_text(config_text), json.loads(config_text)


def frame_image(data, ordinal, width, height):
    frame_bytes = width * height * BYTES_PER_PIXEL
    chunk = data[ordinal * frame_bytes : (ordinal + 1) * frame_bytes]
    bgr = np.frombuffer(chunk, dtype=np.uint8).reshape((height, width, BYTES_PER_PIXEL))
    rgb = bgr[:, :, ::-1]
    return Image.fromarray(np.ascontiguousarray(rgb))


def main(argv):
    if len(argv) != 3:
        fail("usage: caption_worker.py REQUEST_JSON OUTCOME_JSON")
    with open(argv[1], "r", encoding="utf-8") as f:
        request = json.load(f)
    if request.get("schema") != REQUEST_SCHEMA or request.get("schemaVersion") != 1:
        fail("unrecognised request schema")

    frames = request["frames"]
    width, height, count = (
        int(frames["width"]),
        int(frames["height"]),
        int(frames["count"]),
    )
    if frames["pixelFormat"] != PIXEL_FORMAT:
        fail(
            f"worker consumes {PIXEL_FORMAT} only, request declares {frames['pixelFormat']}"
        )
    frame_bytes = width * height * BYTES_PER_PIXEL
    with open(frames["file"], "rb") as f:
        data = f.read()
    if len(data) != frame_bytes * count:
        fail(
            f"frame file holds {len(data)} bytes, request declares {count} frames of {frame_bytes} bytes"
        )
    frames_sha256 = hashlib.sha256(data).hexdigest()
    if frames_sha256 != frames["sha256"]:
        fail("frame file does not hash to the request's declared identity")

    for extent in request["extents"]:
        for o in extent["ordinals"]:
            if not (0 <= int(o) < count):
                fail(f"extent {extent['id']} names frame {o} outside {count} frames")

    model = request["model"]
    config_sha256, config = verify_model_dir(model)
    recipe = request["recipe"]
    if recipe.get("presentation") != "image-sequence":
        fail("only the image-sequence presentation is admitted")
    if not recipe.get("greedy", False):
        fail("only greedy decoding is admitted by this worker")

    import mlx.core as mx
    import mlx_vlm
    from mlx_vlm import generate, load
    from mlx_vlm.prompt_utils import apply_chat_template
    from mlx_vlm.utils import load_config

    mx.random.seed(int(recipe.get("seed", 0)))
    loaded_model, processor = load(model["localPath"], trust_remote_code=False)
    model_config = load_config(model["localPath"])
    image_processor = getattr(processor, "image_processor", processor)
    if hasattr(image_processor, "min_pixels"):
        image_processor.min_pixels = int(recipe["minPixels"])
    if hasattr(image_processor, "max_pixels"):
        image_processor.max_pixels = int(recipe["maxPixels"])
    if hasattr(image_processor, "size") and isinstance(image_processor.size, dict):
        image_processor.size = {
            "shortest_edge": int(recipe["minPixels"]),
            "longest_edge": int(recipe["maxPixels"]),
        }

    prompt_text = recipe["prompt"]
    results = []
    for extent in request["extents"]:
        ordinals = [int(o) for o in extent["ordinals"]]
        images = [frame_image(data, o, width, height) for o in ordinals]
        formatted = apply_chat_template(
            processor, model_config, prompt_text, num_images=len(images)
        )
        # Read back what the processor applies to these images (grid in patches), independently of
        # generation, so the outcome records the library's own preprocessing rather than an echo.
        grid = None
        try:
            encoded = processor(text=[formatted], images=images, return_tensors="np")
            g = encoded.get("image_grid_thw")
            if g is not None:
                grid = [[int(x) for x in row] for row in np.asarray(g).tolist()]
        except Exception as e:  # noqa: BLE001 - recorded, not hidden
            grid = {"error": str(e)[:200]}
        out = generate(
            loaded_model,
            processor,
            formatted,
            images,
            max_tokens=int(recipe["maxNewTokens"]),
            temperature=0.0,
            verbose=False,
        )
        if isinstance(out, str):
            text, prompt_tokens, generation_tokens = out, None, None
        else:
            text = getattr(out, "text", str(out))
            prompt_tokens = getattr(out, "prompt_tokens", None)
            generation_tokens = getattr(out, "generation_tokens", None)
        results.append(
            {
                "id": extent["id"],
                "ordinals": ordinals,
                "text": text,
                "promptTokens": prompt_tokens,
                "generatedTokens": generation_tokens,
                "imageGridThw": grid,
            }
        )

    applied = {
        "promptSha256": sha256_text(prompt_text),
        "minPixels": getattr(image_processor, "min_pixels", None),
        "maxPixels": getattr(image_processor, "max_pixels", None),
        "size": getattr(image_processor, "size", None),
        "patchSize": getattr(image_processor, "patch_size", None),
        "temporalPatchSize": getattr(image_processor, "temporal_patch_size", None),
        "mergeSize": getattr(image_processor, "merge_size", None),
        "imageMean": getattr(image_processor, "image_mean", None),
        "imageStd": getattr(image_processor, "image_std", None),
        "maxNewTokens": int(recipe["maxNewTokens"]),
        "greedy": True,
        "temperature": 0.0,
        "presentation": "image-sequence",
    }

    outcome = {
        "schema": SCHEMA,
        "schemaVersion": 1,
        "requestId": request["requestId"],
        "runtime": {
            "python": platform.python_version(),
            "mlx_vlm": mlx_vlm.__version__,
            "mlx": mx.__version__,
        },
        "model": {
            "repo": model["repo"],
            "revision": model["revision"],
            "configSha256": config_sha256,
            "modelType": config.get("model_type"),
            "architectures": config.get("architectures"),
            "shardsVerified": True,
        },
        "frames": {
            "count": count,
            "sha256": frames_sha256,
            "width": width,
            "height": height,
            "pixelFormat": PIXEL_FORMAT,
        },
        "recipe": dict(recipe),
        "applied": applied,
        "extents": results,
        "coverage": {"requested": len(request["extents"]), "observed": len(results)},
    }
    with open(argv[2], "w", encoding="utf-8") as f:
        json.dump(outcome, f, indent=2, sort_keys=True, ensure_ascii=False)
        f.write("\n")


if __name__ == "__main__":
    main(sys.argv)
