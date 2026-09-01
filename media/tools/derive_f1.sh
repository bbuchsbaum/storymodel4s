#!/usr/bin/env bash
# Derive the F1 v1 excerpt (storymodel4s media court) from the official Big Buck Bunny download.
#
# Source (Blender Foundation, CC BY 3.0, https://peach.blender.org/about/):
#   https://download.blender.org/peach/bigbuckbunny_movies/BigBuckBunny_320x180.mp4.zip
#   archive SHA-256  109e3ede8790bd633f374ca311d9cc61dce8d7f98f5b0797ca98199c9fbceedf
#   inner file       BigBuckBunny_320x180.mp4 (64,657,027 bytes)
#   inner SHA-256    f78f39603e6774907f2faafabf26a667f4a6fc31769ec304a8a8f7c62d280508
#
# Derivation: packet copy (no re-encode) of 30 s starting at the keyframe at 300.000 s
# (video PTS 7200 on timebase 1/24), both streams, metadata and chapters dropped, bit-exact
# muxing. An excerpt is a new stream identity (ADR 0007 §1); its SHA-256 is recorded in
# media/src/test/resources/f1/f1-v1.manifest.json. The excerpt bytes are not committed.
#
# Usage: derive_f1.sh SOURCE_MP4 OUT_MP4
set -euo pipefail
src="${1:?source BigBuckBunny_320x180.mp4}"
out="${2:?output path}"
ffmpeg="${FFMPEG:-ffmpeg}"

"$ffmpeg" -hide_banner -loglevel error -y \
  -fflags +bitexact -flags +bitexact \
  -ss 300 -i "$src" -t 30 \
  -map 0:v:0 -map 0:a:0 -c copy \
  -map_metadata -1 -map_chapters -1 \
  -f mp4 "$out"

shasum -a 256 "$out"
