#!/usr/bin/env python3
"""Project-authored F0 picture frames (storymodel4s media court, fixture F0 v1).

Writes 48 raw rgb24 frames of 32x18 pixels to stdout: three flat-colour "shots" of 16 frames
each. The colours are chosen so that a classical content detector sees two large hue/luma
discontinuities (at frame 16 and frame 32) and nothing inside a shot. Frame content carries no
text, no faces, no licensed material; it is generated arithmetic.

Standard library only. Deterministic: identical bytes on every run and platform.
"""
import sys

WIDTH, HEIGHT = 32, 18
FRAMES_PER_SHOT = 16
SHOTS = [(200, 30, 30), (30, 200, 30), (30, 30, 200)]


def frame(rgb):
    return bytes(rgb) * (WIDTH * HEIGHT)


def main():
    out = sys.stdout.buffer
    for rgb in SHOTS:
        f = frame(rgb)
        for _ in range(FRAMES_PER_SHOT):
            out.write(f)
    out.flush()


if __name__ == "__main__":
    main()
