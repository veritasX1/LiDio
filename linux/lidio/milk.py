"""Milkdrop for Ubuntu (card ef3a3dfb): projectM 4 (LGPL-2.1, built by native/build-milk.sh with a small patch) in a window of
its own, fed with what plays. The presets are projectM's public-domain "cream of the crop" (a selection, like on Android).
Click: next preset · double click or F: full screen · ←/→: presets · Esc: close."""
import ctypes, os, random, threading
import gi
gi.require_version("Gtk", "4.0")
from gi.repository import GLib, Gtk, Gdk
from .i18n import _

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PRESETS = os.path.join(HERE, "data", "milkdrop")


def _library():
    for path in (os.path.join(HERE, "lib", "libprojectM-4.so.4"), "libprojectM-4.so.4"):
        try:
            lib = ctypes.CDLL(path)
        except OSError:
            continue
        lib.projectm_create.restype = ctypes.c_void_p
        for name, args in (("projectm_set_window_size", [ctypes.c_void_p, ctypes.c_size_t, ctypes.c_size_t]),
                           ("projectm_load_preset_file", [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_bool]),
                           ("projectm_pcm_add_float", [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_uint, ctypes.c_int]),
                           ("projectm_opengl_render_frame", [ctypes.c_void_p]), ("projectm_destroy", [ctypes.c_void_p]),
                           ("projectm_set_mesh_size", [ctypes.c_void_p, ctypes.c_size_t, ctypes.c_size_t]),
                           ("projectm_set_fps", [ctypes.c_void_p, ctypes.c_int32]), ("projectm_set_aspect_correction", [ctypes.c_void_p, ctypes.c_bool]),
                           ("projectm_set_preset_duration", [ctypes.c_void_p, ctypes.c_double]),
                           ("projectm_set_soft_cut_duration", [ctypes.c_void_p, ctypes.c_double])):
            getattr(lib, name).argtypes = args
        return lib
    return None


LIB = _library()


def available():
    return LIB is not None and os.path.isdir(PRESETS)


def presets():
    out = []
    for root, _, files in os.walk(PRESETS):
        out += [os.path.join(root, f) for f in files if f.endswith(".milk")]
    return sorted(out)


class MilkWindow(Gtk.Window):
    def __init__(self, app):
        super().__init__(application=app, title=_("LiDio – Milkdrop"), default_width=800, default_height=500)
        self.app, self.player = app, app.player
        self.list = presets()
        self.index = random.randrange(len(self.list)) if self.list else 0
        self.handle, self._want, self._lock, self._pcm = None, True, threading.Lock(), []
        self.area = Gtk.GLArea(auto_render=False, has_depth_buffer=False)
        self.area.set_allowed_apis(Gdk.GLAPI.GL)
        self.area.set_required_version(3, 3)
        self.area.connect("realize", self._realize); self.area.connect("unrealize", self._unrealize)
        self.area.connect("render", self._render); self.area.connect("resize", self._resize)
        overlay = Gtk.Overlay(); overlay.set_child(self.area)
        self.name = Gtk.Label(css_classes=["milk-name"], halign=Gtk.Align.START, valign=Gtk.Align.END, margin_start=14, margin_bottom=12)
        overlay.add_overlay(self.name)
        self.set_child(overlay)
        click = Gtk.GestureClick(); click.connect("pressed", self._click); self.area.add_controller(click)
        keys = Gtk.EventControllerKey(); keys.connect("key-pressed", self._key); self.add_controller(keys)
        self.player.pcm_listeners.append(self._feed)
        self.area.add_tick_callback(lambda *_: (self.area.queue_render(), True)[1])
        self.connect("close-request", self._closing)

    # ---------- sound ----------
    def _feed(self, data, channels):
        # GStreamer's thread: keep the newest samples (as mono floats is enough for projectM).
        with self._lock:
            self._pcm.append((data, channels))
            if len(self._pcm) > 8:
                del self._pcm[:-8]

    # ---------- GL ----------
    def _realize(self, area):
        area.make_current()
        if area.get_error() or not LIB:
            return
        h = LIB.projectm_create()
        if not h:
            return
        LIB.projectm_set_mesh_size(h, 64, 48); LIB.projectm_set_fps(h, 60); LIB.projectm_set_aspect_correction(h, True)
        LIB.projectm_set_preset_duration(h, 1e9); LIB.projectm_set_soft_cut_duration(h, 3.0)
        self.handle = h
        self._load(False)

    def _unrealize(self, area):
        area.make_current()
        if self.handle:
            LIB.projectm_destroy(self.handle); self.handle = None

    def _resize(self, area, w, h):
        if self.handle:
            scale = area.get_scale_factor()
            LIB.projectm_set_window_size(self.handle, w * scale, h * scale)

    def _render(self, area, ctx):
        if not self.handle:
            return False
        with self._lock:
            chunks, self._pcm = self._pcm, []
        for data, ch in chunks:
            n = len(data) // 4 // max(1, ch)
            if n:
                buf = (ctypes.c_char * len(data)).from_buffer_copy(data)
                LIB.projectm_pcm_add_float(self.handle, buf, n, 2 if ch == 2 else 1)
        LIB.projectm_opengl_render_frame(self.handle)
        return True

    # ---------- presets ----------
    def _load(self, smooth=True):
        if not (self.handle and self.list):
            return
        path = self.list[self.index % len(self.list)]
        self.area.make_current()
        LIB.projectm_load_preset_file(self.handle, path.encode(), smooth)
        self.name.set_text(os.path.basename(path)[:-5])
        self.name.set_opacity(1)
        GLib.timeout_add(3000, lambda: (self.name.set_opacity(0), False)[1])

    def step(self, d):
        self.index = (self.index + d) % max(1, len(self.list)); self._load()

    def _click(self, g, n, x, y):
        if n == 2:
            self.unfullscreen() if self.is_fullscreen() else self.fullscreen()
        elif n == 1:
            GLib.timeout_add(260, lambda: (self.step(random.randint(1, max(1, len(self.list) - 1))) if g.get_current_button() else None, False)[1])

    def _key(self, c, keyval, code, state):
        if keyval == Gdk.KEY_Escape:
            if self.is_fullscreen():
                self.unfullscreen()
            else:
                self.close()
            return True
        if keyval in (Gdk.KEY_f, Gdk.KEY_F, Gdk.KEY_F11):
            self.unfullscreen() if self.is_fullscreen() else self.fullscreen(); return True
        if keyval == Gdk.KEY_Right:
            self.step(1); return True
        if keyval == Gdk.KEY_Left:
            self.step(-1); return True
        if keyval == Gdk.KEY_space:
            self.player.toggle(); return True
        return False

    def _closing(self, *_):
        if self._feed in self.player.pcm_listeners:
            self.player.pcm_listeners.remove(self._feed)
        return False
