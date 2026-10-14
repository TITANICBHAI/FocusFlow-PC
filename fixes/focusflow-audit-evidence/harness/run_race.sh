#!/usr/bin/env bash
# F11 evidence: emulate the --uninstall wizard JVM calling Database.init() repeatedly while another process writes to the same DB.
# usage: run_race.sh <run-label>
# Needs: the compiled harness (out/final/h3), the compiled app with the recording stub CrashReporter (out/app2),
#        kotlin-stdlib and the sqlite-jdbc jar built from the 3.47.1.0 tag. Nothing here talks to the network.
set -u
S=/tmp/claude-0/-home-claude/ae498799-be00-567f-b13b-23b1405eefe9/scratchpad
LABEL=${1:-run}
H=$S/repro/home_race_$LABEL
CP=$S/repro/out/final/h3:$S/repro/out/app2:$S/kotlinc/kotlinc/lib/kotlin-stdlib.jar:$S/sqlite-build/sqlite-jdbc-3.47.1.0-fromsrc.jar
export JAVA_TOOL_OPTIONS=
rm -rf "$H"; mkdir -p "$H"
java -Duser.home="$H" -cp "$CP" Repro3Kt prep >/dev/null 2>&1
DB="$H/.focusflow/focusflow.db"
for spec in "1000 150" "20 300" "1 300"; do
  set -- $spec
  PERIOD=$1; N=$2
  python3 "$S/repro/harness/writer.py" "$DB" "$PERIOD" 40 >/dev/null 2>&1 &
  WPID=$!
  sleep 0.5
  echo "--- writer every ${PERIOD} ms, wizard-style Database.init x${N} ---"
  TIMEFORMAT="real %3Rs"
  time { java -Duser.home="$H" -cp "$CP" Repro3Kt race "$N" 2>&1 | grep -v "^Picked up"; }
  kill "$WPID" 2>/dev/null; wait "$WPID" 2>/dev/null
done
