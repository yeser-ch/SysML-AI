#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR="$SCRIPT_DIR/build/test"
OUT_DIR="$BUILD_DIR/classes"

# ── Dependency check ───────────────────────────────────────────────────────────
if ! command -v javac &>/dev/null; then
    echo "ERROR: javac not found. Install a JDK:"
    echo "       sudo apt install default-jdk"
    exit 1
fi

echo "Java: $(javac -version 2>&1)"

# ── Compile stubs + plugin sources + test harness ─────────────────────────────
echo "Compiling stubs..."
rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"

find "$SCRIPT_DIR/stubs" "$SCRIPT_DIR/src" "$SCRIPT_DIR/test" -name "*.java" > "$BUILD_DIR/sources.txt"

javac -d "$OUT_DIR" @"$BUILD_DIR/sources.txt"
echo "Compilation successful."

# ── Copy prompt resources onto the classpath ──────────────────────────────────
mkdir -p "$OUT_DIR/prompts"
cp "$SCRIPT_DIR/expert_prompt.txt" "$OUT_DIR/prompts/"
cp "$SCRIPT_DIR/basic_prompt.txt"  "$OUT_DIR/prompts/"

# ── Run ───────────────────────────────────────────────────────────────────────
echo ""
echo "Running TestHarness $*"
echo "──────────────────────────────────────────────"
java -cp "$OUT_DIR" test.TestHarness "$@"
