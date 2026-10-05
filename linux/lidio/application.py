"""LiDio for Ubuntu: the application – one window, playback that goes on in the background (window closed: the music keeps
playing, GNOME's media controls and the media keys still work; "Beenden" ends it), the server dialog."""
import os, threading
import gi
gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Adw, Gio, GLib, Gtk, Gdk

from . import accounts, lyrics
from .player import Player
from .offline import Offline
from .mpris import Mpris
from .servers import MediaBrowser, Subsonic, ServerError

APP_ID = "io.github.veritasx1.LiDio"


class Application(Adw.Application):
    def __init__(self):
        super().__init__(application_id=APP_ID, flags=Gio.ApplicationFlags.HANDLES_OPEN)
        self._pending_share = None
        self.player = Player()
        self.offline = Offline(); self.player.offline = self.offline
        self.window = None
        self.settings_file = os.path.join(accounts.FOLDER, "einstellungen.json")
        self._held = False
        for name, cb, keys in [("quit", lambda *_: self.quit(), ["<Control>q"]), ("servers", lambda *_: self.server_dialog(), []),
                               ("about", lambda *_: self.about(), []),
                               ("import", lambda *_: self.window and self.window.choose_import_file(), ["<Control><Shift>o"]),
                               ("settings", lambda *_: self.settings_dialog(), ["<Control>comma"]),
                               ("mini", lambda *_: self.open_mini(), ["<Control><Shift>m"]),
                               ("milkdrop", lambda *_: self.open_milkdrop(), ["<Control><Shift>v"]),
                               ("help", lambda *_: self.show_help(), ["F1"]), ("lyrics-online", None, [])]:
            if cb:
                a = Gio.SimpleAction.new(name, None); a.connect("activate", cb); self.add_action(a)
            if keys:
                self.set_accels_for_action(f"app.{name}", keys)
        online = Gio.SimpleAction.new_stateful("lyrics-online", None, GLib.Variant("b", self._setting("liedtexteNetz", True)))
        online.connect("change-state", lambda a, v: (a.set_state(v), self._set("liedtexteNetz", v.unpack())))
        self.add_action(online)

    def menu_model(self):
        m = Gio.Menu()
        m.append("Mini-Player", "app.mini")
        m.append("Milkdrop", "app.milkdrop")
        m.append("Playlist importieren …", "app.import")
        m.append("Einstellungen …", "app.settings")
        m.append("Server …", "app.servers")
        m.append("Liedtexte aus dem Netz (lrclib.net)", "app.lyrics-online")
        m.append("Hilfe", "app.help")
        m.append("Über LiDio", "app.about")
        m.append("Beenden", "app.quit")
        return m

    def _setting(self, key, default):
        import json
        try:
            with open(self.settings_file) as f:
                return json.load(f).get(key, default)
        except (OSError, ValueError):
            return default

    def _set(self, key, value):
        import json
        data = {}
        try:
            with open(self.settings_file) as f:
                data = json.load(f)
        except (OSError, ValueError):
            pass
        data[key] = value
        os.makedirs(accounts.FOLDER, exist_ok=True)
        with open(self.settings_file, "w") as f:
            json.dump(data, f)

    def do_startup(self):
        Adw.Application.do_startup(self)
        css = Gtk.CssProvider(); css.load_from_path(os.path.join(os.path.dirname(__file__), "style.css"))
        Gtk.StyleContext.add_provider_for_display(Gdk.Display.get_default(), css, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION)
        self.mpris = Mpris(self, self.player)
        self.player.listeners.append(self._playing_changed)

    def do_activate(self):
        self.present_window()

    def do_open(self, files, n, hint):
        """lidio://t#… (from the link page "In LiDio öffnen") or https://lisoft.goip.de/lidio/t#…: play that title."""
        from .share import Shared
        self.present_window()
        for f in files:
            shared = Shared.parse(f.get_uri())
            if shared:
                self.open_shared(shared); break

    # ---------- shared titles ----------
    def share_id(self, account, server):
        """The id a link from this account carries: the server's own id; for Navidrome its outside address (or the home one)."""
        if account["kind"] == "local":
            return ""
        if account["kind"] == "navidrome":
            from .servers import Subsonic
            return Subsonic(account.get("external") or account["address"], "", "").server_id()
        ids = self._setting("serverkennung", {})
        if account["id"] not in ids:
            sid = server.server_id()
            if sid:
                ids[account["id"]] = sid; self._set("serverkennung", ids)
            return sid
        return ids[account["id"]]

    def _ids_of(self, account):
        if account["kind"] == "local":
            return set()
        if account["kind"] == "navidrome":
            from .servers import Subsonic
            return {Subsonic(a, "", "").server_id() for a in (account["address"], account.get("external")) if a}
        ids = self._setting("serverkennung", {})
        if account["id"] not in ids:
            try:
                self.share_id(account, accounts.server_for(account, accounts.reachable(account)))
            except Exception:      # noqa: BLE001
                pass
            ids = self._setting("serverkennung", {})
        return {ids.get(account["id"], "")} - {""}

    def open_shared(self, shared):
        from .window import run
        from . import importing as I

        def work():
            for acc in accounts.all_accounts():
                if shared.server and shared.server in self._ids_of(acc):
                    server = accounts.server_for(acc, accounts.reachable(acc))
                    t = server.track(shared.id)
                    if t:
                        return acc, server, t
            acc = accounts.active()
            if not acc:
                return None, None, None
            server = accounts.server_for(acc, accounts.reachable(acc))
            r = I.match(server, [I.Wanted(shared.title, shared.artist, shared.album)])[0]
            return acc, server, (r.track if r.match != I.MISSING else None)

        def done(r):
            acc, server, t = r
            if not t:
                self.window.toast(f"„{shared.title}“ von {shared.artist} ist nicht in deiner Mediathek." if acc else
                                  f"Für „{shared.title}“ zuerst einen Server verbinden.")
                return
            if acc["id"] != (accounts.active() or {}).get("id"):
                accounts.set_active(acc["id"]); self.window.connect_account()
            self.player.play(server, [t])
            self.window.toast(f"Spielt „{t.title}“ – geteilt von jemandem mit LiDio")
        run(work, done, lambda m: self.window.toast(m))

    def present_window(self):
        from .window import Window
        if not self.window:
            self.window = Window(self)
        self.window.set_visible(True)
        self.window.present()

    def notify_background(self):
        """Window closed while music plays: hold the app (MPRIS keeps working) and say so once."""
        if not self._held:
            self.hold(); self._held = True
        n = Gio.Notification.new("LiDio spielt weiter")
        n.set_body("Steuern über die Medienanzeige oben oder die Medientasten. Ganz beenden: Strg+Q im Fenster.")
        self.send_notification("hintergrund", n)

    def _playing_changed(self, what):
        # "Gehörtes behalten" (card 39768ef4): a title from the server that started playing is kept on this computer too.
        if what == "track" and self._setting("behalten", False):
            t, server = self.player.current, self.player.server
            if t and server is self.offline.server and getattr(server, "kind", "") not in ("local", "web") and not self.offline.has(t):
                self.offline.download([t])
        # Paused while the window is hidden: let go again, so the app can end like any other.
        if self._held and not self.player.playing and self.window and not self.window.get_visible():
            self.release(); self._held = False

    def load_lyrics(self):
        t, server = self.player.current, self.player.server
        if not (t and server and self.window):
            self.window and self.window.show_lyrics([]); return
        online = self._setting("liedtexteNetz", True)

        def work():
            lines = lyrics.load(server, t, online)
            GLib.idle_add(lambda: (self.window.show_lyrics(lines) if self.player.current is t else None, False)[1])
        threading.Thread(target=work, daemon=True).start()

    def open_milkdrop(self):
        from . import milk
        if not milk.available():
            if self.window:
                self.window.toast("Milkdrop fehlt: native/build-milk.sh einmal ausführen (siehe LIESMICH).")
            return
        w = getattr(self, "milk", None)
        if not w or not w.get_visible():
            self.milk = milk.MilkWindow(self)
        self.milk.present()

    def show_help(self):
        from .help import guide
        if self.window:
            guide(self.window)

    def open_mini(self):
        """Music's MiniPlayer – or, with "Winamp" chosen in the settings, Winamp's windows with a real skin."""
        from .mini import MiniPlayer, WinampWindow
        old = getattr(self, "mini", None)
        if old:
            old.close()
        self.mini = (WinampWindow if self._setting("klein", "apple") == "winamp" else MiniPlayer)(self)
        self.mini.present()
        if self.window and self.window.get_visible():
            self.window.set_visible(False)
            if self.player.playing and not self._held:
                self.hold(); self._held = True

    def spotify_key(self):
        """The user's own Spotify app (developer.spotify.com): the id in the settings, the secret in the keyring."""
        from gi.repository import Secret
        cid = self._setting("spotifyId", "")
        secret = Secret.password_lookup_sync(accounts.SCHEMA, {"account": "spotify"}, None) if cid else ""
        return cid, secret or ""

    def settings_dialog(self):
        from gi.repository import Secret
        d = Adw.PreferencesDialog(title="Einstellungen")
        page = Adw.PreferencesPage(title="Allgemein", icon_name="preferences-system-symbolic")
        look = Adw.PreferencesGroup(title="Darstellung")
        modern = Adw.ComboRow(title="Erscheinungsbild", subtitle="Modern: wie Musik in macOS 26 (Glas, große Cover)",
                              model=Gtk.StringList.new(["Modern", "Klassisch"]))
        modern.set_selected(0 if self._setting("modern", True) else 1)
        modern.connect("notify::selected", lambda r, _: (self._set("modern", r.get_selected() == 0), self.window and self.window.apply_look()))
        look.add(modern)
        dup = Adw.SwitchRow(title="Doppelte ausblenden", subtitle="Gleicher Titel vom gleichen Interpreten nur einmal", active=self._setting("doppelte", True))
        dup.connect("notify::active", lambda r, _: self._set("doppelte", r.get_active()))
        look.add(dup)
        keep = Adw.SwitchRow(title="Gehörtes behalten", subtitle="Was du vom Server hörst, legt LiDio auch auf diesem Computer ab (unter „Geladen“).",
                             active=self._setting("behalten", False))
        keep.connect("notify::active", lambda r, _: self._set("behalten", r.get_active()))
        look.add(keep)
        small = Adw.ComboRow(title="Mini-Player", subtitle="Strg+Umschalt+M. Winamp: mit echten Winamp-2-Skins aus dem Skin-Museum",
                             model=Gtk.StringList.new(["Wie Musik (Apple)", "Winamp"]))
        small.set_selected(1 if self._setting("klein", "apple") == "winamp" else 0)
        small.connect("notify::selected", lambda r, _: self._set("klein", "winamp" if r.get_selected() == 1 else "apple"))
        look.add(small)
        page.add(look)
        lyr = Adw.PreferencesGroup(title="Liedtexte")
        net = Adw.SwitchRow(title="Liedtexte aus dem Netz", subtitle="Fehlt einer auf dem Server, fragt LiDio lrclib.net (nur Titel und Interpret).",
                            active=self._setting("liedtexteNetz", True))
        net.connect("notify::active", lambda r, _: (self._set("liedtexteNetz", r.get_active()), self.lookup_action("lyrics-online").set_state(GLib.Variant("b", r.get_active()))))
        lyr.add(net); page.add(lyr)
        sp = Adw.PreferencesGroup(title="Spotify-Playlists (optional)",
                                  description="Spotify gibt Playlists nur an eigene Apps heraus: auf developer.spotify.com kostenlos eine App anlegen "
                                              "und Client-ID und Secret hier eintragen. Sie bleiben auf diesem Computer (Secret im Schlüsselbund).")
        cid = Adw.EntryRow(title="Client-ID", text=self._setting("spotifyId", ""))
        sec = Adw.PasswordEntryRow(title="Client-Secret", text=self.spotify_key()[1])

        def store(*_):
            self._set("spotifyId", cid.get_text().strip())
            if sec.get_text().strip():
                Secret.password_store_sync(accounts.SCHEMA, {"account": "spotify"}, Secret.COLLECTION_DEFAULT, "LiDio – Spotify", sec.get_text().strip(), None)
            else:
                Secret.password_clear_sync(accounts.SCHEMA, {"account": "spotify"}, None)
        for r in (cid, sec):
            r.set_show_apply_button(True); r.connect("apply", store); sp.add(r)
        page.add(sp)
        d.add(page)
        d.present(self.window)

    def about(self):
        a = Adw.AboutDialog(application_name="LiDio", application_icon=APP_ID, version="0.1", developer_name="Olaf von Heidenstein",
                            license_type=Gtk.License.GPL_3_0, comments="Deine Musik vom eigenen Server – wie Musik auf dem Mac, ohne Abo.",
                            website="https://lisoft.goip.de")
        a.present(self.window)

    # ---------- server dialog ----------
    def server_dialog(self):
        if not self.window:
            return
        d = Adw.Dialog(title="Server", content_width=460)
        tv = Adw.ToolbarView(); tv.add_top_bar(Adw.HeaderBar())
        page = Adw.PreferencesPage()
        have = accounts.all_accounts()
        if have:
            g = Adw.PreferencesGroup(title="Verbunden")
            for a in have:
                row = Adw.ActionRow(title=a["name"], subtitle=f"{a['kind'].capitalize()} · {a['user']} · {a['address']}")
                use = Gtk.CheckButton(active=a["id"] == (accounts.active() or {}).get("id"), valign=Gtk.Align.CENTER)
                use.connect("toggled", lambda b, a=a: (accounts.set_active(a["id"]), self.window.connect_account(), d.close()) if b.get_active() else None)
                row.add_prefix(use)
                rm = Gtk.Button(icon_name="user-trash-symbolic", css_classes=["flat"], valign=Gtk.Align.CENTER, tooltip_text="Entfernen")
                rm.connect("clicked", lambda b, a=a: (accounts.remove(a["id"]), d.close(), self.window.connect_account()))
                row.add_suffix(rm)
                g.add(row)
            page.add(g)
        g = Adw.PreferencesGroup(title="Server hinzufügen", description="Deine Musik von deinem eigenen Server – ohne Abo.")
        kinds = Gtk.StringList.new(["Emby", "Jellyfin", "Navidrome", "Ordner auf diesem Computer"])
        kind = Adw.ComboRow(title="Quelle", model=kinds)
        folders = []
        pick = Adw.ActionRow(title="Ordner wählen …", subtitle="z. B. dein Musik-Ordner – LiDio liest nur diese", activatable=True, visible=False)
        pick.add_suffix(Gtk.Image.new_from_icon_name("folder-open-symbolic"))

        def choose(*_):
            fd = Gtk.FileDialog(title="Musik-Ordner wählen")

            def done(dlg, res):
                try:
                    files = dlg.select_multiple_folders_finish(res)
                except GLib.Error:
                    return
                for i in range(files.get_n_items()):
                    path = files.get_item(i).get_path()
                    if path and path not in folders:
                        folders.append(path)
                pick.set_subtitle("\n".join(folders) or "z. B. dein Musik-Ordner")
            fd.select_multiple_folders(self.window, None, done)
        pick.connect("activated", choose)
        address = Adw.EntryRow(title="Adresse im WLAN (z. B. 192.168.1.20:8096)")
        external = Adw.EntryRow(title="Unterwegs (optional, z. B. musik.example.de)")
        user = Adw.EntryRow(title="Benutzer")
        password = Adw.PasswordEntryRow(title="Passwort")
        for w in (kind, pick, address, external, user, password):
            g.add(w)

        def kind_changed(*_):
            local = kind.get_selected() == 3
            pick.set_visible(local)
            for w in (address, external, user, password):
                w.set_visible(not local)
        kind.connect("notify::selected", kind_changed)
        page.add(g)
        status = Gtk.Label(wrap=True, css_classes=["error"], margin_top=8)
        btn = Gtk.Button(label="Verbinden", css_classes=["suggested-action", "pill"], halign=Gtk.Align.CENTER, margin_top=12)
        bg = Adw.PreferencesGroup(); box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL); box.append(status); box.append(btn); bg.add(box)
        bg.set_description("LiDio spricht nur mit diesem Server. Das Passwort bzw. der Zugangsschlüssel liegt im GNOME-Schlüsselbund. "
                           "Keine Werbung, keine Statistik, kein Konto bei uns.")
        page.add(bg)
        tv.set_content(page); d.set_child(tv)

        def connect(*_):
            k = ["emby", "jellyfin", "navidrome", "local"][kind.get_selected()]
            if k == "local":
                if not folders:
                    status.set_text("Bitte mindestens einen Ordner wählen."); return
                btn.set_sensitive(False); status.set_text("Liest deine Ordner …")
                from .window import run
                from .local import LocalLibrary

                def made(acc_and_n):
                    d.close(); self.window.connect_account()
                acc = accounts.add("local", "\n".join(folders), "", "", "", "Auf diesem Computer")
                run(lambda: (acc, LocalLibrary(acc["id"], folders).scan()), made, lambda m: (btn.set_sensitive(True), status.set_text(m)))
                return
            a, ext, u, pw = address.get_text().strip(), external.get_text().strip(), user.get_text().strip(), password.get_text()
            if not a or not u:
                status.set_text("Bitte Adresse und Benutzername eintragen."); return
            btn.set_sensitive(False); status.set_text("")

            def work():
                if k == "navidrome":
                    s = Subsonic(a, u, pw); name = s.check(); return name, pw, ""
                s = MediaBrowser(k, a, device=accounts.device_id()); s.login(u, pw); name = s.check(); return name, s.token, s.user_id

            def done(r):
                name, secret, uid = r
                accounts.add(k, a, ext, u, secret, name, uid)
                d.close(); self.window.connect_account()

            def fail(msg):
                btn.set_sensitive(True); status.set_text(msg)
            from .window import run
            run(work, done, fail)
        btn.connect("clicked", connect)
        password.connect("entry-activated", connect)
        d.present(self.window)
