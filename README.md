# LiDio

Deine Musik von deinem eigenen Server – mit dem Bedienkomfort von Apple Music, aber ohne Abo.
LiDio verbindet sich mit **Emby**, **Jellyfin** und **Navidrome**, gestaltet nach Apples Human Interface Guidelines –
und hat einen Winamp-Modus mit echten Winamp-2-Skins.

## Werte

- Keine Werbung, keine Käufe, keine Abos, keine Tracker.
- LiDio spricht nur mit deinem Server (und – nur wenn du es öffnest – mit dem Skin-Museum oder der Playlist-Suche).
- Zugangsdaten verschlüsselt auf dem Gerät (Android-Keystore).

## Ubuntu

`linux/` – LiDio für den Desktop (GTK 4, libadwaita, GStreamer): `linux/install.sh` legt Starter, Symbol und den Befehl `lidio` an.
Tests: `python3 -m unittest linux/tests/test_core.py`; `linux/tests/drive.py` bedient die App und speichert Bilder (immer mit eigenem
`XDG_CONFIG_HOME`/`XDG_CACHE_HOME`).

## Lizenz

Freie Software unter der GNU General Public License, Version 3 (`LICENSE`). Schrift: Inter (SIL Open Font License 1.1).

Enthaltene Fremdteile: FFmpeg (LGPL-2.1, nur Audio-Decoder), Sonivox (Apache-2.0), Media3-FFmpeg-Decoder von Jellyfin (GPL-3.0),
Haze (Apache-2.0), libprojectM 4.1.7 (LGPL-2.1, Milkdrop-Visualisierung; Bau: `android/native/build-milk.sh`) und eine Auswahl
von 240 Milkdrop-Presets aus projectMs „Cream of the Crop“ (gemeinfrei, Auswahl: `tools/pick-presets.py`).
Wiedergabe mit AndroidX Media3 (Apache 2.0).

Copyright (C) 2026 Olaf Winkler

## Bauen

    cd android && ./gradlew assembleRelease     # Tests: ./gradlew testDebugUnitTest
