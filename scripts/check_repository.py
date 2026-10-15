#!/usr/bin/env python3
# Copyright (C) 2026 AnoY606-git and Open Auth contributors
# SPDX-License-Identifier: GPL-3.0-only
"""Проверяет имена отслеживаемых файлов; содержимое и секреты не анализирует."""
import argparse
import os
from pathlib import Path, PurePosixPath
import subprocess
import sys


INTERNAL_DIRECTORIES = frozenset({
    ".toolchain", ".superpowers", ".agents", ".codex", ".development", ".worktrees",
    "specs", "plans",
})
OUTPUT_DIRECTORIES = frozenset({
    "build", ".gradle", ".kotlin", ".idea", "__pycache__", ".cxx", ".externalnativebuild", "captures",
})
LOCAL_NAMES = frozenset({
    "local.properties", "keystore.properties", "signing.properties", "id_rsa", "id_dsa", "id_ecdsa", "id_ed25519",
})
OUTPUT_SUFFIXES = frozenset({
    ".apk", ".aab", ".apks", ".aar", ".dex", ".class", ".jar", ".pyc", ".hprof", ".log", ".tmp",
    ".so", ".o", ".a", ".dll", ".dylib", ".exe",
})
PRIVATE_SUFFIXES = frozenset({".jks", ".keystore", ".p12", ".pfx", ".pem", ".key", ".oab"})
FIXTURE_ROOTS = (
    "core/src/test/resources/fixtures/", "app/src/test/resources/fixtures/",
    "app/src/androidTest/resources/fixtures/", "scripts/tests/fixtures/",
)


def check_paths(paths):
    """Возвращает запрещённые пути, сохраняя порядок; доверяет только именам Git."""
    rejected = []
    for path in paths:
        parts = PurePosixPath(path).parts
        lower = tuple(part.lower() for part in parts)
        name = lower[-1] if lower else ""
        internal = (bool(INTERNAL_DIRECTORIES.intersection(lower))
                    or name in {"agents.md", "claude.md", "gemini.md"}
                    or any(lower[i:i + 2] in (("docs", "internal"), ("docs", "superpowers"))
                           for i in range(len(lower) - 1)))
        output_directory = bool(OUTPUT_DIRECTORIES.intersection(lower[:-1]))
        local = name in LOCAL_NAMES or name == ".env" or (name.startswith(".env.") and name != ".env.example")
        suffix = PurePosixPath(name).suffix
        binary = suffix in OUTPUT_SUFFIXES or suffix in PRIVATE_SUFFIXES
        fixture = path.startswith(FIXTURE_ROOTS)
        wrapper = path == "gradle/wrapper/gradle-wrapper.jar"
        if internal or output_directory or (local and not fixture) or (binary and not fixture and not wrapper):
            rejected.append(path)
    return rejected


def tracked_paths(root, *, env=None):
    """Читает NUL-разделённые пути индекса Git без shell и чтения файлов."""
    result = subprocess.run(
        ["git", "-C", os.fspath(root), "ls-files", "--cached", "-z"],
        env=env, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    )
    return [os.fsdecode(path) for path in result.stdout.split(b"\0") if path]


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent.parent)
    args = parser.parse_args(argv)
    try:
        rejected = check_paths(tracked_paths(args.root))
    except (OSError, subprocess.CalledProcessError):
        print("Не удалось прочитать индекс Git.", file=sys.stderr)
        return 2
    if rejected:
        print("Запрещённые отслеживаемые пути (проверка имён файлов):")
        for path in rejected:
            print("  " + ascii(path))
        return 1
    print("Проверка имён отслеживаемых файлов пройдена; содержимое файлов не анализировалось.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
