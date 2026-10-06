#!/usr/bin/env bash
# Compares two snapshots, ignoring the models' "N minutes ago" text:
#   evidence/compare.sh <before> <after>      (or: just compare <before> <after>)
# Prints IDENTICAL: nothing lost, or the lines that differ (and exits with 1).
set -uo pipefail

before=${1:?usage: $0 <before> <after>}
after=${2:?usage: $0 <before> <after>}
cd "$(dirname "$0")"

diff <(grep -v ago "$before.txt") <(grep -v ago "$after.txt") && echo "IDENTICAL: nothing lost"
