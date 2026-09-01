#!/usr/bin/env python3
"""Untrusted boundary worker for the storymodel4s media court (admission ledger §5, plan §6/§7).

Reads a request JSON naming a raw BGR24 frame file that the JVM adapter produced and identified,
runs PySceneDetect's classical ContentDetector over those frames through the direct frame
interface, and writes an outcome JSON with the raw per-frame metrics and the frame ordinals at
which the detector reported a cut.

What this worker owns: nothing. It never opens a media container, never computes a timestamp,
and reports frame ordinals only; the JVM adapter re-anchors ordinals to PTS through its own
packet index. The FrameTimecode it hands the detector is a counter the API requires, at a nominal
rate the request declares, and is not evidence about time.

The outcome carries two views of the recipe: ``detector`` echoes the request (so the adapter can
see what was asked), and ``applied`` is read back from the constructed detector's own state (so
the adapter can check what the library actually installed). The adapter refuses when they differ.

Usage: scenedetect_worker.py REQUEST_JSON OUTCOME_JSON
"""

import hashlib
import json
import platform
import sys

import numpy as np

import scenedetect
from scenedetect import FrameTimecode, StatsManager
from scenedetect.detector import FlashFilter
from scenedetect.detectors import ContentDetector

SCHEMA = "storymodel4s.media.detector-outcome"
REQUEST_SCHEMA = "storymodel4s.media.detector-request"
PIXEL_FORMAT = "bgr24"
BYTES_PER_PIXEL = 3
FILTER_MODES = {"MERGE": FlashFilter.Mode.MERGE, "SUPPRESS": FlashFilter.Mode.SUPPRESS}
FILTER_MODE_NAMES = {v: k for k, v in FILTER_MODES.items()}


def fail(reason):
    sys.stderr.write(reason + "\n")
    sys.exit(2)


def build_detector(spec):
    weights = spec["weights"]
    return ContentDetector(
        threshold=float(spec["threshold"]),
        min_scene_len=int(spec["minSceneLen"]),
        weights=ContentDetector.Components(
            delta_hue=float(weights["deltaHue"]),
            delta_sat=float(weights["deltaSat"]),
            delta_lum=float(weights["deltaLum"]),
            delta_edges=float(weights["deltaEdges"]),
        ),
        luma_only=bool(spec["lumaOnly"]),
        kernel_size=spec["kernelSize"],
        filter_mode=FILTER_MODES[spec["filterMode"]],
    )


def applied_recipe(detector):
    """What the library installed, read from the detector's own state (scenedetect 0.7.1).

    These are private attributes; the wheel is hash-pinned, so their names are stable for this
    court. A missing attribute is reported as null and the adapter refuses.
    """
    weights = getattr(detector, "_weights", None)
    kernel = getattr(detector, "_kernel", None)
    flash = getattr(detector, "_flash_filter", None)
    return {
        "threshold": getattr(detector, "_threshold", None),
        "weights": None
        if weights is None
        else {
            "deltaHue": float(weights.delta_hue),
            "deltaSat": float(weights.delta_sat),
            "deltaLum": float(weights.delta_lum),
            "deltaEdges": float(weights.delta_edges),
        },
        "kernelSize": None if kernel is None else int(kernel.shape[0]),
        "minSceneLen": None
        if flash is None
        else int(getattr(flash, "_filter_length", -1)),
        "filterMode": None
        if flash is None
        else FILTER_MODE_NAMES.get(getattr(flash, "_mode", None)),
    }


def main(argv):
    if len(argv) != 3:
        fail("usage: scenedetect_worker.py REQUEST_JSON OUTCOME_JSON")
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

    spec = request["detector"]
    if spec["type"] != "ContentDetector":
        fail("only ContentDetector is admitted")
    counter_fps = float(spec["frameCounterFps"])
    detector = build_detector(spec)
    stats = StatsManager()
    detector.stats_manager = stats

    cuts = []
    for ordinal in range(count):
        chunk = data[ordinal * frame_bytes : (ordinal + 1) * frame_bytes]
        img = np.frombuffer(chunk, dtype=np.uint8).reshape(
            (height, width, BYTES_PER_PIXEL)
        )
        found = detector.process_frame(FrameTimecode(ordinal, counter_fps), img)
        cuts.extend(int(t.frame_num) for t in found)
    cuts.extend(
        int(t.frame_num)
        for t in detector.post_process(FrameTimecode(count, counter_fps))
    )

    keys = list(ContentDetector.METRIC_KEYS)
    metrics = []
    for ordinal in range(count):
        row = {"ordinal": ordinal}
        if stats.metrics_exist(ordinal, keys):
            for key, value in zip(keys, stats.get_metrics(ordinal, keys)):
                row[key] = None if value is None else float(value)
        metrics.append(row)

    outcome = {
        "schema": SCHEMA,
        "schemaVersion": 1,
        "requestId": request["requestId"],
        "runtime": {
            "python": platform.python_version(),
            "scenedetect": scenedetect.__version__,
            "numpy": np.__version__,
            "opencv": __import__("cv2").__version__,
        },
        "frames": {
            "count": count,
            "sha256": frames_sha256,
            "width": width,
            "height": height,
            "pixelFormat": PIXEL_FORMAT,
        },
        "detector": dict(spec),
        "applied": applied_recipe(detector),
        "metricKeys": keys,
        "metrics": metrics,
        "cuts": sorted(set(cuts)),
        "coverage": {"requested": count, "observed": count},
    }
    with open(argv[2], "w", encoding="utf-8") as f:
        json.dump(outcome, f, indent=2, sort_keys=True)
        f.write("\n")


if __name__ == "__main__":
    main(sys.argv)
