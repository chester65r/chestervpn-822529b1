import json
import unittest
from datetime import datetime, timezone
from unittest.mock import Mock

from health import probe


class HealthProbeTests(unittest.TestCase):
    def test_reports_interface_and_recent_handshakes_without_keys(self):
        recent = int(datetime.now(timezone.utc).timestamp())
        runner = Mock(side_effect=[
            Mock(stdout=json.dumps([{"ifname": "wg0", "flags": ["POINTOPOINT", "UP"], "operstate": "UNKNOWN"}])),
            Mock(stdout=f"publickeyAAA 0\npublickeyBBB {recent}\n"),
        ])
        result = probe("wg0", runner=runner)
        self.assertEqual(result["status"], "ok")
        self.assertEqual(result["peer_count"], 2)
        self.assertEqual(result["recent_handshakes_3m"], 1)
        self.assertNotIn("publickey", json.dumps(result))
        self.assertEqual(runner.call_args_list[0].args[0], ["ip", "-j", "link", "show", "dev", "wg0"])
        self.assertEqual(runner.call_args_list[1].args[0], ["wg", "show", "wg0", "latest-handshakes"])

    def test_invalid_interface_rejected_before_commands(self):
        runner = Mock()
        with self.assertRaises(ValueError):
            probe("wg0; touch /tmp/nope", runner=runner)
        runner.assert_not_called()

    def test_interface_without_up_flag_is_unhealthy(self):
        runner = Mock(return_value=Mock(stdout=json.dumps([{"ifname": "wg0", "flags": [], "operstate": "UNKNOWN"}])))
        self.assertEqual(probe("wg0", runner=runner)["status"], "unhealthy")
        runner.assert_called_once()

    def test_command_failure_fails_closed(self):
        runner = Mock(side_effect=RuntimeError("not available"))
        result = probe("wg0", runner=runner)
        self.assertEqual(result["status"], "unhealthy")
        self.assertNotIn("error", result)
        self.assertNotIn("peer_count", result)

    def test_stale_handshakes_are_not_recent(self):
        old = int(datetime.now(timezone.utc).timestamp()) - 301
        runner = Mock(side_effect=[
            Mock(stdout=json.dumps([{"ifname": "wg0", "flags": ["UP"]}])),
            Mock(stdout=f"somekey {old}\n"),
        ])
        self.assertEqual(probe("wg0", runner=runner)["recent_handshakes_3m"], 0)

    def test_malformed_wg_output_fails_closed(self):
        runner = Mock(side_effect=[
            Mock(stdout=json.dumps([{"ifname": "wg0", "flags": ["UP"]}])),
            Mock(stdout="malformed-peer-line\n"),
        ])
        self.assertEqual(probe("wg0", runner=runner)["status"], "unhealthy")


if __name__ == "__main__":
    unittest.main()
