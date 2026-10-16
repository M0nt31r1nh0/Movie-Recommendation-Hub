#!/usr/bin/env python3
"""The report's figure: throughput and latency against the number of clients, from summary.csv.

  python3 plot.py results/<label> [--out ../../report/figures/evaluation.pdf]

Needs matplotlib (pip install matplotlib), so run it on your computer rather than in the management
container. Throughput is on the left axis, p50/p95/p99 latency on the right, with the standard deviation
over the repeats as error bars.
"""
import argparse
import csv
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402


def main():
    p = argparse.ArgumentParser()
    p.add_argument("folder")
    p.add_argument("--out", help="default: <folder>/evaluation.pdf (and .png)")
    args = p.parse_args()
    folder = Path(args.folder)
    with open(folder / "summary.csv") as fh:
        rows = [{k: float(v) if v not in ("", None) else None for k, v in r.items()} for r in csv.DictReader(fh)]
    x = [r["clients"] for r in rows]

    plt.rcParams.update({"font.family": "serif", "font.serif": ["Times New Roman", "Times", "DejaVu Serif"],
                         "font.size": 8})
    # Two panels sharing the x axis: throughput (linear) on top, latency below on a log scale, since it spans
    # from milliseconds at low load to seconds past saturation.
    fig, (top, bottom) = plt.subplots(2, 1, sharex=True, figsize=(3.4, 2.9), height_ratios=[1, 1.15])
    top.errorbar(x, [r["rps_mean"] for r in rows], yerr=[r["rps_sd"] for r in rows], color="#1F4E79",
                 marker="o", ms=3, lw=1.2, capsize=2)
    top.set_ylabel("throughput (req/s)")
    top.set_ylim(bottom=0)
    for key, style, marker in (("p50", ":", "o"), ("p95", "--", "s"), ("p99", "-", "^")):
        sd = [r.get(f"{key}_sd") or 0 for r in rows]
        bottom.errorbar(x, [r[f"{key}_mean"] for r in rows], yerr=sd, color="#A0522D", ls=style, marker=marker,
                        ms=2.5, lw=1, capsize=2, label=key)
    bottom.set_yscale("log")
    bottom.set_ylabel("latency (ms)")
    bottom.set_xlabel("concurrent clients")
    bottom.legend(loc="upper left", fontsize=6.5, frameon=False)
    for ax in (top, bottom):
        ax.grid(alpha=0.3, lw=0.5, which="both")
    fig.tight_layout(pad=0.3, h_pad=0.4)

    out = Path(args.out) if args.out else folder / "evaluation.pdf"
    fig.savefig(out)
    fig.savefig(out.with_suffix(".png"), dpi=200)
    print(f"Wrote {out} and {out.with_suffix('.png')}")


if __name__ == "__main__":
    main()
