"""The main window, after Music on the Mac (card b1768b44): sidebar (search, library, playlists), transport and a central
"now playing" display in the toolbar, album grids and detail pages in the middle, the queue on the right."""
import os, re, threading
import gi
gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Adw, GLib, Gtk, Gdk, Graphene, Pango

from gi.repository import Gio

from . import accounts
from .covers import Cover
from .servers import ServerError
from .player import REPEAT_OFF, REPEAT_ONE
from .widgets import Where, DownloadButton
from .more import MoreMixin
from .importui import ImportMixin
from .i18n import _

try:       # optional extension package – not part of the published source
    if os.environ.get("LIDIO_PRIVAT") != "1":
        raise ImportError(_("öffentliche Fassung"))
    from . import privat as PRIVAT
    from .privat import ui as PRIVAT_UI
    if not PRIVAT.available():
        PRIVAT = None
except ImportError:
    PRIVAT = None


def run(work, done, fail=None):
    """work() in a thread, done(result) on the main loop; a ServerError goes to fail (or is shown)."""
    def go():
        try:
            r = work()
        except Exception as e:      # noqa: BLE001 – every failure ends up as a German message
            GLib.idle_add(lambda: ((fail or (lambda m: None))(str(e) if isinstance(e, ServerError) else _("Unerwartete Antwort vom Server.")), False)[1])
            return
        GLib.idle_add(lambda: (done(r), False)[1])
    threading.Thread(target=go, daemon=True).start()


def clock(seconds):
    s = int(max(0, seconds)); h, m = divmod(s, 3600); m, s = divmod(m, 60)
    return f"{h}:{m:02d}:{s:02d}" if h else f"{m}:{s:02d}"


def summary(n, seconds):
    minutes = round(seconds / 60)
    return _("{n} Titel, ", n=n) + (_("{value} Std. {value2} Min.", value=minutes // 60, value2=minutes % 60) if minutes >= 60 else _("{minutes} Minuten", minutes=minutes))


def label(text, css=None, xalign=0, ellipsize=True, wrap=False, lines=1):
    l = Gtk.Label(label=text, xalign=xalign)
    if ellipsize and not wrap:
        l.set_ellipsize(Pango.EllipsizeMode.END)
    if wrap:
        l.set_wrap(True); l.set_wrap_mode(Pango.WrapMode.WORD_CHAR); l.set_lines(lines); l.set_ellipsize(Pango.EllipsizeMode.END)
    for c in (css or "").split():
        l.add_css_class(c)
    return l


class Window(MoreMixin, ImportMixin, Adw.ApplicationWindow):
    def __init__(self, app):
        super().__init__(application=app, title=__import__("lidio.application", fromlist=["APP_NAME"]).APP_NAME)
        self.app, self.player = app, app.player
        self.server, self.account = None, None
        self.set_default_size(1240, 800)
        self.set_size_request(760, 520)
        self._playlists, self._downloads = [], []
        self.search_scope, self.web_source = "mine", "music"
        if PRIVAT:
            PRIVAT_UI.follow(app.player)
            app.offline.extra = PRIVAT.copy_of
        self._install_actions()

        # ---- the toolbar: transport | now playing | volume, lyrics, queue ----
        header = Adw.HeaderBar(); self.header = header
        transport = Gtk.Box(spacing=2, css_classes=["transport"]); self.transport = transport
        self.shuffle_btn = self._button("media-playlist-shuffle-symbolic", _("Zufall"), lambda *_: self.player.toggle_shuffle(), toggle=True)
        self.prev_btn = self._button("media-skip-backward-symbolic", _("Zurück"), lambda *_: self.player.previous())
        self.play_btn = self._button("media-playback-start-symbolic", _("Wiedergabe"), lambda *_: self.player.toggle()); self.play_btn.add_css_class("play")
        self.next_btn = self._button("media-skip-forward-symbolic", _("Weiter"), lambda *_: self.player.next())
        self.repeat_btn = self._button("media-playlist-repeat-symbolic", _("Wiederholen"), lambda *_: self.player.cycle_repeat(), toggle=True)
        for b in (self.shuffle_btn, self.prev_btn, self.play_btn, self.next_btn, self.repeat_btn):
            transport.append(b)
        # Card fb44cb2d: the pages have no header bar of their own (the toolbar is the player), so Adw's back button never
        # showed and an album was a dead end. Like the ‹ in Music on the Mac: a back button at the very left of the toolbar,
        # only while there is a page to go back to (Alt+← and the mouse's back button work, too – Adw.NavigationView).
        self.back_btn = self._button("go-previous-symbolic", _("Zurück zur vorigen Seite"), lambda *_: self.nav.pop())
        self.back_btn.set_visible(False)
        header.pack_start(self.back_btn)
        header.pack_start(transport)

        lcd = Gtk.Box(css_classes=["lcd"], width_request=430); self.lcd = lcd
        self.lcd_cover = Cover(46, 7); self.lcd_cover.set_valign(Gtk.Align.CENTER)
        lcd.append(self.lcd_cover)
        mid = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, hexpand=True, valign=Gtk.Align.CENTER, margin_start=10, margin_end=10)
        self.lcd_title = label("LiDio", "lcd-title", xalign=0.5)
        self.lcd_artist = label("", "lcd-artist", xalign=0.5)
        times = Gtk.Box(spacing=6)
        self.lcd_pos = label("", "caption dim"); self.lcd_left = label("", "caption dim", xalign=1)
        self.progress = Gtk.Scale.new_with_range(Gtk.Orientation.HORIZONTAL, 0, 1, 0.001); self.progress.set_draw_value(False); self.progress.set_hexpand(True)
        self.progress.connect("change-value", self._seek)
        times.append(self.lcd_pos); times.append(self.progress); times.append(self.lcd_left)
        mid.append(self.lcd_title); mid.append(self.lcd_artist); mid.append(times)
        lcd.append(mid)
        self.lcd_star = Gtk.Button(icon_name="non-starred-symbolic", css_classes=["flat", "circular", "lcd-star"], valign=Gtk.Align.CENTER,
                                   action_name="win.lcd-fav", visible=False, margin_end=6)
        lcd.append(self.lcd_star)
        header.set_title_widget(lcd)

        self.queue_btn = self._button("view-list-bullet-symbolic", _("Als Nächstes"), self._toggle_queue, toggle=True)
        self.lyrics_btn = self._button("chat-bubble-text-symbolic", _("Liedtext"), self._toggle_lyrics, toggle=True)
        self.volume = Gtk.Scale.new_with_range(Gtk.Orientation.HORIZONTAL, 0, 1, 0.01); self.volume.set_draw_value(False)
        self.volume.set_size_request(110, -1); self.volume.set_value(1.0)
        self.volume.connect("value-changed", lambda s: self.player.set_volume(s.get_value()))
        menu = Gtk.MenuButton(icon_name="open-menu-symbolic", tooltip_text="Menü")
        m = Gtk.PopoverMenu.new_from_model(self.app.menu_model())
        menu.set_popover(m)
        header.pack_end(menu); header.pack_end(self.queue_btn); header.pack_end(self.lyrics_btn)
        vol_box = Gtk.Box(spacing=2, valign=Gtk.Align.CENTER); self.vol_box = vol_box
        vol_box.append(Gtk.Image.new_from_icon_name("audio-volume-low-symbolic")); vol_box.append(self.volume)
        header.pack_end(vol_box)

        # ---- sidebar ----
        side = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        self.search = Gtk.SearchEntry(placeholder_text=_("Suchen"), margin_start=10, margin_end=10, margin_top=10, margin_bottom=4)
        self.search.connect("search-changed", self._search_changed)
        side.append(self.search)
        scroll = Gtk.ScrolledWindow(vexpand=True, hscrollbar_policy=Gtk.PolicyType.NEVER)
        self.sidebar = Gtk.ListBox(css_classes=["navigation-sidebar"])
        self.sidebar.set_header_func(self._side_header)
        self.sidebar.connect("row-selected", self._side_selected)
        scroll.set_child(self.sidebar)
        side.append(scroll)
        self.server_btn = Gtk.Button(css_classes=["flat"], margin_start=6, margin_end=6, margin_bottom=6)
        self.server_btn.connect("clicked", lambda *_: self.app.activate_action("servers"))
        side.append(self.server_btn)

        # ---- content: a navigation stack ----
        self.nav = Adw.NavigationView()
        self.nav.connect("notify::visible-page", lambda n, *_: self.back_btn.set_visible(
            n.get_visible_page() is not None and n.get_previous_page(n.get_visible_page()) is not None))
        self.queue_panel = self._queue_panel()
        self.inner = Adw.OverlaySplitView(sidebar_position=Gtk.PackType.END, show_sidebar=False, sidebar_width_fraction=0.28,
                                          min_sidebar_width=280, max_sidebar_width=380)
        self.inner.set_content(self.nav); self.inner.set_sidebar(self.queue_panel)
        self.toasts = Adw.ToastOverlay(child=self.inner)
        split = Adw.OverlaySplitView(min_sidebar_width=220, max_sidebar_width=260, sidebar_width_fraction=0.2)
        # Card bb728258 (Olaf: "Apple Music wirkt anders auf dem Desktop"): in Music on macOS 26 the player floats as a glass capsule
        # at the bottom of the content – the Modern look puts transport, display and buttons there (apply_look moves them).
        self.capsule = Gtk.Box(spacing=14, css_classes=["player-capsule"], halign=Gtk.Align.CENTER, valign=Gtk.Align.END, margin_bottom=14)
        # Its own solid colour per theme – the named colours come out transparent in this stylesheet, and the content must not
        # show through the controls.
        sm = Adw.StyleManager.get_default()
        tone = lambda *_a: (self.capsule.remove_css_class("dark" if not sm.get_dark() else "light"),
                            self.capsule.add_css_class("dark" if sm.get_dark() else "light"))
        sm.connect("notify::dark", tone); tone()
        self.content_over = Gtk.Overlay(child=self.toasts); self.content_over.add_overlay(self.capsule)
        split.set_sidebar(side); split.set_content(self.content_over)
        side.add_css_class("side-panel")
        tv = Adw.ToolbarView(); tv.add_top_bar(header); tv.set_content(split)
        # Main view and the full-screen player (click the cover in the middle of the toolbar, Ctrl+Shift+F).
        self.top = Gtk.Stack(transition_type=Gtk.StackTransitionType.SLIDE_UP_DOWN, transition_duration=280)
        self.top.add_named(tv, "main")
        self.now = None
        self.set_content(self.top)
        click = Gtk.GestureClick(); click.connect("released", lambda *_: self.open_now_playing()); self.lcd_cover.add_controller(click)
        self.lcd_cover.set_cursor(Gdk.Cursor.new_from_name("pointer")); self.lcd_cover.set_tooltip_text("Vollbild-Player")

        self.player.listeners.append(self._player_changed)
        self.app.offline.listeners.append(self._offline_changed)
        GLib.timeout_add(500, self._tick)
        keys = Gtk.EventControllerKey(); keys.connect("key-pressed", self._key); self.add_controller(keys)
        self.connect("close-request", self._close)
        self.apply_look()
        self.connect_account()

    def apply_look(self):
        """Modern (default, like Music in macOS 26) or Klassisch – a class on the window, the stylesheet does the rest; Modern also
        moves the player from the toolbar into the floating capsule at the bottom (card bb728258)."""
        modern = self.app._setting("modern", True)
        (self.add_css_class if modern else self.remove_css_class)("modern")
        movable = [self.transport, self.lcd, self.lyrics_btn, self.queue_btn, self.vol_box]
        for w in movable:
            parent = w.get_parent()
            if parent is self.capsule:
                self.capsule.remove(w)
            elif parent is not None:
                self.header.remove(w) if w is not self.lcd else self.header.set_title_widget(None)
        if modern:
            for w in movable:
                self.capsule.append(w)
            self.lcd.set_size_request(380, -1); self.volume.set_size_request(80, -1)
            self.capsule.set_visible(True)
        else:
            self.header.pack_start(self.transport); self.header.set_title_widget(self.lcd); self.lcd.set_size_request(430, -1)
            for w in (self.queue_btn, self.lyrics_btn, self.vol_box):
                self.header.pack_end(w)
            self.volume.set_size_request(110, -1)
            self.capsule.set_visible(False)

    # ---------- small helpers ----------
    def _button(self, icon, tip, cb, toggle=False):
        b = (Gtk.ToggleButton if toggle else Gtk.Button)(icon_name=icon, tooltip_text=tip, css_classes=["flat"], valign=Gtk.Align.CENTER)
        b.update_property([Gtk.AccessibleProperty.LABEL], [tip])
        b.connect("toggled" if toggle else "clicked", lambda w: None if getattr(self, "_syncing", False) else cb(w))
        return b

    def toast(self, text):
        self.toasts.add_toast(Adw.Toast(title=text, timeout=3))

    def open_now_playing(self):
        if not self.player.current:
            return
        from .nowplaying import NowPlaying
        if not self.now:
            self.now = NowPlaying(self); self.top.add_named(self.now, "now")
        self.top.set_visible_child_name("now")
        self.app.load_lyrics()

    def close_now_playing(self):
        self.top.set_visible_child_name("main")

    def _key(self, ctrl, keyval, code, state):
        if keyval == Gdk.KEY_Escape and self.top.get_visible_child_name() == "now":
            self.close_now_playing(); return True
        if state & Gdk.ModifierType.CONTROL_MASK and state & Gdk.ModifierType.SHIFT_MASK and keyval in (Gdk.KEY_F, Gdk.KEY_f):
            self.close_now_playing() if self.top.get_visible_child_name() == "now" else self.open_now_playing(); return True
        if keyval == Gdk.KEY_space and not isinstance(self.get_focus(), Gtk.Editable):
            self.player.toggle(); return True
        if state & Gdk.ModifierType.CONTROL_MASK and keyval == Gdk.KEY_f:
            self.search.grab_focus(); return True
        if state & Gdk.ModifierType.CONTROL_MASK and keyval == Gdk.KEY_Right:
            self.player.next(); return True
        if state & Gdk.ModifierType.CONTROL_MASK and keyval == Gdk.KEY_Left:
            self.player.previous(); return True
        return False

    def _close(self, *_):
        # Like Music: closing the window keeps the music playing – LiDio stays in the top bar's media controls.
        if self.player.playing:
            self.set_visible(False)
            self.app.notify_background()
            return True
        return False

    # ---------- account ----------
    def connect_account(self):
        self.account = accounts.active()
        if not self.account:
            self.server = None
            self._fill_sidebar([])
            btn = Gtk.Button(label=_("Server verbinden …"), css_classes=["red-button"], halign=Gtk.Align.CENTER)
            btn.connect("clicked", lambda *_: self.app.server_dialog())
            empty = Adw.StatusPage(icon_name="io.github.veritasx1.LiDio", title=_("Willkommen bei LiDio"),
                                   description=_("Deine Musik von deinem eigenen Server – Emby, Jellyfin oder Navidrome. Ohne Abo, ohne Werbung."), child=btn)
            self.nav.replace([self._page("LiDio", empty)])
            # after the window exists – the dialog needs it as parent
            GLib.idle_add(lambda: (self.app.server_dialog(), False)[1])
            return
        acc = self.account
        self.server_btn.set_child(label(acc["name"], "dim caption"))
        if acc["kind"] == "local":
            self._connected(accounts.server_for(acc)); return

        def work():
            return accounts.server_for(acc, accounts.reachable(acc))
        run(work, self._connected, lambda msg: self.toast(msg))

    def _connected(self, server):
        if not self.app._setting("rundgang", False):
            # The first time with a server: the short tour (card 5ef5e3c8).
            self.app._set("rundgang", True)
            from .help import tour
            GLib.idle_add(lambda: (tour(self), False)[1])
        self.server = server
        self.player.server = self.player.server or server
        self.app.offline.use(self.account, server)
        self._fill_sidebar([])
        run(server.playlists, self._fill_sidebar, lambda m: self.toast(m))
        self.show_page("start")
        if server.kind == "local":
            # Folders: read what changed since last time, then show it.
            run(server.scan, lambda n: (self.toast(_("{n} Titel in deinen Ordnern", n=n)), self.show_page("start")), self.toast)

    # ---------- sidebar ----------
    TOP = [("start", _("Start"), "go-home-symbolic"), ("new", _("Neu"), "view-app-grid-symbolic"),
           ("web-lists", _("Playlists im Netz"), "network-workgroup-symbolic")]
    ENTRIES = [("recent", _("Zuletzt hinzugefügt"), "document-open-recent-symbolic"), ("artists", _("Interpreten"), "avatar-default-symbolic"),
               ("albums", _("Alben"), "media-optical-symbolic"), ("tracks", _("Titel"), "audio-x-generic-symbolic"),
               ("genres", _("Genres"), "view-grid-symbolic"), ("favorites", _("Lieblingstitel"), "starred-symbolic"),
               ("downloaded", _("Geladen"), "folder-download-symbolic"), ("mixtapes", _("Mixtapes"), "lidio-cassette-symbolic")]

    def _fill_sidebar(self, playlists):
        selected = getattr(self.sidebar.get_selected_row(), "key", None)
        self._playlists = list(playlists)
        while (row := self.sidebar.get_row_at_index(0)) is not None:
            self.sidebar.remove(row)
        self._sel_guard = True
        for key, text, icon in self.TOP:
            self._side_row(key, text, icon, "LiDio")
        # Like Music: what is pinned stands at the top of the library.
        for kind, pid, name in self.pins():
            self._side_row(("pin", kind, pid, name), name, "view-pin-symbolic", "Angeheftet")
        for key, text, icon in self.ENTRIES:
            self._side_row(key, text, icon, _("Mediathek"))
        if PRIVAT:
            self._side_row("netz", _("Aus dem Netz"), "weather-overcast-symbolic", _("Mediathek"))
            self._side_row("upload", _("Musik hochladen"), "lidio-computer-upload-symbolic", _("Mediathek"))
        for p in playlists:
            self._side_row(("playlist", p.id, p.name), p.name, "lidio-cassette-symbolic" if getattr(p, "mixtape", False) else "view-list-symbolic", _("Playlists"))
        if self.server:
            self._side_row("new-playlist", _("Neue Playlist …"), "list-add-symbolic", _("Playlists"))
        for i in range(400):
            row = self.sidebar.get_row_at_index(i)
            if row is None:
                break
            if selected is not None and row.key == selected:
                self.sidebar.select_row(row); break
        self._sel_guard = False

    def _side_row(self, key, text, icon, section):
        box = Gtk.Box(spacing=10)
        box.append(Gtk.Image.new_from_icon_name(icon)); box.append(label(text))
        row = Gtk.ListBoxRow(child=box); row.key, row.section = key, section
        self.sidebar.append(row)

    def _side_header(self, row, before):
        if before is None or before.section != row.section:
            row.set_header(label(row.section.upper(), "sidebar-heading"))
        else:
            row.set_header(None)

    def _side_selected(self, box, row):
        if row is None or getattr(self, "_sel_guard", False):
            return
        self.show_page(row.key, from_sidebar=True)

    # ---------- pages ----------
    def upload_dialog(self):
        if PRIVAT:
            PRIVAT_UI.upload_dialog(self)

    def _page(self, title, child, tag=None):
        page = Adw.NavigationPage(title=title, child=child)
        if tag:
            page.set_tag(tag)
        return page

    def show_page(self, key, from_sidebar=False):
        if not self.server:
            return
        if key == "new-playlist":
            self.sidebar.unselect_all(); self.new_playlist(); return
        if key == "upload":
            self.sidebar.unselect_all(); self.upload_dialog(); return
        if isinstance(key, tuple) and key[0] == "pin":
            page = self.album_page(key[2]) if key[1] == "album" else self.playlist_page(key[2], key[3])
        elif isinstance(key, tuple) and key[0] == "playlist":
            page = self.playlist_page(key[1], key[2])
        elif key == "start":
            page = self.start_page()
        elif key == "new":
            page = self.new_page()
        elif key == "mixtapes":
            page = self.mixtapes_page()
        elif key == "netz" and PRIVAT:
            page = PRIVAT_UI.library_page(self)
        elif key == "web-lists":
            page = self.web_lists_page()
        elif key == "genres":
            page = self.genres_page()
        elif key == "downloaded":
            page = self.downloaded_page()
        elif key == "recent":
            page = self.albums_page(_("Zuletzt hinzugefügt"), "newest")
        elif key == "albums":
            page = self.albums_page(_("Alben"), "title", sortable=True)
        elif key == "artists":
            page = self.artists_page()
        elif key == "tracks":
            page = self.tracks_page(_("Titel"), lambda: self.server.tracks(500))
        elif key == "favorites":
            page = self.tracks_page(_("Lieblingstitel"), self.server.favorites)
        else:
            return
        self.nav.replace([page])

    def push(self, page):
        self.nav.push(page)

    def _scroll(self, child):
        s = Gtk.ScrolledWindow(hscrollbar_policy=Gtk.PolicyType.NEVER, vexpand=True)
        # Modern: room below the last row, so the floating player never covers it.
        if self.app._setting("modern", True):
            child.set_margin_bottom(max(child.get_margin_bottom(), 96))
        s.set_child(child)
        return s

    def _loading(self):
        return Adw.StatusPage(title=_("Wird geladen …"))

    def albums_page(self, title, order, sortable=False):
        outer = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        top = Gtk.Box(margin_start=28, margin_end=28, margin_top=20, margin_bottom=8)
        top.append(label(title, "large-title"))
        grid = Gtk.FlowBox(selection_mode=Gtk.SelectionMode.NONE, homogeneous=True, min_children_per_line=2, max_children_per_line=12,
                           column_spacing=22, row_spacing=22, margin_start=28, margin_end=28, margin_bottom=28, valign=Gtk.Align.START)
        state = {"order": order, "offset": 0, "busy": False, "done": False}

        def more():
            if state["busy"] or state["done"]:
                return
            state["busy"] = True
            run(lambda: self.server.albums(state["order"], 120, state["offset"]), added)

        def added(albums):
            state["busy"] = False; state["offset"] += len(albums); state["done"] = len(albums) < 120
            for a in albums:
                grid.append(self.album_tile(a))

        if sortable:
            spacer = Gtk.Box(hexpand=True); top.append(spacer)
            choices = [("title", _("Titel")), ("artist", _("Interpret")), ("year", "Erscheinungsjahr"), ("newest", _("Zuletzt hinzugefügt")), ("recent", _("Zuletzt gespielt"))]
            drop = Gtk.DropDown.new_from_strings([c[1] for c in choices]); drop.set_tooltip_text(_("Sortieren nach"))
            drop.set_valign(Gtk.Align.CENTER)

            def resort(d, _):
                state.update(order=choices[d.get_selected()][0], offset=0, done=False)
                while (c := grid.get_first_child()) is not None:
                    grid.remove(c)
                more()
            drop.connect("notify::selected", resort)
            top.append(drop)
        outer.append(top); outer.append(grid)
        scroll = self._scroll(outer)
        scroll.get_vadjustment().connect("value-changed", lambda adj: more() if adj.get_value() + adj.get_page_size() > adj.get_upper() - 600 else None)
        more()
        return self._page(title, scroll)

    def album_tile(self, album):
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4, width_request=180)
        cover = Cover(180, 8)
        server = self.server
        cover.show(server.cover_url(album.cover_id or album.id, 360), fallback=lambda: server.album_cover(album.id, 360))
        box.append(cover)
        # Labels must not ask for their full text width, or the grid puts only two albums in a line.
        for text, css in ((album.title, "tile-title"), (album.artist, "dim")):
            l = label(text, css); l.set_max_width_chars(1); l.set_hexpand(True); box.append(l)
        click = Gtk.GestureClick(); click.connect("released", lambda *_: self.push(self.album_page(album.id)))
        box.add_controller(click)
        box.set_cursor(Gdk.Cursor.new_from_name("pointer"))
        menu = self._collection_menu(lambda: self.server.album(album.id)[1], ("album", album.id, album.title))
        right = Gtk.GestureClick(button=3); right.connect("pressed", lambda g, n, x, y: (menu.set_parent(box) if not menu.get_parent() else None, menu.popup()))
        box.add_controller(right)
        return box

    def _collection_menu(self, tracks_of, pin=None):
        pop = Gtk.Popover(has_arrow=True)
        col = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        if pin:
            pinned = self.is_pinned(pin)
            b = Gtk.Button(label=_("Nicht mehr anheften") if pinned else _("Anheften"), css_classes=["flat"]); b.get_child().set_xalign(0)
            b.connect("clicked", lambda *_: (pop.popdown(), self.toggle_pin(pin)))
            col.append(b)
        for text, act in [(_("Wiedergabe"), lambda t: self.player.play(self.server, t)), (_("Zufall"), lambda t: self.player.play(self.server, t, shuffled=True)),
                          (_("Als Nächstes spielen"), lambda t: self.player.play_next(t)), (_("Zuletzt spielen"), lambda t: self.player.add(t))]:
            b = Gtk.Button(label=text, css_classes=["flat"]); b.get_child().set_xalign(0)
            b.connect("clicked", lambda w, a=act: (pop.popdown(), run(tracks_of, a)))
            col.append(b)
        pop.set_child(col)
        return pop

    def album_page(self, album_id):
        page_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        page = self._page(_("Album"), self._scroll(page_box))

        def show(r):
            album, tracks = r
            page.set_title(album.title)
            head = Gtk.Box(spacing=28, margin_start=36, margin_end=36, margin_top=30, margin_bottom=10)
            cover = Cover(260, 10); cover.show(self.server.cover_url(album.cover_id, 600) if album.cover_id else
                                                 (self.server.cover_url(tracks[0].cover_id, 600) if tracks and tracks[0].cover_id else None))
            head.append(cover)
            info = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4, valign=Gtk.Align.END, hexpand=True)
            info.append(label(album.title, "album-title", wrap=True, lines=2))
            artist = Gtk.Button(css_classes=["flat"], halign=Gtk.Align.START); artist.set_child(label(album.artist, "album-artist"))
            if album.artist_id:
                artist.connect("clicked", lambda *_: self.push(self.artist_page(album.artist_id)))
            info.append(artist)
            info.append(label(" · ".join(x for x in [self.genre_name(album.genre) if album.genre else "", str(album.year or "")] if x).upper(), "dim caption"))
            info.append(self._play_buttons(tracks))
            head.append(info)
            page_box.append(head)
            # Card 8a7dfec3: the same title twice (two files of one song) – hidden on album pages, too, like on Android.
            tracks = self._dedup(tracks)
            page_box.append(self.track_list(tracks, numbers=True))
            foot = label((_("Erschienen {year}\n", year=album.year) if album.year else "") + summary(len(tracks), sum(t.duration for t in tracks)),
                         "dim caption", ellipsize=False)
            foot.set_margin_start(40); foot.set_margin_top(12); foot.set_margin_bottom(30)
            page_box.append(foot)
        run(lambda: self.server.album(album_id), show, self.toast)
        return page

    def _play_buttons(self, tracks, extra=None, dedup=True):
        tracks = self._dedup(tracks) if dedup else tracks
        row = Gtk.Box(spacing=12, margin_top=14)
        play = Gtk.Button(css_classes=["red-button"]); play.set_child(Gtk.Label(label=_("▶  Wiedergabe")))
        play.connect("clicked", lambda *_: self.player.play(self.server, tracks))
        shuf = Gtk.Button(css_classes=["red-button"]); shuf.set_child(Gtk.Label(label=_("⤮  Zufall")))
        shuf.connect("clicked", lambda *_: self.player.play(self.server, tracks, shuffled=True))
        row.append(play); row.append(shuf)
        row.append(Gtk.Box(hexpand=True))
        if tracks and getattr(self.server, "kind", "") != "local":
            d = DownloadButton(self, tracks); self._downloads.append(d); row.append(d)
        if extra:
            row.append(extra)
        return row

    # ---------- pins (Android: "Anheften") ----------
    def pins(self):
        acc = (self.account or {}).get("id", "")
        return [tuple(p) for p in self.app._setting("angeheftet", {}).get(acc, [])]

    def is_pinned(self, pin):
        return any(p[0] == pin[0] and p[1] == pin[1] for p in self.pins())

    def toggle_pin(self, pin):
        acc = (self.account or {}).get("id", "")
        allp = self.app._setting("angeheftet", {})
        mine = [p for p in allp.get(acc, []) if not (p[0] == pin[0] and p[1] == pin[1])]
        if not self.is_pinned(pin):
            mine.insert(0, list(pin))
        allp[acc] = mine[:12]
        self.app._set("angeheftet", allp)
        self._fill_sidebar(self._playlists)
        self.toast(_("Angeheftet – steht jetzt oben in der Seitenleiste") if self.is_pinned(pin) else _("Nicht mehr angeheftet"))

    def _dedup(self, tracks):
        """Setting "Doppelte ausblenden" (card 29c6affc): the same title by the same artist only once – album pages too (8a7dfec3)."""
        from .importing import without_duplicates
        return without_duplicates(tracks) if self.app._setting("doppelte", True) else tracks

    def track_list(self, tracks, numbers=False, show_art=False, playlist=None):
        if not numbers:
            tracks = self._dedup(tracks)
        box = Gtk.ListBox(css_classes=["tracklist"], selection_mode=Gtk.SelectionMode.MULTIPLE, margin_start=28, margin_end=28)
        box.set_activate_on_single_click(False)
        self._rows = getattr(self, "_rows", [])
        for i, t in enumerate(tracks):
            row = Gtk.ListBoxRow(); row.track = t
            if i % 2:
                row.add_css_class("odd")
            h = Gtk.Box(spacing=12)
            if show_art:
                c = Cover(36, 4); c.show(self.server.cover_url(t.cover_id, 80) if t.cover_id else None); h.append(c)
            elif numbers:
                h.append(label(str(t.number or i + 1), "track-number", xalign=1))
            col = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, hexpand=True, valign=Gtk.Align.CENTER)
            col.append(label(t.title, "title"))
            if not numbers or show_art:
                col.append(label(t.artist + (f" · {t.album}" if show_art and t.album else ""), "dim caption"))
            h.append(col)
            row.star = Gtk.Image.new_from_icon_name("starred-symbolic"); row.star.add_css_class("favorite"); row.star.set_visible(bool(t.favorite))
            row.star.set_tooltip_text(_("Lieblingstitel"))
            h.append(row.star)
            h.append(label(clock(t.duration) if t.duration else "", "track-time", xalign=1))
            row.where = Where(self.app.offline, t, lambda: self.server)
            h.append(row.where)
            row.set_child(h)
            right = Gtk.GestureClick(button=3)
            right.connect("pressed", lambda g, n, x, y, row=row, box=box: self._row_menu(box, row, x, y, playlist))
            row.add_controller(right)
            box.append(row)
            self._rows.append(row)
        # Like Music: a click selects, a double click (or Enter) plays.
        box.connect("row-activated", lambda b, r: self.player.play(self.server, tracks, tracks.index(r.track)) if hasattr(r, "track")
                    else (PRIVAT_UI.missing_play(self, r.line) if PRIVAT and hasattr(r, "line") else None))
        self._mark_playing()
        return box

    def _missing_rows_public(self, playlist):
        """The public LiDio loads nothing from the net: missing titles grey at their place, the crossed-out cloud."""
        rows = []
        for line in playlist.missing:
            m = re.match(r"^(\d+) · ", line)
            text = re.sub(r"^\d+ · ", "", line)
            artist, _sep, title = text.partition(" – ")
            if not title:
                artist, title = "", text
            h = Gtk.Box(spacing=12, css_classes=["missing"])
            h.append(Cover(36, 4))
            col = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, hexpand=True, valign=Gtk.Align.CENTER)
            col.append(label(title, "title")); col.append(label(artist, "dim caption")); h.append(col)
            off = Gtk.Image.new_from_icon_name("weather-overcast-symbolic"); off.add_css_class("dim"); h.append(off)
            row = Gtk.ListBoxRow(child=h, activatable=False); row.set_tooltip_text(_("Fehlt auf dem Server"))
            row.position = int(m.group(1)) if m else None
            rows.append(row)
        return rows

    def _row_menu(self, box, row, x, y, playlist):
        chosen = [r.track for r in box.get_selected_rows() if hasattr(r, "track")]
        if row.track not in chosen:
            box.unselect_all(); box.select_row(row); chosen = [row.track]
        p = next((q for q in self._playlists if q.id == playlist), None) if isinstance(playlist, str) else playlist
        ok, pt = row.compute_point(box, Graphene.Point().init(x, y))
        self.track_menu(box, pt.x if ok else x, pt.y if ok else y, chosen, p)

    def _refresh_rows(self):
        for r in list(getattr(self, "_rows", [])):
            if r.get_parent() is None:
                self._rows.remove(r); continue
            r.star.set_visible(bool(r.track.favorite))

    def _offline_changed(self):
        for r in list(getattr(self, "_rows", [])):
            if r.get_parent() is None:
                continue
            r.where.update()
        for b in list(self._downloads):
            if b.get_root() is None:
                self._downloads.remove(b)
            else:
                b.update()

    def artists_page(self):
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        box.append(Gtk.Label(label=_("Interpreten"), xalign=0, css_classes=["large-title"], margin_start=28, margin_top=20, margin_bottom=8))
        lst = Gtk.ListBox(css_classes=["navigation-sidebar"], margin_start=20, margin_end=20)
        box.append(lst)

        def show(artists):
            for a in artists[:3000]:
                row = Gtk.ListBoxRow(); row.artist = a
                row.set_child(Gtk.Label(label=a.name, xalign=0, margin_start=8, margin_top=4, margin_bottom=4))
                lst.append(row)
        lst.connect("row-activated", lambda l, r: self.push(self.artist_page(r.artist.id)))
        run(self.server.artists, show, self.toast)
        return self._page(_("Interpreten"), self._scroll(box))

    def artist_page(self, artist_id):
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        page = self._page(_("Interpret"), self._scroll(box))

        def show(r):
            artist, albums = r
            page.set_title(artist.name)
            box.append(Gtk.Label(label=artist.name, xalign=0, css_classes=["large-title"], margin_start=28, margin_top=20, margin_bottom=8))
            grid = Gtk.FlowBox(selection_mode=Gtk.SelectionMode.NONE, homogeneous=True, max_children_per_line=12, column_spacing=22, row_spacing=22,
                               margin_start=28, margin_end=28, margin_bottom=28)
            for a in albums:
                grid.append(self.album_tile(a))
            box.append(grid)
        run(lambda: self.server.artist(artist_id), show, self.toast)
        return page

    def tracks_page(self, title, load):
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        box.append(Gtk.Label(label=title, xalign=0, css_classes=["large-title"], margin_start=28, margin_top=20, margin_bottom=8))

        def show(tracks):
            box.append(self._wrap(self._play_buttons(tracks)))
            box.append(self.track_list(tracks, show_art=True))
        run(load, show, self.toast)
        return self._page(title, self._scroll(box))

    def _wrap(self, w):
        b = Gtk.Box(margin_start=28, margin_bottom=14); b.append(w); return b

    def playlist_page(self, playlist_id, name):
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        page = self._page(name, self._scroll(box))

        def show(r):
            p, tracks = r
            head = Gtk.Box(spacing=28, margin_start=36, margin_end=36, margin_top=30, margin_bottom=10)
            cover = Cover(220, 10); cover.show("mixtape:" + p.name if getattr(p, "mixtape", False) and not getattr(p, "own_cover", False) else
                                                self.server.cover_url(p.cover_id, 500) if p.cover_id else
                                                (self.server.cover_url(tracks[0].cover_id, 500) if tracks and tracks[0].cover_id else None))
            head.append(cover)
            info = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4, valign=Gtk.Align.END, hexpand=True)
            info.append(label(p.name, "album-title", wrap=True, lines=2))
            if getattr(p, "mixtape", False):
                # Card c9b15c67: a Mixtape shows the cassette under its name.
                mt = Gtk.Box(spacing=4); mt.append(Gtk.Image.new_from_icon_name("lidio-cassette-symbolic")); mt.append(label(_("Mixtape"), "dim"))
                info.append(mt)
            info.append(label(summary(len(tracks), sum(t.duration for t in tracks)), "dim"))
            info.append(self._play_buttons(tracks, self.playlist_menu(p, tracks)))
            head.append(info)
            box.append(head)
            # Card eea4ee65: "Musik hinzufügen" right in the playlist (also an empty Mixtape), like Music.
            if hasattr(self.server, "add_to_playlist"):
                add = Gtk.Button(css_classes=["flat", "add-music"], halign=Gtk.Align.START, margin_start=28)
                ab = Gtk.Box(spacing=10)
                ab.append(Gtk.Image.new_from_icon_name("lidio-cassette-add-symbolic" if getattr(p, "mixtape", False) else "list-add-symbolic"))
                ab.append(Gtk.Label(label=_("Musik hinzufügen"))); add.set_child(ab)
                add.connect("clicked", lambda *_a: self.add_music_dialog(p))
                box.append(add)
            lst = self.track_list(tracks, show_art=True, playlist=p)
            if p.missing:
                # Card f436a1f7: like Android – what the server lacks stands greyed at its place in the list (cover and length
                # from the net in LiDio privat, with the cloud to load it), above it how many and "Alle laden".
                if PRIVAT:
                    box.append(PRIVAT_UI.missing_tools(self, p, None))
                    rows = PRIVAT_UI.missing_rows(self, p, tracks)
                else:
                    box.append(label(_("{count} Titel fehlen auf dem Server – grau", count=len(p.missing)), "dim"))
                    box.get_last_child().set_margin_start(28)
                    rows = self._missing_rows_public(p)
                for r in sorted((r for r in rows if r.position), key=lambda r: r.position):
                    lst.insert(r, r.position - 1)
                for r in rows:
                    if not r.position:
                        lst.append(r)
            box.append(lst)
            box.append(Gtk.Box(height_request=30))
        run(lambda: self.server.playlist(playlist_id), show, self.toast)
        return page

    # ---------- search ----------
    def _search_changed(self, entry):
        q = entry.get_text().strip()
        if hasattr(self, "_search_timer") and self._search_timer:
            GLib.source_remove(self._search_timer)
        self._search_timer = GLib.timeout_add(350, lambda: (self._search(q), setattr(self, "_search_timer", None), False)[-1]) if len(q) >= 2 else None

    def _search(self, q):
        if not self.server:
            return
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        box.append(Gtk.Label(label=_("Suchergebnisse für „{q}“", q=q), xalign=0, css_classes=["large-title"], margin_start=28, margin_top=20, margin_bottom=8))
        if PRIVAT:
            box.append(PRIVAT_UI.scope_bar(self, q))
            if self.search_scope == "net":
                PRIVAT_UI.search_page(self, q, box)
                self.sidebar.unselect_all()
                self.nav.replace([self._page(_("Suchen"), self._scroll(box))])
                return

        def show(r):
            if not (r.artists or r.albums or r.tracks or r.playlists):
                box.append(Adw.StatusPage(title=_("Keine Treffer"), description=_("Auf deinem Server nichts zu „{q}“.", q=q)))
            if r.tracks:
                box.append(Gtk.Label(label=_("Titel"), xalign=0, css_classes=["section-title"], margin_start=28, margin_top=10, margin_bottom=6))
                box.append(self.track_list(r.tracks[:12], show_art=True))
            if r.albums:
                box.append(Gtk.Label(label=_("Alben"), xalign=0, css_classes=["section-title"], margin_start=28, margin_top=18, margin_bottom=6))
                grid = Gtk.FlowBox(selection_mode=Gtk.SelectionMode.NONE, homogeneous=True, max_children_per_line=12, column_spacing=22,
                                   row_spacing=22, margin_start=28, margin_end=28)
                for a in r.albums:
                    grid.append(self.album_tile(a))
                box.append(grid)
            if r.artists:
                box.append(Gtk.Label(label=_("Interpreten"), xalign=0, css_classes=["section-title"], margin_start=28, margin_top=18, margin_bottom=6))
                flow = Gtk.FlowBox(selection_mode=Gtk.SelectionMode.NONE, max_children_per_line=8, margin_start=28, margin_end=28)
                for a in r.artists:
                    b = Gtk.Button(label=a.name, css_classes=["pill"]); b.connect("clicked", lambda w, a=a: self.push(self.artist_page(a.id)))
                    flow.append(b)
                box.append(flow)
            if r.playlists:
                box.append(Gtk.Label(label=_("Playlists"), xalign=0, css_classes=["section-title"], margin_start=28, margin_top=18, margin_bottom=6))
                for p in r.playlists[:12]:
                    b = Gtk.Button(label=p.name, css_classes=["flat"], halign=Gtk.Align.START, margin_start=22)
                    b.connect("clicked", lambda w, p=p: self.push(self.playlist_page(p.id, p.name)))
                    box.append(b)
            box.append(Gtk.Box(height_request=30))
        run(lambda: self.server.search(q), show, self.toast)
        self.sidebar.unselect_all()
        self.nav.replace([self._page(_("Suchen"), self._scroll(box))])

    # ---------- queue & lyrics panel ----------
    def _queue_panel(self):
        self.panel_stack = Gtk.Stack()
        q = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        top = Gtk.Box(spacing=6, margin_start=10, margin_end=10, margin_top=10)
        top.append(label(_("Als Nächstes"), "section-title"))
        top.append(Gtk.Box(hexpand=True))
        self.autoplay_btn = Gtk.ToggleButton(icon_name="media-playlist-consecutive-symbolic", tooltip_text=_("Autoplay – danach ähnliche Musik"),
                                             active=True, css_classes=["flat"])
        self.autoplay_btn.connect("toggled", lambda b: setattr(self.player, "autoplay", b.get_active()))
        top.append(self.autoplay_btn)
        q.append(top)
        self.queue_list = Gtk.ListBox(css_classes=["tracklist"], selection_mode=Gtk.SelectionMode.NONE)
        self.queue_list.connect("row-activated", lambda l, r: (self.player.jump(self.player.order[r.position]) if hasattr(r, "position") else None))
        s = Gtk.ScrolledWindow(vexpand=True, hscrollbar_policy=Gtk.PolicyType.NEVER); s.set_child(self.queue_list)
        q.append(s)
        self.panel_stack.add_named(q, "queue")
        self.lyrics_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=10, margin_start=16, margin_end=16, margin_top=16)
        ls = Gtk.ScrolledWindow(vexpand=True, hscrollbar_policy=Gtk.PolicyType.NEVER); ls.set_child(self.lyrics_box)
        self.lyrics_scroll = ls
        self.panel_stack.add_named(ls, "lyrics")
        return self.panel_stack

    def _toggle_queue(self, btn):
        if btn.get_active():
            self._syncing = True; self.lyrics_btn.set_active(False); self._syncing = False
            self.panel_stack.set_visible_child_name("queue"); self._fill_queue()
        self.inner.set_show_sidebar(btn.get_active() or self.lyrics_btn.get_active())

    def _toggle_lyrics(self, btn):
        if btn.get_active():
            self._syncing = True; self.queue_btn.set_active(False); self._syncing = False
            self.panel_stack.set_visible_child_name("lyrics"); self.app.load_lyrics()
        self.inner.set_show_sidebar(btn.get_active() or self.queue_btn.get_active())

    def _fill_queue(self):
        while (r := self.queue_list.get_row_at_index(0)) is not None:
            self.queue_list.remove(r)
        up = self.player.upcoming()
        normal = [(p, t) for p, t in up if t.id not in self.player.autoplayed]
        auto = [(p, t) for p, t in up if t.id in self.player.autoplayed]
        for title, items in (("", normal), (_("Autoplay"), auto)):
            if title and items:
                hr = Gtk.ListBoxRow(activatable=False, selectable=False); hr.set_child(label(title, "queue-heading")); self.queue_list.append(hr)
            for p, t in items:
                row = Gtk.ListBoxRow(); row.position = p
                h = Gtk.Box(spacing=10, margin_start=8, margin_end=8)
                c = Cover(36, 4); c.show(self.player.server.cover_url(t.cover_id, 80) if t.cover_id and self.player.server else None); h.append(c)
                col = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, hexpand=True, valign=Gtk.Align.CENTER)
                col.append(label(t.title)); col.append(label(t.artist, "dim caption"))
                h.append(col)
                rm = Gtk.Button(icon_name="window-close-symbolic", css_classes=["flat", "circular"], tooltip_text=_("Entfernen"), valign=Gtk.Align.CENTER)
                rm.connect("clicked", lambda w, p=p: self.player.remove(p))
                h.append(rm)
                row.set_child(h)
                self.queue_list.append(row)
        if not up:
            self.queue_list.append(Gtk.ListBoxRow(child=label(_("Danach geht es mit Ähnlichem weiter.") if self.player.autoplay else _("Nichts mehr in der Warteschlange."),
                                                              "dim", wrap=True, lines=3), activatable=False))

    def show_lyrics(self, lines):
        if self.now:
            self.now.show_lyrics(lines)
        while (c := self.lyrics_box.get_first_child()) is not None:
            self.lyrics_box.remove(c)
        self._lyric_labels = []
        if not lines:
            self.lyrics_box.append(label(_("Für diesen Titel gibt es keinen Liedtext."), "dim", wrap=True, lines=3))
            return
        for ms, text in lines:
            l = label(text or "♪", "lyrics-line", wrap=True, lines=4)
            if ms is not None:
                click = Gtk.GestureClick(); click.connect("released", lambda *_, ms=ms: self.player.seek(ms / 1000)); l.add_controller(click)
            self.lyrics_box.append(l); self._lyric_labels.append((ms, l))

    # ---------- player → window ----------
    def _player_changed(self, what):
        if what == "spectrum":
            return
        t = self.player.current
        self._syncing = True
        self.play_btn.set_icon_name("media-playback-pause-symbolic" if self.player.playing else "media-playback-start-symbolic")
        self.play_btn.set_tooltip_text(_("Pause") if self.player.playing else _("Wiedergabe"))
        self.shuffle_btn.set_active(self.player.shuffle)
        self.repeat_btn.set_active(self.player.repeat != REPEAT_OFF)
        self.repeat_btn.set_icon_name("media-playlist-repeat-song-symbolic" if self.player.repeat == REPEAT_ONE else "media-playlist-repeat-symbolic")
        self._syncing = False
        if what in ("track", "state", "queue"):
            self.lcd_title.set_text(t.title if t else "LiDio")
            self.lcd_artist.set_text(f"{t.artist} — {t.album}" if t and t.album else (t.artist if t else ""))
            self.lcd_cover.show(self.player.server.cover_url(t.cover_id, 120) if t and t.cover_id and self.player.server else None)
            self._mark_playing(); self._sync_lcd_star()
            if self.queue_btn.get_active():
                self._fill_queue()
            if what == "track" and (self.lyrics_btn.get_active() or self.top.get_visible_child_name() == "now"):
                self.app.load_lyrics()
        if what == "error" and self.player.error:
            self.toast(self.player.error)

    def _mark_playing(self):
        t = self.player.current
        for r in list(getattr(self, "_rows", [])):
            if r.get_parent() is None:
                self._rows.remove(r); continue
            if t and r.track.id == t.id:
                r.add_css_class("playing")
            else:
                r.remove_css_class("playing")

    def _tick(self):
        p = self.player
        if self.now and self.top.get_visible_child_name() == "now":
            self.now.tick()
        if p.current:
            pos, length = p.position(), p.length()
            if not getattr(self, "_dragging", False):
                self.progress.set_range(0, max(1, length)); self.progress.set_value(pos)
            self.lcd_pos.set_text(clock(pos)); self.lcd_left.set_text("-" + clock(length - pos))
            for i, (ms, l) in enumerate(getattr(self, "_lyric_labels", [])):
                nxt = self._lyric_labels[i + 1][0] if i + 1 < len(self._lyric_labels) else None
                now = ms is not None and ms <= pos * 1000 + 250 and (nxt is None or nxt > pos * 1000 + 250)
                if now and not l.has_css_class("now"):
                    l.add_css_class("now")
                    adj = self.lyrics_scroll.get_vadjustment()
                    ok, rect = l.compute_bounds(self.lyrics_box)
                    if ok:
                        adj.set_value(max(0, rect.get_y() - adj.get_page_size() / 3))
                elif not now:
                    l.remove_css_class("now")
        else:
            self.lcd_pos.set_text(""); self.lcd_left.set_text("")
        return True

    def _seek(self, scale, scroll, value):
        self.player.seek(value)
        return False
