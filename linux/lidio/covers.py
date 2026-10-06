"""Covers: memory, then ~/.cache/lidio/cover, then the server – loaded in a few threads, handed to the widget on the main loop."""
import hashlib, os, re, threading, urllib.request
from concurrent.futures import ThreadPoolExecutor
import gi
gi.require_version("Gdk", "4.0")
gi.require_version("Gtk", "4.0")
from gi.repository import Gdk, GLib, Gtk

CACHE = os.path.join(os.environ.get("XDG_CACHE_HOME") or os.path.expanduser("~/.cache"), "lidio", "cover")
_pool = ThreadPoolExecutor(6)
_memory, _lock = {}, threading.Lock()


def _key(url):
    # logins change, the picture doesn't: leave tokens and salts out of the key
    return hashlib.md5(re.sub(r"([?&])(api_key|ApiKey|t|s|u)=[^&]*", r"\1", url).encode()).hexdigest()


_fallbacks = {}


def mixtape_png(name, size=600, dark=False):
    """A Mixtape without an own cover (Olaf 06.10.2026, design A "Klassisch schwarz", the same drawing as Android's MixtapeArt):
    a compact cassette after real proportions, the name handwritten (Caveat, OFL) on the label. Dark mode: dark ground, a faint
    rim, a dimmed label."""
    import math
    import cairo
    gi.require_version("PangoCairo", "1.0")
    from gi.repository import Pango, PangoCairo
    # The default font map is per thread (covers are drawn in a pool) – the handwriting is added to each thread's map once.
    if not getattr(_font_added, "done", False):
        _font_added.done = True
        try:
            PangoCairo.FontMap.get_default().add_font_file(os.path.join(os.path.dirname(os.path.dirname(__file__)), "data", "fonts", "Caveat.ttf"))
        except Exception:      # older Pango: the font comes from install.sh (~/.local/share/fonts)
            pass
    surf = cairo.ImageSurface(cairo.FORMAT_ARGB32, size, size)
    cr = cairo.Context(surf)
    u = size / 400

    def rgb(h, a=1.0):
        cr.set_source_rgba(int(h[1:3], 16) / 255, int(h[3:5], 16) / 255, int(h[5:7], 16) / 255, a)

    def rrect(x, y, w, h, r):
        cr.new_sub_path(); cr.arc(x + w - r, y + r, r, -math.pi / 2, 0); cr.arc(x + w - r, y + h - r, r, 0, math.pi / 2)
        cr.arc(x + r, y + h - r, r, math.pi / 2, math.pi); cr.arc(x + r, y + r, r, math.pi, 1.5 * math.pi); cr.close_path()

    g = cairo.LinearGradient(0, 0, size, size)
    for stop, h in ((0, "#2A2724" if dark else "#ECE7DC"), (1, "#161514" if dark else "#D9D2C3")):
        g.add_color_stop_rgb(stop, int(h[1:3], 16) / 255, int(h[3:5], 16) / 255, int(h[5:7], 16) / 255)
    cr.set_source(g); cr.paint()
    cr.scale(u, u); cr.translate(0, 52); cr.translate(200, 150); cr.rotate(math.radians(-4)); cr.translate(-200, -150)
    rrect(44, 48, 320, 205, 14); cr.set_source_rgba(0, 0, 0, 0.5 if dark else 0.22); cr.fill()
    rrect(40, 42, 320, 205, 14); rgb("#232325" if dark else "#1C1C1E"); cr.fill_preserve()
    if dark:
        cr.set_source_rgba(1, 1, 1, 0.12); cr.set_line_width(1.5); cr.stroke()
    else:
        cr.new_path()
    for x, y in ((52, 54), (348, 54), (52, 235), (348, 235), (200, 204)):
        cr.arc(x, y, 3.2, 0, 2 * math.pi); rgb("#55555A"); cr.fill()
    # label around the window (measured on Olaf's photo of a real tape): name on top, orange stripe, lines below the window
    rrect(56, 54, 288, 140, 6); rgb("#DCD3BF" if dark else "#F3ECDC"); cr.fill()
    cr.rectangle(56, 100, 288, 6); rgb("#E8501F"); cr.fill()
    for i in range(3):
        cr.rectangle(66, 171 + i * 8, 268, 1.5); rgb("#2A2A2A", 0.55); cr.fill()
    cr.save(); cr.translate(200, 80); cr.rotate(math.radians(-2)); cr.translate(-200, -80)
    layout = PangoCairo.create_layout(cr)
    fd = Pango.FontDescription.from_string(f"Caveat Semi-Bold {30 if len(name) > 16 else 38}px")
    layout.set_font_description(fd); layout.set_text(name, -1); layout.set_width(int(264 * Pango.SCALE))
    layout.set_alignment(Pango.Alignment.CENTER); layout.set_ellipsize(Pango.EllipsizeMode.END)
    _w, h = layout.get_pixel_size()
    cr.move_to(68, 96 - h); rgb("#1D2A6B"); PangoCairo.show_layout(cr, layout); cr.restore()
    # window and reels at half height, 136 units apart (42 mm of 100)
    rrect(98, 117, 204, 46, 23); rgb("#141416"); cr.fill_preserve(); cr.set_source_rgb(0, 0, 0); cr.set_line_width(1.5); cr.stroke()
    rrect(170, 128, 60, 24, 3); rgb("#2C2C30"); cr.fill()
    for x in (132, 268):
        cr.arc(x, 140, 16, 0, 2 * math.pi); rgb("#D6C8A8"); cr.fill_preserve(); rgb("#8A7A58"); cr.set_line_width(1.5); cr.stroke()
        cr.arc(x, 140, 6.5, 0, 2 * math.pi); rgb("#3A3326"); cr.fill()
        for k in range(6):
            a = math.radians(k * 60); cr.arc(x + 10 * math.cos(a), 140 + 10 * math.sin(a), 1.5, 0, 2 * math.pi); cr.fill()
    # head opening, drive holes at a third and two thirds, the pad between
    cr.move_to(104, 247); cr.line_to(118, 212); cr.line_to(282, 212); cr.line_to(296, 247); cr.close_path()
    cr.set_source_rgba(1, 1, 1, 0.06); cr.fill_preserve(); cr.set_source_rgba(1, 1, 1, 0.14); cr.set_line_width(1.2); cr.stroke()
    for x, y, r in ((150, 232, 6.5), (250, 232, 6.5), (126, 236, 3.5), (274, 236, 3.5)):
        cr.arc(x, y, r, 0, 2 * math.pi); cr.set_source_rgba(0, 0, 0, 0.85); cr.fill()
    rrect(187, 224, 26, 14, 2); cr.set_source_rgba(0, 0, 0, 0.6); cr.fill()
    # guide rollers near the lower corners
    for x in (74, 326):
        cr.arc(x, 226, 10, 0, 2 * math.pi); cr.set_source_rgba(0, 0, 0, 0.8); cr.fill()
        cr.arc(x, 226, 6, 0, 2 * math.pi); rgb("#9A9AA0"); cr.fill()
        cr.arc(x, 226, 2.5, 0, 2 * math.pi); rgb("#3A3A3E"); cr.fill()
    import io
    out = io.BytesIO(); surf.write_to_png(out)
    return out.getvalue()


import threading as _threading
_font_added = _threading.local()


def load(url, callback, fallback=None):
    """fallback(): a second address (blocking) when the first gives no picture – remembered."""
    if not url:
        return
    if url.startswith("mixtape:"):
        # A Mixtape without an own cover: the drawn cassette (light/dark like the window).
        from gi.repository import Adw
        dark = Adw.StyleManager.get_default().get_dark()
        k = f"{url}|{dark}"
        with _lock:
            tex = _memory.get(k)
        if tex:
            callback(tex); return

        def draw():
            t = Gdk.Texture.new_from_bytes(GLib.Bytes.new(mixtape_png(url[len("mixtape:"):], 600, dark)))
            with _lock:
                _memory[k] = t
            GLib.idle_add(lambda: (callback(t), False)[1])
        _pool.submit(draw)
        return
    if url in _fallbacks:
        url = _fallbacks[url]
        if not url:
            return
    k = _key(url)
    with _lock:
        tex = _memory.get(k)
    if tex:
        callback(tex); return

    def work():
        path = os.path.join(CACHE, k)
        data = None
        if os.path.exists(path):
            with open(path, "rb") as f:
                data = f.read()
        else:
            try:
                with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "LiDio/0.1 (https://lisoft.goip.de/lidio)"}), timeout=15) as r:
                    data = r.read()
                os.makedirs(CACHE, exist_ok=True)
                with open(path + ".neu", "wb") as f:
                    f.write(data)
                os.replace(path + ".neu", path)
            except Exception:
                if fallback:
                    try:
                        second = fallback()
                    except Exception:
                        second = None
                    _fallbacks[url] = second
                    if second:
                        load(second, callback)
                return
        try:
            tex = Gdk.Texture.new_from_bytes(GLib.Bytes.new(data))
        except GLib.Error:
            return
        with _lock:
            if len(_memory) > 600:
                _memory.clear()
            _memory[k] = tex
        GLib.idle_add(lambda: (callback(tex), False)[1])
    _pool.submit(work)


class Cover(Gtk.Overlay):
    """A cover with rounded corners; until it arrives the grey square with a note (like Apple's placeholder)."""

    def __init__(self, size, radius=6):
        super().__init__()
        self.set_size_request(size, size)
        self.add_css_class("cover")
        self.set_overflow(Gtk.Overflow.HIDDEN)
        self.set_halign(Gtk.Align.START); self.set_valign(Gtk.Align.START)
        self.placeholder = Gtk.Image.new_from_icon_name("folder-music-symbolic")
        self.placeholder.set_pixel_size(max(16, size // 3)); self.placeholder.add_css_class("cover-placeholder")
        self.placeholder.set_size_request(size, size)
        self.set_child(self.placeholder)
        self.picture = Gtk.Picture(content_fit=Gtk.ContentFit.COVER, can_shrink=True)
        self.picture.set_size_request(size, size)
        self.add_overlay(self.picture)
        self.url = None
        prov = Gtk.CssProvider(); prov.load_from_string(f"* {{ border-radius: {radius}px; }}")
        self.get_style_context().add_provider(prov, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION)

    def show(self, url, fallback=None):
        self.url = url
        self.picture.set_paintable(None)
        if url:
            load(url, lambda tex: self.picture.set_paintable(tex) if self.url == url else None, fallback)
