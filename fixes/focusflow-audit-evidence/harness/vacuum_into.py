# F15 evidence: VACUUM INTO a destination that already exists. Uses Python's bundled SQLite (version printed), not the app's engine.
import sqlite3, tempfile, os
print("python sqlite version:", sqlite3.sqlite_version)
d = tempfile.mkdtemp(prefix="vac_")
src = os.path.join(d, "src.db"); dst = os.path.join(d, "dst.db"); empty = os.path.join(d, "empty.db")
c = sqlite3.connect(src); c.execute("create table t(x)"); c.execute("insert into t values (1)"); c.commit()
c.execute("VACUUM INTO ?", (dst,))
print("first VACUUM INTO a new file: ok, size", os.path.getsize(dst))
try:
    c.execute("VACUUM INTO ?", (dst,))
    print("second VACUUM INTO the SAME (non-empty) destination: ok")
except Exception as e:
    print("second VACUUM INTO the SAME (non-empty) destination fails:", type(e).__name__, e)
open(empty, "wb").close()
try:
    c.execute("VACUUM INTO ?", (empty,))
    print("VACUUM INTO an existing EMPTY file: ok, size", os.path.getsize(empty))
except Exception as e:
    print("VACUUM INTO an existing EMPTY file fails:", type(e).__name__, e)
