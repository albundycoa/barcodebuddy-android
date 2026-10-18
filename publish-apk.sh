#!/bin/bash
# Baut die signierte Release-APK und stellt sie für das Update in der App bereit. Die App liest die Datei latest.json
# und lädt die APK, wenn die Version neuer ist als die installierte.
#
# Aufruf aus diesem Ordner:
#   ./publish-apk.sh            privat: auf den Update-Server der Docker-VM (Standard)
#   ./publish-apk.sh --github   öffentlich: als GitHub-Release (fragt vorher nach)
#
# Ablauf:
#   1. Version aus app/build.gradle (appVersion), Beschreibung aus CHANGELOG.md ("## [X.Y.Z]")
#   2. Tests laufen lassen, Release-APK bauen (Schlüssel aus ~/.barcodebuddy-keys, nicht im Repository)
#   3. latest.json mit Version, Größe und SHA-256 schreiben
#   4. Hochladen. Die JSON-Datei kommt zuletzt, damit die App die neue Version erst sieht, wenn die APK komplett da ist.
#
# Privat: Auf der Docker-VM (SSH-Alias "docker-vm") liegt der Ordner /opt/barcodebuddy-apk, den der Container "bb-apk"
# (nginx) unter http://192.168.2.69:9296/ ausliefert. Die gleiche Adresse steht als Standard in der App.
# Vorher: Änderungen committen und pushen.
set -euo pipefail

MODE="docker"
[ "${1:-}" = "--github" ] && MODE="github"

REPO="albundycoa/barcodebuddy-android"
HOST="docker-vm"
REMOTE_DIR="/opt/barcodebuddy-apk"
BASE_URL="http://192.168.2.69:9296"
cd "$(dirname "$0")"

[ -f "$HOME/.barcodebuddy-keys/keystore.properties" ] || { echo "Schlüssel fehlt: ~/.barcodebuddy-keys/keystore.properties"; exit 1; }
git diff --quiet && git diff --cached --quiet || { echo "Es gibt nicht committete Änderungen."; exit 1; }
git fetch -q origin
BRANCH=$(git rev-parse --abbrev-ref HEAD)
[ "$(git rev-parse HEAD)" = "$(git rev-parse "origin/$BRANCH")" ] || { echo "Der Stand ist nicht gepusht (Branch $BRANCH)."; exit 1; }

VERSION=$(grep -oP 'def appVersion = "\K[0-9]+\.[0-9]+\.[0-9]+' app/build.gradle)
[ -n "$VERSION" ] || { echo "Version nicht gefunden (erwartet: def appVersion = \"X.Y.Z\" in app/build.gradle)"; exit 1; }
IFS=. read -r MAJOR MINOR PATCH <<< "$VERSION"
CODE=$((MAJOR * 10000 + MINOR * 100 + PATCH))
TAG="v$VERSION"

NOTES=$(awk -v v="$VERSION" '
  $0 ~ "^## \\[" v "\\]" { on = 1; next }
  on && /^## \[/ { exit }
  on { print }
' CHANGELOG.md | sed -e :a -e '/^\n*$/{$d;N;ba' -e '}')
[ -n "$NOTES" ] || { echo "Im CHANGELOG.md fehlt der Abschnitt \"## [$VERSION]\"."; exit 1; }

if [ "$MODE" = "github" ]; then
  if gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
    echo "Den Release $TAG gibt es schon. Erst die Version in app/build.gradle erhöhen."
    exit 1
  fi
  DOWNLOAD_BASE="https://github.com/$REPO/releases/download/$TAG"
else
  ssh "$HOST" "test -d $REMOTE_DIR" || { echo "Ordner $REMOTE_DIR auf $HOST fehlt."; exit 1; }
  # Eine schon veröffentlichte Version nicht stillschweigend überschreiben
  if ssh "$HOST" "grep -q '\"version_code\": $CODE,' $REMOTE_DIR/latest.json 2>/dev/null"; then
    echo "Die Version $VERSION liegt schon auf dem Update-Server. Erst die Version in app/build.gradle erhöhen."
    exit 1
  fi
  DOWNLOAD_BASE="$BASE_URL"
fi

echo ">>> Tests und Release-APK ($VERSION, Code $CODE)"
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) GRADLEW=./gradlew.bat ;;
  *) GRADLEW=./gradlew ;;
esac
$GRADLEW :app:testDebugUnitTest :app:assembleRelease -q

APK="app/build/outputs/apk/release/app-release.apk"
[ -f "$APK" ] || { echo "Keine APK gefunden: $APK"; exit 1; }
FILE="barcodebuddy-$VERSION.apk"
OUT="$(mktemp -d)"
cp "$APK" "$OUT/$FILE"
SIZE=$(wc -c < "$OUT/$FILE" | tr -d ' ')
SHA=$(sha256sum "$OUT/$FILE" | cut -d' ' -f1)
BUILT=$(date +%d.%m.%Y)
# PYTHONUTF8: Python unter Windows liest sonst mit der Systemcodierung, aus Umlauten wird Buchstabensalat
NOTES_JSON=$(printf '%s' "$NOTES" | PYTHONUTF8=1 python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))' 2>/dev/null \
  || printf '%s' "$NOTES" | PYTHONUTF8=1 python -c 'import json,sys; print(json.dumps(sys.stdin.read()))')
cat > "$OUT/latest.json" <<EOF
{"version": "$VERSION", "version_code": $CODE, "url": "$DOWNLOAD_BASE/$FILE", "size": $SIZE, "sha256": "$SHA", "built": "$BUILT", "notes": $NOTES_JSON}
EOF

echo ">>> $FILE, $((SIZE / 1024)) KB, SHA-256 $SHA"
echo ">>> Beschreibung:"
echo "$NOTES"

if [ "$MODE" = "github" ]; then
  read -r -p "Release $TAG jetzt öffentlich auf GitHub veröffentlichen? (j/N) " ANSWER
  [ "$ANSWER" = "j" ] || { echo "Abgebrochen. Dateien liegen in $OUT"; exit 0; }
  gh release create "$TAG" "$OUT/$FILE" "$OUT/latest.json" \
    --repo "$REPO" --target "$(git rev-parse HEAD)" --title "Version $VERSION" --notes "$NOTES" --latest
  echo ">>> Fertig: https://github.com/$REPO/releases/tag/$TAG"
else
  scp -q "$OUT/$FILE" "$HOST:$REMOTE_DIR/$FILE"
  scp -q "$OUT/latest.json" "$HOST:$REMOTE_DIR/latest.json"
  # alte APKs aufräumen: die aktuelle und die davor behalten
  ssh "$HOST" "cd $REMOTE_DIR && ls -t barcodebuddy-*.apk 2>/dev/null | tail -n +3 | xargs -r rm -f"
  echo ">>> Fertig: $BASE_URL/latest.json"
fi
