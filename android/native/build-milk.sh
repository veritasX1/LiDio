#!/bin/bash
# Card ef3a3dfb: builds libprojectM 4.1.7 (LGPL-2.1, OpenGL ES 3) and LiDio's JNI layer lidio_milk.c for arm64-v8a and
# armeabi-v7a → app/src/main/jniLibs/<abi>/libprojectM-4.so + liblidiomilk.so. Presets: tools/pick-presets.py.
#   ANDROID_NDK=~/Android/Sdk/ndk/28.2.13676358 ./native/build-milk.sh [work folder]
set -euo pipefail
VERSION=4.1.7
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$HERE/../app/src/main/jniLibs"
WORK="${1:-$HOME/LiDio-build}"
NDK="${ANDROID_NDK:-$HOME/Android/Sdk/ndk/28.2.13676358}"
API=29
mkdir -p "$WORK"; cd "$WORK"
[ -d "libprojectM-$VERSION" ] || { curl -sL -o pm.tar.gz "https://github.com/projectM-visualizer/projectm/releases/download/v$VERSION/libprojectM-$VERSION.tar.gz"; tar xzf pm.tar.gz; }
for ABI in arm64-v8a armeabi-v7a; do
  case $ABI in arm64-v8a) TRIPLE=aarch64-linux-android ;; armeabi-v7a) TRIPLE=armv7a-linux-androideabi ;; esac
  OUT="$WORK/pm-$ABI"
  if [ ! -f "$OUT/lib/libprojectM-4.so" ]; then
    cmake -S "libprojectM-$VERSION" -B "build-pm-$ABI" -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" -DANDROID_ABI=$ABI \
      -DANDROID_PLATFORM=android-$API -DCMAKE_BUILD_TYPE=Release -DENABLE_SYSTEM_PROJECTM_EVAL=OFF -DENABLE_PLAYLIST=OFF \
      -DBUILD_SHARED_LIBS=ON -DENABLE_INSTALL=ON -DCMAKE_INSTALL_PREFIX="$OUT" >/dev/null
    cmake --build "build-pm-$ABI" -j"$(nproc)" >/dev/null && cmake --install "build-pm-$ABI" >/dev/null
  fi
  CC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/${TRIPLE}${API}-clang"
  mkdir -p "$APP/$ABI"
  cp "$OUT/lib/libprojectM-4.so" "$APP/$ABI/"
  "$CC" -shared -fPIC -O2 -I"$OUT/include" "$HERE/lidio_milk.c" -L"$OUT/lib" -lprojectM-4 -o "$APP/$ABI/liblidiomilk.so"
  "$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" "$APP/$ABI/libprojectM-4.so" "$APP/$ABI/liblidiomilk.so"
  echo "$ABI: $(du -h "$APP/$ABI/libprojectM-4.so" | cut -f1) + $(du -h "$APP/$ABI/liblidiomilk.so" | cut -f1)"
done
