# Datenschutzerklärung — Read Files Tech

_Zuletzt aktualisiert: 24. Oktober 2026 (Version 3.0.0)_

## In einem Satz

Read Files Tech erhebt, überträgt und speichert keinerlei Daten auf einem Server: Die App hat keine Berechtigung, auf das Internet zuzugreifen, und Ihre Dateien verlassen Ihr Telefon nur, wenn Sie sie selbst einer anderen App übergeben.

## Wer ist verantwortlich

Read Files Tech wird von Patrice Haltaya (Files Tech) als Privatperson veröffentlicht. Kontakt: **contact@files-tech.com**. Der Quellcode ist öffentlich: https://github.com/gitubpatrice/READ-FILES-TECH.

Der Entwickler erhält keine Daten von der App: Er hat also nichts über Sie zu speichern, einzusehen oder zu löschen. Ihn erreicht nur eine E-Mail, die Sie ihm schreiben möchten.

## Was die App nicht tut

- Keine Werbung, keine Tracker, keine Reichweitenmessung, keine gesendeten Absturzberichte.
- Kein Konto, keine Registrierung.
- **Keine Internetberechtigung**: `INTERNET` und `ACCESS_NETWORK_STATE` sind aus dem Manifest der App entfernt, und ihr Build lehnt jede Version ab, die eine davon trüge. Die App kann keinen Server erreichen.
- **Keine Google-Dienste**: weder Google-Play-Dienste noch ML Kit noch Firebase. Die Texterkennung (OCR) läuft auf dem Telefon, mit Tesseract. Die WebView, die HTML-Seiten anzeigt, ist so eingestellt, dass sie keine Nutzungsstatistiken an ihren Hersteller sendet, und die App fragt keinen externen Dienst nach ihrer Emoji-Schrift.
- Die App erstellt keine Liste Ihrer Apps. Sie prüft nur, ob vier Apps installiert sind, um Ihnen anzubieten, ihnen eine Datei zu senden: PDF Tech, kDrive, Proton Drive und Google Drive.

## Was die App liest, und warum

Read Files Tech ist ein Dateiexplorer und -betrachter. Mit Ihrer Zustimmung — „Zugriff auf alle Dateien“ ab Android 11, davor die Speicherberechtigung — kann sie alle Dateien des gemeinsamen Speichers des Telefons lesen und ändern, die SD-Karte eingeschlossen. Sie tut dies nur für das, worum Sie sie bitten: einen Ordner durchsuchen, eine Datei öffnen, sie konvertieren, suchen, Duplikate finden … Die Ordner `Android/data` und `Android/obb` anderer Apps gibt sie nie heraus.

## Was auf Ihrem Telefon gespeichert wird

- **Zuletzt geöffnete Dateien (Pfad, Name, Größe, Datum, Favorit), aus dieser Liste entfernte Dateien, Design, Ausgabeordner, automatisches Teilen** — private Einstellungen der App, unverschlüsselt.
- **Einstellungen des Tresors (Salt, Parameter der Schlüsselableitung), Fehlversuchszähler** — private Einstellungen der App, unverschlüsselt; kein Passwort.
- **Verlauf der Editoren: die letzten 10 Versionen jeder im Code-Editor oder im CSV-Editor geänderten Datei, mit ihrem Pfad** — privater Speicher der App, **unverschlüsselt**.
- **Dateien des Tresors** — privater Speicher der App, verschlüsselt (siehe unten).
- **Modelle der OCR** — privater Speicher der App, mit der App geliefert.
- **Temporäre Kopien: eine geöffnete oder geteilte Tresordatei, ein entsperrtes geschütztes PDF, ein geteilter Archiveintrag, die Fotos des Scanners** — privater Cache der App, unverschlüsselt; gelöscht, sobald sie nicht mehr gebraucht werden, spätestens beim nächsten Start.
- **Von den Werkzeugen erstellte Dateien: Scans, Konvertierungen, Komprimierungen, Bilder ohne EXIF, OCR-Texte, Tresor-Sicherungen, entpackte Archive** — Ordner `Files Tech` des gemeinsamen Speichers, oder der in den Einstellungen gewählte, unverschlüsselt, außer den Tresor-Sicherungen.
- **Papierkorb: gelöschte Dateien und ihre Beschreibung (Name, ursprünglicher Pfad, Größe, Datum)** — versteckter Ordner `.RFT_Corbeille` im Stammverzeichnis jedes Datenträgers, unverschlüsselt; **nie automatisch geleert**.

Dateien im gemeinsamen Speicher — der Ordner `Files Tech`, der Papierkorb — kann jede andere App lesen, der Sie Zugriff auf Ihre Dateien gegeben haben.

## Der Tresor

- Jede Datei wird mit **AES-256-GCM** verschlüsselt, mit einem Schlüssel, der per **Argon2id** aus Ihrem Passwort abgeleitet wird. Ein mit einer älteren Version erstellter Tresor behält die Ableitung seiner Erstellung (PBKDF2 oder ein leichteres Argon2id). Das Passwort wird nie gespeichert.
- **Nicht verschlüsselt** sind: die **Namen** der Tresordateien, ihre **Größe**, ihre Anzahl und ihre Daten, sowie die Einstellungen der Schlüsselableitung.
- Der Tresor sperrt sich 30 Sekunden, nachdem Sie die App verlassen haben, nach 3 Minuten ohne Berührung des Bildschirms und wenn Sie seinen Bildschirm verlassen. Nach 5 falschen Passwörtern wartet jeder Versuch länger, bis zu 30 Minuten.
- Die Bildschirme des Tresors und die in der App geöffneten Tresordateien können nicht aufgenommen werden und erscheinen nicht in der Vorschau der letzten Apps.
- Eine **Sicherung** `.rftvault` ist vollständig verschlüsselt, Namen eingeschlossen, mit einem eigenen Sicherungspasswort; sie wird in den Ordner der Tresor-Sicherungen im gemeinsamen Speicher geschrieben.
- **Teilen (entschlüsselt)** erstellt eine temporäre unverschlüsselte Kopie, die beim Sperren und beim nächsten Start gelöscht wird. **Exportieren** schreibt eine dauerhafte unverschlüsselte Kopie in den von Ihnen gewählten Ordner. Was die App, die eine Datei erhält, damit macht, hängt nicht mehr von Read Files Tech ab.

## Wann etwas Ihr Telefon verlässt

Immer auf eine Handlung von Ihnen hin, und an die App, die Sie wählen:

- **Teilen**, **Öffnen mit**: Die Datei wird der gewählten App übergeben, nur zum Lesen, für dieses eine Mal.
- **Senden** an kDrive, Google Drive oder Proton Drive, falls installiert.
- **In PDF Tech bearbeiten**: Schreibzugriff wird nur gegeben, wenn die installierte App die Signatur von PDF Tech trägt.
- **Nach dem Erstellen automatisch teilen**, wenn Sie diese Einstellung einschalten (standardmäßig aus).
- **Ein Foto aufnehmen** für den Scanner oder die OCR: Die Kamera-App des Telefons schreibt das Foto in eine Datei, die Read Files Tech ihr nur dafür öffnet.
- **Links** — die Seite der Versionen, die Website, E-Mail —: in Ihrem Browser oder Ihrer E-Mail-App geöffnet, die sich selbst verbinden. „Fehler melden“ bereitet eine E-Mail vor, die Sie selbst lesen und senden.

Der HTML-Betrachter zeigt eine Seite ohne JavaScript und ohne jede Verbindung an: Nur die Dateien aus dem Ordner der Seite werden geladen.

## Panikmodus

In den Einstellungen löscht der Panikmodus nach einer Bestätigung sofort: den Tresor und seine Einstellungen, alle Einstellungen der App (zuletzt geöffnete Dateien eingeschlossen), den gesamten Cache, den Verlauf der Editoren, die alten entpackten Archive und **den Papierkorb** auf allen Datenträgern. Er löscht nicht: Ihre Dateien, den Ordner `Files Tech` (Tresor-Sicherungen eingeschlossen), die aus dem Tresor exportierten Dateien, die bereits anderen Apps übergebenen Kopien und auch nicht die Zwischenablage. Das Löschen entfernt die Dateien, ohne ihren Inhalt zu überschreiben.

## Android-Sicherung

Die Sicherung von Android — in die Cloud oder von einem Telefon auf ein anderes — ist für die Daten der App ausgeschaltet. Dateien im gemeinsamen Speicher fallen nicht darunter: Ein Werkzeug, das Ihren Speicher kopiert, kann sie mitnehmen.

## Android-Berechtigungen

| Berechtigung | Warum |
|---|---|
| `MANAGE_EXTERNAL_STORAGE` (ab Android 11) | Zugriff auf alle Dateien: der Explorer |
| `READ_EXTERNAL_STORAGE` (bis Android 12), `WRITE_EXTERNAL_STORAGE` (bis Android 10) | Dasselbe auf älteren Versionen |
| `REQUEST_INSTALL_PACKAGES` | Eine im Explorer angetippte APK an das Installationsprogramm von Android übergeben, das selbst um Bestätigung bittet |
| `…DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | Von einer Android-Bibliothek hinzugefügt, intern in der App |

Weder Kamera noch Standort, Kontakte, Mikrofon, Benachrichtigungen oder Internet.

## Systemprotokoll

Schlägt ein Vorgang fehl, schreibt die App eine einzige Zeile in das Systemprotokoll: die Art des Fehlers, ohne Dateinamen und ohne Inhalt.

## Daten früherer Versionen

Version 3.0.0 übernimmt die Daten der Versionen 2.x an Ort und Stelle — Einstellungen, Tresor, Papierkorb, Verlauf —: Nichts wird gesendet oder anderswohin kopiert.

## Ihre Rechte

Ihre Daten sind auf Ihrem Telefon, unter Ihrer Kontrolle: Sie können sie in der App löschen (Dateien, Papierkorb, Tresor, Panikmodus) oder indem Sie sie deinstallieren. Die Deinstallation löscht die privaten Daten der App, nicht aber den Ordner `Files Tech` und den Papierkorb, die sich im gemeinsamen Speicher befinden. Für alle Fragen: **contact@files-tech.com**.

## Kinder

Die App richtet sich nicht besonders an Kinder; sie enthält weder Werbung noch Profilbildung.

## Änderungen

Diese Erklärung kann sich mit der App ändern; das Datum oben gibt ihre Version an.

## Sprache

Diese Erklärung wurde auf Französisch verfasst; die Fassungen in anderen Sprachen sind Übersetzungen. Bei Abweichungen **ist die französische Fassung maßgeblich**.

---

Read Files Tech gehört zur Suite **Files Tech**, veröffentlicht von **Patrice Haltaya**.
