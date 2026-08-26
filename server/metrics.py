"""Mr Nobody usage metrics store.

Records anonymous device checks on update pings and computes aggregate metrics
(Total unique devices, Daily Active Users, Weekly/Monthly Active Users, version distribution).

Privacy design:
* Zero personal data or hardware identifiers stored.
* Install IDs are hashed using SHA-256 before insertion into SQLite.
* Database is local SQLite with zero external dependencies.
"""

import hashlib
import json
import os
import sqlite3
import time
from datetime import datetime, timezone
from pathlib import Path


class MetricsStore:
    def __init__(self, db_path=None):
        if db_path is None:
            db_path = Path(__file__).resolve().parent / "metrics.db"
        self.db_path = Path(db_path).resolve()
        self._init_db()

    def _get_conn(self):
        conn = sqlite3.connect(str(self.db_path), timeout=5.0)
        conn.row_factory = sqlite3.Row
        return conn

    def _init_db(self):
        with self._get_conn() as conn:
            conn.execute("""
                CREATE TABLE IF NOT EXISTS devices (
                    device_hash TEXT PRIMARY KEY,
                    app_version TEXT,
                    first_seen INTEGER,
                    last_seen INTEGER,
                    check_count INTEGER DEFAULT 1
                )
            """)
            conn.execute("""
                CREATE TABLE IF NOT EXISTS daily_pings (
                    date TEXT,
                    device_hash TEXT,
                    app_version TEXT,
                    PRIMARY KEY (date, device_hash)
                )
            """)
            conn.execute("""
                CREATE INDEX IF NOT EXISTS idx_devices_last_seen ON devices(last_seen)
            """)
            conn.execute("""
                CREATE INDEX IF NOT EXISTS idx_daily_pings_date ON daily_pings(date)
            """)

    def record_ping(self, install_id=None, app_version=None, client_ip=None, user_agent=None):
        """Record an update check ping from a device. Returns (device_hash, is_new)."""
        now = int(time.time())
        today = datetime.now(timezone.utc).strftime("%Y-%m-%d")
        version = (app_version or "").strip() or "unknown"

        # If the app provided an anonymous install UUID, hash it with SHA-256.
        # If legacy client (no install_id), hash IP + User-Agent so older installs are still counted.
        if install_id and install_id.strip():
            raw_id = "inst:" + install_id.strip()
        else:
            raw_id = f"legacy:{client_ip or 'unknown'}:{user_agent or 'unknown'}"

        device_hash = hashlib.sha256(raw_id.encode("utf-8")).hexdigest()[:32]

        is_new = False
        try:
            with self._get_conn() as conn:
                cur = conn.cursor()
                cur.execute("SELECT 1 FROM devices WHERE device_hash = ?", (device_hash,))
                row = cur.fetchone()
                if row is None:
                    is_new = True
                    cur.execute("""
                        INSERT INTO devices (device_hash, app_version, first_seen, last_seen, check_count)
                        VALUES (?, ?, ?, ?, 1)
                    """, (device_hash, version, now, now))
                else:
                    cur.execute("""
                        UPDATE devices
                        SET app_version = CASE WHEN ? != 'unknown' THEN ? ELSE app_version END,
                            last_seen = ?,
                            check_count = check_count + 1
                        WHERE device_hash = ?
                    """, (version, version, now, device_hash))

                cur.execute("""
                    INSERT OR IGNORE INTO daily_pings (date, device_hash, app_version)
                    VALUES (?, ?, ?)
                """, (today, device_hash, version))
                conn.commit()
        except Exception as e:
            # Metrics recording must never take down the update server
            return device_hash, False

        return device_hash, is_new

    def get_summary(self):
        """Compute active device statistics and version breakdown."""
        now = int(time.time())
        one_day_ago = now - 86400
        seven_days_ago = now - (7 * 86400)
        thirty_days_ago = now - (30 * 86400)

        with self._get_conn() as conn:
            cur = conn.cursor()

            # Total unique devices
            cur.execute("SELECT COUNT(*) FROM devices")
            total_devices = cur.fetchone()[0]

            # Active windows
            cur.execute("SELECT COUNT(*) FROM devices WHERE last_seen >= ?", (one_day_ago,))
            active_24h = cur.fetchone()[0]

            cur.execute("SELECT COUNT(*) FROM devices WHERE last_seen >= ?", (seven_days_ago,))
            active_7d = cur.fetchone()[0]

            cur.execute("SELECT COUNT(*) FROM devices WHERE last_seen >= ?", (thirty_days_ago,))
            active_30d = cur.fetchone()[0]

            # Total pings across all devices
            cur.execute("SELECT COALESCE(SUM(check_count), 0) FROM devices")
            total_pings = cur.fetchone()[0]

            # Version distribution
            cur.execute("""
                SELECT app_version, COUNT(*) as cnt
                FROM devices
                GROUP BY app_version
                ORDER BY cnt DESC
            """)
            versions = {row["app_version"]: row["cnt"] for row in cur.fetchall()}

            # Recent daily active counts (last 14 days)
            cur.execute("""
                SELECT date, COUNT(DISTINCT device_hash) as active_count
                FROM daily_pings
                GROUP BY date
                ORDER BY date DESC
                LIMIT 14
            """)
            daily_history = [{"date": row["date"], "count": row["active_count"]} for row in cur.fetchall()]

        return {
            "total_devices": total_devices,
            "active_24h": active_24h,
            "active_7d": active_7d,
            "active_30d": active_30d,
            "total_pings": total_pings,
            "versions": versions,
            "daily_history": daily_history,
            "generated_at": datetime.now(timezone.utc).isoformat(),
        }

    def render_html(self, summary=None):
        """Render a clean, high-contrast dashboard matching Mr Nobody's theme."""
        if summary is None:
            summary = self.get_summary()

        total = summary["total_devices"]
        dau = summary["active_24h"]
        wau = summary["active_7d"]
        mau = summary["active_30d"]
        total_pings = summary["total_pings"]

        # Versions HTML
        version_rows = []
        for ver, count in summary["versions"].items():
            pct = round((count / total * 100), 1) if total > 0 else 0
            version_rows.append(f"""
              <tr>
                <td style="padding:10px 14px;border-bottom:1px solid #282525;font-family:monospace;color:#f7f5f1;">v{ver}</td>
                <td style="padding:10px 14px;border-bottom:1px solid #282525;color:#aaa5a6;">{count:,}</td>
                <td style="padding:10px 14px;border-bottom:1px solid #282525;">
                  <div style="background:#282525;border-radius:3px;height:6px;width:120px;overflow:hidden;display:inline-block;vertical-align:middle;margin-right:8px;">
                    <div style="background:#f7f6e9;height:100%;width:{pct}%;"></div>
                  </div>
                  <span style="font-family:monospace;font-size:11px;color:#77747b;">{pct}%</span>
                </td>
              </tr>
            """)
        version_table_body = "".join(version_rows) if version_rows else "<tr><td colspan='3' style='padding:14px;color:#77747b;'>No device checks recorded yet.</td></tr>"

        # Daily history HTML
        daily_rows = []
        for item in summary["daily_history"]:
            daily_rows.append(f"""
              <tr>
                <td style="padding:8px 14px;border-bottom:1px solid #282525;font-family:monospace;color:#f7f5f1;">{item['date']}</td>
                <td style="padding:8px 14px;border-bottom:1px solid #282525;font-weight:600;color:#2ecc71;">{item['count']:,} active</td>
              </tr>
            """)
        daily_table_body = "".join(daily_rows) if daily_rows else "<tr><td colspan='2' style='padding:14px;color:#77747b;'>No daily history yet.</td></tr>"

        return f"""<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <title>Mr Nobody — App Usage Metrics</title>
  <style>
    :root {{
      --brown:#171312; --black:#090909; --card:#101010; --white:#f7f5f1;
      --gray:#77747b; --copy:#aaa5a6; --line:#282525; --cream:#f7f6e9;
      --green:#2ecc71;
    }}
    * {{ box-sizing:border-box; margin:0; padding:0; }}
    body {{ background:var(--brown); color:var(--white); font-family:-apple-system,BlinkMacSystemFont,Segoe UI,Roboto,sans-serif; padding:40px 20px; }}
    .container {{ max-width:960px; margin:0 auto; display:flex; flex-direction:column; gap:28px; }}
    .header {{ display:flex; justify-content:space-between; align-items:center; border-bottom:1px solid var(--line); padding-bottom:20px; flex-wrap:wrap; gap:16px; }}
    .title {{ font-size:24px; font-weight:700; letter-spacing:-0.03em; }}
    .badge {{ display:inline-flex; align-items:center; gap:6px; font-family:monospace; font-size:11px; padding:4px 10px; background:var(--card); border:1px solid var(--line); border-radius:99px; color:var(--gray); }}
    .dot {{ width:6px; height:6px; border-radius:50%; background:var(--green); }}
    .stats-grid {{ display:grid; grid-template-columns:repeat(auto-fit,minmax(200px,1fr)); gap:16px; }}
    .card {{ background:var(--card); border:1px solid var(--line); border-radius:8px; padding:24px; display:flex; flex-direction:column; gap:8px; }}
    .card-label {{ font-family:monospace; font-size:11px; color:var(--gray); text-transform:uppercase; letter-spacing:.08em; }}
    .card-num {{ font-size:36px; font-weight:700; letter-spacing:-.04em; color:var(--white); }}
    .card-sub {{ font-size:12px; color:var(--copy); }}
    .section-title {{ font-size:16px; font-weight:600; margin-bottom:12px; }}
    table {{ width:100%; border-collapse:collapse; background:var(--card); border:1px solid var(--line); border-radius:8px; overflow:hidden; font-size:13px; text-align:left; }}
    th {{ background:var(--black); padding:12px 14px; color:var(--gray); font-family:monospace; font-size:11px; text-transform:uppercase; border-bottom:1px solid var(--line); }}
    .footer {{ font-family:monospace; font-size:11px; color:var(--gray); display:flex; justify-content:space-between; border-top:1px solid var(--line); padding-top:20px; }}
    a {{ color:var(--cream); text-decoration:none; }}
    a:hover {{ text-decoration:underline; }}
  </style>
</head>
<body>
  <div class="container">
    <div class="header">
      <div>
        <div class="title">Mr Nobody · Device Metrics</div>
        <p style="font-size:13px;color:var(--copy);margin-top:4px;">Anonymous active device counts recorded via update check pings.</p>
      </div>
      <div class="badge">
        <span class="dot"></span>
        <span>Auto-recorded via /update.json</span>
      </div>
    </div>

    <div class="stats-grid">
      <div class="card">
        <div class="card-label">Total Unique Devices</div>
        <div class="card-num">{total:,}</div>
        <div class="card-sub">All distinct devices ever recorded</div>
      </div>
      <div class="card">
        <div class="card-label">Active Last 24h (DAU)</div>
        <div class="card-num" style="color:var(--green);">{dau:,}</div>
        <div class="card-sub">Devices active today</div>
      </div>
      <div class="card">
        <div class="card-label">Active Last 7 Days (WAU)</div>
        <div class="card-num">{wau:,}</div>
        <div class="card-sub">Weekly active devices</div>
      </div>
      <div class="card">
        <div class="card-label">Active Last 30 Days (MAU)</div>
        <div class="card-num">{mau:,}</div>
        <div class="card-sub">Monthly active devices</div>
      </div>
    </div>

    <div style="display:grid; grid-template-columns:repeat(auto-fit,minmax(380px,1fr)); gap:20px;">
      <div>
        <div class="section-title">App Version Distribution</div>
        <table>
          <thead>
            <tr>
              <th>Version</th>
              <th>Devices</th>
              <th>Share</th>
            </tr>
          </thead>
          <tbody>
            {version_table_body}
          </tbody>
        </table>
      </div>

      <div>
        <div class="section-title">Daily Active History (Last 14 Days)</div>
        <table>
          <thead>
            <tr>
              <th>Date (UTC)</th>
              <th>Active Devices</th>
            </tr>
          </thead>
          <tbody>
            {daily_table_body}
          </tbody>
        </table>
      </div>
    </div>

    <div class="footer">
      <span>Total check requests recorded: {total_pings:,}</span>
      <div>
        <a href="/stats.json" target="_blank">Raw JSON API</a> · 
        <a href="/">Landing Page</a>
      </div>
    </div>
  </div>
</body>
</html>"""
