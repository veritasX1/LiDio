#!/bin/bash
# Card ef3a3dfb (Ubuntu): builds libprojectM 4.1.7 (LGPL-2.1, desktop OpenGL 3.3) with LiDio's small patch
# (projectm-target-fbo.patch: draw into the framebuffer GTK's GLArea has bound) → linux/lib/libprojectM-4.so.4.
# Needs cmake, g++ and the OpenGL headers (sudo apt install cmake g++ libgl-dev).
#   ./native/build-milk.sh [work folder]
set -euo pipefail
VERSION=4.1.7
HERE="$(cd "$(dirname "$0")" && pwd)"
LIB="$HERE/../lib"
WORK="${1:-$HOME/LiDio-build}"
mkdir -p "$WORK" "$LIB"; cd "$WORK"
[ -f "libprojectM-$VERSION.tar.gz" ] || curl -sL -o "libprojectM-$VERSION.tar.gz" "https://github.com/projectM-visualizer/projectm/releases/download/v$VERSION/libprojectM-$VERSION.tar.gz"
rm -rf "pm-linux-src" && mkdir pm-linux-src && tar xzf "libprojectM-$VERSION.tar.gz" -C pm-linux-src --strip-components=1
patch -d pm-linux-src -p1 < "$HERE/projectm-target-fbo.patch"
cmake -S pm-linux-src -B build-pm-linux -DCMAKE_BUILD_TYPE=Release -DENABLE_SYSTEM_PROJECTM_EVAL=OFF -DENABLE_PLAYLIST=OFF \
  -DBUILD_SHARED_LIBS=ON -DENABLE_INSTALL=ON -DCMAKE_INSTALL_PREFIX="$WORK/pm-linux" >/dev/null
cmake --build build-pm-linux -j"$(nproc)" >/dev/null && cmake --install build-pm-linux >/dev/null
cp -L "$WORK/pm-linux/lib/libprojectM-4.so.4" "$LIB/libprojectM-4.so.4"
strip "$LIB/libprojectM-4.so.4"
echo "fertig: $(du -h "$LIB/libprojectM-4.so.4" | cut -f1)"
