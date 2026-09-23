#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
OUT=$(mktemp -d)
kotlinc \
 "$ROOT/android/app/src/main/java/com/hammam/attendai/domain/model/AttendanceModels.kt" \
 "$ROOT/android/app/src/main/java/com/hammam/attendai/domain/attendance/AttendanceEngine.kt" \
 "$ROOT/android/app/src/main/java/com/hammam/attendai/domain/attendance/ConfidenceEngine.kt" \
 "$ROOT/android/app/src/main/java/com/hammam/attendai/domain/attendance/PresenceStateMachine.kt" \
 "$ROOT/android/app/src/main/java/com/hammam/attendai/domain/attendance/AntiFraudEngine.kt" \
 "$ROOT/android/app/src/main/java/com/hammam/attendai/domain/attendance/SessionClockGuard.kt" \
 "$ROOT/android/app/src/main/java/com/hammam/attendai/security/Pbkdf2Sha256.kt" \
 "$ROOT/android/app/src/main/java/com/hammam/attendai/ble/BleRuntimePolicy.kt" \
 "$ROOT/android/app/src/main/java/com/hammam/attendai/ble/RotatingPresenceToken.kt" \
 "$ROOT/scripts/CoreValidation.kt" -include-runtime -d "$OUT/core.jar"
java -jar "$OUT/core.jar"
rm -rf "$OUT"
