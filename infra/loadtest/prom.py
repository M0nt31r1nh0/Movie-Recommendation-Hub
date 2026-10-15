#!/usr/bin/env python3
"""What the cluster was doing during each measured run, from Prometheus: to find the bottleneck.

  python3 prom.py results/<label> [--url http://localhost:9090]     (just metrics <label>)

For each run in results/<label>/windows.csv, averages over its steady-state window:
  - CPU (cores) of every pod in the movies and kafka namespaces, and each node's CPU use (%);
  - memory (MiB) of every pod;
and writes results/<label>/resources.csv, then prints, per number of clients, the busiest pods and the
nodes. Prometheus isn't public: `just metrics` port-forwards it. Standard library only.
"""
import argparse
import csv
import json
import sys
import urllib.parse
import urllib.request
from collections import defaultdict
from pathlib import Path

PODS = 'namespace=~"movies|kafka",container!="",container!="POD"'
QUERIES = {
    "cpu_cores": f"sum by (namespace, pod) (rate(container_cpu_usage_seconds_total{{{PODS}}}[1m]))",
    "memory_mib": f"sum by (namespace, pod) (container_memory_working_set_bytes{{{PODS}}}) / 1048576",
    "node_cpu_pct": '100 * (1 - avg by (instance) (rate(node_cpu_seconds_total{mode="idle"}[1m])))',
}


def query(url, expr, at):
    q = urllib.parse.urlencode({"query": expr, "time": at})
    with urllib.request.urlopen(f"{url}/api/v1/query?{q}") as res:
        body = json.load(res)
    if body.get("status") != "success":
        sys.exit(f"Prometheus: {body}")
    return body["data"]["result"]


def main():
    p = argparse.ArgumentParser()
    p.add_argument("folder")
    p.add_argument("--url", default="http://localhost:9090")
    p.add_argument("--top", type=int, default=6)
    args = p.parse_args()
    folder = Path(args.folder)

    out = []
    with open(folder / "windows.csv") as fh:
        windows = list(csv.DictReader(fh))
    for w in windows:
        start, end = int(w["steady_from"]), int(w["steady_to"])
        span = f"[{end - start}s:15s]"
        for metric, expr in QUERIES.items():
            for r in query(args.url, f"avg_over_time(({expr}){span})", end):
                who = r["metric"].get("pod") or r["metric"].get("instance")
                out.append({"clients": int(w["clients"]), "repeat": int(w["repeat"]), "metric": metric,
                            "target": who, "value": round(float(r["value"][1]), 3)})

    with open(folder / "resources.csv", "w", newline="") as fh:
        wr = csv.DictWriter(fh, fieldnames=["clients", "repeat", "metric", "target", "value"])
        wr.writeheader()
        wr.writerows(out)

    by = defaultdict(list)
    for r in out:
        by[(r["clients"], r["metric"], r["target"])].append(r["value"])
    for clients in sorted({r["clients"] for r in out}):
        cpu = sorted(((sum(v) / len(v), t) for (c, m, t), v in by.items() if c == clients and m == "cpu_cores"),
                     reverse=True)[: args.top]
        nodes = sorted((t, sum(v) / len(v)) for (c, m, t), v in by.items() if c == clients and m == "node_cpu_pct")
        print(f"{clients} clients: " + ", ".join(f"{t} {v:.2f} cores" for v, t in cpu))
        print("    nodes: " + ", ".join(f"{t} {v:.0f}%" for t, v in nodes))
    print(f"Wrote {folder / 'resources.csv'}")


if __name__ == "__main__":
    main()
