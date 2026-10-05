"""The rest of the Android app's functions for the window (card da6a91d1): Start with mixes, genres, music on this computer,
the title context menu (like Music on the Mac), favourites, and playlists – new, add, remove, rename, delete, share."""
import random
import gi
gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Adw, Gio, GLib, Gtk, Gdk

from .covers import Cover
from .widgets import shelf
from .i18n import _


def _label(text, css=None):
    from .window import label
    return label(text, css)


class MoreMixin:
    # ---------- actions for the context menus ----------
    def _install_actions(self):
        self._ctx = {"tracks": [], "playlist": None}
        for name, cb in [("t-play", lambda: self.player.play(self.server, self._ctx["tracks"])),
                         ("t-next", lambda: self._queue(self._ctx["tracks"], next_=True)),
                         ("t-last", lambda: self._queue(self._ctx["tracks"], next_=False)),
                         ("t-fav", self._toggle_favorite), ("t-album", self._go_album), ("t-artist", self._go_artist),
                         ("t-load", lambda: self.app.offline.download(self._ctx["tracks"])),
                         ("t-unload", lambda: self.app.offline.remove(self._ctx["tracks"])),
                         ("t-remove", self._remove_from_playlist), ("lcd-fav", self._lcd_favorite),
                         ("t-share", self._share)]:
            a = Gio.SimpleAction.new(name, None); a.connect("activate", lambda a, p, cb=cb: cb()); self.add_action(a)
        add = Gio.SimpleAction.new("t-add", GLib.VariantType.new("s"))
        add.connect("activate", lambda a, p: self._add_to_playlist(p.get_string()))
        self.add_action(add)

    def _queue(self, tracks, next_):
        if not self.player.current:
            self.player.play(self.server, tracks); return
        (self.player.play_next if next_ else self.player.add)(list(tracks))
        self.toast((_("Spielt als Nächstes: ") if next_ else _("Spielt zuletzt: ")) + (tracks[0].title if len(tracks) == 1 else _("{count} Titel", count=len(tracks))))

    def track_menu(self, widget, x, y, tracks, playlist=None):
        """Right click on a title (or several): Music's context menu."""
        self._ctx = {"tracks": tracks, "playlist": playlist}
        t = tracks[0]
        off = self.app.offline
        m = Gio.Menu()
        s1 = Gio.Menu()
        s1.append(_("Als Nächstes spielen"), "win.t-next"); s1.append(_("Zuletzt spielen"), "win.t-last")
        m.append_section(None, s1)
        s2 = Gio.Menu()
        s2.append(_("Aus Lieblingstiteln entfernen") if t.favorite else _("Zu Lieblingstiteln"), "win.t-fav")
        add = Gio.Menu()
        new = Gio.Menu(); new.append(_("Neue Playlist …"), "win.t-add::"); add.append_section(None, new)
        mine = Gio.Menu()
        for p in getattr(self, "_playlists", [])[:60]:
            if not playlist or p.id != playlist.id:
                mine.append(p.name, f"win.t-add::{p.id}")
        add.append_section(None, mine)
        s2.append_submenu(_("Zur Playlist hinzufügen"), add)
        if playlist:
            s2.append(_("Aus Playlist entfernen"), "win.t-remove")
        m.append_section(None, s2)
        s3 = Gio.Menu()
        if getattr(self.server, "kind", "") == "local":
            pass
        elif all(off.has(x) for x in tracks):
            s3.append(_("Von diesem Computer entfernen"), "win.t-unload")
        else:
            s3.append(_("Auf diesem Computer laden"), "win.t-load")
        m.append_section(None, s3)
        if len(tracks) == 1:
            s4 = Gio.Menu()
            if t.album_id:
                s4.append(_("Zum Album"), "win.t-album")
            s4.append(_("Zum Interpreten"), "win.t-artist")
            if getattr(self.server, "kind", "") != "local":
                s4.append(_("Teilen – Link kopieren"), "win.t-share")
            m.append_section(None, s4)
        pop = Gtk.PopoverMenu.new_from_model(m)
        pop.set_has_arrow(False); pop.set_parent(widget)
        r = Gdk.Rectangle(); r.x, r.y, r.width, r.height = int(x), int(y), 1, 1
        pop.set_pointing_to(r)
        pop.connect("closed", lambda p: GLib.idle_add(lambda: (p.unparent(), False)[1]))
        pop.popup()

    def _share(self):
        """Card 287d55cf: the link goes to the clipboard – paste it into Signal & Co.; there it opens LiDio and plays."""
        from .share import Shared
        from .window import run
        t, acc, server = self._ctx["tracks"][0], self.account, self.server
        run(lambda: self.app.share_id(acc, server), lambda sid: (self.get_clipboard().set(Shared(sid, t.id, t.title, t.artist, t.album).text()),
                                                                 self.toast(_("Link kopiert – zum Beispiel in Signal einfügen"))), self.toast)

    def _toggle_favorite(self, tracks=None):
        tracks = tracks or self._ctx["tracks"]
        on = not tracks[0].favorite
        server = self.server

        def work():
            for t in tracks:
                server.set_favorite(t, on)

        def done(_):
            for t in tracks:
                t.favorite = on
            cur = self.player.current
            if cur and any(t.id == cur.id for t in tracks):
                cur.favorite = on
            self._refresh_rows(); self._sync_lcd_star()
            self.toast(_("Zu Lieblingstiteln hinzugefügt") if on else _("Aus Lieblingstiteln entfernt"))
        from .window import run
        run(work, done, self.toast)

    def _lcd_favorite(self):
        if self.player.current:
            self._toggle_favorite([self.player.current])

    def _sync_lcd_star(self):
        t = self.player.current
        self.lcd_star.set_visible(bool(t))
        on = bool(t and t.favorite)
        self.lcd_star.set_icon_name("starred-symbolic" if on else "non-starred-symbolic")
        self.lcd_star.set_tooltip_text(_("Aus Lieblingstiteln entfernen") if on else _("Zu Lieblingstiteln"))
        (self.lcd_star.add_css_class if on else self.lcd_star.remove_css_class)("favorite")

    def _go_album(self):
        t = self._ctx["tracks"][0]
        if t.album_id:
            self.push(self.album_page(t.album_id))

    def _go_artist(self):
        t = self._ctx["tracks"][0]
        name = t.artist.split(",")[0].split(" feat.")[0].strip()
        from .window import run

        def found(r):
            a = next((a for a in r.artists if a.name.lower() == name.lower()), (r.artists or [None])[0])
            if a:
                self.push(self.artist_page(a.id))
            else:
                self.toast(_("„{name}“ nicht als Interpret gefunden.", name=name))
        run(lambda: self.server.search(name), found, self.toast)

    # ---------- playlists ----------
    def _ask_name(self, heading, initial, button, then):
        d = Adw.AlertDialog(heading=heading)
        entry = Gtk.Entry(text=initial, activates_default=True, placeholder_text=_("Name der Playlist"))
        d.set_extra_child(entry)
        d.add_response("cancel", _("Abbrechen")); d.add_response("ok", button)
        d.set_default_response("ok"); d.set_close_response("cancel")
        d.set_response_appearance("ok", Adw.ResponseAppearance.SUGGESTED)
        entry.connect("changed", lambda e: d.set_response_enabled("ok", bool(e.get_text().strip())))
        d.set_response_enabled("ok", bool(initial.strip()))
        d.connect("response", lambda d, r: then(entry.get_text().strip()) if r == "ok" and entry.get_text().strip() else None)
        d.present(self)
        entry.grab_focus()

    def new_playlist(self, tracks=None):
        from .window import run
        tracks = tracks or []

        def make(name):
            def done(p):
                self.toast(_("Playlist „{name}“ angelegt", name=name) + (_(" mit {count} Titeln", count=len(tracks)) if tracks else ""))
                self.reload_playlists(select=p.id)
            run(lambda: self.server.create_playlist(name, tracks), done, self.toast)
        self._ask_name(_("Neue Playlist"), "", "Anlegen", make)

    def _add_to_playlist(self, playlist_id):
        tracks = self._ctx["tracks"]
        if not playlist_id:
            self.new_playlist(tracks); return
        from .window import run
        name = next((p.name for p in self._playlists if p.id == playlist_id), "Playlist")
        run(lambda: self.server.add_to_playlist(playlist_id, tracks),
            lambda _: self.toast(_("Zu „{name}“ hinzugefügt", name=name) + (_(" ({count} Titel)", count=len(tracks)) if len(tracks) > 1 else "")), self.toast)

    def _remove_from_playlist(self):
        from .window import run
        p, tracks = self._ctx["playlist"], self._ctx["tracks"]
        if not p:
            return
        run(lambda: self.server.remove_from_playlist(p.id, tracks),
            lambda _: (self.toast(_("Aus der Playlist entfernt")), self.nav.replace([self.playlist_page(p.id, p.name)])), self.toast)

    def reload_playlists(self, select=None):
        from .window import run

        def done(lists):
            self._fill_sidebar(lists)
            if select:
                for i in range(200):
                    row = self.sidebar.get_row_at_index(i)
                    if row is None:
                        break
                    if isinstance(row.key, tuple) and row.key[1] == select:
                        self.sidebar.select_row(row); break
        run(self.server.playlists, done, self.toast)

    def playlist_menu(self, p, tracks):
        """The ••• of a playlist: rename, share, delete."""
        from .window import run
        pop = Gtk.Popover(has_arrow=True)
        col = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)

        def item(text, cb, css=None):
            b = Gtk.Button(label=text, css_classes=["flat"] + ([css] if css else [])); b.get_child().set_xalign(0)
            b.connect("clicked", lambda *_: (pop.popdown(), cb()))
            col.append(b)
        item(_("Als Nächstes spielen"), lambda: self._queue(tracks, True))
        item(_("Nicht mehr anheften") if self.is_pinned(("playlist", p.id, p.name)) else _("Anheften"), lambda: self.toggle_pin(("playlist", p.id, p.name)))
        item(_("Umbenennen …"), lambda: self._ask_name(_("Playlist umbenennen"), p.name, _("Umbenennen"),
                                                    lambda n: run(lambda: self.server.rename_playlist(p.id, n),
                                                                  lambda _: (self.reload_playlists(select=p.id), self.toast("Umbenannt")), self.toast)))
        item(_("Freigeben …"), lambda: self.share_dialog(p))
        col.append(Gtk.Separator(margin_top=4, margin_bottom=4))

        def delete():
            d = Adw.AlertDialog(heading=_("„{name}“ löschen?", name=p.name), body=_("Die Playlist verschwindet vom Server – für alle, mit denen sie geteilt ist. Die Musik bleibt."))
            d.add_response("cancel", _("Abbrechen")); d.add_response("del", _("Löschen"))
            d.set_response_appearance("del", Adw.ResponseAppearance.DESTRUCTIVE); d.set_close_response("cancel")
            d.connect("response", lambda d, r: run(lambda: self.server.delete_playlist(p.id),
                                                   lambda _: (self.toast(_("Playlist gelöscht")), self.reload_playlists(), self.show_page("recent")),
                                                   self.toast) if r == "del" else None)
            d.present(self)
        item(_("Playlist löschen …"), delete, "destructive-text")
        pop.set_child(col)
        btn = Gtk.MenuButton(icon_name="view-more-horizontal-symbolic", css_classes=["flat", "circular"], valign=Gtk.Align.CENTER,
                             tooltip_text=_("Weitere Optionen"), popover=pop)
        return btn

    def share_dialog(self, p):
        """Card f3cba42b: for everyone or chosen users of this server, each with "listen" or "listen and change"."""
        from .window import run
        d = Adw.Dialog(title=_("Freigeben"), content_width=420)
        tv = Adw.ToolbarView(); tv.add_top_bar(Adw.HeaderBar())
        page = Adw.PreferencesPage(); tv.set_content(page); d.set_child(tv)
        d.present(self)

        def show(s):
            if s is None:
                page.add(Adw.PreferencesGroup(description=_("Dieser Server erlaubt keine Freigabe von Playlists."))); return
            g = Adw.PreferencesGroup(title=f"„{p.name}“", description=_("Wer auf diesem Server die Playlist sehen darf. Mehr nicht – nichts verlässt deinen Server."))
            everyone = Adw.SwitchRow(title=_("Für alle auf diesem Server"), subtitle=_("Alle dürfen zuhören"), active=s.everyone)
            g.add(everyone); page.add(g)
            levels = [("none", _("Nicht freigegeben")), ("read", _("Darf zuhören")), ("write", _("Darf zuhören und ändern"))]
            rows = {}
            if s.per_user and s.users:
                ug = Adw.PreferencesGroup(title=_("Einzelne Personen"))
                for u in s.users:
                    r = Adw.ComboRow(title=u.name, model=Gtk.StringList.new([l[1] for l in levels]))
                    r.set_selected([l[0] for l in levels].index(u.level) if u.level in [l[0] for l in levels] else 0)
                    ug.add(r); rows[u.id] = r
                page.add(ug)
            if not s.can_manage:
                g.set_description(_("Du darfst diese Playlist hören, aber nicht freigeben – das kann nur, wer sie angelegt hat."))
                everyone.set_sensitive(False)
                for r in rows.values():
                    r.set_sensitive(False)
                return
            save = Gtk.Button(label=_("Sichern"), css_classes=["suggested-action", "pill"], halign=Gtk.Align.CENTER, margin_top=12)
            sg = Adw.PreferencesGroup(); sg.add(save); page.add(sg)

            def store(*_):
                users = {uid: levels[r.get_selected()][0] for uid, r in rows.items()}
                save.set_sensitive(False)
                run(lambda: self.server.share(p.id, users, everyone.get_active()), lambda _: (d.close(), self.toast(_("Freigabe gesichert"))),
                    lambda m: (save.set_sensitive(True), self.toast(m)))
            save.connect("clicked", store)
        run(lambda: self.server.sharing(p.id), show, lambda m: (d.close(), self.toast(m)))

    # ---------- Start (Music's "Home") ----------
    def start_page(self):
        from .window import run
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        box.append(Gtk.Label(label=_("Start"), xalign=0, css_classes=["large-title"], margin_start=28, margin_top=20, margin_bottom=8))
        slots = {}
        for key, title in (("recent", _("Zuletzt gespielt")), ("mixes", _("Mixe für dich")), ("newest", _("Neu hinzugefügt")), ("frequent", _("Oft gehört"))):
            holder = Gtk.Box(orientation=Gtk.Orientation.VERTICAL); slots[key] = holder; box.append(holder)

        def heading(holder, title):
            holder.append(Gtk.Label(label=title, xalign=0, css_classes=["section-title"], margin_start=28, margin_top=16, margin_bottom=8))

        def albums(key, title):
            def show(items):
                if items:
                    heading(slots[key], title)
                    slots[key].append(shelf([self.album_tile(a) for a in items]))
            run(lambda: self.server.albums(key, 20), show)
        albums("recent", _("Zuletzt gespielt"))
        heading(slots["mixes"], _("Mixe für dich"))
        slots["mixes"].append(shelf([self._mix_tile(_("Lieblings-Mix"), _("Deine Favoriten und Ähnliches"), "favoriten", "mix-pink"),
                                     self._mix_tile(_("Neu entdecken"), _("Noch nie gehört"), "entdecken", "mix-blue")]))
        albums("newest", _("Neu hinzugefügt"))
        albums("frequent", _("Oft gehört"))
        box.append(Gtk.Box(height_request=30))
        return self._page(_("Start"), self._scroll(box))

    def _mix_tile(self, title, sub, kind, css):
        b = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4, width_request=180)
        art = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, css_classes=["mix-tile", css], width_request=180, height_request=180)
        t = Gtk.Label(label=title, xalign=0, valign=Gtk.Align.END, vexpand=True, wrap=True, css_classes=["mix-title"], margin_start=12, margin_bottom=12)
        art.append(t)
        b.append(art); b.append(_label(sub, "dim"))
        click = Gtk.GestureClick(); click.connect("released", lambda *_: self.push(self.mix_page(kind)))
        b.add_controller(click); b.set_cursor(Gdk.Cursor.new_from_name("pointer"))
        return b

    def mix_page(self, kind):
        from .window import run
        title = _("Lieblings-Mix") if kind == "favoriten" else _("Neu entdecken")
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        top = Gtk.Box(margin_start=28, margin_end=28, margin_top=20, margin_bottom=8)
        top.append(_label(title, "large-title")); top.append(Gtk.Box(hexpand=True))
        again = Gtk.Button(label=_("Neu mischen"), css_classes=["flat", "red-text"], valign=Gtk.Align.CENTER); top.append(again)
        box.append(top)
        body = Gtk.Box(orientation=Gtk.Orientation.VERTICAL); box.append(body)
        server = self.server

        def load():
            if kind == "favoriten":
                favs = server.favorites(); random.shuffle(favs)
                similar = []
                for f in favs[:3]:
                    try:
                        similar += server.similar(f, 15)
                    except Exception:      # noqa: BLE001
                        pass
                seen, out = set(), []
                for t in favs[:25] + similar:
                    if t.id not in seen:
                        seen.add(t.id); out.append(t)
                random.shuffle(out)
                return out[:50]
            return server.discover(50)

        def show(tracks):
            while (c := body.get_first_child()) is not None:
                body.remove(c)
            if not tracks:
                body.append(Adw.StatusPage(title=_("Noch nichts da"), description=_("Markiere Titel mit ★ – daraus entsteht dein Mix.") if kind == "favoriten"
                                           else _("Hier ist gerade nichts Ungehörtes.")))
                return
            body.append(self._wrap(self._play_buttons(tracks)))
            body.append(self.track_list(tracks, show_art=True))
            body.append(Gtk.Box(height_request=30))
        again.connect("clicked", lambda *_: run(load, show, self.toast))
        run(load, show, self.toast)
        return self._page(title, self._scroll(box))

    # ---------- genres ----------
    GENRE_COLORS = ["#ff375f", "#bf5af2", "#0a84ff", "#30d158", "#ff9f0a", "#64d2ff", "#ff453a", "#5e5ce6", "#ffd60a", "#ac8e68"]

    def genres_page(self):
        from .window import run
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        box.append(Gtk.Label(label=_("Genres"), xalign=0, css_classes=["large-title"], margin_start=28, margin_top=20, margin_bottom=8))
        grid = Gtk.FlowBox(selection_mode=Gtk.SelectionMode.NONE, homogeneous=True, max_children_per_line=8, min_children_per_line=2,
                           column_spacing=16, row_spacing=16, margin_start=28, margin_end=28, margin_bottom=28, valign=Gtk.Align.START)
        box.append(grid)

        def show(genres):
            for i, g in enumerate(genres):
                tile = Gtk.Box(width_request=200, height_request=110, css_classes=["genre-tile"])
                prov = Gtk.CssProvider(); prov.load_from_string(f"* {{ background: {self.GENRE_COLORS[i % len(self.GENRE_COLORS)]}; }}")
                tile.get_style_context().add_provider(prov, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION)
                l = Gtk.Label(label=self.genre_name(g.name), xalign=0, valign=Gtk.Align.END, vexpand=True, wrap=True, css_classes=["genre-title"],
                              margin_start=12, margin_bottom=10)
                tile.append(l)
                click = Gtk.GestureClick(); click.connect("released", lambda *_, g=g: self.push(self.genre_page(g)))
                tile.add_controller(click); tile.set_cursor(Gdk.Cursor.new_from_name("pointer"))
                grid.append(tile)
            if not genres:
                box.append(Adw.StatusPage(title=_("Keine Genres"), description=_("Dein Server kennt für die Musik noch keine Genres.")))
        run(self.server.genres, show, self.toast)
        return self._page(_("Genres"), self._scroll(box))

    def genre_name(self, name):
        """Genres stay English in the files; shown in the system's language (card aa4935bb)."""
        from . import genres
        return genres.local(name)

    def genre_page(self, g):
        from .window import run
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        title = self.genre_name(g.name)
        box.append(Gtk.Label(label=title, xalign=0, css_classes=["large-title"], margin_start=28, margin_top=20, margin_bottom=8))
        grid = Gtk.FlowBox(selection_mode=Gtk.SelectionMode.NONE, homogeneous=True, max_children_per_line=12, min_children_per_line=2,
                           column_spacing=22, row_spacing=22, margin_start=28, margin_end=28, margin_bottom=28, valign=Gtk.Align.START)
        box.append(grid)
        run(lambda: self.server.genre_albums(g), lambda albums: [grid.append(self.album_tile(a)) for a in albums], self.toast)
        return self._page(title, self._scroll(box))

    # ---------- music on this computer ----------
    def downloaded_page(self):
        off = self.app.offline
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        top = Gtk.Box(margin_start=28, margin_end=28, margin_top=20, margin_bottom=4)
        top.append(_label(_("Geladen"), "large-title")); top.append(Gtk.Box(hexpand=True))
        box.append(top)
        tracks = off.tracks()
        size = off.size()
        box.append(Gtk.Label(label=(_("{count} Titel auf diesem Computer · {value:.1f} GB", count=len(tracks), value=size / 1e9) if size > 1e9 else
                                    _("{count} Titel auf diesem Computer · {value:.0f} MB", count=len(tracks), value=size / 1e6)) if tracks else "",
                             xalign=0, css_classes=["dim"], margin_start=28, margin_bottom=10))
        if off.busy():
            stop = Gtk.Button(label=_("Laden stoppen ({value} offen)", value=len(off.waiting) + len(off.live)), css_classes=["flat", "red-text"], valign=Gtk.Align.CENTER)
            stop.connect("clicked", lambda *_: off.cancel())
            top.append(stop)
        if not tracks:
            box.append(Adw.StatusPage(icon_name="folder-download-symbolic", title=_("Noch nichts geladen"),
                                      description=_("Klicke bei einem Titel auf das Server-Symbol oder bei Alben und Playlists auf den Pfeil – dann spielt die Musik auch ohne Verbindung zum Server.")))
        else:
            box.append(self._wrap(self._play_buttons(tracks)))
            box.append(self.track_list(tracks, show_art=True))
            box.append(Gtk.Box(height_request=30))
        return self._page(_("Geladen"), self._scroll(box))
