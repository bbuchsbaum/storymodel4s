#!/usr/bin/env bash
# Realize the admission ledger's nominated FFmpeg 9.0.1 "Lei" as an LGPL-only, everything-disabled
# build carrying exactly the components the F0 and F1 manifests require (ledger §4: "No decoder is
# selected until F0 and F1 manifests establish exact stream requirements").
#
#   demux    mov                          (F0 MOV, F1 MP4)
#   decode   rawvideo, h264               (F0 picture, F1 picture)
#   parse    h264
#   encode   rawvideo; mux rawvideo       (identified BGR24 frames out)
#   scale    libswscale + filters scale, format, null   (declared colour conversion)
#   protocol file only; network, devices, hwaccels, asm, autodetected libraries all disabled
#   programs ffprobe and ffmpeg (the candidate recipe was probe-only; decode is now required)
#   linking  static, so one binary digest covers the demuxing and decoding code (the shared-library
#            gap recorded in ledger §6.2); LGPL-only flags are unchanged and nothing is redistributed
#
# Inputs: a tarball already verified against the FFmpeg release key (this script does not fetch or
# verify; see tmp/ffmpeg-9.0.1/RECEIPT.txt). Outputs: $PREFIX/bin/{ffmpeg,ffprobe}, config.log and
# the enabled component lists copied next to them.
#
# Usage: build_ffmpeg_9.0.1.sh TARBALL BUILD_DIR PREFIX
set -euo pipefail
tarball="${1:?verified ffmpeg-9.0.1.tar.xz}"
build="${2:?build directory}"
prefix="${3:?install prefix}"

mkdir -p "$build" "$prefix"
tar -xJf "$tarball" -C "$build"
src="$build/ffmpeg-9.0.1"
cd "$src"

./configure \
  --prefix="$prefix" \
  --fatal-warnings \
  --disable-gpl \
  --disable-version3 \
  --disable-nonfree \
  --disable-autodetect \
  --disable-iconv \
  --disable-zlib \
  --disable-bzlib \
  --disable-lzma \
  --disable-network \
  --disable-avdevice \
  --disable-swresample \
  --disable-hwaccels \
  --disable-videotoolbox \
  --disable-audiotoolbox \
  --disable-asm \
  --disable-runtime-cpudetect \
  --disable-doc \
  --disable-debug \
  --disable-everything \
  --disable-programs \
  --enable-ffprobe \
  --enable-ffmpeg \
  --enable-static \
  --disable-shared \
  --enable-avfilter \
  --enable-swscale \
  --enable-protocol=file \
  --enable-demuxer=mov \
  --enable-decoder=rawvideo,h264 \
  --enable-parser=h264 \
  --enable-encoder=rawvideo \
  --enable-muxer=rawvideo \
  --enable-filter=scale,format,null

make -j"$(sysctl -n hw.ncpu 2>/dev/null || nproc)" >"$build/make.log" 2>&1
make install >"$build/install.log" 2>&1

mkdir -p "$prefix/receipt"
cp ffbuild/config.log "$prefix/receipt/config.log"
"$prefix/bin/ffprobe" -version > "$prefix/receipt/ffprobe-version.txt"
"$prefix/bin/ffmpeg" -version > "$prefix/receipt/ffmpeg-version.txt"
"$prefix/bin/ffprobe" -buildconf > "$prefix/receipt/buildconf.txt"
"$prefix/bin/ffmpeg" -hide_banner -decoders > "$prefix/receipt/decoders.txt" 2>&1
"$prefix/bin/ffmpeg" -hide_banner -demuxers > "$prefix/receipt/demuxers.txt" 2>&1
"$prefix/bin/ffmpeg" -hide_banner -filters > "$prefix/receipt/filters.txt" 2>&1
"$prefix/bin/ffmpeg" -hide_banner -protocols > "$prefix/receipt/protocols.txt" 2>&1
otool -L "$prefix/bin/ffprobe" > "$prefix/receipt/ffprobe-otool.txt" 2>&1 || true
otool -L "$prefix/bin/ffmpeg" > "$prefix/receipt/ffmpeg-otool.txt" 2>&1 || true
shasum -a 256 "$prefix/bin/ffprobe" "$prefix/bin/ffmpeg" | tee "$prefix/receipt/SHA256SUMS"
