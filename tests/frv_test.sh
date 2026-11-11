#!/bin/bash
# Tests the .frv writer and reader on a computer. Needs a JDK.
set -e
cd "$(dirname "$0")/.."
T=$(mktemp -d)
javac -nowarn -d "$T" src/pl/flatraster/FrvWriter.java src/pl/flatraster/FrvReader.java tests/FrvTest.java 2>&1 | grep -v "^Picked up" || true
java -cp "$T" pl.flatraster.FrvTest 2>&1 | grep -v "^Picked up"
exit ${PIPESTATUS[0]}
