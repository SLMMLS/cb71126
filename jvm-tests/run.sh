#!/usr/bin/env bash
# Runs the file-transfer tests of the phone app on a plain JVM: no Android, no Gradle, no emulator.
# They cover the logic that does not need Android (names, sealing, receiving, sending, the queue) and talk to a
# real HTTP server inside the test, so a wrong byte on the wire shows up. See ../CHANGES.md (round 5).
#
# Needs: JDK 17+, kotlinc 2.0.x, android.jar (API 34, only to compile), BouncyCastle (bcprov), org.json
# (a real one: the copy inside android.jar is a stub that throws). Point to them with environment variables.
set -euo pipefail

: "${KOTLINC:?path to the kotlinc script, e.g. ~/kotlinc/bin/kotlinc}"
: "${ANDROID_JAR:?path to android.jar of API 34}"
: "${BCPROV_JAR:?path to bcprov-jdk18on-*.jar}"
: "${ORG_JSON_JAR:?path to a real org.json jar}"
KOTLIN_LIB="${KOTLIN_LIB:-$(dirname "$KOTLINC")/../lib}"

HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$HERE/../app/src/main/kotlin/com/clipsync/app"
OUT="$(mktemp -d)"
CP="$KOTLIN_LIB/kotlin-stdlib.jar:$KOTLIN_LIB/kotlinx-coroutines-core-jvm.jar:$BCPROV_JAR:$ORG_JSON_JAR"

"$KOTLINC" -no-stdlib -cp "$CP:$ANDROID_JAR" -jvm-target 17 -d "$OUT" \
  "$APP"/files/FileNames.kt "$APP"/files/FileProtocol.kt "$APP"/files/TransferModels.kt \
  "$APP"/files/FileReceiver.kt "$APP"/files/FileSender.kt "$APP"/files/TransferManager.kt "$APP"/files/FileSources.kt \
  "$APP"/crypto/CryptoUtils.kt "$APP"/crypto/Identity.kt \
  "$APP"/network/NetworkErrors.kt "$APP"/network/PinnedHttp.kt "$APP"/network/Protocol.kt \
  "$HERE"/stubs/SyncPort.kt \
  "$HERE"/Main.kt "$HERE"/TestKit.kt "$HERE"/ReceiverTests.kt "$HERE"/SenderTests.kt "$HERE"/ManagerTests.kt

# A UTF-8 locale: the tests create files with Cyrillic names, which a JVM in the "C" locale cannot do.
LC_ALL="${LC_ALL:-C.UTF-8}" java -cp "$OUT:$CP" com.clipsync.jvmtests.MainKt "$HERE/../../src-tauri/tests-data/file-name-vectors.json"
