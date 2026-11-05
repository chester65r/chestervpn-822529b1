import base64
import os
import pathlib
import tempfile
import unittest

from peer_admin import PeerAdminError, _key, _validate_endpoint, _sections, apply


def key(seed):
    return base64.b64encode(bytes((seed + i) % 256 for i in range(32))).decode()


DEVICE, SERVER, PSK = key(1), key(40), key(80)
CONFIG = f"[Interface]\nPrivateKey = {key(120)}\nAddress = 10.66.66.1/24\nListenPort = 51820\n\n"


class PeerAdminUnitTests(unittest.TestCase):
    def test_key_validation(self):
        self.assertEqual(_key(DEVICE), DEVICE)
        for invalid in ("../bad", base64.b64encode(bytes(32)).decode()):
            with self.subTest(invalid=invalid), self.assertRaises(PeerAdminError):
                _key(invalid)

    def test_endpoint_validation(self):
        self.assertEqual(_validate_endpoint("vpn.example.org:51820"), "vpn.example.org:51820")
        self.assertEqual(_validate_endpoint("[2001:db8::1]:51820"), "[2001:db8::1]:51820")
        for bad in ("host:0", "host:70000", "host:abc", "a..b:55", "[not-ip]:55"):
            with self.subTest(bad=bad), self.assertRaises(PeerAdminError):
                _validate_endpoint(bad)

    def test_sections(self):
        interface, peers = _sections(CONFIG + f"[Peer]\nPublicKey = {DEVICE}\nAllowedIPs = 10.66.66.2/32\n")
        self.assertTrue(interface)
        self.assertEqual(len(peers), 1)

    def fake_runner(self, fail_sync=False, fail_sync_once=False):
        calls = []
        sync_failures = 0
        def runner(args, **kwargs):
            nonlocal sync_failures
            calls.append(args)
            class Result: stdout = ""
            if args[:2] == ["wg", "genpsk"]: Result.stdout = PSK
            elif args[:4] == ["wg", "show", "wg0", "public-key"]: Result.stdout = SERVER
            elif args[:2] == ["wg-quick", "strip"]: Result.stdout = "[Interface]\nPrivateKey = private-server-material\n"
            elif args[:2] == ["wg", "syncconf"] and (fail_sync or (fail_sync_once and sync_failures == 0)):
                sync_failures += 1
                raise OSError("simulated failure")
            return Result()
        return runner, calls

    def prepare(self, root, content=CONFIG):
        config = root / "wg0.conf"
        config.write_text(content)
        config.chmod(0o600)
        return config

    def apply(self, action, config, *args, **kwargs):
        return apply(action, config, "wg0", *args, owner_uid=os.geteuid(), euid=0, **kwargs)

    def test_enrollment_saves_no_device_private_key_and_protects_response(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp); config = self.prepare(root)
            private_dir = root / "private"; private_dir.mkdir(mode=0o700); private_dir.chmod(0o700)
            response = private_dir / "enrollment.conf"
            runner, calls = self.fake_runner()
            self.apply("enroll", config, DEVICE, "10.66.66.2/32", "vpn.example.org:51820", response, runner=runner, temp_root=tmp)
            self.assertIn(f"PublicKey = {DEVICE}", config.read_text())
            self.assertEqual(config.stat().st_mode & 0o777, 0o600)
            self.assertEqual(response.stat().st_mode & 0o777, 0o600)
            self.assertIn(f"PresharedKey = {PSK}", response.read_text())
            self.assertIn(f"PublicKey = {SERVER}", response.read_text())
            self.assertIn("DNS = 10.66.66.1", response.read_text())
            self.assertNotIn("PrivateKey =", response.read_text())
            self.assertNotIn("private-server-material", config.read_text())
            self.assertTrue(any(c[:2] == ["wg", "syncconf"] for c in calls))

    def test_duplicate_enrollment_denied(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp); config = self.prepare(root, CONFIG + f"[Peer]\nPublicKey = {DEVICE}\nAllowedIPs = 10.66.66.2/32\n")
            runner, calls = self.fake_runner()
            with self.assertRaises(PeerAdminError):
                self.apply("enroll", config, DEVICE, "10.66.66.3/32", "vpn.example.org:51820", root / "x.conf", runner=runner, temp_root=tmp)
            self.assertEqual(calls, [])

    def test_revoke_persists_and_syncs_without_firewall_calls(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp); config = self.prepare(root, CONFIG + f"[Peer]\nPublicKey = {DEVICE}\nAllowedIPs = 10.66.66.2/32\n")
            runner, calls = self.fake_runner()
            self.apply("revoke", config, DEVICE, runner=runner, temp_root=tmp)
            self.assertNotIn(f"PublicKey = {DEVICE}", config.read_text())
            self.assertTrue(any(c[:2] == ["wg", "syncconf"] for c in calls))
            self.assertFalse(any(c[0] in {"iptables", "nft", "ufw"} for c in calls))

    def test_revoke_sync_failure_still_removes_live_peer(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp); config = self.prepare(root, CONFIG + f"[Peer]\nPublicKey = {DEVICE}\nAllowedIPs = 10.66.66.2/32\n")
            runner, calls = self.fake_runner(fail_sync=True)
            with self.assertRaises(PeerAdminError):
                self.apply("revoke", config, DEVICE, runner=runner, temp_root=tmp)
            self.assertNotIn(f"PublicKey = {DEVICE}", config.read_text())
            self.assertTrue(any(c[:5] == ["wg", "set", "wg0", "peer", DEVICE] for c in calls))

    def test_enroll_sync_failure_rolls_back_and_deletes_response(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp); config = self.prepare(root); before = config.read_text()
            private_dir = root / "private"; private_dir.mkdir(mode=0o700); private_dir.chmod(0o700)
            response = private_dir / "enrollment.conf"
            runner, calls = self.fake_runner(fail_sync_once=True)
            with self.assertRaises(PeerAdminError):
                self.apply("enroll", config, DEVICE, "10.66.66.2/32", "vpn.example.org:51820", response, runner=runner, temp_root=tmp)
            self.assertEqual(config.read_text(), before)
            self.assertFalse(response.exists())
            self.assertEqual(sum(c[:2] == ["wg", "syncconf"] for c in calls), 2)

    def test_mode_and_root_check(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp); config = self.prepare(root)
            runner, calls = self.fake_runner()
            with self.assertRaises(PeerAdminError):
                apply("revoke", config, "wg0", DEVICE, runner=runner, temp_root=tmp, euid=1000, owner_uid=os.geteuid())
            config.chmod(0o644)
            with self.assertRaises(PeerAdminError):
                self.apply("revoke", config, DEVICE, runner=runner, temp_root=tmp)
            self.assertEqual(calls, [])


if __name__ == "__main__":
    unittest.main()
