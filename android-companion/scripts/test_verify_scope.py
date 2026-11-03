"""Regression tests for the local-only Android manifest guard."""

from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

GOOD_MANIFEST = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <application android:allowBackup="false" android:usesCleartextTraffic="false">
        <activity android:name=".MainActivity" android:exported="true" />
    </application>
</manifest>
"""


class VerifyScopeTests(unittest.TestCase):
    def run_guard(self, manifest_text: str) -> subprocess.CompletedProcess[str]:
        source_script = Path(__file__).with_name("verify_scope.py")
        with tempfile.TemporaryDirectory() as temp_dir:
            project = Path(temp_dir) / "android-companion"
            script_dir = project / "scripts"
            manifest_dir = project / "app" / "src" / "main"
            script_dir.mkdir(parents=True)
            manifest_dir.mkdir(parents=True)
            test_script = script_dir / "verify_scope.py"
            test_script.write_text(source_script.read_text(encoding="utf-8"), encoding="utf-8")
            (manifest_dir / "AndroidManifest.xml").write_text(manifest_text, encoding="utf-8")
            return subprocess.run(
                [sys.executable, str(test_script)],
                check=False,
                capture_output=True,
                text=True,
            )

    def test_accepts_minimal_local_only_manifest(self) -> None:
        result = self.run_guard(GOOD_MANIFEST)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("Companion scope verified", result.stdout)

    def test_rejects_internet_permission(self) -> None:
        manifest = GOOD_MANIFEST.replace(
            '<application ',
            '<uses-permission android:name="android.permission.INTERNET" />\n    <application ',
        )
        result = self.run_guard(manifest)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("android.permission.INTERNET", result.stderr)

    def test_rejects_android_vpn_service(self) -> None:
        manifest = GOOD_MANIFEST.replace(
            "</application>",
            """<service android:name=".ExampleVpnService"
                android:permission="android.permission.BIND_VPN_SERVICE">
                <intent-filter>
                    <action android:name="android.net.VpnService" />
                </intent-filter>
            </service>
            </application>""",
        )
        result = self.run_guard(manifest)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("VPN service", result.stderr)

    def test_rejects_cleartext_and_backup(self) -> None:
        for attribute in ("android:usesCleartextTraffic=\"false\"", "android:allowBackup=\"false\""):
            with self.subTest(attribute=attribute):
                manifest = GOOD_MANIFEST.replace(attribute, attribute.replace("false", "true"))
                result = self.run_guard(manifest)
                self.assertNotEqual(result.returncode, 0)


if __name__ == "__main__":
    unittest.main()
