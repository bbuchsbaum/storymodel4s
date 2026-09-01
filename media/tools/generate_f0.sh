#!/usr/bin/env bash
# Generate the project-authored F0 v1 media fixture (storymodel4s media court).
#
# Output: media/src/test/resources/f0/f0-v1.mov
#   stream 0  video  rawvideo rgb24 32x18, timebase 1/24000, nominal 24 fps, VARIABLE frame rate:
#             frames 5, 6 and 20 of the 48 generated frames are dropped with PTS passthrough, so
#             the surviving packets carry durations 1000 (normal), 3000 (the frame at PTS 4000
#             holds for three frame-times) and 2000 (the frame at PTS 19000 holds for two). These
#             are holds, not gaps: every packet's duration reaches the next PTS. 45 packets survive.
#   stream 1  audio  pcm_s16le mono 8000 Hz, a 440 Hz sine, 2.000 s.
# The container is written bit-exact: no creation timestamps, but the muxer and encoder version
# tags (Lavf61.7.100, Lavc61.19.101) are present, so byte reproducibility is bound to this exact
# FFmpeg build. The recorded SHA-256 lives in f0-v1.manifest.json.
#
# This script is the generator the fixture manifest names. It is run by a developer, never by CI.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
out_dir="$here/../src/test/resources/f0"
out="$out_dir/f0-v1.mov"
ffmpeg="${FFMPEG:-ffmpeg}"
mkdir -p "$out_dir"

python3 "$here/f0_frames.py" > "$out_dir/.f0-frames.rgb"

"$ffmpeg" -hide_banner -loglevel error -y \
  -fflags +bitexact -flags +bitexact \
  -f rawvideo -pix_fmt rgb24 -video_size 32x18 -framerate 24 -i "$out_dir/.f0-frames.rgb" \
  -f lavfi -i "sine=frequency=440:sample_rate=8000:duration=2" \
  -map 0:v:0 -map 1:a:0 \
  -vf "select='not(eq(n\,5)+eq(n\,6)+eq(n\,20))'" -fps_mode passthrough \
  -c:v rawvideo -pix_fmt rgb24 -video_track_timescale 24000 \
  -c:a pcm_s16le \
  -map_metadata -1 -map_chapters -1 \
  -f mov "$out"

rm -f "$out_dir/.f0-frames.rgb"
shasum -a 256 "$out"
