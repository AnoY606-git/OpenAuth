# Copyright (C) 2026 AnoY606-git and Open Auth contributors
# SPDX-License-Identifier: GPL-3.0-only
"""Проверки контракта состава Git без чтения содержимого файлов."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import check_repository


class RepositoryPolicyTest(unittest.TestCase):
    def test_rejects_internal_materials_at_any_depth(self):
        paths = [".toolchain/jdk/bin/java", ".superpowers/brief.md", ".agents/a.md",
                 ".codex/config.toml", "docs/internal/report.md", "docs/superpowers/plan.md",
                 "docs/specs/vault.md", "docs/plans/implementation.md", "specs/a.md",
                 "plans/a.md", "module/AGENTS.md", "CLAUDE.md", "GEMINI.md"]
        self.assertEqual(set(paths), set(check_repository.check_paths(paths)))

    def test_rejects_local_credentials_and_outputs(self):
        paths = [".env", ".env.production", "app/local.properties", "signing.properties",
                 "keystore.properties", "keys/private.pem", "release.jks", "key.keystore",
                 "key.p12", "key.key", "id_rsa", "app/build/result.txt", ".gradle/cache",
                 "download.apk", "release.aab", "classes.dex", "output.class", "x.oab",
                 "download.jar", "__pycache__/x.pyc"]
        self.assertEqual(set(paths), set(check_repository.check_paths(paths)))

    def test_allows_product_sources_wrapper_and_explicit_synthetic_fixtures(self):
        paths = ["README.md", "docs/backup.md", "app/src/main/Example.kt", ".env.example",
                 "config/.env.example", "gradle/wrapper/gradle-wrapper.jar",
                 "core/src/test/resources/fixtures/corrupt.oab",
                 "app/src/androidTest/resources/fixtures/example.pem",
                 "scripts/tests/fixtures/fake.key"]
        self.assertEqual([], check_repository.check_paths(paths))

    def test_fixture_exception_does_not_allow_internal_instructions(self):
        paths = ["scripts/tests/fixtures/AGENTS.md", "fixtures/real.oab",
                 "app/src/main/resources/fixtures/key.pem"]
        self.assertEqual(set(paths), set(check_repository.check_paths(paths)))

    def test_env_prefix_does_not_reject_unrelated_product_filename(self):
        self.assertEqual([], check_repository.check_paths(["docs/.environment.md"]))

    def test_rejects_android_and_native_build_binaries(self):
        paths = ["library.aar", "native/libvault.so", "native/crypto.o", "tool.exe"]
        self.assertEqual(paths, check_repository.check_paths(paths))

    def test_rejects_downloaded_oauth_client_configuration(self):
        paths = ["client_secret_123.apps.googleusercontent.com.json", "config/client_secret_android.json",
                 "scripts/tests/fixtures/client_secret_example.json", "nested/CLIENT_SECRET_ANDROID.JSON"]
        self.assertEqual(paths, check_repository.check_paths(paths))
        self.assertEqual([], check_repository.check_paths(["docs/oauth-client-example.json", "client_secret_notes.md"]))

    def test_reads_only_tracked_names_from_temporary_git_index(self):
        with tempfile.TemporaryDirectory(prefix="openauth policy ") as directory:
            root = Path(directory)
            env = dict(os.environ, GIT_INDEX_FILE=str(root / "temporary-index"))
            subprocess.run(["git", "init", "--quiet", str(root)], check=True)
            unusual = "strange name.txt" if os.name == "nt" else "strange\nname.txt"
            tracked = ["docs/backup.md", unusual, "$(touch SHOULD_NOT_EXIST).txt", ".env"]
            for name in tracked:
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("синтетические данные", encoding="utf-8")
            (root / "untracked.oab").write_text("fixture", encoding="utf-8")
            subprocess.run(["git", "-C", str(root), "add", "--", *tracked], env=env, check=True)
            self.assertEqual(set(tracked), set(check_repository.tracked_paths(root, env=env)))
            self.assertEqual([".env"], check_repository.check_paths(check_repository.tracked_paths(root, env=env)))
            self.assertFalse((root / "SHOULD_NOT_EXIST").exists())

    def test_nul_separated_git_output_preserves_newlines_on_every_platform(self):
        from unittest.mock import patch
        output = subprocess.CompletedProcess([], 0, stdout=b"docs/backup.md\0strange\nname.txt\0")
        with patch.object(check_repository.subprocess, "run", return_value=output):
            self.assertEqual(["docs/backup.md", "strange\nname.txt"], check_repository.tracked_paths(Path(".")))

    def test_cli_rejects_forbidden_tracked_path_with_nonzero_exit(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            subprocess.run(["git", "init", "--quiet", str(root)], check=True)
            (root / ".env").write_text("sensitive text must not be printed", encoding="utf-8")
            subprocess.run(["git", "-C", str(root), "add", ".env"], check=True)
            result = subprocess.run([sys.executable, str(Path(check_repository.__file__)), "--root", str(root)], capture_output=True, text=True)
            self.assertEqual(1, result.returncode)
            self.assertIn(".env", result.stdout)
            self.assertNotIn("sensitive text", result.stdout + result.stderr)

    def test_cli_reports_non_repository_as_error(self):
        with tempfile.TemporaryDirectory() as directory:
            result = subprocess.run([sys.executable, str(Path(check_repository.__file__)), "--root", directory], capture_output=True, text=True)
            self.assertEqual(2, result.returncode)


if __name__ == "__main__":
    unittest.main()
