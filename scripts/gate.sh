#!/usr/bin/env bash
# The local merge gate (PLAN §3), incremental builds only:
#   asset lint → :desktop:compileKotlin → :web:compileKotlinWasmJs → golden-JSON tests → :ios:compileKotlinIosSimulatorArm64 (Mac)
#   → :desktop:engineCheck → :desktop:homeCheck (only with --home, needs the owner's cookies)
#   scripts/gate.sh [--quick] [--home]      --quick: no engineCheck / iOS
set -uo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd); cd "$ROOT" || exit 2
quick=0; home=0
for a in "$@"; do case "$a" in --quick) quick=1 ;; --home) home=1 ;; esac; done
if [ -z "${JAVA_HOME:-}" ] && [ -x /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/bin/java ]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
fi
fails=()
run() { local name=$1; shift; echo "== $name"; if "$@"; then echo "   ok"; else fails+=("$name"); echo "   FAILED"; fi; }
run "asset lint" scripts/asset-lint.sh
run "desktop compile" ./gradlew -q :desktop:compileKotlin
run "web compile" ./gradlew -q :web:compileKotlinWasmJs
run "golden-JSON" ./gradlew -q --init-script tests/golden/golden.init.gradle :desktop:goldenCheck
if [ $quick = 0 ]; then
  [ "$(uname)" = Darwin ] && run "iOS compile" ./gradlew -q :ios:compileKotlinIosSimulatorArm64
  run "engineCheck" ./gradlew -q :desktop:engineCheck
  run "accountCheck" ./gradlew -q :desktop:accountCheck
fi
[ $home = 1 ] && run "homeCheck" ./gradlew -q :desktop:homeCheck
if [ ${#fails[@]} = 0 ]; then echo "gate: all green"; else echo "gate: FAILED: ${fails[*]}"; exit 1; fi
