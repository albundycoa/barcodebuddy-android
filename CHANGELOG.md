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
