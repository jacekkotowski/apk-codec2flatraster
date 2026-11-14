# Compile flags and source list for the DRED library (Opus 1.6.1 with DRED on), shared by build.sh and the tests.
# No config.h and no processor-specific source files, so the same files build for every processor type.
# Source with OPUS set to the Opus folder; gives OPUSFLAGS and OPUSSRCS.
OPUS=${OPUS:-third_party/opus-1.6.1}
OPUSFLAGS="-O2 -DOPUS_BUILD -DVAR_ARRAYS -DHAVE_LRINT -DHAVE_LRINTF -DFLOAT_APPROX -DENABLE_DRED -DENABLE_DEEP_PLC -DENABLE_RES24 -DDISABLE_DEBUG_FLOAT -I$OPUS -I$OPUS/include -I$OPUS/celt -I$OPUS/silk -I$OPUS/silk/float -I$OPUS/dnn -I$OPUS/src"
# The source lists come from the upstream .mk files.
OPUSSRCS=$(for v in OPUS_SOURCES OPUS_SOURCES_FLOAT CELT_SOURCES SILK_SOURCES SILK_SOURCES_FLOAT DEEP_PLC_SOURCES DRED_SOURCES; do
  cat "$OPUS"/*_sources.mk | sed ':a;/\\$/{N;s/\\\n//;ba}' | grep "^$v *=" | sed 's/^[A-Z_]* *= *//'
done | tr ' \t' '\n\n' | grep '\.c$' | sed "s|^|$OPUS/|" | tr '\n' ' ')
