# Änderungen

Dieser Fork der Barcode-Buddy-App führt seine eigene Versionsnummer. Sie folgt der
[semantischen Versionierung](https://semver.org/lang/de/) `MAJOR.MINOR.PATCH`:

- **PATCH** (0.1.0 → 0.1.1): Fehlerbehebungen und kleine Korrekturen, keine neue Funktion.
- **MINOR** (0.1.0 → 0.2.0): neue Funktion oder spürbare Änderung der Bedienung.
- **MAJOR**: bleibt bei 0, bis die App als ausgereift gilt (1.0.0). Danach steht eine Erhöhung für Änderungen, nach denen
  man die App neu einrichten muss (zum Beispiel ein neues Koppeln).

Der Versionscode der APK ist `MAJOR*10000 + MINOR*100 + PATCH` und steigt dadurch mit jeder Version. Er darf nie
zurückgehen, sonst lässt sich ein Update nicht mehr einspielen. Gepflegt wird die Version an einer einzigen Stelle,
`appVersion` in `app/build.gradle`. Veröffentlicht wird mit `./publish-apk.sh`: privat auf den Update-Server im Heimnetz, mit `--github` als
öffentlicher GitHub-Release.

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
