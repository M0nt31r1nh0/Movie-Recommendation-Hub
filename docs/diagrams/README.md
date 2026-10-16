# Diagrams

Each diagram has three files. `<name>.drawio` is the source, `<name>.png` is the image the READMEs show, and `<name>.pdf` is the vector version the report includes.

| Diagram | Shows | Used in |
|---|---|---|
| `deployment` | The three VMs, the pods on each, and the workers' data disks | [README.md](../../README.md), [infra/README.md](../../infra/README.md), the report |
| `kubernetes` | The `movies`, `kafka` and `monitoring` namespaces and their workloads | [infra/README.md](../../infra/README.md) |
| `storage` | The two Terraform stacks, the data disks, and how a claim finds its folder | [infra/README.md](../../infra/README.md) |
| `backend-services` | The four services, their databases, the Kafka topics, Ollama and Keycloak | [backend/README.md](../../backend/README.md) |
| `ask-ai-flow` | One Ask AI request, step by step | [backend/README.md](../../backend/README.md) |
| `frontend` | The pages, the shared modules, and the APIs each page calls | [frontend/README.md](../../frontend/README.md) |

## Editing a diagram

Open `<name>.drawio` in [draw.io Desktop](https://github.com/jgraph/drawio-desktop/releases) or at [app.diagrams.net](https://app.diagrams.net) (**File → Open from → Device**). The PNGs also carry the diagram inside them, so opening `<name>.png` in draw.io works too.

After saving, export both images again with the same names. In draw.io Desktop, use **File → Export as → PNG** at zoom 200% with **Include a copy of my diagram** ticked, then **File → Export as → PDF** with **Crop** ticked. From a terminal, with draw.io Desktop installed:

```bash
draw.io -x -f png -e -s 2 -o deployment.png deployment.drawio
draw.io -x -f pdf --crop -o deployment.pdf deployment.drawio
```

On Windows the program is `%LOCALAPPDATA%\Programs\draw.io\draw.io.exe`, and on macOS it is `/Applications/draw.io.app/Contents/MacOS/draw.io`.

Colours follow one key throughout, with blue for the application, green for databases and Kafka, orange for the inference engine, and grey for the platform and operations.
