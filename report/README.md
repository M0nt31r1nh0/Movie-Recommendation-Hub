# PSIA 2026/27, Group 17 report (`project-g17.pdf`)

Build with `./build.sh` (or `latexmk -pdf -jobname=project-g17 main.tex`). The report uses the plain `article` class with an ACM-like look, which means Times 10pt, 2.0 cm side and 1.5 cm top and bottom margins, single spacing and five pages at most, as the statement requires. `build.sh` prints LaTeX errors and overfull lines, and `pdfinfo project-g17.pdf` checks the page count.

| File | Content |
|---|---|
| `main.tex` | Title block, with the group, members and numbers, and the video and repository links |
| `sections/01-introduction.tex` | Context, the application, the advanced feature, links |
| `sections/02a-overview.tex` | Architecture overview and Table 1 (components) |
| `sections/fig-architecture.tex` | Fig. 1, `../docs/diagrams/deployment.pdf` (edit `../docs/diagrams/deployment.excalidraw`, see `../docs/diagrams/README.md`) |
| `sections/02b-backend.tex` | Services and data, search, the recommendation pipeline and background agent, model choice, security and tests |
| `sections/02c-frontend.tex` | The Next.js frontend |
| `sections/02d-deployment.tex` | Local and cloud deployment, the deployment commands, monitoring |
| `sections/03-advanced.tex` | Persistent state, with its goal, storage choice, design and validation (Table 2) |
| `sections/04-evaluation.tex` | Load test, with the method, results (Table 3, Fig. 2) and bottleneck |
| `sections/05-conclusion.tex` | Conclusion and the declaration of use of AI |
| `figures/evaluation.pdf` | Fig. 2, made by `../infra/loadtest/plot.py` from `../infra/loadtest/results/final/summary.csv` |

The one placeholder still to fill is marked `\TODO{...}` in red. It is the video link, in the title block and the introduction. Find it with `grep -n TODO main.tex sections/*.tex`.

To redraw Fig. 2 after a new load test, run `python ../infra/loadtest/plot.py ../infra/loadtest/results/final --out figures/evaluation.pdf`.
