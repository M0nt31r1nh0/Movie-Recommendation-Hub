#!/usr/bin/env python3
"""Turns a label's k6 summaries into the report's tables.

  python3 summarize.py results/<label>        (just summarize <label>)

Reads every c<clients>-r<repeat>.json that run.sh wrote, keeps the steady-state numbers only, and writes:
  results/<label>/summary.csv     one row per number of clients: throughput, p50/p95/p99, errors (mean and
                                  standard deviation over the repeats)
  results/<label>/endpoints.csv   the same per endpoint
and prints the summary as a Markdown table. Standard library only.
"""
import csv
import json
import statistics
import sys
from collections import defaultdict
from pathlib import Path

ENDPOINTS = ["browse", "search", "movie", "profile", "watched", "mark", "unmark", "recommendations"]


def steady(metrics, name, endpoint=None):
    tags = "phase:steady" + (f",name:{endpoint}" if endpoint else "")
    return metrics.get(f"{name}{{{tags}}}", {}).get("values", {})


def numbers(run, endpoint=None):
    m, secs = run["metrics"], run["meta"]["steady"]
    d = steady(m, "http_req_duration", endpoint)
    return {
        "rps": steady(m, "http_reqs", endpoint).get("count", 0) / secs,
        "avg": d.get("avg"), "p50": d.get("p(50)"), "p95": d.get("p(95)"), "p99": d.get("p(99)"),
        "errors": 100 * steady(m, "http_req_failed", endpoint).get("rate", 0),
    }


def mean_sd(values):
    values = [v for v in values if v is not None]
    if not values:
        return None, None
    return statistics.fmean(values), (statistics.stdev(values) if len(values) > 1 else 0.0)


def main():
    folder = Path(sys.argv[1] if len(sys.argv) > 1 else "results")
    runs = defaultdict(list)
    for f in sorted(folder.glob("c*-r*.json")):
        if f.stat().st_size == 0:
            print(f"skipping {f.name}: empty (see its .log)", file=sys.stderr)
            continue
        run = json.loads(f.read_text())
        runs[run["meta"]["clients"]].append(run)
    if not runs:
        sys.exit(f"No c*-r*.json in {folder}")

    fields = ["rps", "avg", "p50", "p95", "p99", "errors"]
    header = ["clients", "repeats"] + [f"{k}_{s}" for k in fields for s in ("mean", "sd")]
    rows, ep_rows = [], []
    for clients in sorted(runs):
        per_run = [numbers(r) for r in runs[clients]]
        row = {"clients": clients, "repeats": len(per_run)}
        for k in fields:
            row[f"{k}_mean"], row[f"{k}_sd"] = mean_sd([n[k] for n in per_run])
        rows.append(row)
        for e in ENDPOINTS:
            per_ep = [numbers(r, e) for r in runs[clients]]
            ep = {"clients": clients, "endpoint": e}
            for k in fields:
                ep[f"{k}_mean"], ep[f"{k}_sd"] = mean_sd([n[k] for n in per_ep])
            ep_rows.append(ep)

    def write(path, cols, data):
        with open(path, "w", newline="") as fh:
            w = csv.DictWriter(fh, fieldnames=cols)
            w.writeheader()
            for r in data:
                w.writerow({k: (round(v, 3) if isinstance(v, float) else v) for k, v in r.items()})

    write(folder / "summary.csv", header, rows)
    write(folder / "endpoints.csv", ["clients", "endpoint"] + header[2:], ep_rows)

    f = lambda v: "-" if v is None else f"{v:.1f}"
    print("| Clients | req/s | p50 (ms) | p95 (ms) | p99 (ms) | errors (%) | repeats |")
    print("|---:|---:|---:|---:|---:|---:|---:|")
    for r in rows:
        print(f"| {r['clients']} | {f(r['rps_mean'])} +/- {f(r['rps_sd'])} | {f(r['p50_mean'])} | {f(r['p95_mean'])} "
              f"| {f(r['p99_mean'])} | {r['errors_mean']:.2f} | {r['repeats']} |")
    print(f"\nWrote {folder / 'summary.csv'} and {folder / 'endpoints.csv'}")


if __name__ == "__main__":
    main()
