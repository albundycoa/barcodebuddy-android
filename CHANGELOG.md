# Änderungen

Dieser Fork der Barcode-Buddy-App führt seine eigene Versionsnummer. Sie folgt der
[semantischen Versionierung](https://semver.org/lang/de/) `MAJOR.MINOR.PATCH`:

- **PATCH** (0.1.0 → 0.1.1): Fehlerbehebungen und kleine Korrekturen, keine neue Funktion.
- **MINOR** (0.1.0 → 0.2.0): neue Funktion oder spürbare Änderung der Bedienung.
- **MAJOR**: bleibt bei 0, bis die App als ausgereift gilt (1.0.0). Danach steht eine Erhöhung für Änderungen, nach denen
  man die App neu einrichten muss (zum Beispiel ein neues Koppeln).

Der Versionscode der APK ist `MAJOR*10000 + MINOR*100 + PATCH` und steigt dadurch mit jeder Version. Er darf nie
zurückgehen, sonst lässt sich ein Update nicht mehr einspielen. Gepflegt wird die Version an einer einzigen Stelle,
`appVersion` in `gradle.properties`. Veröffentlicht wird mit `./publish-apk.sh`: privat auf den Update-Server im Heimnetz, mit `--github` als
öffentlicher GitHub-Release.

## [0.4.4] – Symbole in den Knöpfen mittig

- Die Symbole − und + sowie der Papierkorb sitzen in den runden Knöpfen der Liste „Gescannt“ (und im Verlauf und bei den
  Umrechnungen) jetzt genau in der Mitte; vorher saßen sie leicht nach rechts verschoben. „Anlegen / zuordnen“ klebt nicht mehr am Minus-Knopf.
- Uhr: keine Änderung.

## [0.4.3] – Karten: Knöpfe in einer Linie

- In der Liste „Gescannt“ stehen jetzt auch der Preis und Fehlermeldungen oben bei den Texten. Darunter sitzen „Anlegen / zuordnen“ und die
  Knöpfe − und + (mit Menge und Papierkorb) in einer gemeinsamen Mittellinie.
- Uhr: keine Änderung.

## [0.4.2] – Übersichtlichere Karten

- In der Liste „Gescannt“ stehen Name und Hinweise (z. B. „Unbekanntes Produkt · Vorschlag von OpenFoodFacts“, Barcode) jetzt über der
  ganzen Kartenbreite; Preis, „Anlegen / zuordnen“ und die Mengenknöpfe folgen darunter. Lange Namen brechen nicht mehr neben den Knöpfen um.
- Uhr: keine Änderung.

## [0.4.1] – Vorschläge auch für ältere Einträge

- Unbekannte Barcodes, die schon vor dem Update in der Einkaufsliste standen, bekommen ebenfalls einen Namensvorschlag von OpenFoodFacts,
  sobald die Liste das nächste Mal geladen wird.
- Uhr: keine Änderung.

## [0.4.0] – Namensvorschläge und nur Hochformat

- **Namensvorschlag für unbekannte Produkte**: Scannt man einen Barcode, den Grocy nicht kennt, fragt die App im Hintergrund die offene
  Datenbank OpenFoodFacts ab. In der Einkaufsliste steht dann statt der nackten Nummer der Name (Marke, Produkt, Menge) mit dem Hinweis
  „Unbekanntes Produkt · Vorschlag von OpenFoodFacts“. Beim Anlegen des Produkts in Grocy ist der Name schon vorausgefüllt und lässt sich
  wie alle anderen Felder ändern. Das gilt auch für unbekannte Barcodes in der Inventur.
- Übertragen wird nur der Barcode, und nur für echte Produktcodes (EAN/UPC). Treffer und Fehlanzeigen werden zwischengespeichert. Unter
  Einstellungen → Unbekannte Produkte lässt sich das Nachschlagen ausschalten.
- **Nur Hochformat**: Die App (inklusive Kamera-Scan) läuft nur noch hochkant. Die Einstellung „Ausrichtung“ entfällt.
- Uhr: keine Änderung.

## [0.3.0] – Einrichtungsassistent

- **Einrichtungsassistent**: Beim ersten Start führt die App durch Barcode Buddy (QR-Code oder von Hand), Grocy (QR-Code aus
  „API-Schlüssel verwalten“ oder von Hand) und die Wahl des Scanners, bei SPP mit Anlernmodus-Code und Geräteauswahl. Jede Verbindung
  wird gleich geprüft. Jeder Schritt lässt sich überspringen.
- **Hinweiszeile** im Hauptbildschirm, solange Barcode Buddy oder Grocy fehlen: „Einrichten“ springt zum fehlenden Schritt, „Ausblenden“
  blendet sie aus. Der Assistent ist außerdem unter Einstellungen → Einrichtung erreichbar.
- Ohne Barcode Buddy lassen sich bekannte Produkte weiter direkt in Grocy buchen; unbekannte Barcodes bleiben in der Liste, statt dass der
  Einkauf abbricht.
- Wer schon gekoppelt war, sieht den Assistenten nicht von selbst.
- Uhr: keine Änderung (die Uhr-App trägt nur die neue Version).

## [0.2.0] – Menü anordnen

- **Menü anordnen** (Einstellungen → Darstellung): Die Einträge im Drei-Punkte-Menü lassen sich am Griff verschieben und über den Haken
  ausblenden. „Einstellungen“ bleibt immer sichtbar, „Zurücksetzen“ stellt die Standardreihenfolge wieder her. Neue Einträge späterer
  Versionen erscheinen automatisch am Ende.
- Uhr: keine Änderung. Die Uhr-App trägt aber die neue Version, sie lässt sich wie gewohnt über „Uhr-App aktualisieren“ nachziehen.
- Download-Seite auf dem Update-Server (nur im Heimnetz), Beschreibung der Versionen ohne Markdown-Zeichen.

## [0.1.2] – Uhr aktualisieren ohne Kabel

- **Uhr-App aktualisieren (Menü)**: Die Handy-App enthält die passende Uhr-App und schickt sie über die Data Layer an die Uhr. Die
  Uhr installiert sie selbst, beim ersten Mal nach einer Rückfrage. Dafür braucht die Uhr-App einmalig die Erlaubnis
  „Apps installieren“ (per `adb shell appops set de.bulling.barcodebuddyscanner REQUEST_INSTALL_PACKAGES allow`).
  Handy und Uhr haben immer dieselbe Version.

- **Uhr (Wear OS)**: Die Uhr-App wird jetzt mit demselben Schlüssel signiert wie die Handy-App und trägt dieselbe Version. Die
  Data Layer von Google lässt Handy und Uhr nur miteinander reden, wenn Paketkennung und Signatur gleich sind. Seit dem
  Schlüsselwechsel kamen deshalb weder Scans zur Uhr noch Häkchen vom Handy ("Mismatched certificate").
- **Uhr**: Abgehakte Einträge stehen unten unter "Erledigt" und rutschen beim Abhaken sofort dorthin.
- Die Version steht jetzt in `gradle.properties` und gilt für Handy und Uhr.

## [0.1.1] – Update-Weg geprüft

Keine Änderung in der Bedienung. Diese Version prüft den Weg über den privaten Update-Server: Die App findet sie, lädt
sie, vergleicht die Prüfsumme und installiert sie über die vorhandene Version.

## [0.1.0] – erster Stand des Forks

Ausgangspunkt ist die Barcode-Buddy-App von Forceu (Version 0.4.1 mit eigener Einkaufsliste, Produktanlage und
Scanner-Anbindung). Dazu kamen:

- **Inventur** (Menü): Alles durchscannen, die App zählt mit und überträgt am Ende nur Abweichungen an Grocy. Packungen mit
  mehreren Einheiten lassen sich anpassen, unbekannte Barcodes zuordnen oder neu anlegen, die Zählung übersteht einen
  Neustart.
- **Bestand prüfen** (Menü): Bestand, Haltbarkeit und Einkaufslisten-Stand scannen, dazu verbrauchen oder auf die
  Einkaufsliste setzen.
- **Ablauf-Übersicht** (Menü): Chargen nach Mindesthaltbarkeitsdatum.
- **Einkaufsliste**: Menge ändern (Plus, Minus, Eingabe), Gesamtpreis bei mehrfacher Menge, Abhaken nur über Kästchen und
  Namen.
- **Kamera-Reiter** zwischen Einkaufsliste und Gescannt, abhängig von der Scan-Methode; neue Scan-Methode
  „SPP-Scanner + Kamera“.
- **Sync** lädt nur noch, wenn sich in Grocy etwas geändert hat.
- **Updates** direkt aus der App. Die App fragt einen Update-Server (zurzeit privat im Heimnetz, die Adresse ist
  in den Einstellungen änderbar; GitHub-Releases gehen genauso).
- **SPP-Anlernmodus** des Scanners (Einstellungen und Scanner-Steuerung): Die App zeigt den QR-Code zum Abscannen mit dem
  Scanner, damit man ihn nicht aus dem Handbuch abscannen muss.
- API-Schlüssel bleiben aus dem Cloud-Backup, Fehlermeldungen stehen in `strings.xml`, Unit-Tests und GitHub-Action.
