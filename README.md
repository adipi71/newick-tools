# Nexus Tree Tools

**Core purpose**: Bidirectional conversion between NEXUS tree files (used by FigTree) and JSON (jsTree format), with annotation enrichment along the way.
Java pipeline to convert and annotate phylogenetic NEXUS trees: parses NEXUS to JSON, adds hierarchical labels, and writes back to NEXUS and Newick formats
                                                                                                                            
**Pipeline:**

1. NEXUS → JSON (NexusToJstree.java) — parses a .nexus phylogenetic tree file and flattens it into a jsTree-compatible JSON, preserving node attributes like colors, highlights, branch lengths, and labels.                                     
2. JSON labeling (JstreeLabeler.java) — adds hierarchical labels (hier_label) to each node (e.g. 1.2.3) and color/highlight propagation from parent to child nodes.                                                                   
3. JSON → NEXUS (JstreeToNexus.java) — reconstructs an annotated .nexus file from the labeled JSON, also producing plain Newick (.nwk) and a peartree-compatible NEXUS variant.
                                                                                                                            
**Output formats:**

  - Labeled JSON with hierarchy metadata                                                                                    
  - TSV tables of all nodes             
  - Annotated NEXUS (FigTree)
  - Plain Newick (with/without quotes)                                                                                      
  - peartree-compatible NEXUS         
                                                                                                                     
---

## Quick and dirty

```bash
./label_and_nexus.sh resources/WND_L2.nexus output
```

`label_and_nexus.sh` — a shell script that compiles and runs the full pipeline in one shot on a given `.nexus` file. Here using the example input file in `resources/WND_L2.nexus`.

See the [flow diagram](docs/label_and_nexus_flow.md) for a visual overview of the pipeline.
                         


Compiles, labels and converts in one shot. Produces in `output/`:

- `WND_L2.json` — flat jstree JSON
- `WND_L2_labeled.json` — JSON with `hier_label`, `hier_label16`, `hier_label32`, `root_color`
- `WND_L2_labeled.tsv` — complete table of all nodes
- `WND_L2_labeled_root_colors.tsv` — root nodes only, by color
- `WND_L2_labeled.nexus` — annotated NEXUS (FigTree)
- `WND_L2_labeled.nwk` — plain Newick with quotes
- `WND_L2_labeled_noquote.nwk` — plain Newick without quotes
- `WND_L2_labeled_peartree.nexus` — peartree-compatible NEXUS (`[&key="val"]`)

---

## Main files

```
resources/
├── WND_L2.nexus             ← default NEXUS file
└── Giuliano_colours.nexus   ← default NEXUS file

src/
├── Main.java                ← CLI entry point
├── NexusToJstree.java       ← NEXUS → JSON jstree
├── JstreeLabeler.java       ← JSON → JSON + hierarchical label
└── JstreeToNexus.java       ← JSON → annotated NEXUS

output/                      ← generated automatically
```


## Build

```bash
mkdir -p bin
javac src/*.java -d bin
```

---

## Usage

### Full pipeline on default files

```bash
java -cp bin Main
```

Runs the pipeline on all `.nexus` files in `resources/` and saves results to `output/`.

---

### Individual commands

#### NEXUS → JSON jstree

```bash
java -cp bin Main nexus2json resources/WND_L2.nexus output/WND_L2.json
```

Each node becomes a flat object `{ id, parent, text, data: { color, hilight, label, branch_length, ... } }`.

---

#### JSON → JSON + hierarchical label

```bash
java -cp bin Main label output/WND_L2.json output/WND_L2_labeled.json
```

Adds `hier_label` to each node:

| Node        | hier_label |
|-------------|------------|
| Root        | `0`        |
| 1st child   | `1`        |
| 2nd child   | `2`        |
| child of 1  | `1.1`      |
| child of 2  | `2.1`      |

Alongside `hier_label`, the labeler also computes three encodings of it — `hier_label2`, `hier_label16` (prefixed `b16:`), `hier_label32` (prefixed `b32:`) — kept in the node data and exported to TSV/NEXUS.

**`hier_label2`** — each dot-separated segment `s` of `hier_label` is converted independently:
- `s == 1` → `0`
- `s == 2` → `1`
- `s > 2` → `s - 1` written as 4-bit binary, digits separated by `.` (e.g. `3` → `2` → `0.0.1.0`, `5` → `4` → `0.1.0.0`)

**`hier_label16`** — `hier_label2` is split into blocks of 4 binary digits (left to right); each full block becomes one hex digit (`0`–`F`). If the last block has fewer than 4 digits, it is **not** zero-padded: it's appended as `.` + a prefix (indicating how many binary digits it holds) + the decimal/hex value of those digits:

| digits in last block | prefix | range        |
|-----------------------|--------|---------------|
| 1                      | `b` (binary)     | `b0`–`b1` |
| 2                      | `q` (quaternary) | `q0`–`q3` |
| 3                      | `o` (octal)      | `o0`–`o7` |

**`hier_label32`** — same scheme, blocks of 5 binary digits, full blocks → base32 digit (`0`–`9`, `A`–`V`). Same incomplete-last-block rule as `hier_label16`, plus:

| digits in last block | prefix | range |
|-----------------------|--------|-------|
| 4                      | `h` (hex) | `h0`–`hf` |

Example: `hier_label2 = 000000000000000100` →  `hier_label16 = b16:0001.q0`,  `hier_label32 = b32:000.o4`.

---

#### JSON → reconstructed NEXUS

```bash
java -cp bin Main json2nexus output/WND_L2_labeled.json output/WND_L2_reconstructed.nexus
```

---

#### JSON → TSV

```bash
java -cp bin Main json2tsv output/WND_L2_labeled.json output/WND_L2_labeled.tsv
```

Exports every node as a row with fixed columns `id`, `parent`, `text` followed by all fields in the `data` block.

```
id    parent  text      color    hier_label  ...
n0    #       n0                 0
n1    n0      SampleA   #ff0000  1
```

The output is directly usable as input to `tsv2json`.

---

#### TSV → JSON jstree

```bash
java -cp bin Main tsv2json output/WND_L2_labeled.tsv output/WND_L2_from_tsv.json
```

Reconstructs a jstree JSON from a TSV produced by `json2tsv`. Columns `id`, `parent`, `text` become top-level node fields; all other columns go into the `data` block. If `text` is missing it falls back to `id`.

---

#### JSON + TSV → JSON enriched with attributes

```bash
java -cp bin Main enrich output/WND_L2_labeled.json attrs.tsv output/WND_L2_enriched.json
```

Merges attributes from a TSV into the `data` block of matching nodes (matched by `id`). Adds new attributes, overwrites existing ones.

The TSV must have a header row with an `id` column and one or more attribute columns:

```
id	attributo1	attributo2
n3	valore_a	valore_b
n7	valore_c	
```

---

#### Full pipeline on a single file

```bash
java -cp bin Main pipeline resources/WND_L2.nexus output/
```

Produces in `output/`:
- `WND_L2.json`
- `WND_L2_labeled.json`
- `WND_L2_reconstructed.nexus`

---

### Help

```bash
java -cp bin Main help
```

---

## Annotation mapping

| Field in `data`      | Newick annotation         |
|----------------------|---------------------------|
| `color`              | `[&!color=#rrggbb]`       |
| `hilight`            | `[&!hilight={n,v,#color}]`|
| `label`              | `[&label=N]`              |
| `annotation_name`    | `[&!name="value"]`        |
| other custom fields  | `[&!key=value]`           |
| `branch_length`      | `:value`                  |
| `hier_label`         | ignored                   |

---

## VS Code

Open the `nexus-tree-tools/` folder in VS Code.  
The project comes preconfigured with seven debug configurations in `.vscode/launch.json`:

- **Default pipeline** — pipeline on both default files
- **Pipeline on WND_L2.nexus**
- **Pipeline on Giuliano_colours.nexus**
- **nexus2json only** / **label only** / **json2nexus only** — individual steps
- **Show help**

Press `F5` or open *Run and Debug* (`Ctrl+Shift+D`) and choose a configuration.

> **Prerequisite**: *Extension Pack for Java* extension (`vscjava.vscode-java-pack`).
