#!/bin/bash
# Computer test of the self-test (src/pl/dyktafon/SelfTest.java, src/pl/flatraster/PictureTest.java) against the real native libraries.
# VideoTest and SelfTestRun use Android's encoders and microphone and can only be tried on a phone.
set -e
cd "$(dirname "$0")/../.."
[ -f tests/dred/out/libdred.so ] || bash tests/dred/build.sh > /dev/null
O=tests/selftest/out; rm -rf $O; mkdir -p $O
JH=$(dirname $(dirname $(readlink -f $(which javac))))
gcc -O2 -fPIC -shared -DNDEBUG -I$JH/include -I$JH/include/linux -o $O/libflat.so jni/flatjni.c jni/flat.c jni/smooth.c -lm
D=src/pl/dyktafon; javac -nowarn -d $O $D/{SelfTest,Dred,DredLib,VoiceChain,Denoiser,Vad,Limiter,Compressor}.java src/pl/flatraster/{Flat,PictureTest}.java tests/selftest/ST.java 2>&1 | grep -v "^Picked up" || true
java -Djava.library.path=$O:tests/dred/out -cp $O pl.flatraster.ST 2>&1 | grep -v "^Picked up"
