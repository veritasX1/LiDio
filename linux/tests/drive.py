#!/usr/bin/env python3
"""Drives LiDio for Ubuntu for a test run and renders the window into PNGs (GNOME's screenshot service is closed to other apps).
Steps come as arguments: shot:<name>  wait:<s>  page:<start|recent|albums|artists|tracks|genres|favorites|downloaded>  load:first  menu:first  unload:all  album:first  play:first
search:<text>  queue  lyrics  pause  quit.  Use separate XDG_CONFIG_HOME / XDG_CACHE_HOME – never the real ones.
  python3 tests/drive.py <out folder> step …"""
import os, sys
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from gi.repository import GLib, Gtk, Graphene
from lidio.application import Application

out, steps = sys.argv[1], sys.argv[2:]
os.makedirs(out, exist_ok=True)
app = Application()
# Olaf's own LiDio may be running – the test run is a second, separate instance (not handed over to his window).
from gi.repository import Gio
app.set_flags(app.get_flags() | Gio.ApplicationFlags.NON_UNIQUE)


def shot(name):
    w = app.window
    width, height = w.get_width(), w.get_height()
    paintable = Gtk.WidgetPaintable.new(w)
    snap = Gtk.Snapshot()
    paintable.snapshot(snap, width, height)
    node = snap.to_node()
    if node:
        tex = w.get_renderer().render_texture(node, Graphene.Rect().init(0, 0, width, height))
        tex.save_to_png(os.path.join(out, name + ".png"))
        print("Bild:", name, flush=True)


def first_tile():
    # the first album tile on the visible page
    def walk(w):
        if isinstance(w, Gtk.FlowBoxChild):
            return w
        c = w.get_first_child()
        while c:
            r = walk(c)
            if r:
                return r
            c = c.get_next_sibling()
    page = app.window.nav.get_visible_page()
    return walk(page.get_child()) if page else None


def step(i=0):
    if i >= len(steps):
        return False
    s = steps[i]; arg = s.split(":", 1)[1] if ":" in s else ""
    delay = 300
    w = app.window
    if s.startswith("wait"):
        delay = int(float(arg) * 1000)
    elif s.startswith("shot"):
        shot(arg)
    elif s.startswith("page"):
        w.show_page(arg)
    elif s.startswith("album:") and arg != "first":
        def walk(x):
            if isinstance(x, Gtk.Label) and x.get_text() == arg:
                return x
            c = x.get_first_child()
            while c:
                r = walk(c)
                if r:
                    return r
                c = c.get_next_sibling()
        l = walk(w.nav.get_visible_page().get_child())
        box = l.get_parent() if l else None
        if box:
            for c in box.observe_controllers():
                if isinstance(c, Gtk.GestureClick) and c.get_button() in (0, 1):
                    c.emit("released", 1, 10, 10); break
    elif s.startswith("size:"):
        wd, ht = arg.split("x"); w.set_default_size(int(wd), int(ht))
    elif s == "dark":
        from gi.repository import Adw
        Adw.StyleManager.get_default().set_color_scheme(Adw.ColorScheme.FORCE_DARK)
    elif s == "album:first":
        t = first_tile()
        box = t.get_child() if t else None
        print("Album-Kachel:", bool(box), flush=True)
        if box:
            for c in box.observe_controllers():
                if isinstance(c, Gtk.GestureClick) and c.get_button() in (0, 1):
                    c.emit("released", 1, 10, 10); break
    elif s == "play:first":
        p = w.nav.get_visible_page()
        def find(x):
            if isinstance(x, Gtk.ListBox) and x.has_css_class("tracklist"):
                return x
            c = x.get_first_child()
            while c:
                r = find(c)
                if r:
                    return r
                c = c.get_next_sibling()
        lb = find(p.get_child())
        if lb and lb.get_row_at_index(0):
            lb.emit("row-activated", lb.get_row_at_index(0))
    elif s in ("load:first", "menu:first"):
        p = w.nav.get_visible_page()
        def find(x):
            if isinstance(x, Gtk.ListBox) and x.has_css_class("tracklist"):
                return x
            c = x.get_first_child()
            while c:
                r = find(c)
                if r:
                    return r
                c = c.get_next_sibling()
        lb = find(p.get_child()); row = lb.get_row_at_index(0) if lb else None
        if row and s == "load:first":
            app.offline.download([row.track])
        elif row:
            w._row_menu(lb, row, 200, 10, None)
    elif s.startswith("playlist:"):
        p = next((p for p in w._playlists if arg.lower() in p.name.lower()), None) or (w._playlists or [None])[0]
        if p:
            w.show_page(("playlist", p.id, p.name))
    elif s.startswith("remote:"):
        from lidio import importing as I
        w.push(w.remote_page(I.RemoteList("Deezer", arg, "")))
    elif s == "transfer":
        # the import page with the remote list's titles, compared – nothing is created
        p = w.nav.get_visible_page()
        def btn(x):
            if isinstance(x, Gtk.Button) and x.get_icon_name() == "network-server-symbolic" and x.has_css_class("download-button"):
                return x
            c = x.get_first_child()
            while c:
                r = btn(c)
                if r:
                    return r
                c = c.get_next_sibling()
        b = btn(p.get_child()); b and b.emit("clicked")
    elif s == "compare":
        p = w.nav.get_visible_page()
        def red(x):
            if isinstance(x, Gtk.Button) and x.has_css_class("red-button") and x.get_visible():
                return x
            c = x.get_first_child()
            while c:
                r = red(c)
                if r:
                    return r
                c = c.get_next_sibling()
        b = red(p.get_child()); b and b.emit("clicked")
    elif s == "now":
        w.open_now_playing()
    elif s == "classic":
        app._set("modern", False); w.apply_look()
    elif s == "modern":
        app._set("modern", True); w.apply_look()
    elif s.startswith("mini:"):
        app._set("klein", "winamp" if arg == "winamp" else "apple"); app.open_mini()
    elif s.startswith("minishot:"):
        m = app.mini
        width, height = m.get_width(), m.get_height()
        pt = Gtk.WidgetPaintable.new(m); snap = Gtk.Snapshot(); pt.snapshot(snap, width, height)
        node = snap.to_node()
        print("mini", width, height, m.get_mapped(), node, flush=True)
        if node:
            m.get_renderer().render_texture(node, Graphene.Rect().init(0, 0, width, height)).save_to_png(os.path.join(out, arg + ".png")); print("Bild:", arg, flush=True)
    elif s == "hover":
        app.mini.add_css_class("hover")
    elif s.startswith("scope:"):
        w.search_scope = arg; w._search(w.search.get_text().strip())
    elif s == "unload:all":
        app.offline.remove(app.offline.tracks())
    elif s.startswith("search"):
        w.search.set_text(arg)
    elif s == "queue":
        w.queue_btn.set_active(not w.queue_btn.get_active())
    elif s == "lyrics":
        w.lyrics_btn.set_active(not w.lyrics_btn.get_active())
    elif s == "pause":
        app.player.pause()
    elif s == "volume0":
        app.player.set_volume(0.0); w.volume.set_value(0.0)
    elif s == "quit":
        app.player.stop(); app.quit(); return False
    print("Schritt:", s, flush=True)
    GLib.timeout_add(delay, step, i + 1)
    return False


app.connect("activate", lambda *_: GLib.timeout_add(2500, step, 0))
app.run([sys.argv[0]])
