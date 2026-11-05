from pathlib import Path
import re
import unittest

SQL = (Path(__file__).parent / "0003_vpn_control_plane.sql").read_text()


class ControlPlaneMigrationTests(unittest.TestCase):
    def test_expected_tables_have_rls(self):
        tables = ["vpn_devices", "vpn_gateways", "vpn_peers", "vpn_sessions", "vpn_gateway_health", "vpn_audit_events"]
        for table in tables:
            with self.subTest(table=table):
                self.assertRegex(SQL, rf"create table public\.{table}\s*\(")
                self.assertRegex(SQL, rf"alter table public\.{table} enable row level security")

    def test_private_credential_fields_are_not_stored(self):
        columns = re.findall(r"^\s{2}([a-z_]+)\s+(?:text|varchar|bytea)\b", SQL, re.MULTILINE)
        banned = {"private_key", "preshared_key", "secret", "token", "credential", "config"}
        self.assertFalse(set(columns) & banned)
        self.assertIn("device private keys remain on-device", SQL)

    def test_client_write_access_does_not_authorize_peers_or_gateway(self):
        self.assertIn("grant select, insert on public.vpn_devices to authenticated", SQL)
        self.assertNotRegex(SQL, r"grant\s+(?:[^;]*\b(?:insert|update|delete|all)\b[^;]*)\s+on public\.vpn_(?:gateways|peers|sessions|gateway_health|audit_events)\s+to authenticated")
        self.assertIn("users register their own public device key", SQL)
        self.assertIn("state = 'pending' and revoked_at is null", SQL)

    def test_peer_addresses_and_tenant_identity_are_constrained(self):
        self.assertIn("foreign key (device_id, user_id)", SQL)
        self.assertIn("foreign key (gateway_id, user_id)", SQL)
        self.assertIn("unique (gateway_id, assigned_address)", SQL)
        self.assertIn("masklen(assigned_address) = 32", SQL)

    def test_migration_explicitly_marked_unapplied(self):
        self.assertIn("PREPARED MIGRATION", SQL)


if __name__ == "__main__":
    unittest.main()
