import sqlite3, sys, time
path, period_ms, seconds = sys.argv[1], float(sys.argv[2]), float(sys.argv[3])
c = sqlite3.connect(path, isolation_level=None, timeout=5)
c.execute("PRAGMA journal_mode=WAL")
i = 0
end = time.time() + seconds
while time.time() < end:
    c.execute("INSERT OR REPLACE INTO settings(key,value) VALUES('load_test', ?)", (str(i),))
    c.execute("SELECT value FROM settings WHERE key='load_test'").fetchone()
    i += 1
    time.sleep(period_ms / 1000.0)
print("writer did", i, "write+read cycles", flush=True)
