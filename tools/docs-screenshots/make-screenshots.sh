#!/usr/bin/env bash
# Erzeugt die Fensterbilder fürs Handbuch (docs/images) ohne CATIA, unter Linux mit Xvfb.
# Aufruf aus dem Repository: tools/docs-screenshots/make-screenshots.sh [szene1,szene2 …]
set -euo pipefail
REPO=$(cd "$(dirname "$0")/../.." && pwd)
T=$(mktemp -d)
javac -nowarn -d "$T/stubs" $(find "$REPO/tools/docs-screenshots/stubs" -name '*.java')
javac -nowarn -encoding UTF-8 -cp "$T/stubs" -d "$T/plugin" $(find "$REPO/src" -name '*.java')
javac -nowarn -encoding UTF-8 -cp "$T/plugin:$T/stubs" -d "$T/docs" "$REPO/tools/docs-screenshots/Docs.java"
mkdir -p "$T/home"
HOME="$T/home" xvfb-run -a -s "-screen 0 1280x1100x24" \
  java -Duser.home="$T/home" -cp "$T/docs:$T/plugin:$T/stubs" Docs "$REPO/docs/images" "$REPO/tools/docs-screenshots/data" "${1:-}"
rm -rf "$T"
