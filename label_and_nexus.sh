#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

INPUT="output/WND_L2.json"
LABELED="output/WND_L2_labeled.json"
NEXUS_OUT="output/WND_L2_labeled.nexus"

echo "=== Compilazione ==="
javac src/*.java -d bin

echo ""
echo "=== Label gerarchiche + root_color ==="
java -cp bin Main label "$INPUT" "$LABELED"

echo ""
echo "=== Conversione JSON → NEXUS ==="
java -cp bin Main json2nexus "$LABELED" "$NEXUS_OUT"

echo ""
echo "=== Output generati ==="
ls -lh "$LABELED" \
        "output/WND_L2_labeled.tsv" \
        "output/WND_L2_labeled_root_colors.tsv" \
        "$NEXUS_OUT" \
        "output/WND_L2_labeled.nwk" \
        "output/WND_L2_labeled_noquote.nwk" \
        2>/dev/null || ls -lh output/WND_L2_labeled*
