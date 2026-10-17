# Diagrams

Each diagram has two files: `<name>.excalidraw` is the source and `<name>.png` is the image the READMEs show. `deployment` also has `deployment.pdf`, the vector version the report includes as Fig. 1.

| Diagram | Shows | Used in |
|---|---|---|
| `deployment` | The three VMs, the pods on each, and the workers' data disks | [README.md](../../README.md), [infra/README.md](../../infra/README.md), the report |
| `kubernetes` | The `movies`, `kafka` and `monitoring` namespaces and their workloads, Loki and Fluent Bit included | [infra/README.md](../../infra/README.md) |
| `storage` | The two Terraform stacks, the data disks, and how a claim finds its folder | [infra/README.md](../../infra/README.md) |
| `backend-services` | The four services, their databases, the Kafka topics, Ollama and Keycloak | [backend/README.md](../../backend/README.md) |
| `ask-ai-flow` | One Ask AI request, step by step | [backend/README.md](../../backend/README.md) |
| `frontend` | The pages, the shared modules, and the APIs each page calls | [frontend/README.md](../../frontend/README.md) |

## Editing a diagram

Open [excalidraw.com](https://excalidraw.com), then **☰ → Open** and pick `<name>.excalidraw` (or drag the file onto the canvas). The PNGs also carry the scene inside them, so opening `<name>.png` the same way works too. Each box is grouped with its text, and each arrow with its label, so they move together.

After editing, save the source with **☰ → Save to…** under the same name, then export the image again (and, for `deployment`, the PDF):

- **PNG:** **☰ → Export image**, with **Background** and **Embed scene** on and the scale at **2×**, then **PNG**, saved as `<name>.png`.
- **PDF** (`deployment` only): the same dialog's **SVG**, then convert it, e.g. `rsvg-convert -f pdf -o deployment.pdf deployment.svg` (librsvg), or open the SVG in a browser and print it to PDF with no margins. Then rebuild the report (`report/build.sh`).

## Style

The diagrams use Excalidraw's **Architect** sloppiness (straight lines instead of the hand-drawn wobble), and all their text is in Cascadia, its monospace, with titles two sizes larger (Excalidraw has no bold). Lines are 1 px, arrowheads filled and 8 px, and corners round to at most 16 px. Excalidraw draws a head no longer than half the arrow's last segment, so each arrow has an extra point 16 px before its tip; keep it there when editing, or the head grows back to 15 px. Colours come from Excalidraw's palette and follow one key throughout, with blue for the application, green for databases and Kafka, orange for the inference engine, and grey for the platform and operations.
