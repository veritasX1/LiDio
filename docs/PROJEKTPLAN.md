# Projektplan LiDio

Stand: 5. Oktober 2026 · Version 0.1 Beta (Android und Ubuntu)

## Ziel
Die eigene Musiksammlung vom eigenen Server (Emby, Jellyfin, Navidrome) oder aus Ordnern hören – so bequem wie mit der
Musik-App von Apple, aber ohne Abo, ohne Konto beim Hersteller und ohne Datensammlung. Für Android und Ubuntu.

## Grundsätze
- **Apples Gestaltungsrichtlinien zu 100 %** – Aussehen *und* Bedienkonzept (Modern = iOS/macOS 26, Klassisch wählbar).
- **Privatsphäre:** keine Werbung, kein Tracking, keine Käufe; Verbindungen nur zum eigenen Server und zu Diensten, die man selbst einschaltet.
- **Einfach zuerst:** Profi-Funktionen (Winamp, Milkdrop, Equalizer) sind Beiwerk und stören den normalen Weg nicht.
- **Rechtlich sauber:** keine fremde Musik, keine fremden Grafiken; Bilder für Seiten nur mit erfundenen Daten.

## Erreicht (0.1 Beta)
- Server: Navidrome, Jellyfin, Emby; zwei Adressen (zu Hause / unterwegs); Ordner auf dem Gerät.
- Mediathek wie in Musik: Start mit Mixen, Alben, Interpreten, Titel, Genres, Lieblingstitel, Playlists; Suche.
- Wiedergabe: gapless, Warteschlange, Autoplay, Zufall, Wiederholen, Liedtext (Server oder lrclib.net), Sperrbildschirm/MPRIS.
- Laden für unterwegs: einzeln, Alben, Playlists – nacheinander, mit Fortschritt; „Gehörtes behalten“.
- Playlists: anlegen, bearbeiten, freigeben (alle/Personen, hören/ändern), importieren und abgleichen (Datei, Deezer, Spotify).
- Teilen-Links (lisoft.goip.de/lidio/t), die LiDio beim Empfänger öffnen.
- Winamp-2-Modus mit echten Skins, Equalizer, Spektrum; Milkdrop (projectM); Mini-Player.
- Ubuntu-App mit gleichem Funktionsumfang im Stil von Musik auf dem Mac.

## Nächste Meilensteine
1. **0.2 – Feinschliff:** Sichtprüfung Modern-Look gegen iOS 26 (Referenzgerät), Genres einheitlich englisch mit Übersetzung in der Anzeige.
2. **0.3 – Mehrsprachig:** Englisch und Französisch (App, Hilfe, Seite).
3. **0.4 – Server-Helfer:** fehlende Titel einer Playlist automatisch einsortieren, „Playlist aktualisieren“ gegen die Quelle;
   Metadaten vervollständigen (Album-Interpreten, MusicBrainz).
4. **1.0:** Tests auf mehreren Geräten und Servern, F-Droid-Eintrag.

## Risiken und offene Fragen
- Server-Eigenheiten (Emby schreibt eigene Playlists aus seiner Datenbank neu – Änderungen nur über die API).
- Große Bibliotheken: Listen schlank abfragen (gemessen: Emby-Feld „ChildCount“ kostet Sekunden).
- Spotify gibt redaktionelle Playlists nur noch eingeschränkt heraus.

## Arbeitsweise
Anforderungen als Karten auf dem Board „LiDio“ (Entwicklungsprojekt: Auswirkung, Verifikation, Version je Karte).
Commits tragen die Karten-Id in eckigen Klammern. Tests: `./gradlew testOeffentlichDebugUnitTest`, `python3 -m unittest linux/tests/test_core.py`.
