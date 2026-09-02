#!/usr/bin/env python3
"""Untrusted captioning worker for the storymodel4s media court (captioning admission record §4–§7).

Reads a request JSON naming a raw BGR24 frame file that the JVM adapter produced and identified,
a list of extents (frame ordinals) to describe, a pinned local model directory, and a recipe; runs
the model once per extent with the frames presented as an image sequence; writes an outcome JSON
with the text, token counts, and what the library actually applied, read back from the processor
rather than echoed: the image grid it produced for every frame is the evidence that the declared
pixel limits were applied.

What this worker owns: nothing. It never opens a media container, never computes a timestamp, and
reports frame ordinals only; the JVM adapter re-anchors extents to PTS through its own packet
index. It runs offline (the Hugging Face offline switches are forced on, not defaulted) and
verifies every file the request pins, refusing a model directory that carries an unlisted weight
file or whose weight index names one.

Usage: caption_worker.py REQUEST_JSON OUTCOME_JSON
"""

import hashlib
import json
import os
import platform
import sys

os.environ["HF_HUB_OFFLINE"] = "1"
os.environ["TRANSFORMERS_OFFLINE"] = "1"
os.environ["HF_HUB_DISABLE_TELEMETRY"] = "1"

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
    """Every pinned file must hash as declared; no unlisted weight file may exist; the weight index
    may name only pinned shards. Returns the digest of config.json and the parsed config."""
    path = model["localPath"]
    files = model["filesSha256"]
    for name, expected in files.items():
        p = os.path.join(path, name)
        if not os.path.isfile(p):
            fail(f"pinned model file missing: {name}")
        got = sha256_file(p)
        if got != expected:
            fail(
                f"model file {name} hashes to {got[:16]}, request declares {expected[:16]}"
            )
    for name in sorted(os.listdir(path)):
        if name.endswith(".safetensors") and name not in files:
            fail(f"unlisted weight file present in the model directory: {name}")
    index_name = "model.safetensors.index.json"
    if index_name in files:
        with open(os.path.join(path, index_name), "r", encoding="utf-8") as f:
            index = json.load(f)
        for shard in sorted(set(index.get("weight_map", {}).values())):
            if shard not in files:
                fail(f"weight index names an unpinned shard: {shard}")
    if "config.json" not in files:
        fail("the model pin must include config.json")
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
    if not (
        hasattr(image_processor, "min_pixels")
        and hasattr(image_processor, "max_pixels")
    ):
        fail(
            "the image processor exposes no min_pixels/max_pixels; the pixel limits cannot be applied"
        )
    image_processor.min_pixels = int(recipe["minPixels"])
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
        # The processor's own grid for these images, obtained independently of generation. It is
        # the evidence that the pixel limits were applied; the adapter checks it against them.
        encoded = processor(text=[formatted], images=images, return_tensors="np")
        g = encoded.get("image_grid_thw")
        if g is None:
            fail(f"the processor returned no image_grid_thw for extent {extent['id']}")
        grid = [[int(x) for x in row] for row in np.asarray(g).tolist()]
        if len(grid) != len(images):
            fail(f"extent {extent['id']}: {len(grid)} grids for {len(images)} images")
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
        "minPixels": int(image_processor.min_pixels),
        "maxPixels": int(image_processor.max_pixels),
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
        "offline": {
            "HF_HUB_OFFLINE": os.environ.get("HF_HUB_OFFLINE"),
            "TRANSFORMERS_OFFLINE": os.environ.get("TRANSFORMERS_OFFLINE"),
        },
        "model": {
            "repo": model["repo"],
            "revision": model["revision"],
            "configSha256": config_sha256,
            "modelType": config.get("model_type"),
            "architectures": config.get("architectures"),
            "filesVerified": sorted(model["filesSha256"].keys()),
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
