#!/usr/bin/env bash
# Builds project-g17.pdf (pdflatex + bibtex). Usage: ./build.sh
set -e
cd "$(dirname "$0")"
pdflatex -interaction=nonstopmode -jobname=project-g17 main.tex >/dev/null || true
bibtex project-g17 >/dev/null || true
pdflatex -interaction=nonstopmode -jobname=project-g17 main.tex >/dev/null || true
pdflatex -interaction=nonstopmode -jobname=project-g17 main.tex >/dev/null || true
grep -E "Output written|^!|Overfull|undefined" project-g17.log || true
