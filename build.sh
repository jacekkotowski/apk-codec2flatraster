#!/bin/bash
# Builds FlatRaster.apk next to this script. aapt2, javac, d8, zipalign, apksigner and NDK clang: no Gradle.
# Needs ANDROID_SDK_ROOT and signing.env (KS_FILE, KS_PASS); see README.md.
set -e
cd "$(dirname "$0")"
S=${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT to your Android SDK}
[ -f signing.env ] || { echo "signing.env is missing, see README.md"; exit 1; }
. ./signing.env
BT=$S/build-tools/36.0.0; J=$S/platforms/android-36/android.jar; NDK=${ANDROID_NDK_ROOT:-$S/android-ndk-r28c}; O=out; rm -rf $O; mkdir -p $O/classes $O/res
TC=$(echo $NDK/toolchains/llvm/prebuilt/*/bin)
. ./opusflags.sh
FLAGS="-O2 -fPIC -shared -DNDEBUG -Wl,-z,max-page-size=16384 -Wl,--no-undefined"
# Each processor type builds in parallel; the logs are printed after.
for pair in "arm64-v8a aarch64-linux-android29" "armeabi-v7a armv7a-linux-androideabi29" "x86_64 x86_64-linux-android29"; do set -- $pair; mkdir -p $O/lib/$1
 (
 # DRED coder and player (Opus 1.6.1, flags in opusflags.sh), with its JNI file (bound to pl.dyktafon.DredLib).
 $TC/clang --target=$2 $OPUSFLAGS -DNDEBUG -fPIC -shared -fvisibility=hidden -Wl,-z,max-page-size=16384 -Wl,--no-undefined -o $O/lib/$1/libdred.so jni/dredjni.c $OPUSSRCS -lm -llog 2>&1 | grep -E "error|undefined" | head -8
 $TC/clang --target=$2 $FLAGS -Wall -o $O/lib/$1/libflat.so jni/flatjni.c jni/exactjni.c jni/flat.c jni/flatcode.c jni/smooth.c -lm
 $TC/llvm-strip --strip-unneeded $O/lib/$1/libdred.so $O/lib/$1/libflat.so
 ) > $O/lib-$1.log 2>&1 &
done
wait; cat $O/lib-*.log
for a in arm64-v8a armeabi-v7a x86_64; do for l in dred flat; do [ -f $O/lib/$a/lib$l.so ] || { echo "lib$l.so for $a did not build"; exit 1; }; done; done
$BT/aapt2 compile --dir res -o $O/res/res.zip
$BT/aapt2 link -o $O/base.apk -I $J --manifest AndroidManifest.xml --min-sdk-version 29 --target-sdk-version 36 -A assets $O/res/res.zip
javac -nowarn --release 11 -classpath $J -d $O/classes src/pl/flatraster/*.java src/pl/dyktafon/*.java 2>&1 | grep -v "^Picked up\|^Note" || true
[ -f $O/classes/pl/flatraster/MainActivity.class ] || { echo "Java did not compile"; exit 1; }
$BT/d8 --release --lib $J --min-api 29 --output $O $(find $O/classes -name '*.class') 2>&1 | grep -v "^Picked up" || true
cp $O/base.apk $O/u.apk; (cd $O && zip -q u.apk classes.dex && zip -q -r u.apk lib)
$BT/zipalign -f -p 4 $O/u.apk $O/a.apk
$BT/apksigner sign --ks "$KS_FILE" --ks-pass "pass:$KS_PASS" --out FlatRaster.apk $O/a.apk 2>&1 | grep -v "^Picked up" || true
$BT/apksigner verify FlatRaster.apk 2>&1 | grep -v "^Picked up\|WARNING" || true
ls -la FlatRaster.apk; unzip -l FlatRaster.apk | grep "lib/.*\.so"
