#!/bin/bash
# Baut die signierte Release-APK und veröffentlicht sie als GitHub-Release. Aus diesem Release holt sich die App
# ihre Updates: sie liest die Datei latest.json und lädt die APK, wenn die Version neuer ist als die installierte.
#
# Ablauf:
#   1. Version aus app/build.gradle (appVersion), Beschreibung aus CHANGELOG.md ("## [X.Y.Z]")
#   2. Tests laufen lassen, Release-APK bauen (Schlüssel aus ~/.barcodebuddy-keys, nicht im Repository)
#   3. latest.json mit Version, Größe und SHA-256 schreiben
#   4. Nach Rückfrage: GitHub-Release "vX.Y.Z" anlegen und beide Dateien anhängen
#
# Aufruf aus diesem Ordner: ./publish-apk.sh
# Vorher: Änderungen committen und pushen (der Release-Tag zeigt auf den gepushten Stand).
set -euo pipefail

REPO="albundycoa/barcodebuddy-android"
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

if gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
  echo "Den Release $TAG gibt es schon. Erst die Version in app/build.gradle erhöhen."
  exit 1
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
NOTES_JSON=$(printf '%s' "$NOTES" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))' 2>/dev/null \
  || printf '%s' "$NOTES" | python -c 'import json,sys; print(json.dumps(sys.stdin.read()))')
cat > "$OUT/latest.json" <<EOF
{"version": "$VERSION", "version_code": $CODE, "url": "https://github.com/$REPO/releases/download/$TAG/$FILE", "size": $SIZE, "sha256": "$SHA", "built": "$BUILT", "notes": $NOTES_JSON}
EOF

echo ">>> $FILE, $((SIZE / 1024)) KB, SHA-256 $SHA"
echo ">>> Beschreibung:"
echo "$NOTES"
read -r -p "Release $TAG jetzt auf GitHub veröffentlichen? (j/N) " ANSWER
[ "$ANSWER" = "j" ] || { echo "Abgebrochen. Dateien liegen in $OUT"; exit 0; }

gh release create "$TAG" "$OUT/$FILE" "$OUT/latest.json" \
  --repo "$REPO" --target "$(git rev-parse HEAD)" --title "Version $VERSION" --notes "$NOTES" --latest
echo ">>> Fertig: https://github.com/$REPO/releases/tag/$TAG"
