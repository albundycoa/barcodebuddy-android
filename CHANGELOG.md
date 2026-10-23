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

## [0.19.2] – Mengenanzeige gerundet

- Mengen wie „⅙ Pack“ stehen jetzt auf drei Stellen gerundet („0,167 Pack“ statt „0,16666666666666666 Pack“), auf dem Handy wie auf der Uhr.
- Uhr-App und Zifferblatt: keine weitere Änderung (nur die gemeinsame Versionsnummer).

## [0.19.1] – Mengen der Einkaufsliste wie in Grocy

- Fehler behoben: Grocy speichert die Menge eines Eintrags der Einkaufsliste in der **Lagereinheit** des Produkts, die Einheit am Eintrag (z. B. Pack) ist nur die Anzeige.
  Die App hat die Zahl bisher als Menge in dieser Einheit gelesen. Bei Weizenbrötchen (Lagereinheit Stück, 1 Pack = 8 Stück) zeigte sie darum „1 Pack“, Grocy aber
  „0,125 Packs“. Jetzt rechnet die App wie Grocy um: Anzeige, Summe der Liste, „durch Scan erledigt“ und der Preis stimmen mit Grocy überein.
- Auch beim Schreiben wird richtig umgerechnet: Fügst du ein Produkt in einer anderen Einheit (z. B. „1 Pack“) hinzu, ändert die Menge oder verschiebst sie, speichert die App
  die Menge in der Lagereinheit, sodass Grocy dieselbe Menge anzeigt. Einträge, die mit älteren Versionen in einer anderen Einheit als der Lagereinheit angelegt wurden, können
  in Grocy eine zu kleine Menge zeigen; sie lassen sich in der App über die Menge korrigieren.
- Uhr-App und Zifferblatt: keine Änderung (nur die gemeinsame Versionsnummer).

## [0.19.0] – Mehrere Einkaufslisten

- Hat Grocy **mehrere Einkaufslisten** (z. B. „Einkaufszettel“ und „Für später“), zeigt die Einkaufsliste oben für jede einen Chip zum Umschalten. Die **Hauptliste** (zuerst die
  erste Liste in Grocy, mit ★ markiert) erscheint beim Start. Lange auf einen Chip drücken macht eine andere Liste zur Hauptliste.
- Jeder Eintrag hat einen Knopf zum **Verschieben** auf eine andere Liste. Steht dasselbe Produkt dort schon, werden die Mengen zusammengezählt.
- Hinzufügen (Produktsuche, freie Einträge) und Löschen wirken auf die gerade angezeigte Liste. Gescannte Artikel, die Uhr, der Bestand („Auf der Liste“) und das
  Abziehen beim Einkauf arbeiten mit der Hauptliste; gescannte Mengen haken nur Einträge der Hauptliste ab. Haken und Mengen anderer Listen werden ebenfalls an Grocy übertragen.
- Uhr-App und Zifferblatt: keine Änderung (sie zeigen die Hauptliste).

## [0.18.1] – Angebotspreise aus Marktguru

- Teilst du eine Marktguru-Liste und fügst Einträge hinzu, wird der **Angebotspreis** mit übernommen. Die Einkaufsliste zeigt ihn als „1,99 € (Angebot)“ und rechnet
  Eintrag und **Gesamtwert** damit; er gilt bis zum Ende des Angebots (danach gilt wieder der letzte Einkaufspreis) und nur, solange der Eintrag auf der Liste steht.
- In **Grocy** steht am Eintrag der Einkaufsliste eine Notiz, z. B. „Angebot 1,99 € bei Lidl bis 31.10.2026“ (bei freien Einträgen im Namen). Scannst du das Produkt
  oder hakst es ab und sendest den Einkauf, wird es mit dem Angebotspreis eingebucht (dann wird er in Grocy auch zum letzten Preis des Produkts).
- Uhr-App und Zifferblatt: keine Änderung (nur die gemeinsame Versionsnummer).

## [0.18.0] – Zur Einkaufsliste teilen (z. B. aus Marktguru)

- Die App erscheint beim **Teilen von Text** als Ziel „Zur Einkaufsliste“. Teilst du in der Marktguru-App deine Einkaufsliste, liest die App Menge, Name, Geschäft und
  Gültigkeit der Angebote aus. Eine Maske zeigt alle Einträge mit Häkchen; zu jedem schlägt sie ein passendes Grocy-Produkt vor, sonst wird es ein **freier Eintrag**
  (mit einer Produktgruppe für alle freien Einträge, damit sie richtig einsortiert werden). Ein Tipp auf die blaue Zeile wechselt die Zuordnung (Vorschlag, freier
  Eintrag oder ein anderes Produkt suchen). Mit „Hinzufügen“ kommen alle angehakten auf die Einkaufsliste in Grocy, auf dem Handy wie auf der Uhr.
- Anderer Text (z. B. eine Notiz mit einer Einkaufsliste) funktioniert auch: jede Zeile wird ein Eintrag.
- Uhr-App und Zifferblatt: keine Änderung (nur die gemeinsame Versionsnummer).

## [0.17.1] – Bestand: Sortierung merken, Abmelden bei beiden Servern

- **Bestand**: Die gewählte Sortierung (Name, Wert, Ablauf) bleibt jetzt gespeichert. Außerdem zeigte das Menü immer „nach Ablauf“ als gewählt an, obwohl die
  Liste anders sortiert war; die Markierung stimmt jetzt.
- **Abmelden** im Drei-Punkte-Menü meldet bei **Barcode Buddy und Grocy** ab (beide Adressen und Schlüssel werden vergessen) und fragt vorher mit einem
  Hinweis nach, ob du wirklich abmelden oder abbrechen willst. Verlauf, Scanner und übrige Einstellungen bleiben erhalten.
- Uhr-App und Zifferblatt: keine Änderung (nur die gemeinsame Versionsnummer).

## [0.17.0] – Von Hand abgehakte Produkte beim Senden entfernen

- Neue dritte Auswahl in den Einstellungen unter „Von Hand abgehakte Produkte“: **Beim Senden ohne Buchung aus der Liste in Grocy entfernen**. Beim Senden werden die
  von Hand abgehakten Einträge dann aus der Einkaufsliste in Grocy gelöscht, ohne dass etwas in den Bestand gebucht wird. Die beiden bisherigen Auswahlen
  (einbuchen und entfernen / nur abhaken) bleiben unverändert, Standard ist weiter das Einbuchen.
- Uhr-App und Zifferblatt: keine Änderung (nur die gemeinsame Versionsnummer).

## [0.16.0] – Gelöschter Scan öffnet den Eintrag der Einkaufsliste wieder

- Wird ein Eintrag der Einkaufsliste durch einen Scan abgehakt und du löschst den Scan (oder verringerst seine Stückzahl unter die Menge der Liste), steht der Eintrag
  wieder offen in der Einkaufsliste (auch in Grocy). Das gilt nur für Einträge, die erst durch den Scan erledigt wurden: von Hand abgehakte oder schon vorher
  erledigte Einträge bleiben, wie sie sind. Handy und Uhr (Scanliste, Kachel) verhalten sich gleich.
- Uhr-App und Zifferblatt: keine Änderung (nur die gemeinsame Versionsnummer).

## [0.15.2] – Uhr: Kachel blättert in beide Richtungen

- Kachel: Unter den Zeilen stehen jetzt ▲ und ▼ mit der Seitenzahl. ▲ blättert zurück, ▼ weiter; nach der letzten Seite springt ▼ wieder auf die erste.
- Handy: keine Änderung (nur die gemeinsame Versionsnummer).

## [0.15.1] – Uhr: Kachel, Fehlscan wieder abwählen

- Kachel: Ein angetippter Scan (mit „Entfernen“) lässt sich durch erneutes Antippen der Zeile wieder abwählen, ohne zu löschen.
- Handy: keine Änderung (nur die gemeinsame Versionsnummer).

## [0.15.0] – Uhr: Zifferblatt „Einkauf“

- **Zifferblatt** im Watch Face Format (eigene kleine App `watchface`, nur Beschreibung ohne Code, daher auf Wear OS 5/6 und One UI Watch 8 zugelassen):
  Datum und Uhrzeit klein oben, darunter zwei Karten im Design der Handy-App mit den Komplikationen **Gescannt** (Anzahl, Summe) und **Einkaufsliste** (offene
  Einträge, Summe). Ein Tipp auf eine Karte öffnet die jeweilige Liste auf der Uhr. Im Ruhemodus (Always-on) bleiben nur Datum und Uhrzeit. Beide Karten lassen
  sich im Zifferblatt-Editor (lange drücken → Anpassen) durch andere Komplikationen ersetzen.
- Installieren: `./gradlew :watchface:assembleDebug`, dann `adb -s <uhr> install -r watchface/build/outputs/apk/debug/watchface-debug.apk`, danach in der Auswahl der
  Uhr „Einkauf“ wählen. Es ist eine eigene App (`de.bulling.barcodebuddyscanner.watchface`), ein Update kommt nicht über das Handy; die Zahlen liefert die Uhr-App.
- Handy und Uhr-App: keine Änderung (nur die gemeinsame Versionsnummer).

## [0.14.0] – Uhr: Kachel für Scans und Einkaufsliste

- **Kachel**: Neben dem Zifferblatt gibt es eine Kachel „Einkauf“ im Design der Handy-App. Zwei Reiter wechseln zwischen **Gescannt** (Anzahl und Summe) und
  **Einkauf** (offene Einträge). In der Einkaufsliste hakt ein Tipp einen Eintrag ab (oder macht es rückgängig), bei den Scans wird ein Fehlscan zuerst
  angetippt und dann mit „Entfernen“ gelöscht, damit ein versehentliches Berühren nichts löscht. „▼“ blättert, drei Zeilen pro Seite. Der gewählte Reiter bleibt gemerkt.
  Einrichten: Zifferblatt lange drücken oder vom Zifferblatt nach links wischen → Kachel hinzufügen → „Einkauf“.
- Ein eigenes Zifferblatt mit Liste ist auf der Galaxy Watch mit One UI Watch 8 (Wear OS 6) nicht möglich: Das System lässt dort nur Zifferblätter im Watch Face Format
  zu, die keine eigene Anzeige-Logik haben. Zifferblatt-Komplikationen („Gescannt“, „Einkaufsliste“) gibt es weiter.
- Handy: keine Änderung (nur die gemeinsame Versionsnummer).

## [0.13.0] – Einkaufsliste bearbeiten: löschen, Produkte und freie Einträge hinzufügen

- **Handy**: In der Einkaufsliste gibt es oben eine Zeile „Hinzufügen“. Sie öffnet eine Suche über alle Grocy-Produkte (Treffer zeigen Gruppe und, falls schon
  auf der Liste, die vorhandene Menge); ein Treffer führt zur Mengenwahl (Einkaufseinheit, zählt zu einem vorhandenen Eintrag dazu). Findet man nichts, legt
  „Freier Eintrag“ einen Eintrag ohne Produkt an und lässt dabei die **Produktgruppe** wählen (in deiner Reihenfolge), damit er richtig einsortiert wird.
  Jeder Eintrag hat ein Löschen-Symbol (mit Rückfrage), bei freien Einträgen lässt sich auch die Menge ändern.
- **Uhr**: Oben in der Einkaufsliste steht „＋ Hinzufügen“ (Produktsuche mit Tastatur oder Diktat, freie Einträge mit Gruppenwahl, dann Menge). Ein Eintrag lässt sich
  durch langes Drücken nach einer Rückfrage entfernen. Das Handy führt alles in Grocy aus und schickt die neue Liste zurück.
- Die Uhr bekommt den Produktkatalog automatisch vom Handy.

## [0.12.0] – Uhr: Komplikationen für Zifferblätter

- **Komplikationen**: Die Uhr-App bietet zwei Datenquellen für jedes Zifferblatt mit freien Komplikationsfeldern an: „Gescannt“ (Anzahl der gescannten
  Artikel, lang: „3 Artikel · 12,50 €“) und „Einkaufsliste“ (Anzahl offener Einträge, lang: „9 offen · 15,62 €“). Ein Tipp darauf öffnet die jeweilige
  Liste direkt. Die Zahlen aktualisieren sich, sobald das Handy neue Daten schickt.
- So nutzt du es für den Einkauf: Zifferblatt (z. B. eine Kopie deines Wetter-Zifferblatts) lange drücken → Anpassen → ein Komplikationsfeld wählen → „Gescannt“
  oder „Einkaufsliste“ der Einkaufs-App. Die ganze Liste selbst kann ein Zifferblatt nicht zeigen, nur Zahl und Summe.
- Handy: keine Änderung (nur die gemeinsame Versionsnummer).

## [0.11.0] – Uhr: Einkaufsliste anlassen

- Das Anlassen des Displays (gedimmt in Ruhe, hell bei Bewegung, Tippen oder Änderung) gibt es jetzt auch bei der **Einkaufsliste** der Uhr. In den
  Einstellungen der Uhr hat jede Liste ihren eigenen Schalter: „Scanliste anlassen“ und „Einkaufsliste anlassen“ (beide standardmäßig an).
- Handy: keine Änderung (nur die gemeinsame Versionsnummer).

## [0.10.2] – Uhr: Dimmen nach Bewegung, Uhrzeit oben

- Das Dimmen bei der Scanliste richtet sich jetzt nach der Bewegung statt nach der Lage der Uhr: Liegt oder hängt sie ca. 6 Sekunden still,
  wird das Display dunkel, bei jeder Bewegung (Anheben), Berührung oder einem neuen Scan wird es wieder hell. (Vorher wurde nicht gedimmt, wenn
  das Display nach oben zeigte.)
- **Uhrzeit** ganz oben in den Listen der Uhr (Einkaufsliste, Gescannt, Bestand), weil bei angelassenem Display die Systemzeit fehlt.
- Handy: keine Änderung (nur die gemeinsame Versionsnummer).

## [0.10.1] – Uhr: Display bei der Scanliste selbst anlassen

- Der Ambient-Modus von Wear OS ließ die Scanliste auf der Uhr zurück zum Zifferblatt fallen. Stattdessen hält die App jetzt das Display selbst an,
  solange die Seite „Gescannt“ offen ist (Schalter „Scanliste anlassen“ in den Einstellungen der Uhr): gedimmt, wenn du die Uhr unten hältst, hell beim
  Anheben (Neigungssensor), Tippen oder neuem Scan. Nach 15 Minuten gedimmt ohne Berührung oder neue Daten geht das Display wieder wie gewohnt aus.
  Das braucht mehr Akku als sonst.
- Handy: keine Änderung (nur die gemeinsame Versionsnummer).

## [0.10.0] – Uhr: Scanliste bleibt an

- **Uhr: Einstellungen**: Neuer Knopf „Einstellungen“ auf der Startseite der Uhr mit dem Schalter „Scanliste anlassen“ (Standard: an).
- Ist er an, bleibt die Scanliste der Uhr im Ambient-Modus sichtbar: Das Display wird gedimmt statt auszugehen und wird beim Anheben der Uhr wieder hell.
  Aus: die Uhr verhält sich wie bisher. Das System kann den Ambient-Modus nur anzeigen, wenn er auf der Uhr erlaubt ist (Always-on-Display).
- Handy: keine Änderung (nur die gemeinsame Versionsnummer).

## [0.9.0] – Einkaufsliste im Bestand sehen und ändern

- **Im Bestand sichtbar**: Steht ein Produkt auf der Einkaufsliste, zeigt die Zeile „Auf der Liste: 2 Pack“ (bei mehreren Einheiten alle), und der
  Einkaufswagen-Knopf zeigt die Menge. Gezählt werden die offenen Einträge der Standardliste; erledigte nicht.
- **Maske ändern**: Ein Klick auf den Einkaufswagen öffnet bei einem Produkt auf der Liste dieselbe Maske mit der bisherigen Menge. Menge ändern und
  „Speichern“ (die Menge wird auf den neuen Wert gesetzt, nicht addiert). „Entfernen“ nimmt den Eintrag von der Liste. Wechselst du die Einheit, erscheint
  die Menge dieser Einheit, oder 1, wenn das Produkt mit ihr noch nicht auf der Liste steht.
- Uhr: keine funktionale Änderung (nur die gemeinsame Versionsnummer).

## [0.8.0] – Produkt auf die Einkaufsliste setzen

- **Bestand → Einkaufswagen-Knopf**: Jedes Produkt der Bestandsseite hat einen Knopf, der von unten eine Maske öffnet. Dort stehen das Produkt, die
  Menge (Standard 1, Komma oder Punkt) und die Einheit (Einkaufseinheit zuerst, dazu die Lagereinheit). „Hinzufügen“ trägt es wie Grocys eigenes Formular
  in die Einkaufsliste ein; steht das Produkt schon mit derselben Einheit drauf, wird die Menge dazugezählt. Die Liste auf dem Handy aktualisiert sich danach.
- Die Knöpfe der Bestandszeile sind enger gesetzt, damit „Alle“ in einer Zeile bleibt.
- Uhr: keine funktionale Änderung (nur die gemeinsame Versionsnummer).

## [0.7.0] – Bestand auf der Uhr

- **Uhr: Bestand**: Neuer Knopf „Bestand“ auf der Startseite der Uhr. Die Liste zeigt jedes Produkt mit Menge und Wert, oben „52 Produkte · 86,36 €“.
  Antippen öffnet das Produkt mit „Verbrauchen 1“ (Schnellverbrauchsmenge), „Alle verbrauchen“ (erst nach einer zweiten Bestätigung) und
  „Öffnen“. Gebucht wird über das Handy (nur es hat die Zugangsdaten für Grocy); danach erscheint die Meldung auf der Uhr mit „Rückgängig“.
- Namen im Bestand werden jetzt nach deutscher Sortierung geordnet („Äpfel“ steht bei A).
- **Die Uhr braucht dieses Update**: Menü „Uhr-App aktualisieren“ auf dem Handy, danach auf der Uhr bestätigen.

## [0.6.0] – Bestandsübersicht und feste Navigation

- **Bestand**: Neue Seite wie Grocys „Stock overview“ mit der Kopfzeile „Bestandsübersicht · 52 Produkte · 86,36 € Gesamtwert“ (der Wert wird
  wie in Grocy je Produkt auf Cent gerundet). Jedes Produkt zeigt Menge (auch „davon geöffnet“), Wert, Gruppe und nächstes MHD. Je Produkt gibt es
  die Knöpfe Verbrauch (Schnellverbrauchsmenge, Standard 1), „Alle“ verbrauchen (mit Rückfrage) und Öffnen. Jede Buchung lässt sich über die
  Meldung unten sofort mit „Rückgängig“ zurücknehmen. Dazu Suche (Produkt oder Gruppe), Sortierung nach Name, Wert oder Ablauf und Ziehen zum
  Aktualisieren. Auf Handys steht das Produkt als Karte mit den Knöpfen darunter, auf breiten Bildschirmen als Tabellenzeile Verbrauch – Produkt – Menge – Wert.
- **Feste Navigation** unten: „Einkauf“ (Einkaufsliste und Gescannt, wie bisher der Start der App) und „Bestand“.
- Uhr: keine Änderung.

## [0.5.1] – Pfandrückgabe, Komma-Eingabe, Pfand-Liste

- **Pfand zurück**: In der Liste „Gescannt“ steht ganz oben die Zeile „Pfand zurück“ (sobald du Pfand eingerichtet hast). Antippen, Betrag vom
  Pfandbon eintragen (z. B. 3,75). Er wird von der Summe abgezogen (Anzeige „Pfand zurück −3,75 €“), mit dem Einkauf im Verlauf gespeichert
  und im Kassenbon als Abzug aufgeführt. Nur in der App, an Grocy und Barcode Buddy geht nichts davon.
- **Beträge mit Komma**: In allen Betragsfeldern (Preis, Gesamtpreis, Pfand, Produkt anlegen, Umrechnungen, Mengen in Einkaufsliste und Inventur)
  kannst du jetzt „2,20“ oder „2.20“ tippen; auch „1.234,56“ wird verstanden.
- **Menü Pfand** ist jetzt eine Liste nur der Gruppen mit Pfand: Mit dem Plus eine Gruppe wählen und den Betrag eintragen, Zeile antippen zum
  Ändern oder Löschen, das Papierkorb-Symbol löscht mit „Rückgängig“. Der schon eingetragene Wert (z. B. Getränkepfand 0,25 €) bleibt erhalten.
- Uhr: keine Änderung.

## [0.5.0] – Pfand, Scanreihenfolge, Geschäft ändern

- **Scanreihenfolge**: Die Liste „Gescannt“ ist nicht mehr nach Geschäften gruppiert, sondern nach Scanzeit sortiert. Jeder neue Scan steht
  ganz oben, ein erneut gescannter Artikel rückt wieder nach oben.
- **Geschäft ändern**: Bei bekannten Produkten steht in der Karte „Geschäft: …“. Antippen öffnet die Auswahl, der Barcode wird danach in Grocy
  mit dem neuen Geschäft gespeichert (nur das Geschäft, alles andere bleibt).
- **Pfand**: Neuer Menüpunkt „Pfand“. Dort steht jede Produktgruppe aus Grocy mit einem Betragsfeld (z. B. 0,25 für „Getränkepfand“). Das Pfand
  wird je Stück zur Zeile addiert (3 × Cola = Preis + 3 × 0,25 €) und erscheint in der Liste, in der Summe („davon Pfand“), im Verlauf und im
  Beleg. Es wird nur in der App gerechnet; an Grocy und Barcode Buddy geht weiterhin der reine Produktpreis. Weitere Gruppen kannst du jederzeit
  dort nachtragen; neue Gruppen aus Grocy erscheinen nach der nächsten Synchronisierung.
- Beim ersten Start nach dem Update lädt die App die Grocy-Daten einmal neu (Produktgruppen werden gebraucht).
- Uhr: keine Änderung.

## [0.4.5] – Produktname im Zuordnen-Dialog

- Hat OpenFoodFacts das Produkt erkannt, zeigt der Dialog nach „Anlegen / zuordnen“ (Einkaufsliste und Inventur) den Produktnamen statt des Barcodes.
- Uhr: keine Änderung.

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
