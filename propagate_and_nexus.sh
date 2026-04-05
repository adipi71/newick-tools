#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

INPUT="output/WND_L2_labeled.json"
PROPAGATED="output/WND_L2_propagated.json"
NEXUS_OUT="output/WND_L2_propagated.nexus"

echo "=== Compilazione ==="
javac src/*.java -d bin

echo ""
echo "=== Propagazione attributo 'color' ==="
java -cp bin Main propagate "$INPUT" color "$PROPAGATED"

echo ""
echo "=== Conversione JSON → NEXUS ==="
java -cp bin Main json2nexus "$PROPAGATED" "$NEXUS_OUT"

echo ""
echo "=== Output generati ==="
ls -lh "$PROPAGATED" "$NEXUS_OUT"
