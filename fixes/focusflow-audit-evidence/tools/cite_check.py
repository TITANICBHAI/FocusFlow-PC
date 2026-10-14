#!/usr/bin/env python3
"""Citation checker for PLAN.md.

For every `File.ext:LINES` (and short-form `:LINES` that follows an explicit file name on the same plan line)
 - verifies the file exists in the audited tree / upstream clone and the line numbers are in range,
 - runs a plausibility test: identifiers that appear (backticked) just before the citation should occur
   inside the cited lines (+/- SLACK lines),
 - prints only problems (OUT-OF-RANGE, NO-FILE, CHECK) plus a summary.

SECRET SAFETY: never prints text from CrashReporter.kt lines 58-68 or ReviewPromptService.kt lines 55-65,
and never prints any source line containing a >=40 char base64-ish token.
"""
import os, re, sys, glob, subprocess, collections

SCR = "/tmp/claude-0/-home-claude/ae498799-be00-567f-b13b-23b1405eefe9/scratchpad"
APP = SCR + "/src"
UP = "/home/claude/xerial/sqlite-jdbc/src/main/java/org/sqlite"
JDK = SCR + "/jdk-tree/jdk"
PLAN = sys.argv[1] if len(sys.argv) > 1 else SCR + "/PLAN.md"
SLACK = 3
VERBOSE = "-v" in sys.argv
SHORT_ONLY = "-s" in sys.argv
REVIEW = "-r" in sys.argv

SECRET = {"CrashReporter.kt": (58, 68), "ReviewPromptService.kt": (55, 65)}
TOKEN = re.compile(r"[A-Za-z0-9+/=]{40,}")

# ---------- file map ----------
fmap = collections.defaultdict(list)
for dp, dn, fn in os.walk(APP + "/src"):
    for f in fn:
        if f.endswith((".kt",)):
            fmap[f].append(os.path.join(dp, f))
for f in ("build.gradle.kts", "settings.gradle.kts", "gradle.properties"):
    fmap[f].append(os.path.join(APP, f))
for dp, dn, fn in os.walk(UP):
    for f in fn:
        if f.endswith(".java"):
            fmap[f].append(os.path.join(dp, f))
# JDK SelectorProvider (blob from the partial clone)
jdk_tmp = SCR + "/tools/_SelectorProvider.java"
if not os.path.exists(jdk_tmp):
    try:
        out = subprocess.run(["git", "-C", JDK, "show", "HEAD:src/java.base/share/classes/java/nio/channels/spi/SelectorProvider.java"],
                             capture_output=True, text=True, timeout=120).stdout
        open(jdk_tmp, "w", encoding="utf-8").write(out)
    except Exception as e:
        print("could not fetch JDK blob:", e)
if os.path.exists(jdk_tmp):
    fmap["SelectorProvider.java"].append(jdk_tmp)

cache = {}
def lines_of(path):
    if path not in cache:
        cache[path] = open(path, encoding="utf-8", errors="replace").read().split("\n")
    return cache[path]

# ---------- parse plan ----------
plan = open(PLAN, encoding="utf-8").read().split("\n")
FILE_RE = r"((?:[A-Za-z0-9_-]+\.)*[A-Za-z0-9_-]+\.(?:kt|kts|java|properties))"
SPEC = r"\d+(?:-\d+)?(?:\s*,\s*\d+(?:-\d+)?)*"
tok_re = re.compile(r"`?" + FILE_RE + r":(" + SPEC + r")|`(:" + SPEC + r")`|\(:(" + SPEC + r")\)|, body `:(" + SPEC + r")`|\bbody `:(" + SPEC + r")`|`:(" + SPEC + r")`")
# simpler approach: scan sequentially for explicit and short forms
explicit = re.compile(FILE_RE + r":(" + SPEC + r")")
short = re.compile(r"(?<![A-Za-z0-9_.])`?:(" + SPEC + r")`?")

def parse_spec(spec):
    out = []
    for part in re.split(r"\s*,\s*", spec):
        if "-" in part:
            a, b = part.split("-")
            out.append((int(a), int(b)))
        else:
            out.append((int(part), int(part)))
    return out

def idents_before(text, pos, window=170):
    seg = text[max(0, pos - window): pos]
    ids = set()
    for m in re.finditer(r"`([^`]{2,80})`", seg):
        for w in re.findall(r"[A-Za-z_][A-Za-z0-9_]{3,}", m.group(1)):
            ids.add(w.lower())
    return ids

def safe_text(fname, ln, text):
    if fname in SECRET:
        lo, hi = SECRET[fname]
        if lo <= ln <= hi:
            return "[SECRET RANGE - not displayed]"
    if TOKEN.search(text):
        return "[LONG TOKEN LINE - not displayed]"
    return text.strip()[:110]

problems = []
total = 0
ok = 0
for ln_no, line in enumerate(plan, 1):
    cur = None
    # collect events in order: explicit and short
    events = []
    for m in explicit.finditer(line):
        events.append((m.start(), "E", m.group(1), m.group(2), m.end()))
    for m in short.finditer(line):
        # skip those that are part of an explicit citation (preceded by file name)
        if any(e[0] <= m.start() < e[4] for e in events):
            continue
        events.append((m.start(), "S", None, m.group(1), m.end()))
    events.sort()
    for pos, kind, fname, spec, end in events:
        if kind == "E":
            cur = fname
        else:
            fname = cur
        if fname is None:
            continue
        # skip things that look like times / ports etc: require backtick or paren context for short forms
        if kind == "S":
            ctx = line[max(0, pos - 1): pos + 1]
            if not ("`" in ctx or "(" in ctx or " " in ctx):
                continue
        total += 1
        if SHORT_ONLY and kind == "S":
            _p = fmap.get(fname)
            if _p:
                _L = lines_of(_p[0]); _a = parse_spec(spec)[0][0]
                print("SHORT plan:%-4d inferred=%s:%s | %s" % (ln_no, fname, spec, safe_text(os.path.basename(_p[0]), _a, _L[_a-1])))
        paths = fmap.get(fname)
        if not paths:
            problems.append((ln_no, "NO-FILE", fname, spec, "file not found in tree/upstream"))
            continue
        # choose path: if multiple, prefer first
        path = paths[0]
        L = lines_of(path)
        ranges = parse_spec(spec)
        bad = [r for r in ranges if r[1] > len(L) or r[0] < 1 or r[0] > r[1]]
        if bad:
            problems.append((ln_no, "OUT-OF-RANGE", fname, spec, "file has %d lines" % len(L)))
            continue
        ids = idents_before(line, pos)
        ids.discard(os.path.splitext(fname)[0].lower())
        found = None
        if ids:
            blob = ""
            for a, b in ranges:
                blob += "\n".join(L[max(0, a - 1 - SLACK): min(len(L), b + SLACK)]).lower() + "\n"
            found = any(i in blob for i in ids)
        if ids and not found:
            first = ranges[0]
            extra = ""
            if REVIEW:
                ctx = line[max(0, pos - 230): pos].replace("\n", " ")
                lo, hi = ranges[0][0], ranges[-1][1]
                mid = (lo + hi) // 2
                extra = "\n      PLAN: ..." + ctx + "\n      L%d: %s\n      L%d: %s\n      L%d: %s" % (
                    lo, safe_text(os.path.basename(path), lo, L[lo - 1]),
                    mid, safe_text(os.path.basename(path), mid, L[mid - 1]),
                    hi, safe_text(os.path.basename(path), hi, L[hi - 1]))
            problems.append((ln_no, "CHECK", fname, spec,
                             "ids=%s | first line: %s%s" % (sorted(ids)[:6], safe_text(os.path.basename(path), first[0], L[first[0] - 1]), extra)))
        else:
            ok += 1
            if VERBOSE:
                first = ranges[0]; last = ranges[-1]
                a = safe_text(os.path.basename(path), first[0], L[first[0] - 1])[:75]
                b = safe_text(os.path.basename(path), last[1], L[last[1] - 1])[:60]
                print("plan:%-4d %s:%s | %s%s" % (ln_no, fname, spec, a, (" ... " + b) if (last[1] != first[0]) else ""))

print("citations checked: %d   ok(plausible or no nearby identifiers): %d   problems: %d" % (total, ok, len(problems)))
for p in problems:
    print("%-13s plan:%-4d %s:%s  -> %s" % (p[1], p[0], p[2], p[3], p[4]))
