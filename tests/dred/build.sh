#!/bin/bash
# Builds, from third_party/opus-1.6.1 with the app's flags (opusflags.sh), for this computer:
#   tests/dred/dredtest       coder and player on the command line (the reference)
#   tests/dred/out/libdred.so the app's JNI library (jni/dredjni.c), with the Java classes and tests beside it
set -e
cd "$(dirname "$0")/../.."
. ./opusflags.sh
mkdir -p tests/dred/obj
objs=""
for f in $OPUSSRCS; do o=tests/dred/obj/$(echo ${f#$OPUS/} | tr / _).o; objs="$objs $o"; [ "$o" -nt "$f" ] || gcc $OPUSFLAGS -fPIC -c -o "$o" "$f" 2>/dev/null & done; wait
gcc $OPUSFLAGS -o tests/dred/dredtest tests/dred/dredtest.c $objs -lm 2>/dev/null
JH=$(dirname $(dirname $(readlink -f $(which javac)))); O=tests/dred/out; mkdir -p $O
gcc $OPUSFLAGS -fPIC -shared -I$JH/include -I$JH/include/linux -Wl,--no-undefined -o $O/libdred.so jni/dredjni.c $objs -lm 2>/dev/null
# The voice classes that touch Android (microphone routing, the noise sample) are left out: they need the Android SDK and no test here uses them.
javac -nowarn -d $O $(grep -L "android\." src/pl/dyktafon/*.java) src/pl/flatraster/FrvWriter.java src/pl/flatraster/FrvReader.java $(ls src/pl/flatraster/{VoicePackets,AudioPlan}.java 2>/dev/null) tests/dred/*.java 2>&1 | grep -v "^Picked up" || true
echo built tests/dred/dredtest and tests/dred/out
