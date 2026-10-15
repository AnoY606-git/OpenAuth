#!/usr/bin/env bash
# Copyright (C) 2026 AnoY606-git and SplitBox contributors
# SPDX-License-Identifier: GPL-3.0-only
# На основе scripts/gradle.sh из https://github.com/AnoY606-git/SplitBox.
set -euo pipefail
OPENAUTH_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
export JAVA_HOME="${OPENAUTH_JAVA_HOME:-$OPENAUTH_ROOT/.toolchain/jdk}"
if [[ ! -x "$JAVA_HOME/bin/java" ]]; then
    printf '%s\n' 'Не найден JDK. Запустите python3 scripts/bootstrap.py или задайте OPENAUTH_JAVA_HOME.' >&2
    exit 1
fi
export ANDROID_HOME="${OPENAUTH_ANDROID_HOME:-$OPENAUTH_ROOT/.toolchain/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$OPENAUTH_ROOT/.toolchain/android-user}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$OPENAUTH_ROOT/.toolchain/gradle-home}"
export PATH="$JAVA_HOME/bin:$PATH"
mkdir -p -- "$ANDROID_USER_HOME" "$GRADLE_USER_HOME"
cd -- "$OPENAUTH_ROOT"
if [[ $# -eq 0 ]]; then
    set -- ./gradlew
fi
exec "$@"
