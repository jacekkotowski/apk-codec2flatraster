#!/bin/bash
# The exact coder in the app's own Java classes with the real native library, on a computer (ExactTest.java). Needs gcc and a JDK.
set -e
cd "$(dirname "$0")/.."
O=$(mktemp -d)
JH=$(dirname $(dirname $(readlink -f $(which javac))))
gcc -O1 -g -Wall -fPIC -shared -fsanitize=address,undefined -fno-sanitize-recover=undefined -I$JH/include -I$JH/include/linux -o $O/libflat.so jni/flatjni.c jni/exactjni.c jni/flat.c jni/flatcode.c jni/smooth.c -lm
javac -nowarn -d $O src/pl/flatraster/{Flat,Exact,ExactEncoder,PictureEncoder,FrvWriter,FrvReader}.java tests/ExactTest.java 2>&1 | grep -v "^Picked up" || true
ASAN=$(gcc -print-file-name=libasan.so)
LD_PRELOAD=$ASAN ASAN_OPTIONS=detect_leaks=0 java -Djava.library.path=$O -cp $O pl.flatraster.ExactTest 2>&1 | grep -v "^Picked up"
exit ${PIPESTATUS[0]}
