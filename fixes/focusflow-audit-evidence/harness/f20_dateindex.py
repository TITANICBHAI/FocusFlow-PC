# F20 evidence: DATE(start_time) = ? vs a range predicate on ISO-8601 text, with the app's index idx_sessions_start.
# Uses Python's bundled SQLite (version printed) - NOT the app's sqlite-jdbc 3.47.1.0 engine; query-plan shape is what matters.
import sqlite3, time, random, datetime, os, tempfile

print("python sqlite version:", sqlite3.sqlite_version)
db = os.path.join(tempfile.mkdtemp(prefix="f20_"), "t.db")
c = sqlite3.connect(db)
c.execute("CREATE TABLE focus_sessions (id TEXT PRIMARY KEY, start_time TEXT NOT NULL, actual_minutes INTEGER, completed INTEGER)")
c.execute("CREATE INDEX idx_sessions_start ON focus_sessions(start_time)")
random.seed(1)
base = datetime.datetime(2000, 1, 1, 0, 0, 0)
N = 200_000
STEP = 4320          # seconds -> ~20 rows/day -> 200k rows span ~27 years (2000-01-01 .. ~2027-05)
rows = []
for i in range(N):
    t = base + datetime.timedelta(seconds=i * STEP + random.randint(0, STEP - 1))
    # Same shape the app stores: DateTimeFormatter.ISO_LOCAL_DATE_TIME (seconds always, 1..9 fraction digits only if nonzero)
    frac = ""
    if i % 3 == 1: frac = ".%06d" % random.randint(1, 999999)
    if i % 3 == 2: frac = ".%09d" % random.randint(1, 999999999)
    s = t.strftime("%Y-%m-%dT%H:%M:%S") + frac
    rows.append((str(i), s, random.randint(5, 50), 1 if i % 5 else 0))
# explicit boundary rows
edge = [("e1", "2013-06-15T00:00:00", 7, 1), ("e2", "2013-06-15T23:59:59.999999999", 9, 1),
        ("e3", "2013-06-16T00:00:00", 11, 1), ("e4", "2013-06-14T23:59:59", 13, 1)]
rows += [(a, b, m, cpl) for a, b, m, cpl in edge]
c.executemany("INSERT INTO focus_sessions VALUES (?,?,?,?)", rows)
c.commit()
lo, hi = c.execute("SELECT MIN(start_time), MAX(start_time) FROM focus_sessions").fetchone()
print("rows:", len(rows), " span:", lo, "->", hi)

q_old = "SELECT COALESCE(SUM(actual_minutes),0), COUNT(*) FROM focus_sessions WHERE DATE(start_time) = ? AND completed = 1"
q_new = "SELECT COALESCE(SUM(actual_minutes),0), COUNT(*) FROM focus_sessions WHERE start_time >= ? AND start_time < ? AND completed = 1"
day = "2013-06-15"; nxt = "2013-06-16"
print("PLAN old:", c.execute("EXPLAIN QUERY PLAN " + q_old, (day,)).fetchall())
print("PLAN new:", c.execute("EXPLAIN QUERY PLAN " + q_new, (day, nxt)).fetchall())

# equivalence over every 2nd day of the whole span + the boundary day (non-empty days only count if they have rows)
mism = 0; checked = 0; nonempty = 0
d0 = datetime.date(2000, 1, 1)
for k in list(range(0, 9900, 2)) + [(datetime.date(2013, 6, 15) - d0).days]:
    a = (d0 + datetime.timedelta(days=k)).isoformat()
    b = (d0 + datetime.timedelta(days=k + 1)).isoformat()
    ro = c.execute(q_old, (a,)).fetchone(); rn = c.execute(q_new, (a, b)).fetchone()
    checked += 1; nonempty += (ro[1] > 0)
    if ro != rn: mism += 1
print("equivalence: days checked=%d (non-empty=%d) mismatches=%d" % (checked, nonempty, mism))
print("boundary day 2013-06-15 old=%s new=%s" % (c.execute(q_old, (day,)).fetchone(), c.execute(q_new, (day, nxt)).fetchone()))

def bench(q, args, n=60):
    t0 = time.perf_counter()
    for _ in range(n):
        r = c.execute(q, args).fetchone()
    return (time.perf_counter() - t0) / n * 1000, r
mid = "2013-06-15"; mid2 = "2013-06-16"
ms_old, r_old = bench(q_old, (mid,))
ms_new, r_new = bench(q_new, (mid, mid2))
print("old  DATE(start_time)=?   : %.2f ms/query  result=%s" % (ms_old, r_old))
print("new  range on start_time  : %.4f ms/query  result=%s" % (ms_new, r_new))
print("getFocusMinutesByDay(7) = 7 queries => old %.1f ms vs new %.3f ms (Python SQLite, single thread, NOT the app)" % (ms_old * 7, ms_new * 7))
