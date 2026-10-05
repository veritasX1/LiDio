#!/bin/bash
# Builds LiDio's native part for arm64-v8a and armeabi-v7a: its own small FFmpeg (LGPL, audio only: decoders + container
# readers, no network, no protocols) and Sonivox (Apache-2.0, the General-MIDI synthesizer from Android), with the JNI
# layers lidio_ffmpeg.c and lidio_midi.c. Result: app/src/main/jniLibs/<abi>/liblidionative.so
#
#   ANDROID_NDK=~/Android/Sdk/ndk/28.2.13676358 ./native/build-native.sh [work folder]
#
# Card b697496c: WMA, APE, WavPack, DSD, Musepack, TAK, TTA, MIDI … play on the phone in their original form.
set -euo pipefail
VERSION=7.1.2
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$HERE/../app/src/main/jniLibs"
WORK="${1:-$HOME/LiDio-build}"
NDK="${ANDROID_NDK:-$HOME/Android/Sdk/ndk/28.2.13676358}"
API=29
TOOLS="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"

mkdir -p "$WORK"; cd "$WORK"
[ -d "ffmpeg-$VERSION" ] || { curl -sL -o ffmpeg.tar.xz "https://ffmpeg.org/releases/ffmpeg-$VERSION.tar.xz"; tar xJf ffmpeg.tar.xz; }
SONIVOX=e3213f7   # github.com/pedrolcl/sonivox
[ -d sonivox ] || { git clone -q https://github.com/pedrolcl/sonivox.git && git -C sonivox checkout -q $SONIVOX; }

DECODERS="mp3 mp3float mp2 mp1 aac aac_latm flac alac opus vorbis wmav1 wmav2 wmapro wmalossless wmavoice ape wavpack \
dsd_lsbf dsd_msbf dsd_lsbf_planar dsd_msbf_planar mpc7 mpc8 tak tta ac3 eac3 dca truehd mlp atrac3 atrac3p cook ra_144 ra_288 \
amrnb amrwb gsm gsm_ms qdm2 shorten als dst \
pcm_s8 pcm_u8 pcm_s16le pcm_s16be pcm_s24le pcm_s24be pcm_s32le pcm_s32be pcm_f32le pcm_f32be pcm_f64le pcm_f64be pcm_alaw pcm_mulaw \
adpcm_ima_wav adpcm_ms"
DEMUXERS="mp3 aac flac ogg mov matroska wav w64 asf ape wv dsf iff mpc mpc8 tak tta aiff ac3 eac3 dts truehd mlp caf amr \
rm shorten au voc"
PARSERS="mpegaudio aac aac_latm flac ac3 dca mlp opus vorbis tak"

flags() { for x in $2; do printf -- "--enable-%s=%s " "$1" "$x"; done; }

for ABI in arm64-v8a armeabi-v7a; do
  case $ABI in
    arm64-v8a) ARCH=aarch64; CPU=armv8-a; TRIPLE=aarch64-linux-android; EXTRA="" ;;
    armeabi-v7a) ARCH=arm; CPU=armv7-a; TRIPLE=armv7a-linux-androideabi; EXTRA="--enable-neon" ;;
  esac
  OUT="$WORK/out-$ABI"
  if [ ! -f "$OUT/lib/libavformat.a" ]; then
    rm -rf "build-$ABI"; cp -r "ffmpeg-$VERSION" "build-$ABI"; cd "build-$ABI"
    ./configure --prefix="$OUT" --target-os=android --arch=$ARCH --cpu=$CPU --enable-cross-compile \
      --cc="$TOOLS/$TRIPLE$API-clang" --cxx="$TOOLS/$TRIPLE$API-clang++" --ar="$TOOLS/llvm-ar" --nm="$TOOLS/llvm-nm" \
      --ranlib="$TOOLS/llvm-ranlib" --strip="$TOOLS/llvm-strip" --sysroot="$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot" \
      --enable-pic --enable-static --disable-shared --disable-programs --disable-doc --disable-avdevice --disable-avfilter \
      --disable-swscale --disable-postproc --disable-network --disable-everything --disable-debug --enable-small \
      --disable-vulkan --disable-hwaccels --disable-iconv --disable-zlib --disable-bzlib --disable-lzma \
      $(flags decoder "$DECODERS") $(flags demuxer "$DEMUXERS") $(flags parser "$PARSERS") $EXTRA > "$WORK/configure-$ABI.log"
    make -j"$(nproc)" > "$WORK/make-$ABI.log" 2>&1
    make install > /dev/null
    cd "$WORK"
  fi
  MIDI="$WORK/sonivox-$ABI"
  if [ ! -f "$MIDI/lib/libsonivox.a" ]; then
    cmake -S sonivox -B "sonivox/b-$ABI" -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" -DANDROID_ABI=$ABI \
      -DANDROID_PLATFORM=$API -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF -DBUILD_TESTING=OFF -DBUILD_APPLICATION=OFF \
      -DMP3_SUPPORT=OFF -DZLIB_SUPPORT=ON -DSF2_SUPPORT=ON -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DCMAKE_INSTALL_PREFIX="$MIDI" > /dev/null
    cmake --build "sonivox/b-$ABI" -j"$(nproc)" > "$WORK/sonivox-$ABI.log" 2>&1
    cmake --install "sonivox/b-$ABI" > /dev/null
  fi
  mkdir -p "$APP/$ABI"
  rm -f "$APP/$ABI/liblidioffmpeg.so"
  "$TOOLS/$TRIPLE$API-clang" -shared -fPIC -O2 -Wall -o "$APP/$ABI/liblidionative.so" "$HERE/lidio_ffmpeg.c" "$HERE/lidio_midi.c" \
    -I"$OUT/include" -I"$MIDI/include" "$OUT/lib/libavformat.a" "$OUT/lib/libavcodec.a" "$OUT/lib/libswresample.a" "$OUT/lib/libavutil.a" \
    "$MIDI/lib/libsonivox.a" -lz -lm -llog -Wl,--gc-sections -Wl,-z,max-page-size=16384 -Wl,--exclude-libs,ALL
  "$TOOLS/llvm-strip" --strip-unneeded "$APP/$ABI/liblidionative.so"
  echo "$ABI: $(du -h "$APP/$ABI/liblidionative.so" | cut -f1)"
done
