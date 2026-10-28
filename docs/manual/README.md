# LaTeX manual

The published manual is [archimesh-manual.pdf](archimesh-manual.pdf). Its entry point is [archimesh-manual.tex](archimesh-manual.tex); chapters live in `chapters/`.

From the repository root:

```bash
# Rebuild using the checked-in vector figures.
bash docs/manual/build.sh

# Regenerate Mermaid figures, then rebuild the manual.
bash docs/manual/build.sh --render-figures
```

The build requires `pdflatex` and the packages referenced by the document and `techdoc.cls`, including `xurl`, `microtype`, `needspace`, and `placeins`. Three passes resolve contents, figure references, and page counts. Intermediate files and logs stay in ignored `.build/`; the canonical PDF is replaced only after a successful build with no unresolved references or overflowing content.

Diagrams are authored in [`diagrams/`](diagrams/) as Mermaid `.mmd` files. The shared configuration sets print-friendly colors and typography. `render-figures.sh` uses Mermaid CLI (`mmdc`, rendered with version 11.12.0 for this revision) and its Chromium/Puppeteer runtime. Set `MERMAID_CLI` to use another executable path. No network access is required once those tools and the browser are installed. LaTeX itself does not invoke a browser or require shell escape.

Rendered vector PDFs are checked into [`figures/`](figures/), so routine manual builds require only TeX. After editing a diagram, regenerate its figure, rebuild the manual, and inspect the resulting pages at reading size. Keep captions and surrounding explanations aligned with the implementation. The figures cover:

- System components and transport boundaries.
- Durable commits, private acceptance, and Kafka delivery.
- Checkout and reconnect decisions after history pruning.
- Checkpoint-plus-tail recovery and retained retry receipts.
- Payload pruning gates and publication fencing.
- Optional scheduled maintenance and checkpoint retention.

The current operational runbook is also available as [checkpoint-pruning.md](../checkpoint-pruning.md), and the portable package contract is in [EXPORT_FORMAT.md](../../server/EXPORT_FORMAT.md).
