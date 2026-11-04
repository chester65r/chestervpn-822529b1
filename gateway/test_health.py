import json
import unittest
from datetime import datetime, timezone
from unittest.mock import Mock

from health import probe


class HealthProbeTests(unittest.TestCase):
    def test_reports_link_and_recent_handshakes_without_public_keys(self):
        recent = int(datetime.now(timezone.utc).timestamp())
        runner = Mock(side_effect=[
            Mock(stdout=json.dumps([{"ifname": "wg0", "operstate": "UP"}])),
            Mock(stdout=f"publickeyAAA 0\npublickeyBBB {recent}\n"),
        ])
        result = probe("wg0", runner=runner)
        self.assertEqual(result["status"], "ok")
        self.assertEqual(result["peer_count"], 2)
        self.assertEqual(result["recent_handshakes_3m"], 1)
        self.assertNotIn("publickey", json.dumps(result))
        self.assertEqual(runner.call_args_list[0].args[0], ["ip", "-j", "link", "show", "dev", "wg0"])
        self.assertEqual(runner.call_args_list[1].args[0], ["wg", "show", "wg0", "latest-handshakes"])

    def test_invalid_interface_rejected_before_running_commands(self):
        runner = Mock()
        with self.assertRaises(ValueError):
            probe("wg0; touch /tmp/nope", runner=runner)
        runner.assert_not_called()

    def test_unavailable_or_down_interface_fails_closed(self):
        runner = Mock(side_effect=RuntimeError("not available"))
        result = probe("wg0", runner=runner)
        self.assertEqual(result["status"], "unhealthy")
        self.assertNotIn("error", result)
        self.assertNotIn("peer_count", result)

    def test_stale_handshakes_do_not_count_as_recent(self):
        old = int(datetime.now(timezone.utc).timestamp()) - 301
        runner = Mock(side_effect=[
            Mock(stdout=json.dumps([{"ifname": "wg0", "operstate": "UP"}])),
            Mock(stdout=f"somekey {old}\n"),
        ])
        self.assertEqual(probe("wg0", runner=runner)["recent_handshakes_3m"], 0)


if __name__ == "__main__":
    unittest.main()
