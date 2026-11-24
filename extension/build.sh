#!/bin/sh
# The whole extension: the Kotlin/Wasm app (release build, binaryen-optimized), then dist/{chrome,firefox} and their zips.
# The optimize task can stay UP-TO-DATE on a new link (KGP 2.2), so it is always re-run.
set -e
cd "$(dirname "$0")/.."
./gradlew -q ${GRADLE_ARGS} :web:compileProductionExecutableKotlinWasmJs
./gradlew -q ${GRADLE_ARGS} :web:compileProductionExecutableKotlinWasmJsOptimize --rerun
node extension/build.mjs
