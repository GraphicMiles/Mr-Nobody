"""Unit tests for Mr Nobody update & metrics server."""

import os
import tempfile
import unittest
from pathlib import Path

from metrics import MetricsStore


class TestMetricsStore(unittest.TestCase):
    def setUp(self):
        self.tmp_dir = tempfile.TemporaryDirectory()
        self.db_path = Path(self.tmp_dir.name) / "test_metrics.db"
        self.store = MetricsStore(self.db_path)

    def tearDown(self):
        self.tmp_dir.cleanup()

    def test_record_single_device(self):
        h, is_new = self.store.record_ping(install_id="device-1", app_version="1.0.7")
        self.assertTrue(is_new)
        self.assertTrue(len(h) > 0)

        # Second ping from same device should not be new
        h2, is_new2 = self.store.record_ping(install_id="device-1", app_version="1.0.7")
        self.assertFalse(is_new2)
        self.assertEqual(h, h2)

        summary = self.store.get_summary()
        self.assertEqual(summary["total_devices"], 1)
        self.assertEqual(summary["active_24h"], 1)
        self.assertEqual(summary["total_pings"], 2)
        self.assertEqual(summary["versions"], {"1.0.7": 1})

    def test_multiple_devices_and_versions(self):
        self.store.record_ping(install_id="dev-a", app_version="1.0.7")
        self.store.record_ping(install_id="dev-b", app_version="1.0.7")
        self.store.record_ping(install_id="dev-c", app_version="1.0.8")

        summary = self.store.get_summary()
        self.assertEqual(summary["total_devices"], 3)
        self.assertEqual(summary["active_24h"], 3)
        self.assertEqual(summary["versions"]["1.0.7"], 2)
        self.assertEqual(summary["versions"]["1.0.8"], 1)

    def test_legacy_device_fallback(self):
        # When no install_id is passed, client_ip + user_agent is hashed
        h1, new1 = self.store.record_ping(client_ip="1.2.3.4", user_agent="Mozilla/5.0")
        self.assertTrue(new1)
        h2, new2 = self.store.record_ping(client_ip="1.2.3.4", user_agent="Mozilla/5.0")
        self.assertFalse(new2)
        self.assertEqual(h1, h2)

    def test_render_html(self):
        self.store.record_ping(install_id="dev-1", app_version="1.0.7")
        html = self.store.render_html()
        self.assertIn("Mr Nobody · Device Metrics", html)
        self.assertIn("Total Unique Devices", html)
        self.assertIn("v1.0.7", html)


if __name__ == "__main__":
    unittest.main()
