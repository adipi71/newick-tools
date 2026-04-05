#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

if [[ $# -lt 1 ]]; then
    echo "Uso: $0 <input.nexus> [output-dir]"
    echo "  input.nexus  file NEXUS di input"
    echo "  output-dir   directory di output (default: output)"
    echo ""
    echo "Esempio:"
    echo "  $0 resources/WND_L2.nexus output"
    exit 1
fi

NEXUS_IN="$1"
OUT_DIR="${2:-output}"
mkdir -p "$OUT_DIR"

BASE=$(basename "${NEXUS_IN%.*}")

JSON="$OUT_DIR/${BASE}.json"
LABELED="$OUT_DIR/${BASE}_labeled.json"
NEXUS_OUT="$OUT_DIR/${BASE}_labeled.nexus"

echo "=== Compilazione ==="
javac src/*.java -d bin

echo ""
echo "=== NEXUS → JSON ==="
java -cp bin Main nexus2json "$NEXUS_IN" "$JSON"

echo ""
echo "=== Label gerarchiche + root_color ==="
java -cp bin Main label "$JSON" "$LABELED"

echo ""
echo "=== Conversione JSON → NEXUS ==="
java -cp bin Main json2nexus "$LABELED" "$NEXUS_OUT"

echo ""
echo "=== Output generati ==="
ls -lh "$LABELED" \
        "$OUT_DIR/${BASE}_labeled.tsv" \
        "$OUT_DIR/${BASE}_labeled_root_colors.tsv" \
        "$NEXUS_OUT" \
        "$OUT_DIR/${BASE}_labeled.nwk" \
        "$OUT_DIR/${BASE}_labeled_noquote.nwk"
