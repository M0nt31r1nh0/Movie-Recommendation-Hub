#!/usr/bin/env bash
# The measured runs: for each number of clients, REPEATS runs of mix.js in the k6 pod.
#   ./run.sh <label> [clients...]          (just run <label> [clients...]; default 1 10 50 100 200)
# Writes results/<label>/c<clients>-r<repeat>.json (k6's summary), .log (k6's output), and windows.csv
# (when each run's steady state began and ended, for prom.py). Run it in the management container.
#
# Settings, from the environment: REPEATS (3), WARMUP (60 s), STEADY (180 s), RAMP (30 s), THINK (1 s),
# USERS (200), SETTLE (30 s pause between runs), BASE_URL (default: Terraform's public_url).
set -euo pipefail
cd "$(dirname "$0")"

label=${1:?usage: $0 <label> [clients...]}
shift
[ $# -gt 0 ] || set -- 1 10 50 100 200

REPEATS=${REPEATS:-3} WARMUP=${WARMUP:-60} STEADY=${STEADY:-180} RAMP=${RAMP:-30} THINK=${THINK:-1}
USERS=${USERS:-200} SETTLE=${SETTLE:-30}
BASE_URL=${BASE_URL:-$(terraform -chdir=../terraform output -raw public_url)}
PASSWORD=$(cat .secrets/password)

out=results/$label
mkdir -p "$out"
[ -f "$out/windows.csv" ] || echo "clients,repeat,started,steady_from,steady_to" > "$out/windows.csv"

total=$(( $# * REPEATS ))
n=0
for repeat in $(seq 1 "$REPEATS"); do
  for clients in "$@"; do
    n=$((n + 1))
    name=$(printf 'c%03d-r%d' "$clients" "$repeat")
    echo "[$n/$total] $clients clients, repeat $repeat: about $(( (WARMUP + STEADY) / 60 + 1 )) minutes"
    started=$(date -u +%s)
    code=0
    kubectl -n loadtest exec k6 -- k6 run --quiet \
      -e BASE_URL="$BASE_URL" -e PASSWORD="$PASSWORD" -e USERS="$USERS" -e CLIENTS="$clients" \
      -e WARMUP="$WARMUP" -e STEADY="$STEADY" -e RAMP="$RAMP" -e THINK="$THINK" \
      -e LABEL="$label" -e REPEAT="$repeat" \
      /scripts/mix.js > "$out/$name.out" 2> "$out/$name.log" || code=$?
    # 99 only means a threshold "failed": they exist to report the steady-state numbers, not to judge them.
    if [ "$code" -ne 0 ] && [ "$code" -ne 99 ]; then
      echo "k6 exited with $code: see $out/$name.log" >&2
      exit 1
    fi
    sed -n '/===K6-JSON-BEGIN===/,/===K6-JSON-END===/p' "$out/$name.out" | sed '1d;$d' > "$out/$name.json"
    grep -m1 '^clients=' "$out/$name.out" || true
    rm "$out/$name.out"
    # k6's clock starts with setup (a few seconds): the steady window is measured from its own start.
    echo "$clients,$repeat,$started,$((started + WARMUP)),$((started + WARMUP + STEADY))" >> "$out/windows.csv"
    sleep "$SETTLE"
  done
done
echo "Done: python3 summarize.py $out   (just summarize $label)"
