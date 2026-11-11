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
C=third_party/codec2/src; H=third_party/codec2/generated
FLAGS="-O2 -fPIC -shared -DNDEBUG -Wl,-z,max-page-size=16384 -Wl,--no-undefined"
for pair in "arm64-v8a aarch64-linux-android29" "armeabi-v7a armv7a-linux-androideabi29" "x86_64 x86_64-linux-android29"; do set -- $pair; mkdir -p $O/lib/$1
 # Codec 2 with its JNI file (bound to pl.dyktafon.C2).
 $TC/clang --target=$2 $FLAGS -I$C -I$H -o $O/lib/$1/libc2.so jni/c2jni.c $C/*.c $H/*.c -lm -llog 2>&1 | grep -E "error|undefined" | head -8 || true
 $TC/clang --target=$2 $FLAGS -Wall -o $O/lib/$1/libflat.so jni/flatjni.c jni/flat.c -lm
 $TC/llvm-strip --strip-unneeded $O/lib/$1/libc2.so $O/lib/$1/libflat.so
done
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
