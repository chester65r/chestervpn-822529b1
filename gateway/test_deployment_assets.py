import pathlib
import unittest

ROOT = pathlib.Path(__file__).parent


class DeploymentAssetTests(unittest.TestCase):
    def test_unit_is_loopback_and_read_only(self):
        unit = (ROOT / "chestervpn-health.service").read_text()
        self.assertIn("--host 127.0.0.1", unit)
        self.assertIn("ProtectSystem=strict", unit)
        self.assertIn("NoNewPrivileges=true", unit)
        self.assertIn("CapabilityBoundingSet=CAP_NET_ADMIN", unit)
        self.assertNotIn("CAP_NET_ADMIN CAP_NET_RAW", unit)

    def test_installer_checks_existing_wireguard_and_does_not_change_firewall(self):
        script = (ROOT / "install-health-probe.sh").read_text()
        self.assertIn("systemctl is-active --quiet wg-quick@wg0.service", script)
        self.assertIn("/etc/wireguard/wg0.conf", script)
        self.assertNotIn("iptables", script)
        self.assertNotIn("nft flush", script)
        self.assertNotIn("wg-quick down", script)


if __name__ == "__main__":
    unittest.main()
