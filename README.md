# Nexus Tree Tools

Conversione bidirezionale tra file NEXUS (FigTree) e JSON jsTree.

```
resources/
├── WND_L2.nexus             ← file NEXUS di default
└── Giuliano_colours.nexus   ← file NEXUS di default

src/
├── Main.java                ← entry point CLI
├── NexusToJstree.java       ← NEXUS → JSON jstree
├── JstreeLabeler.java       ← JSON → JSON + label gerarchica
└── JstreeToNexus.java       ← JSON → NEXUS annotato

output/                      ← generato automaticamente
```

---

## Quick and dirty

```bash
./label_and_nexus.sh resources/WND_L2.nexus output
```

Compila, etichetta e converte in un colpo solo. Produce in `output/`:

- `WND_L2.json` — JSON jstree flat
- `WND_L2_labeled.json` — JSON con `hier_label`, `hier_label16`, `hier_label32`, `root_color`
- `WND_L2_labeled.tsv` — tabella completa di tutti i nodi
- `WND_L2_labeled_root_colors.tsv` — soli nodi radice per colore
- `WND_L2_labeled.nexus` — NEXUS annotato (FigTree)
- `WND_L2_labeled.nwk` — Newick puro con apici
- `WND_L2_labeled_noquote.nwk` — Newick puro senza apici

---

## Compilazione

```bash
mkdir -p bin
javac src/*.java -d bin
```

---

## Utilizzo

### Pipeline completa sui file default

```bash
java -cp bin Main
```

Esegue la pipeline su tutti i file `.nexus` in `resources/` e salva i risultati in `output/`.

---

### Comandi singoli

#### NEXUS → JSON jstree

```bash
java -cp bin Main nexus2json resources/WND_L2.nexus output/WND_L2.json
```

Ogni nodo diventa un oggetto flat `{ id, parent, text, data: { color, hilight, label, branch_length, ... } }`.

---

#### JSON → JSON + label gerarchica

```bash
java -cp bin Main label output/WND_L2.json output/WND_L2_labeled.json
```

Aggiunge `hier_label` a ogni nodo:

| Nodo        | hier_label |
|-------------|------------|
| Radice      | `0`        |
| 1° figlio   | `1`        |
| 2° figlio   | `2`        |
| figlio di 1 | `1.1`      |
| figlio di 2 | `2.1`      |

---

#### JSON → NEXUS ricostruito

```bash
java -cp bin Main json2nexus output/WND_L2_labeled.json output/WND_L2_reconstructed.nexus
```

---

#### Pipeline completa su file singolo

```bash
java -cp bin Main pipeline resources/WND_L2.nexus output/
```

Genera in `output/`:
- `WND_L2.json`
- `WND_L2_labeled.json`
- `WND_L2_reconstructed.nexus`

---

### Help

```bash
java -cp bin Main help
```

---

## Mapping annotazioni

| Campo in `data`      | Annotazione Newick        |
|----------------------|---------------------------|
| `color`              | `[&!color=#rrggbb]`       |
| `hilight`            | `[&!hilight={n,v,#color}]`|
| `label`              | `[&label=N]`              |
| `annotation_name`    | `[&!name="valore"]`       |
| altri campi custom   | `[&!chiave=valore]`       |
| `branch_length`      | `:valore`                 |
| `hier_label`         | ignorato                  |

---

## VS Code

Apri la cartella `nexus-tree-tools/` in VS Code.  
Il progetto è preconfigurato con sette configurazioni di debug in `.vscode/launch.json`:

- **Default pipeline** — pipeline su entrambi i file default
- **Pipeline su WND_L2.nexus**
- **Pipeline su Giuliano_colours.nexus**
- **Solo nexus2json** / **Solo label** / **Solo json2nexus** — passi individuali
- **Mostra help**

Premi `F5` o apri *Run and Debug* (`Ctrl+Shift+D`) e scegli la configurazione.

> **Prerequisito**: estensione *Extension Pack for Java* (`vscjava.vscode-java-pack`).
