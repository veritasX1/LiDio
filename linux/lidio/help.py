"""Help for Ubuntu (card 5ef5e3c8, like the Android app): a short tour on the first start, the guide in chapters, privacy."""
import gi
gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Adw, Gtk

CHAPTERS = [
    ("Was ist LiDio?", "LiDio spielt deine eigene Musik – von deinem Musikserver zu Hause (Emby, Jellyfin oder Navidrome). Es sieht aus und "
     "funktioniert wie Musik auf dem Mac, nur ohne Abo und ohne Apple.\n\nLiDio kostet nichts, zeigt keine Werbung und verkauft nichts. "
     "Es gibt kein LiDio-Konto."),
    ("Server verbinden", "Beim ersten Start trägst du die Adresse deines Servers ein, dazu Benutzername und Passwort – dieselben wie in der "
     "Weboberfläche des Servers.\n\n„Unterwegs“ ist eine zweite Adresse für außer Haus (zum Beispiel deine goip.de-Adresse). Im Heimnetz nimmt "
     "LiDio die schnelle Adresse, sonst die zweite – von selbst.\n\nWeitere Server: Menü ☰ → Server …"),
    ("Mediathek und Suche", "Links die Seitenleiste: Start (Zuletzt gespielt, Mixe für dich, Neu hinzugefügt, Oft gehört), Playlists im Netz, "
     "deine Mediathek (Zuletzt hinzugefügt, Interpreten, Alben, Titel, Genres, Lieblingstitel, Geladen) und deine Playlists.\n\n"
     "Oben in der Seitenleiste die Suche (Strg+F): Titel, Alben, Interpreten und Playlists."),
    ("Abspielen", "Doppelklick auf einen Titel spielt ihn; ein Klick wählt aus (mit Strg oder Umschalt mehrere). Leertaste: Wiedergabe/Pause, "
     "Strg+→ / Strg+←: weiter/zurück.\n\nRechtsklick auf einen Titel: Als Nächstes spielen, Zuletzt spielen, Lieblingstitel, Zur Playlist "
     "hinzufügen, Auf diesem Computer laden, Zum Album, Zum Interpreten. Rechtsklick auf ein Album: Wiedergabe, Zufall, Als Nächstes."),
    ("Jetzt läuft", "Oben in der Mitte siehst du, was läuft. Klick aufs Cover (oder Strg+Umschalt+F) öffnet den Vollbild-Player mit großem Cover "
     "und mitlaufendem Liedtext; Esc schließt ihn.\n\nRechts oben: Liedtext und „Als Nächstes“ (die Warteschlange, mit Autoplay – danach "
     "geht es mit ähnlicher Musik weiter). Der ★ neben dem Titel macht ihn zum Lieblingstitel – gespeichert auf deinem Server."),
    ("Playlists", "Neue Playlist: unten in der Seitenleiste „Neue Playlist …“ oder per Rechtsklick → Zur Playlist hinzufügen. Auf einer "
     "Playlist führt ••• zu Umbenennen, Freigeben (für alle auf dem Server oder einzelne Personen, zum Hören oder Ändern) und Löschen.\n\n"
     "Importieren (Strg+Umschalt+O): eine Datei (M3U, PLS, XSPF, CSV, Text) oder eine öffentliche Playlist von Deezer oder Spotify "
     "(„Playlists im Netz“). LiDio gleicht sie mit deinem Server ab: grün gefunden, orange unsicher (klicken zum Wählen), rot fehlt. "
     "Was fehlt, steht in der Beschreibung der Playlist und grau unter „Fehlt auf dem Server“."),
    ("Auf diesem Computer", "Das Server-Symbol am Ende eines Titels lädt ihn auf diesen Computer, der Pfeil bei Alben und Playlists alles – "
     "immer ein Titel nach dem anderen, mit Fortschrittsring. Ein Computer-Symbol zeigt: liegt hier. So spielt die Musik auch ohne Server.\n\n"
     "Alles Geladene steht unter „Geladen“."),
    ("Mini-Player und Winamp", "Menü ☰ → Mini-Player (Strg+Umschalt+M): ein kleines Fenster wie der MiniPlayer von Musik – oder, unter "
     "Einstellungen → Mini-Player „Winamp“, die Winamp-2-Fenster mit echten Skins, Equalizer und Playlist. Skins wählst du mit dem Knopf "
     "oben links im Winamp-Fenster, auch aus dem Winamp Skin Museum.\n\nSchließt du das große Fenster, während Musik läuft, spielt LiDio "
     "weiter – steuerbar über die Medienanzeige oben und die Medientasten. Ganz beenden: Strg+Q."),
    ("Datenschutz", "LiDio sammelt nichts über dich, hat keine Werbung, keine Statistik und keine Tracker. Es gibt keine Käufe und keine Abos.\n\n"
     "Verbindungen gehen nur dorthin, wo du sie auslöst: zu deinem eigenen Server (Musik, Cover, Playlists, Favoriten); zu Deezer, wenn du "
     "„Playlists im Netz“ öffnest; zu Spotify nur mit deiner eigenen Spotify-App (Client-ID); zum Skin-Museum (skins.webamp.org) nur, "
     "solange die Skin-Auswahl offen ist; Liedtexte: nur wenn „Liedtexte aus dem Netz“ an ist, gehen Titel und Interpret an lrclib.net.\n\n"
     "Dein Server-Zugang liegt im GNOME-Schlüsselbund, nicht in einer Datei."),
]

TOUR = [
    ("go-home-symbolic", "Willkommen bei LiDio", "Deine Musik wie auf dem Mac – ohne Abo. Ein kurzer Rundgang zeigt dir die wichtigsten Stellen."),
    ("view-list-bullet-symbolic", "Links: deine Mediathek", "Start mit Mixen, Playlists im Netz, Interpreten, Alben, Titel, Genres, Lieblingstitel – "
     "und deine Playlists. Oben die Suche."),
    ("media-playback-start-symbolic", "Oben: was läuft", "Steuerung links, in der Mitte Titel und Position – Klick aufs Cover öffnet den "
     "Vollbild-Player. Rechts Liedtext und Warteschlange."),
    ("input-mouse-symbolic", "Rechtsklick hilft immer", "Auf Titeln und Alben: Als Nächstes, Lieblingstitel, Zur Playlist, Laden. "
     "Doppelklick spielt."),
    ("help-browser-symbolic", "Alles nachlesen", "Die ganze Anleitung steht im Menü ☰ → Hilfe (F1)."),
]


def guide(parent):
    d = Adw.Dialog(title="LiDio – Anleitung", content_width=640, content_height=640)
    nav = Adw.NavigationView()
    page = Adw.PreferencesPage()
    g = Adw.PreferencesGroup(description="Ohne Abo, ohne Werbung, ohne Datensammlung.")
    for i, (title, text) in enumerate(CHAPTERS):
        row = Adw.ActionRow(title=f"{i + 1}  {title}", activatable=True)
        row.add_suffix(Gtk.Image.new_from_icon_name("go-next-symbolic"))
        row.connect("activated", lambda r, i=i: nav.push(_chapter(nav, i)))
        g.add(row)
    tour_row = Adw.ActionRow(title="Rundgang", subtitle="Zeigt dir die wichtigsten Stellen – eine Minute.", activatable=True)
    tour_row.connect("activated", lambda *_: (d.close(), tour(parent)))
    g2 = Adw.PreferencesGroup(); g2.add(tour_row)
    page.add(g); page.add(g2)
    tv = Adw.ToolbarView(); tv.add_top_bar(Adw.HeaderBar()); tv.set_content(page)
    nav.add(Adw.NavigationPage(title="Anleitung", child=tv))
    d.set_child(nav)
    d.present(parent)


def _chapter(nav, i):
    title, text = CHAPTERS[i]
    box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=14, margin_start=28, margin_end=28, margin_top=10, margin_bottom=28)
    box.append(Gtk.Label(label=title, xalign=0, css_classes=["title-1"], wrap=True))
    for para in text.split("\n\n"):
        box.append(Gtk.Label(label=para, xalign=0, wrap=True, selectable=True))
    if i + 1 < len(CHAPTERS):
        nxt = Gtk.Button(label=f"Weiter: {CHAPTERS[i + 1][0]} ›", css_classes=["flat", "red-text"], halign=Gtk.Align.START, margin_top=10)
        nxt.connect("clicked", lambda *_: nav.push(_chapter(nav, i + 1)))
        box.append(nxt)
    s = Gtk.ScrolledWindow(hscrollbar_policy=Gtk.PolicyType.NEVER, vexpand=True); s.set_child(box)
    tv = Adw.ToolbarView(); tv.add_top_bar(Adw.HeaderBar()); tv.set_content(s)
    return Adw.NavigationPage(title=title, child=tv)


def tour(parent):
    d = Adw.Dialog(content_width=460, content_height=430)
    carousel = Adw.Carousel(allow_scroll_wheel=True, vexpand=True, hexpand=True, spacing=20)
    for icon, title, text in TOUR:
        sp = Adw.StatusPage(icon_name=icon, title=title, description=text, hexpand=True, vexpand=True, css_classes=["compact"])
        sp.set_size_request(440, -1)
        carousel.append(sp)
    dots = Adw.CarouselIndicatorDots(carousel=carousel)
    btn = Gtk.Button(label="Weiter", css_classes=["suggested-action", "pill"], halign=Gtk.Align.CENTER, margin_bottom=18)

    def step(*_):
        n = round(carousel.get_position())
        if n + 1 >= len(TOUR):
            d.close()
        else:
            carousel.scroll_to(carousel.get_nth_page(n + 1), True)
    btn.connect("clicked", step)
    carousel.connect("page-changed", lambda c, n: btn.set_label("Los geht's" if n + 1 >= len(TOUR) else "Weiter"))
    box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL); box.append(carousel); box.append(dots); box.append(btn)
    tv = Adw.ToolbarView(); tv.add_top_bar(Adw.HeaderBar(show_title=False)); tv.set_content(box)
    d.set_child(tv)
    d.present(parent)
