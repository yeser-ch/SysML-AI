#!/usr/bin/env bash
set -euo pipefail

# ── Configuration ──────────────────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_NAME="schroedingers-block"
BUILD_DIR="$SCRIPT_DIR/build"
OUT_DIR="$BUILD_DIR/classes"
JAR_FILE="$BUILD_DIR/$PROJECT_NAME.jar"
SRC_DIR="$SCRIPT_DIR/src"

# Override with: CAMEO_HOME=/your/path ./build.sh
CAMEO_HOME="${CAMEO_HOME:-/opt/MagicDraw}"

# ── Dependency checks ──────────────────────────────────────────────────────────
if ! command -v javac &>/dev/null; then
    echo "ERROR: javac not found. Install a JDK:"
    echo "       sudo apt install default-jdk"
    exit 1
fi

if [ ! -d "$CAMEO_HOME" ]; then
    echo "ERROR: Cameo/MagicDraw not found at '$CAMEO_HOME'"
    echo "       Set CAMEO_HOME to your installation directory, e.g.:"
    echo "       CAMEO_HOME=~/CameoSystemsModeler ./build.sh"
    exit 1
fi

echo "Using Cameo installation: $CAMEO_HOME"
echo "Java: $(javac -version 2>&1)"

# ── Classpath — all JARs from the MagicDraw lib folders ───────────────────────
CP=$(find "$CAMEO_HOME" -name "*.jar" | tr '\n' ':')

# ── Compile ────────────────────────────────────────────────────────────────────
echo ""
echo "Compiling sources..."
rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"

find "$SRC_DIR" -name "*.java" > "$BUILD_DIR/sources.txt"
javac -cp "$CP" -d "$OUT_DIR" @"$BUILD_DIR/sources.txt"
echo "Compilation successful."

# ── Resources — prompt templates must be on the classpath at /prompts/ ────────
mkdir -p "$OUT_DIR/prompts"
cp "$SCRIPT_DIR/expert_prompt.txt" "$OUT_DIR/prompts/"
cp "$SCRIPT_DIR/basic_prompt.txt"  "$OUT_DIR/prompts/"

# ── Package ────────────────────────────────────────────────────────────────────
echo "Packaging $JAR_FILE..."
jar cf "$JAR_FILE" -C "$OUT_DIR" .
echo "JAR created: $JAR_FILE"

# ── Deploy ─────────────────────────────────────────────────────────────────────
PLUGIN_DIR="$CAMEO_HOME/plugins/com.schroedingersblock.plugin"
echo "Deploying to $PLUGIN_DIR..."
mkdir -p "$PLUGIN_DIR"
cp "$JAR_FILE"               "$PLUGIN_DIR/"
cp "$SCRIPT_DIR/plugin.xml"  "$PLUGIN_DIR/"

echo ""
echo "Done. Restart Cameo/MagicDraw to load the plugin."
