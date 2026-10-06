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

        def done(_a):
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
            lambda _a: self.toast(_("Zu „{name}“ hinzugefügt", name=name) + (_(" ({count} Titel)", count=len(tracks)) if len(tracks) > 1 else "")), self.toast)

    def _remove_from_playlist(self):
        from .window import run
        p, tracks = self._ctx["playlist"], self._ctx["tracks"]
        if not p:
            return
        run(lambda: self.server.remove_from_playlist(p.id, tracks),
            lambda _a: (self.toast(_("Aus der Playlist entfernt")), self.nav.replace([self.playlist_page(p.id, p.name)])), self.toast)

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

    def mixtapes_page(self):
        """Card c9b15c67: the playlists led as Mixtapes – shared or just for oneself; "+" makes a new one."""
        from .window import run
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        top = Gtk.Box(margin_start=28, margin_end=28, margin_top=20, margin_bottom=8)
        top.append(_label(_("Mixtapes"), "large-title")); top.append(Gtk.Box(hexpand=True))
        new = Gtk.Button(icon_name="list-add-symbolic", css_classes=["flat", "circular"], valign=Gtk.Align.CENTER, tooltip_text=_("Neues Mixtape"))
        new.connect("clicked", lambda *_a: self._ask_name(_("Neues Mixtape"), "", _("Anlegen"), lambda n: run(
            lambda: (lambda made: (self.server.mark_mixtape(made.id), made)[1])(self.server.create_playlist(n, [])),
            lambda made: (self.toast(_("Mixtape „{name}“ angelegt – Titel per Rechtsklick → Zur Playlist hinzufügen.", name=n)),
                          self.reload_playlists(select=made.id), self.show_page("mixtapes")), self.toast)))
        top.append(new)
        box.append(top)
        lst = Gtk.ListBox(css_classes=["tracklist"], selection_mode=Gtk.SelectionMode.NONE, margin_start=28, margin_end=28)
        box.append(lst)
        mixtapes = [p for p in self._playlists if getattr(p, "mixtape", False)]
        if not mixtapes:
            box.append(Gtk.Label(label=_("Noch keine Mixtapes. „+“ legt eins an, oder eine Playlist über ••• → „Als Mixtape führen“."),
                                 wrap=True, xalign=0, css_classes=["dim"], margin_start=28, margin_top=8))
        for p in mixtapes:
            row = Gtk.ListBoxRow(); row.p = p
            h = Gtk.Box(spacing=12, margin_top=6, margin_bottom=6)
            c = Cover(48, 6); c.show("mixtape:" + p.name if not getattr(p, "own_cover", False) else self.server.cover_url(p.cover_id, 120) if p.cover_id else None); h.append(c)
            col = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, valign=Gtk.Align.CENTER, hexpand=True)
            col.append(_label(p.name, "title"))
            sub = Gtk.Box(spacing=4)
            sub.append(Gtk.Image.new_from_icon_name("lidio-cassette-symbolic")); sub.append(_label(_("Mixtape"), "dim caption"))
            col.append(sub); h.append(col)
            row.set_child(h); lst.append(row)
        lst.set_activate_on_single_click(True)
        lst.connect("row-activated", lambda l, r: self.push(self.playlist_page(r.p.id, r.p.name)))
        return self._page(_("Mixtapes"), self._scroll(box))

    def add_music_dialog(self, p):
        """Card eea4ee65 (desktop too – Olaf: „am Desktop sehe ich nicht, wie ich Musik dem Mixtape hinzufüge“): search the own
        library, without a word the favourites and what came in lately; the button adds at once and turns into a tick."""
        from .window import run, clock
        dlg = Adw.Dialog(title=_("Musik hinzufügen"), content_width=560, content_height=640)
        tv = Adw.ToolbarView(); tv.add_top_bar(Adw.HeaderBar())
        col = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=10, margin_start=18, margin_end=18, margin_bottom=14)
        col.append(_label(_("zu „{name}“", name=p.name), "dim"))
        entry = Gtk.SearchEntry(placeholder_text=_("Interpreten, Alben, Titel")); col.append(entry)
        lst = Gtk.ListBox(css_classes=["tracklist"], selection_mode=Gtk.SelectionMode.NONE)
        sc = Gtk.ScrolledWindow(vexpand=True, hscrollbar_policy=Gtk.PolicyType.NEVER); sc.set_child(lst); col.append(sc)
        tv.set_content(col); dlg.set_child(tv)
        icon = "lidio-cassette-add-symbolic" if getattr(p, "mixtape", False) else "list-add-symbolic"
        added = set()
        from .importing import without_duplicates

        def fill(tracks):
            while (r := lst.get_first_child()) is not None:
                lst.remove(r)
            for t in without_duplicates(tracks)[:80]:
                h = Gtk.Box(spacing=10, margin_top=4, margin_bottom=4)
                c = Cover(40, 4); c.show(self.server.cover_url(t.cover_id, 80) if t.cover_id else None); h.append(c)
                info = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, hexpand=True, valign=Gtk.Align.CENTER)
                info.append(_label(t.title, "title")); info.append(_label(t.artist, "dim caption")); h.append(info)
                b = Gtk.Button(icon_name="object-select-symbolic" if t.id in added else icon, css_classes=["flat", "circular"],
                               valign=Gtk.Align.CENTER, sensitive=t.id not in added, tooltip_text=_("Hinzufügen"))

                def add(w, t=t):
                    w.set_sensitive(False); added.add(t.id)
                    run(lambda: self.server.add_to_playlist(p.id, [t]),
                        lambda _r: w.set_icon_name("object-select-symbolic"),
                        lambda m: (added.discard(t.id), w.set_sensitive(True), self.toast(_("„{title}“ ließ sich nicht hinzufügen.", title=t.title))))
                b.connect("clicked", add); h.append(b)
                lst.append(Gtk.ListBoxRow(child=h, activatable=False))

        def start():
            def work():
                out = []
                for f in (lambda: self.server.favorites(), lambda: self.server.tracks(40)):
                    try:
                        out += f()
                    except Exception:      # noqa: BLE001
                        pass
                return out
            run(work, fill, self.toast)
        timer = {"id": None}

        def changed(e):
            if timer["id"]:
                GLib.source_remove(timer["id"])
            q = e.get_text().strip()
            timer["id"] = GLib.timeout_add(300, lambda: (timer.update(id=None), run(lambda: self.server.search(q).tracks, fill, self.toast) if q else start(), False)[2])
        entry.connect("search-changed", changed)
        dlg.connect("closed", lambda *_a: self.push(self.playlist_page(p.id, p.name)) if added else None)
        start()
        dlg.present(self)

    def share_mixtape(self, p, tracks):
        """Card c9b15c67: the playlist as a link – copied, ready to paste into Signal & Co. Everyone on this server may add to it
        (Olaf: „alle Nutzer sollen dem Mixtape beitragen können“), and it is led as a Mixtape."""
        from .window import run
        from .share import SharedList

        def work():
            try:
                s = self.server.sharing(p.id)
                if s:
                    self.server.share(p.id, {u.id: "write" for u in s.users}, True)
            except Exception:      # noqa: BLE001 – the link works without it
                pass
            if hasattr(self.server, "mark_mixtape"):
                self.server.mark_mixtape(p.id)
            return SharedList(self.app.share_id(self.account, self.server), p.id, p.name, [(t.artist, t.title) for t in tracks]).text()

        run(work, lambda text: (self.get_clipboard().set(text), self.toast(_("Mixtape-Link kopiert – z. B. in Signal einfügen.")),
                                self.reload_playlists(select=p.id)), self.toast)

    def cover_dialog(self, p):
        """Card 5d1ab4c8: an own cover from a picture – the photo always fills the square (never bars); drag moves it, the wheel
        or two fingers zoom; "Übernehmen" cuts exactly that square at 1000 × 1000. It wins over every automatic picture."""
        from .window import run
        from gi.repository import GdkPixbuf
        f = Gtk.FileFilter(); f.set_name(_("Bilder")); f.add_mime_type("image/*")
        filters = Gio.ListStore.new(Gtk.FileFilter); filters.append(f)

        def chosen(d, res):
            try:
                path = d.open_finish(res).get_path()
                pix = GdkPixbuf.Pixbuf.new_from_file(path)
                pix = pix.apply_embedded_orientation() or pix
            except Exception:      # noqa: BLE001
                return
            side = 420
            st = {"scale": max(side / pix.get_width(), side / pix.get_height()), "x": 0.0, "y": 0.0}
            st["x"] = (side - pix.get_width() * st["scale"]) / 2; st["y"] = (side - pix.get_height() * st["scale"]) / 2

            def clamp():
                low = max(side / pix.get_width(), side / pix.get_height())
                st["scale"] = min(max(st["scale"], low), low * 6)
                w, h = pix.get_width() * st["scale"], pix.get_height() * st["scale"]
                st["x"] = min(0, max(side - w, st["x"])); st["y"] = min(0, max(side - h, st["y"]))

            area = Gtk.DrawingArea(content_width=side, content_height=side, halign=Gtk.Align.CENTER, css_classes=["crop-area"])

            def draw(a, cr, w, h):
                cr.save(); cr.translate(st["x"], st["y"]); cr.scale(st["scale"], st["scale"])
                Gdk.cairo_set_source_pixbuf(cr, pix, 0, 0); cr.paint(); cr.restore()
            area.set_draw_func(draw)
            drag = Gtk.GestureDrag(); start = {}
            drag.connect("drag-begin", lambda g, x, y: start.update(x=st["x"], y=st["y"]))
            drag.connect("drag-update", lambda g, dx, dy: (st.update(x=start["x"] + dx, y=start["y"] + dy), clamp(), area.queue_draw()))
            area.add_controller(drag)

            def zoom_at(factor, cx, cy):
                old = st["scale"]; st["scale"] *= factor; clamp()
                k = st["scale"] / old
                st["x"] = cx - (cx - st["x"]) * k; st["y"] = cy - (cy - st["y"]) * k; clamp(); area.queue_draw()
            scroll = Gtk.EventControllerScroll(flags=Gtk.EventControllerScrollFlags.VERTICAL)
            scroll.connect("scroll", lambda c, dx, dy: (zoom_at(0.9 if dy > 0 else 1.1, side / 2, side / 2), True)[1])
            area.add_controller(scroll)
            pinch = Gtk.GestureZoom(); last = {"s": 1.0}
            pinch.connect("begin", lambda *_a: last.update(s=1.0))
            pinch.connect("scale-changed", lambda g, sc: (zoom_at(sc / last["s"], side / 2, side / 2), last.update(s=sc)))
            area.add_controller(pinch)

            dlg = Adw.Dialog(title=_("Cover"), content_width=500)
            tv = Adw.ToolbarView(); tv.add_top_bar(Adw.HeaderBar())
            col = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12, margin_top=8, margin_bottom=20, margin_start=24, margin_end=24)
            col.append(_label(p.name, "title"))
            col.append(Gtk.Label(label=_("Ziehen verschiebt, Mausrad oder zwei Finger zoomen – das Quadrat wird das Cover."), wrap=True, css_classes=["dim"]))
            col.append(area)
            ok = Gtk.Button(label=_("Übernehmen"), css_classes=["suggested-action", "pill"], halign=Gtk.Align.CENTER)
            col.append(ok)
            tv.set_content(col); dlg.set_child(tv)

            def apply(*_a):
                ok.set_sensitive(False)
                left, top = -st["x"] / st["scale"], -st["y"] / st["scale"]
                size = side / st["scale"]
                sub = pix.new_subpixbuf(int(left), int(top), int(min(size, pix.get_width() - left)), int(min(size, pix.get_height() - top)))
                jpeg = sub.scale_simple(1000, 1000, GdkPixbuf.InterpType.HYPER).save_to_bufferv("jpeg", ["quality"], ["90"])[1]
                run(lambda: self.server.set_playlist_cover(p.id, jpeg, own=True),
                    lambda _r: (dlg.close(), self.toast(_("Cover von „{name}“ gesetzt.", name=p.name)), self.reload_playlists(select=p.id)),
                    lambda m: (ok.set_sensitive(True), self.toast(m)))
            ok.connect("clicked", apply)
            dlg.present(self)
        Gtk.FileDialog(title=_("Cover aus Bild"), filters=filters).open(self, None, chosen)

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
        if hasattr(self.server, "mark_mixtape"):
            # Card c9b15c67 / 5d1ab4c8: Mixtapes and an own cover.
            item(_("Als Mixtape teilen (Link kopieren)"), lambda: self.share_mixtape(p, tracks))
            item(_("Kein Mixtape mehr") if getattr(p, "mixtape", False) else _("Als Mixtape führen"),
                 lambda: run(lambda: self.server.mark_mixtape(p.id, not getattr(p, "mixtape", False)),
                             lambda _r: (self.reload_playlists(select=p.id), self.toast(_("Erledigt"))), self.toast))
            item(_("Cover aus Bild …"), lambda: self.cover_dialog(p))
        col.append(Gtk.Separator(margin_top=4, margin_bottom=4))

        def delete():
            d = Adw.AlertDialog(heading=_("„{name}“ löschen?", name=p.name), body=_("Die Playlist verschwindet vom Server – für alle, mit denen sie geteilt ist. Die Musik bleibt."))
            d.add_response("cancel", _("Abbrechen")); d.add_response("del", _("Löschen"))
            d.set_response_appearance("del", Adw.ResponseAppearance.DESTRUCTIVE); d.set_close_response("cancel")
            d.connect("response", lambda d, r: run(lambda: self.server.delete_playlist(p.id),
                                                   lambda _a: (self.toast(_("Playlist gelöscht")), self.reload_playlists(), self.show_page("recent")),
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

            def store(*_a):
                users = {uid: levels[r.get_selected()][0] for uid, r in rows.items()}
                save.set_sensitive(False)
                run(lambda: self.server.share(p.id, users, everyone.get_active()), lambda _a: (d.close(), self.toast(_("Freigabe gesichert"))),
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

    def new_page(self):
        """Card bb728258: "Neu" like Music on macOS 26 – wide picture cards on top (eyebrow, title, artist), "Neueste Titel" as a grid
        of rows in columns, then what came into the library lately. From the own server: newest by release year, newest added."""
        from .window import run, clock
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        box.append(Gtk.Label(label=_("Neu"), xalign=0, css_classes=["large-title"], margin_start=28, margin_top=20, margin_bottom=8))
        hero, songs, added = (Gtk.Box(orientation=Gtk.Orientation.VERTICAL) for _i in range(3))
        for b in (hero, songs, added):
            box.append(b)

        def heading(holder, title):
            holder.append(Gtk.Label(label=title, xalign=0, css_classes=["section-title"], margin_start=28, margin_top=16, margin_bottom=8))

        def card(album):
            c = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2, width_request=340, css_classes=["hero-card"])
            c.append(_label(_("NEUES ALBUM") if album.year else _("NEU"), "eyebrow"))
            c.append(_label(album.title, "hero-title")); c.append(_label(album.artist, "dim"))
            art = Cover(340, 12); art.set_size_request(340, 210); art.picture.set_size_request(340, 210); art.placeholder.set_size_request(340, 210)
            art.set_margin_top(6)
            server = self.server
            art.show(server.cover_url(album.cover_id or album.id, 700), fallback=lambda: server.album_cover(album.id, 700))
            c.append(art)
            click = Gtk.GestureClick(); click.connect("released", lambda *_a: self.push(self.album_page(album.id)))
            c.add_controller(click); c.set_cursor(Gdk.Cursor.new_from_name("pointer"))
            return c

        def show_hero(albums):
            if not albums:
                return
            row = Gtk.Box(spacing=18, margin_start=28, margin_end=28)
            for a in albums[:8]:
                row.append(card(a))
            sc = Gtk.ScrolledWindow(vscrollbar_policy=Gtk.PolicyType.NEVER, hscrollbar_policy=Gtk.PolicyType.AUTOMATIC, min_content_height=290)
            sc.set_child(row); hero.append(sc)
            # "Neueste Titel": the first titles of the newest albums, four rows per column like Music's grid.
            run(lambda: [t for a in albums[:6] for t in self.server.album(a.id)[1][:3]][:16], show_songs)

        def song_row(t, tracks):
            r = Gtk.Box(spacing=10, width_request=320, hexpand=False, css_classes=["grid-song"])
            c = Cover(40, 4); c.show(self.server.cover_url(t.cover_id, 80) if t.cover_id else None); r.append(c)
            col = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, hexpand=True, valign=Gtk.Align.CENTER)
            for text, css in ((t.title, "title"), (t.artist, "dim caption")):
                l = _label(text, css); l.set_ellipsize(3); l.set_max_width_chars(34); col.append(l)
            r.append(col)
            click = Gtk.GestureClick(); click.connect("released", lambda *_a: self.player.play(self.server, tracks, tracks.index(t)))
            r.add_controller(click); r.set_cursor(Gdk.Cursor.new_from_name("pointer"))
            return r

        def show_songs(tracks):
            if not tracks:
                return
            heading(songs, _("Neueste Titel"))
            grid = Gtk.Grid(column_spacing=24, row_spacing=6, margin_start=28, margin_end=28)
            for i, t in enumerate(tracks):
                grid.attach(song_row(t, tracks), i // 4, i % 4, 1, 1)
            sc = Gtk.ScrolledWindow(vscrollbar_policy=Gtk.PolicyType.NEVER, hscrollbar_policy=Gtk.PolicyType.AUTOMATIC, min_content_height=210)
            sc.set_child(grid); songs.append(sc)

        def show_added(albums):
            if albums:
                heading(added, _("Neu hinzugefügt"))
                added.append(shelf([self.album_tile(a) for a in albums]))
        run(lambda: self.server.albums("year", 12), show_hero)
        run(lambda: self.server.albums("newest", 20), show_added)
        return self._page(_("Neu"), self._scroll(box))

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
