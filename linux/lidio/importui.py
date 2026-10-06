"""Pages for playlists from elsewhere: "Playlists im Netz" (Deezer, Spotify with an own key), one public playlist with its
titles – what the server has plays, the rest is marked –, and "Playlist importieren" with the comparison (like Android)."""
import re, threading, urllib.request
import gi
gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Adw, Gio, GLib, Gtk, Gdk

from . import importing as I
from .covers import Cover
from .i18n import _


def _label(text, css=None, **kw):
    from .window import label
    return label(text, css, **kw)


class ImportMixin:
    # ---------- sources ----------
    def remote_sources(self):
        out = [I.Deezer()]
        cid, secret = self.app.spotify_key()
        if cid and secret:
            out.append(I.Spotify(cid, secret))
        return out

    def remote_search_sources(self):
        return self.remote_sources()

    # ---------- Playlists im Netz ----------
    def web_lists_page(self):
        from .window import run
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        box.append(Gtk.Label(label=_("Playlists im Netz"), xalign=0, css_classes=["large-title"], margin_start=28, margin_top=20, margin_bottom=4))
        box.append(Gtk.Label(label=_("Öffentliche Playlists von Deezer") + (_(" und Spotify") if len(self.remote_sources()) > 1 else "") +
                             _(" ansehen und mit deinem Server abgleichen – geladen wird nichts."), xalign=0, wrap=True, css_classes=["dim"],
                             margin_start=28, margin_end=28, margin_bottom=10))
        bar = Gtk.Box(spacing=8, margin_start=28, margin_end=28, margin_bottom=12)
        entry = Gtk.SearchEntry(placeholder_text=_("Playlist suchen oder Link einfügen"), hexpand=True)
        bar.append(entry)
        imp = Gtk.Button(label=_("Aus Datei …"), css_classes=["flat", "red-text"]); imp.connect("clicked", lambda *_: self.choose_import_file())
        bar.append(imp)
        box.append(bar)
        results = Gtk.Box(orientation=Gtk.Orientation.VERTICAL); box.append(results)
        state = {"timer": None, "q": ""}

        def show(found):
            while (c := results.get_first_child()) is not None:
                results.remove(c)
            if not found:
                results.append(Adw.StatusPage(title=_("Keine Playlists"), description=_("Nichts zu „{value}“ gefunden.", value=state['q'])))
                return
            grid = Gtk.FlowBox(selection_mode=Gtk.SelectionMode.NONE, homogeneous=True, max_children_per_line=12, min_children_per_line=2,
                               column_spacing=22, row_spacing=22, margin_start=28, margin_end=28, margin_bottom=28, valign=Gtk.Align.START)
            for rl in found:
                grid.append(self._remote_tile(rl))
            results.append(grid)

        def search():
            state["timer"] = None
            q = entry.get_text().strip(); state["q"] = q
            link = I.parse_link(q)
            if link:
                entry.set_text(""); self.push(self.remote_page(I.RemoteList(link[0], link[1], ""))); return False
            if len(q) < 2:
                return False
            sources = self.remote_sources()

            def work():
                out = []
                for s in sources:
                    try:
                        out += [x for x in s.search(q)]
                    except Exception:      # noqa: BLE001 – one source failing leaves the other
                        pass
                return out
            run(work, show, self.toast)
            return False

        def changed(*_):
            if state["timer"]:
                GLib.source_remove(state["timer"])
            state["timer"] = GLib.timeout_add(400, search)
        entry.connect("search-changed", changed)
        GLib.idle_add(lambda: (entry.grab_focus(), False)[1])
        return self._page(_("Playlists im Netz"), self._scroll(box))

    def _remote_tile(self, rl):
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4, width_request=180)
        c = Cover(180, 8); c.show(rl.cover); box.append(c)
        for text, css in ((rl.name, "tile-title"), (f"{rl.source} · {rl.owner}" if rl.owner else rl.source, "dim")):
            l = _label(text, css); l.set_max_width_chars(1); l.set_hexpand(True); box.append(l)
        click = Gtk.GestureClick(); click.connect("released", lambda *_: self.push(self.remote_page(rl)))
        box.add_controller(click); box.set_cursor(Gdk.Cursor.new_from_name("pointer"))
        return box

    def remote_page(self, rl):
        """One public playlist: number, cover, title per line; a click plays the server's copy. The server symbol in the
        header compares the whole list and makes it a playlist on the server (card 970f3578)."""
        from .window import run, clock
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        page = self._page(rl.name or rl.source, self._scroll(box))
        server = self.server

        def load():
            if rl.source == "Mixtape":
                # A received Mixtape: its titles travel in the link (card c9b15c67).
                from .share import SharedList
                return rl, [I.Wanted(t, a) for a, t in SharedList.unpack(rl.id)]
            src = next((s for s in self.remote_sources() if s.source == rl.source), None)
            meta = rl
            if rl.source == "Deezer":
                src = src or I.Deezer()
                if not rl.name:
                    meta = src.info(rl.id)
            if not src:
                from .window import PRIVAT
                if PRIVAT:
                    # Optional extension: Spotify lists without an own key.
                    meta2, wanted = PRIVAT.spotify_embed(rl.id)
                    return (meta2 if not rl.name else rl), wanted
                raise I.ServerError(_("Für Spotify fehlt dein eigener Spotify-Schlüssel (Menü → Einstellungen)."))
            return meta, src.tracks(rl.id)

        def show(r):
            meta, wanted = r
            page.set_title(meta.name)
            head = Gtk.Box(spacing=28, margin_start=36, margin_end=36, margin_top=30, margin_bottom=10)
            cover = Cover(220, 10); cover.show(meta.cover); head.append(cover)
            info = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4, valign=Gtk.Align.END, hexpand=True)
            info.append(_label(meta.name, "album-title", wrap=True, lines=2))
            info.append(_label(f"{meta.source}" + (f" · {meta.owner}" if meta.owner else "") + _(" · {count} Titel", count=len(wanted)), "dim"))
            row = Gtk.Box(spacing=12, margin_top=14)
            status = _label("", "dim caption")
            matched = {}

            def play(shuffled=False, start=0):
                def go(results):
                    for i, res in enumerate(results):
                        matched[i] = res
                    tracks = [res.track for res in results[start:] if res.match == I.FOUND and res.track]
                    if tracks:
                        self.player.play(server, tracks, shuffled=shuffled)
                        status.set_text(_("{count} von {value} Titeln auf deinem Server", count=len(tracks), value=len(wanted) - start))
                    else:
                        self.toast(_("Keiner dieser Titel liegt auf deinem Server."))
                status.set_text(_("Sucht auf deinem Server …"))
                run(lambda: I.match(server, wanted[start:]) if not matched else [matched[i] for i in range(start, len(wanted))], go, self.toast)
            for text, sh in ((_("▶  Wiedergabe"), False), (_("⤮  Zufall"), True)):
                b = Gtk.Button(css_classes=["red-button"]); b.set_child(Gtk.Label(label=text)); b.connect("clicked", lambda *_, sh=sh: play(sh)); row.append(b)
            row.append(Gtk.Box(hexpand=True))
            send = Gtk.Button(icon_name="network-server-symbolic", css_classes=["flat", "circular", "download-button"], valign=Gtk.Align.CENTER,
                              tooltip_text=_("Als Playlist auf deinen Server übertragen"))
            send.connect("clicked", lambda *_: self.push(self.import_page(wanted, meta.name, meta.cover, None if meta.source == "Mixtape" else meta.link,
                                                                                  mixtape=meta.source == "Mixtape")))
            row.append(send)
            info.append(row); info.append(status)
            head.append(info); box.append(head)
            lst = Gtk.ListBox(css_classes=["tracklist"], selection_mode=Gtk.SelectionMode.NONE, margin_start=28, margin_end=28)
            for i, w in enumerate(wanted):
                r = Gtk.ListBoxRow(); r.index = i
                if i % 2:
                    r.add_css_class("odd")
                h = Gtk.Box(spacing=12)
                h.append(_label(str(i + 1), "track-number", xalign=1))
                c = Cover(36, 4); c.show(w.cover); h.append(c)
                col = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, hexpand=True, valign=Gtk.Align.CENTER)
                col.append(_label(w.title, "title")); col.append(_label(w.artist + (f" · {w.album}" if w.album else ""), "dim caption"))
                h.append(col)
                h.append(_label(clock(w.seconds) if w.seconds else "", "track-time", xalign=1))
                r.set_child(h); lst.append(r)

            def activated(_a, r):
                i = r.index

                def go(res):
                    matched[i] = res[0]
                    if res[0].match == I.FOUND and res[0].track:
                        self.player.play(server, [res[0].track])
                    else:
                        self.toast(_("„{value}“ liegt nicht auf deinem Server.", value=wanted[i]))
                run(lambda: [matched[i]] if i in matched else I.match(server, [wanted[i]]), go, self.toast)
            lst.connect("row-activated", activated)
            box.append(lst); box.append(Gtk.Box(height_request=30))
        run(load, show, self.toast)
        return page

    # ---------- import ----------
    def choose_import_file(self):
        d = Gtk.FileDialog(title=_("Playlist importieren"))
        f = Gtk.FileFilter(); f.set_name("Playlists (M3U, PLS, XSPF, CSV, Text)")
        for pat in ("*.m3u", "*.m3u8", "*.pls", "*.xspf", "*.csv", "*.txt"):
            f.add_pattern(pat)
        filters = Gio.ListStore.new(Gtk.FileFilter); filters.append(f); d.set_filters(filters)

        def done(dlg, res):
            try:
                file = dlg.open_finish(res)
            except GLib.Error:
                return
            ok, data, _etag = file.load_contents(None)
            name = file.get_basename() or "Playlist"
            wanted = I.read(data.decode("utf-8", "replace"), name) if ok else []
            if not wanted:
                self.toast(_("In der Datei steht keine Playlist, die LiDio lesen kann.")); return
            self.push(self.import_page(wanted, name.rsplit(".", 1)[0]))
        d.open(self, None, done)

    def import_page(self, wanted, name="", cover=None, origin=None, mixtape=False):
        """Compare with the server → found (green), unsure (orange – choose), missing (red); then one playlist of what is there,
        the missing ones written into its description (readable in every app)."""
        from .window import run
        server = self.server
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        box.append(Gtk.Label(label=_("Playlist importieren"), xalign=0, css_classes=["large-title"], margin_start=28, margin_top=20, margin_bottom=8))
        form = Adw.PreferencesGroup(margin_start=28, margin_end=28)
        name_row = Adw.EntryRow(title=_("Name der Playlist"), text=name or "")
        form.add(name_row)
        box.append(form)
        counts = _label(_("{count} Titel in der Liste", count=len(wanted)), "dim"); counts.set_margin_start(28); counts.set_margin_top(12)
        box.append(counts)
        actions = Gtk.Box(spacing=12, margin_start=28, margin_top=10, margin_bottom=10)
        go = Gtk.Button(css_classes=["red-button"]); go.set_child(Gtk.Label(label=_("{count} Titel mit dem Server abgleichen", count=len(wanted))))
        make = Gtk.Button(css_classes=["red-button"], visible=False)
        copy = Gtk.Button(label=_("Fehlende kopieren"), css_classes=["flat", "red-text"], visible=False)
        actions.append(go); actions.append(make); actions.append(copy)
        box.append(actions)
        lst = Gtk.ListBox(css_classes=["boxed-list"], selection_mode=Gtk.SelectionMode.NONE, margin_start=28, margin_end=28, margin_bottom=30, visible=False)
        box.append(lst)
        results = []
        colors = {I.FOUND: ("object-select-symbolic", "ok"), I.UNSURE: ("dialog-question-symbolic", "unsure"), I.MISSING: ("window-close-symbolic", "missing-x")}

        def fill():
            while (c := lst.get_first_child()) is not None:
                lst.remove(c)
            for i, r in enumerate(results):
                sub = {I.FOUND: f"{getattr(r.track, 'artist', '')} – {getattr(r.track, 'title', '')}",
                       I.UNSURE: _("Vielleicht: {getattr} – {getattr2} · klicken zum Wählen", getattr=getattr(r.track, 'artist', ''), getattr2=getattr(r.track, 'title', '')),
                       I.MISSING: _("Nicht auf dem Server")}[r.match]
                row = Adw.ActionRow(title=GLib.markup_escape_text(str(r.wanted)), subtitle=GLib.markup_escape_text(sub))
                icon, css = colors[r.match]
                img = Gtk.Image.new_from_icon_name(icon); img.add_css_class(css); row.add_prefix(img)
                if r.choices or r.match == I.UNSURE:
                    row.set_activatable(True); row.connect("activated", lambda w, i=i: choose(i, w))
                lst.append(row)
            f = sum(r.match == I.FOUND for r in results); u = sum(r.match == I.UNSURE for r in results); m = sum(r.match == I.MISSING for r in results)
            counts.set_markup(f"<span foreground='#34c759'>●</span> {f} gefunden   <span foreground='#ff9500'>●</span> {u} unsicher   "
                              f"<span foreground='#ff3b30'>●</span> {m} fehlen")
            make.set_child(Gtk.Label(label=_("Als Playlist anlegen ({value} Titel)", value=f + u))); make.set_visible(True); make.set_sensitive(f + u > 0)
            copy.set_visible(m > 0); lst.set_visible(bool(results))

        def choose(i, widget):
            r = results[i]
            pop = Gtk.Popover(); col = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
            for t in r.choices:
                b = Gtk.Button(label=f"{t.artist} – {t.title}" + (f" · {t.album}" if t.album else ""), css_classes=["flat"]); b.get_child().set_xalign(0)
                b.connect("clicked", lambda *_, t=t: (pop.popdown(), results.__setitem__(i, I.Result(r.wanted, I.FOUND, t, r.choices)), fill()))
                col.append(b)
            no = Gtk.Button(label=_("Weglassen"), css_classes=["flat", "destructive-text"]); no.get_child().set_xalign(0)
            no.connect("clicked", lambda *_: (pop.popdown(), results.__setitem__(i, I.Result(r.wanted, I.MISSING, None, r.choices)), fill()))
            col.append(no)
            pop.set_child(col); pop.set_parent(widget)
            pop.connect("closed", lambda p: GLib.idle_add(lambda: (p.unparent(), False)[1]))
            pop.popup()

        def compare(*_a):
            go.set_sensitive(False)

            def progress(i):
                GLib.idle_add(lambda: (go.get_child().set_text(_("Abgleich … {value} von {count}", value=i + 1, count=len(wanted))), False)[1])

            def done(found):
                results[:] = found; go.set_visible(False); fill()
            run(lambda: I.match(server, wanted, progress), done, lambda m: (go.set_sensitive(True), self.toast(m)))
        go.connect("clicked", compare)
        copy.connect("clicked", lambda *_a: (self.get_clipboard().set(I.missing_text(results)), self.toast(_("Fehlende Titel kopiert"))))

        def create(*_a):
            tracks = [r.track for r in results if r.match != I.MISSING and r.track]
            missing = [f"{i + 1} · {r.wanted}" for i, r in enumerate(results) if r.match == I.MISSING]
            title = name_row.get_text().strip() or "Importiert"
            make.set_sensitive(False); make.get_child().set_text(_("Wird angelegt …"))

            def work():
                made = server.create_playlist(title, tracks)
                if mixtape and hasattr(server, "mark_mixtape"):
                    server.mark_mixtape(made.id)      # a received Mixtape stays one (card c9b15c67)
                if missing or cover or origin:
                    # Emby: only once it has all titles, or it writes the playlist back empty.
                    if server.wait_filled(made.id, len(tracks)):
                        if missing:
                            server.note_missing(made.id, missing)
                        if cover:
                            try:
                                with urllib.request.urlopen(cover, timeout=15) as r:
                                    server.set_playlist_cover(made.id, r.read())
                            except Exception:      # noqa: BLE001 – the picture is a bonus
                                pass
                return made

            def done(made):
                self.toast(_("„{name}“ angelegt – {count} Titel", name=made.name, count=len(tracks)) + (_(", {count} fehlen", count=len(missing)) if missing else ""))
                self.reload_playlists(select=made.id)
                self.nav.replace([self.playlist_page(made.id, made.name)])
            run(work, done, lambda m: (make.set_sensitive(True), self.toast(m)))
        make.connect("clicked", create)
        return self._page(_("Importieren"), self._scroll(box))
