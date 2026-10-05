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


def load(url, callback, fallback=None):
    """fallback(): a second address (blocking) when the first gives no picture – remembered."""
    if not url:
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
