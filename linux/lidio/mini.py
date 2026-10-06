"""The small player for Ubuntu (card 9861adfa): by default Music's MiniPlayer in Apple's design (the cover, title and controls
on glass), in the settings the Winamp mode with real Winamp 2 skins (.wsz) – main window, equalizer, playlist – like the
Android app. Both are a window of their own; the big window can close meanwhile, the music plays on.
"Always on top" is the desktop's business under Wayland: right-click the title area → "Immer im Vordergrund" (GNOME)."""
import json, math, os, threading, unicodedata, urllib.request, zipfile
import cairo
import gi
gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
gi.require_version("GdkPixbuf", "2.0")
from gi.repository import Adw, GdkPixbuf, Gio, GLib, Gtk, Gdk, Pango

from .covers import Cover
from .player import REPEAT_OFF, REPEAT_ALL
from .sprites import SPRITES, FONT
from .i18n import _

DATA = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "data", "skins")
SKINS = os.path.join(os.environ.get("XDG_DATA_HOME") or os.path.expanduser("~/.local/share"), "lidio", "skins")
SHEETS = {"main", "cbuttons", "titlebar", "numbers", "nums_ex", "text", "posbar", "volume", "balance", "shufrep", "monoster",
          "playpaus", "eqmain", "eq_ex", "pledit", "gen"}
EQ_LABELS = ["60", "170", "310", "600", "1K", "3K", "6K", "12K", "14K", "16K"]


# ======================= Apple: MiniPlayer =======================
class MiniPlayer(Gtk.Window):
    """Music's MiniPlayer: the cover fills the window; on hover the controls fade in over a dark glass at the bottom."""

    def __init__(self, app):
        super().__init__(application=app, title=_("LiDio – Mini-Player"), resizable=False, decorated=False, css_classes=["mini"])
        self.app, self.player = app, app.player
        overlay = Gtk.Overlay()
        handle = Gtk.WindowHandle(); handle.set_child(overlay); self.set_child(handle)
        self.cover = Cover(300, 14); overlay.set_child(self.cover)
        self.glass = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, css_classes=["mini-glass"], valign=Gtk.Align.END, spacing=2)
        self.title = Gtk.Label(xalign=0, ellipsize=Pango.EllipsizeMode.END, css_classes=["mini-title"], max_width_chars=1, hexpand=True)
        self.artist = Gtk.Label(xalign=0, ellipsize=Pango.EllipsizeMode.END, css_classes=["mini-artist"], max_width_chars=1, hexpand=True)
        self.glass.append(self.title); self.glass.append(self.artist)
        self.progress = Gtk.Scale.new_with_range(Gtk.Orientation.HORIZONTAL, 0, 1, 0.001); self.progress.set_draw_value(False)
        self.progress.connect("change-value", lambda s, sc, v: (self.player.seek(v), False)[1])
        self.glass.append(self.progress)
        row = Gtk.Box(spacing=18, halign=Gtk.Align.CENTER)

        def button(icon, tip, cb, css="mini-button"):
            b = Gtk.Button(icon_name=icon, tooltip_text=tip, css_classes=["flat", "circular", css]); b.connect("clicked", lambda *_: cb())
            b.update_property([Gtk.AccessibleProperty.LABEL], [tip]); row.append(b); return b
        button("media-skip-backward-symbolic", _("Zurück"), self.player.previous)
        self.play = button("media-playback-start-symbolic", _("Wiedergabe"), self.player.toggle, "mini-play")
        button("media-skip-forward-symbolic", _("Weiter"), self.player.next)
        self.glass.append(row)
        overlay.add_overlay(self.glass)
        top = Gtk.Box(spacing=6, halign=Gtk.Align.END, valign=Gtk.Align.START, margin_top=8, margin_end=8, css_classes=["mini-top"])
        big = Gtk.Button(icon_name="view-fullscreen-symbolic", tooltip_text=_("Großes Fenster"), css_classes=["circular", "mini-round"])
        big.connect("clicked", lambda *_: (self.app.present_window(), self.close()))
        close = Gtk.Button(icon_name="window-close-symbolic", tooltip_text=_("Mini-Player schließen"), css_classes=["circular", "mini-round"])
        close.connect("clicked", lambda *_: self.close())
        top.append(big); top.append(close)
        overlay.add_overlay(top)
        motion = Gtk.EventControllerMotion()
        motion.connect("enter", lambda *_: self.add_css_class("hover")); motion.connect("leave", lambda *_: self.remove_css_class("hover"))
        self.add_controller(motion)
        self.player.listeners.append(self._changed)
        self._timer = GLib.timeout_add(500, self._tick)
        self.connect("close-request", self._closing)
        self._changed("track")

    def _closing(self, *_):
        if self._changed in self.player.listeners:
            self.player.listeners.remove(self._changed)
        GLib.source_remove(self._timer)
        return False

    def _changed(self, what):
        if what == "spectrum":
            return
        t = self.player.current
        self.play.set_icon_name("media-playback-pause-symbolic" if self.player.playing else "media-playback-start-symbolic")
        if what in ("track", "state"):
            self.title.set_text(t.title if t else "LiDio")
            self.artist.set_text(t.artist if t else _("Nichts läuft"))
            s = self.player.server
            self.cover.show(s.cover_url(t.cover_id, 600) if t and t.cover_id and s else None)

    def _tick(self):
        p = self.player
        if p.current:
            length = p.length(); self.progress.set_range(0, max(1, length)); self.progress.set_value(p.position())
        return True


# ======================= Winamp skins =======================
class Skin:
    def __init__(self, name, sheets, vis, colors):
        self.name, self.sheets, self.vis, self.colors = name, sheets, vis, colors

    @staticmethod
    def read(path, name, fallback=None):
        files = {}
        with zipfile.ZipFile(path) as z:
            total = 0
            for info in z.infolist():
                if info.is_dir():
                    continue
                base = info.filename.replace("\\", "/").rsplit("/", 1)[-1].lower()
                key = base.rsplit(".", 1)[0]
                if (base.endswith(".bmp") and key in SHEETS) or base in ("viscolor.txt", "pledit.txt"):
                    total += info.file_size
                    if total > 32 << 20:       # no skin is that big – guard against zip bombs
                        break
                    files.setdefault(key if base.endswith(".bmp") else base, z.read(info))
        sheets = {}
        for k, data in files.items():
            if k in SHEETS:
                try:
                    loader = GdkPixbuf.PixbufLoader(); loader.write(data); loader.close()
                    sheets[k] = loader.get_pixbuf()
                except GLib.Error:
                    pass
        if "volume" in sheets:
            sheets.setdefault("balance", sheets["volume"])
        if "numbers" in sheets:
            sheets.setdefault("nums_ex", sheets["numbers"])
        if "nums_ex" in sheets:
            sheets.setdefault("numbers", sheets["nums_ex"])
        if fallback:
            for k, v in fallback.sheets.items():
                sheets.setdefault(k, v)
        vis = []
        for line in files.get("viscolor.txt", b"").decode("latin-1").splitlines():
            import re
            n = [min(255, int(x)) for x in re.findall(r"\d+", line.split("//")[0])][:3]
            if len(n) == 3:
                vis.append(tuple(c / 255 for c in n))
        if len(vis) < 18:
            vis = fallback.vis if fallback else [(0, 1, 0)] * 24
        colors = dict(fallback.colors) if fallback else {"normal": (0, 1, 0), "current": (1, 1, 1), "normalbg": (0, 0, 0), "selectedbg": (0, 0, 0.78)}
        for line in files.get("pledit.txt", b"").decode("latin-1").splitlines():
            if "=" in line:
                k, v = line.split("=", 1)
                v = v.strip().lstrip("#")[:6]
                if k.strip().lower() in colors and len(v) == 6:
                    try:
                        colors[k.strip().lower()] = tuple(int(v[i:i + 2], 16) / 255 for i in (0, 2, 4))
                    except ValueError:
                        pass
        return Skin(name, sheets, vis[:24], colors)


_builtin = None


def builtin():
    global _builtin
    if not _builtin:
        _builtin = Skin.read(os.path.join(DATA, "lidio.wsz"), "LiDio Graphit")
    return _builtin


def installed():
    """[(md5, name, path)] – the built-in one first."""
    out = [("", "LiDio Graphit", os.path.join(DATA, "lidio.wsz"))]
    try:
        for f in sorted(os.listdir(SKINS)):
            if f.endswith(".json"):
                with open(os.path.join(SKINS, f)) as fh:
                    d = json.load(fh)
                wsz = os.path.join(SKINS, d["md5"] + ".wsz")
                if os.path.exists(wsz):
                    out.append((d["md5"], d["name"], wsz))
    except OSError:
        pass
    return out


def load_skin(md5):
    for m, name, path in installed():
        if m == md5 and m:
            try:
                return Skin.read(path, name, builtin())
            except (OSError, zipfile.BadZipFile):
                break
    return builtin()


class Museum:
    """The Winamp Skin Museum (skins.webamp.org, by Jordan Eldredge) – public GraphQL, no account. Nothing is asked before
    the skin dialog opens; NSFW skins are left out."""
    API = "https://api.webamp.org/graphql"
    CLASSIC = "5e4f10275dcb1fb211d4a8b4f1bda236"
    FIELDS = "md5 filename download_url screenshot_url nsfw"

    @classmethod
    def _q(cls, query, variables):
        req = urllib.request.Request(cls.API, json.dumps({"query": query, "variables": variables}).encode(),
                                     {"Content-Type": "application/json", "User-Agent": "LiDio/0.1 (https://lisoft.goip.de/lidio)"})
        with urllib.request.urlopen(req, timeout=20) as r:
            return json.loads(r.read())["data"]

    @classmethod
    def page(cls, offset=0, count=40):
        nodes = cls._q("query($n:Int,$o:Int){ skins(first:$n, offset:$o, sort: MUSEUM) { nodes { %s } } }" % cls.FIELDS,
                       {"n": count, "o": offset})["skins"]["nodes"]
        return [n for n in nodes if n and not n.get("nsfw")]

    @classmethod
    def search(cls, text):
        return [n for n in cls._q("query($q:String!,$n:Int){ search_skins(query:$q, first:$n) { %s } }" % cls.FIELDS,
                                  {"q": text, "n": 60})["search_skins"] if n and not n.get("nsfw")]

    @staticmethod
    def install(node):
        os.makedirs(SKINS, exist_ok=True)
        md5 = node["md5"]
        req = urllib.request.Request(node["download_url"], headers={"User-Agent": "LiDio/0.1 (https://lisoft.goip.de/lidio)"})
        with urllib.request.urlopen(req, timeout=30) as r:
            data = r.read(32 << 20)
        with open(os.path.join(SKINS, md5 + ".wsz"), "wb") as f:
            f.write(data)
        name = "Winamp Classic" if md5 == Museum.CLASSIC else node.get("filename", md5).rsplit(".", 1)[0].replace("_", " ").strip()
        with open(os.path.join(SKINS, md5 + ".json"), "w") as f:
            json.dump({"md5": md5, "name": name}, f)
        return md5


# ======================= Winamp window =======================
class WinampWindow(Gtk.Window):
    def __init__(self, app):
        super().__init__(application=app, title=_("LiDio – Winamp"), resizable=False, decorated=False)
        self.app, self.player = app, app.player
        self.k = float(app._setting("winampScale", 2))
        self.skin = load_skin(app._setting("winampSkin", ""))
        self.eq_open = app._setting("winampEq", True)
        self.pl_open = app._setting("winampPl", True)
        self.remaining = False
        self.eq_on = app._setting("eqAn", False)
        self.preamp = float(app._setting("eqPre", 0))
        self.bands = [float(x) for x in app._setting("eqBands", [0] * 10)]
        self.marquee, self.pressed, self.drag = 0, None, None
        self.peaks = [0.0] * 75
        self.balance = float(app._setting("balance", 0))
        # Visualizer like Android (card 3cf2eda4): 0 spectrum, 1 oscilloscope, 2 off; styles and peaks as Winamp 2.
        self.vis = int(app._setting("winampVis", 0)); self.vis_style = int(app._setting("winampVisStil", 0))
        self.vis_peaks = bool(app._setting("winampVisSpitzen", True)); self.vis_thick = bool(app._setting("winampDickeBalken", True))
        self.scope = int(app._setting("winampOszi", 1))
        # Playlist: taller in steps of 29 px (Winamp's resize grip), its own scroll position and selection.
        self.pl_extra = int(app._setting("winampPlExtra", 0)); self.pl_top = None; self.pl_sel = set(); self.pointer = (0, 0)
        self.pl_wide = int(app._setting("winampPlBreit", 0))      # playlist wider in steps of 25 px (Winamp's grip)
        self.pl_menu = None                                        # an open sprite menu: (name, x, bottom y)
        self.area = Gtk.DrawingArea()
        self.area.set_draw_func(self._draw)
        self.set_child(self.area)
        # Beside the 275-px windows (when the playlist is wider) the desktop shows through, as with Winamp.
        css = Gtk.CssProvider(); css.load_from_string("window.winamp, window.winamp > * { background: transparent; box-shadow: none; }")
        Gtk.StyleContext.add_provider_for_display(Gdk.Display.get_default(), css, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION)
        self.add_css_class("winamp")
        click = Gtk.GestureClick(button=0)
        click.connect("pressed", self._press); click.connect("released", self._release)
        self.area.add_controller(click)
        drag = Gtk.GestureDrag(); drag.connect("drag-update", self._drag_update); drag.connect("drag-end", self._drag_end)
        self.area.add_controller(drag)
        scroll = Gtk.EventControllerScroll(flags=Gtk.EventControllerScrollFlags.VERTICAL)
        scroll.connect("scroll", self._scrolled)
        self.area.add_controller(scroll)
        self.player.listeners.append(self._changed)
        self._frame = GLib.timeout_add(33, self._tick)
        self._scroll = GLib.timeout_add(220, self._marquee)
        self.connect("close-request", self._closing)
        self.player.set_eq(self.eq_on, self.preamp, self.bands)
        self.player.set_balance(self.balance)
        self.player.want_wave = self.vis == 1
        motion = Gtk.EventControllerMotion()
        motion.connect("motion", lambda c, x, y: (setattr(self, "pointer", (x / self.k, y / self.k)), self.pl_menu and self.area.queue_draw()))
        self.area.add_controller(motion)
        self.actions = Gio.SimpleActionGroup(); self.insert_action_group("wa", self.actions); self._actions()
        self._resize()

    # ---------- layout ----------
    def _pl_height(self):
        return 116 + 29 * self.pl_extra

    def _height(self):
        return 116 + (116 if self.eq_open else 0) + (self._pl_height() if self.pl_open else 0)

    def _pl_width(self):
        return 275 + 25 * self.pl_wide

    def _resize(self):
        width = max(275, self._pl_width()) if self.pl_open else 275
        self.area.set_content_width(round(width * self.k)); self.area.set_content_height(round(self._height() * self.k))
        self.area.queue_draw()

    def _save(self):
        for k, v in (("winampEq", self.eq_open), ("winampPl", self.pl_open), ("eqAn", self.eq_on), ("eqPre", self.preamp), ("eqBands", self.bands),
                     ("balance", self.balance), ("winampVis", self.vis), ("winampVisStil", self.vis_style), ("winampVisSpitzen", self.vis_peaks),
                     ("winampDickeBalken", self.vis_thick), ("winampOszi", self.scope), ("winampPlExtra", self.pl_extra), ("winampPlBreit", self.pl_wide)):
            self.app._set(k, v)

    def _closing(self, *_):
        if self._changed in self.player.listeners:
            self.player.listeners.remove(self._changed)
        GLib.source_remove(self._frame); GLib.source_remove(self._scroll)
        self.player.want_wave = False
        self._save()
        return False

    def _changed(self, what):
        if what != "spectrum":
            if what == "track":
                self.marquee = 0
            self.area.queue_draw()

    def _tick(self):
        if self.player.playing:
            self.area.queue_draw()
        return True

    def _marquee(self):
        if len(self._title()) > 30:
            self.marquee = (self.marquee + 1) % (len(self._title()) + 5); self.area.queue_draw()
        return True

    def _title(self):
        t = self.player.current
        if not t:
            return "LiDio"
        from .window import clock
        return f"{self.player.index + 1}. {t.artist} - {t.title} ({clock(t.duration)})"

    # ---------- drawing ----------
    def _sprite(self, cr, name, x, y, sw=None, sh=None, sx=0, sy=0):
        s = SPRITES.get(name)
        if not s:
            return
        pb = self.skin.sheets.get(s[0])
        if not pb:
            return
        w, h = sw or s[3], sh or s[4]
        px, py = s[1] + sx, s[2] + sy
        w, h = min(w, pb.get_width() - px), min(h, pb.get_height() - py)
        if w <= 0 or h <= 0:
            return
        cr.save()
        cr.rectangle(x, y, w, h); cr.clip()
        Gdk.cairo_set_source_pixbuf(cr, pb, x - px, y - py)
        cr.get_source().set_filter(cairo.Filter.NEAREST)      # crisp pixels (card 3cf2eda4: 1 was GOOD = blurred)
        cr.paint()
        cr.restore()

    def _text(self, cr, text, x, y, max_chars=999, shift=0):
        pb = self.skin.sheets.get("text")
        if not pb:
            return
        for i, ch in enumerate(text[:max_chars]):
            cell = FONT.get(ch) or FONT.get(ch.lower()) or FONT.get(ch.upper())
            if not cell:
                base = unicodedata.normalize("NFD", ch)[:1]
                cell = FONT.get(base.lower()) or (FONT.get("s") if ch == "ß" else None) or FONT[" "]
            row, col = cell
            cx = x + i * 5 - shift
            cr.save(); cr.rectangle(cx, y, 5, 6); cr.clip()
            Gdk.cairo_set_source_pixbuf(cr, pb, cx - col * 5, y - row * 6); cr.get_source().set_filter(cairo.Filter.NEAREST); cr.paint(); cr.restore()

    def _draw(self, area, cr, w, h):
        cr.scale(self.k, self.k)
        cr.set_antialias(cairo.Antialias.NONE)   # no blended seams between tiles (the playlist's light lines)
        self._regions = []
        self._main(cr)
        y = 116
        if self.eq_open:
            cr.save(); cr.translate(0, y); self._eq(cr, y); cr.restore(); y += 116
        if self.pl_open:
            cr.save(); cr.translate(0, y); self._playlist(cr, y); cr.restore()
            if self.pl_menu:
                self._sprite_menu(cr)

    def _button(self, cr, name, x, y, action, down=None, oy=0):
        s = SPRITES[name]
        self._regions.append((x, y + oy, s[3], s[4], action, down or name, name))
        self._sprite(cr, (down or name) if self.pressed == (x, y + oy) else name, x, y)

    def _main(self, cr):
        p, t = self.player, self.player.current
        self._sprite(cr, "MAIN_WINDOW_BACKGROUND", 0, 0)
        self._sprite(cr, "MAIN_TITLE_BAR_SELECTED", 0, 0)
        self._sprite(cr, "MAIN_CLUTTER_BAR_BACKGROUND", 10, 22)
        self._sprite(cr, "MAIN_STOPPED_INDICATOR" if not t else ("MAIN_PLAYING_INDICATOR" if p.playing else "MAIN_PAUSED_INDICATOR"), 26, 28)
        self._sprite(cr, "MAIN_WORKING_INDICATOR", 24, 28)
        if t and (p.playing or int(GLib.get_monotonic_time() / 1e6) % 2 == 0):
            shown = max(0, p.length() - p.position()) if self.remaining else p.position()
            m, s = divmod(int(shown), 60); m = min(m, 99)
            ex = self.skin.sheets.get("nums_ex") is not None and self.skin.sheets["nums_ex"].get_width() >= 108
            suf = "_EX" if ex else ""
            for d, x in zip((m // 10, m % 10, s // 10, s % 10), (48, 60, 78, 90)):
                self._sprite(cr, f"DIGIT_{d}{suf}", x, 26)
            if self.remaining:
                self._sprite(cr, "MINUS_SIGN_EX" if ex else "MINUS_SIGN", 38, 26 if ex else 32)
        title = self._title()
        cr.save(); cr.rectangle(111, 24, 154, 6); cr.clip()
        self._text(cr, f"{title}  ***  {title}" if len(title) > 30 else title, 111, 24, shift=self.marquee * 5)
        cr.restore()
        if t:
            self._text(cr, "320", 111, 43, 3); self._text(cr, "44", 156, 43, 2)
            self._sprite(cr, "MAIN_STEREO_SELECTED", 239, 41)
        else:
            self._sprite(cr, "MAIN_STEREO", 239, 41)
        self._sprite(cr, "MAIN_MONO", 212, 41)
        self._vis(cr)
        vol = p.volume()
        self._sprite(cr, "MAIN_VOLUME_BACKGROUND", 107, 57, 68, 13, 0, round(vol * 27) * 15)
        self._sprite(cr, "MAIN_VOLUME_THUMB", 107 + round(vol * 51), 58)
        # Balance (card 50adb215): the background darkens towards the sides like Winamp's, the thumb travels 24 px.
        self._sprite(cr, "MAIN_BALANCE_BACKGROUND", 177, 57, 38, 13, 0, round(abs(self.balance) * 27) * 15)
        self._sprite(cr, "MAIN_BALANCE_THUMB_ACTIVE" if self.drag and self.drag[0] == "bal" else "MAIN_BALANCE_THUMB", 177 + round((self.balance + 1) / 2 * 24), 58)
        self._sprite(cr, "MAIN_POSITION_SLIDER_BACKGROUND", 16, 72)
        if t and p.length() > 0:
            f = self.drag[1] if self.drag and self.drag[0] == "pos" else min(1, p.position() / p.length())
            self._sprite(cr, "MAIN_POSITION_SLIDER_THUMB_SELECTED" if self.drag else "MAIN_POSITION_SLIDER_THUMB", 16 + round(f * 219), 72)
        self._regions += [(107, 57, 68, 13, ("slider", "vol"), None, None), (16, 72, 248, 10, ("slider", "pos"), None, None),
                          (177, 57, 38, 13, ("slider", "bal"), None, None), (24, 43, 76, 16, "vis", None, None),
                          (36, 26, 63, 13, self._toggle_remaining, None, None), (0, 0, 240, 14, "move", None, None)]
        self._button(cr, "MAIN_OPTIONS_BUTTON", 6, 3, self._skin_dialog, "MAIN_OPTIONS_BUTTON_DEPRESSED")
        self._button(cr, "MAIN_MINIMIZE_BUTTON", 244, 3, lambda: self.app.present_window(), "MAIN_MINIMIZE_BUTTON_DEPRESSED")
        self._button(cr, "MAIN_SHADE_BUTTON", 254, 3, self._skin_dialog, "MAIN_SHADE_BUTTON_DEPRESSED")
        self._button(cr, "MAIN_CLOSE_BUTTON", 264, 3, self.close, "MAIN_CLOSE_BUTTON_DEPRESSED")
        self._button(cr, "MAIN_EQ_BUTTON_SELECTED" if self.eq_open else "MAIN_EQ_BUTTON", 219, 58, self._toggle_eq,
                     "MAIN_EQ_BUTTON_DEPRESSED_SELECTED" if self.eq_open else "MAIN_EQ_BUTTON_DEPRESSED")
        self._button(cr, "MAIN_PLAYLIST_BUTTON_SELECTED" if self.pl_open else "MAIN_PLAYLIST_BUTTON", 242, 58, self._toggle_pl,
                     "MAIN_PLAYLIST_BUTTON_DEPRESSED_SELECTED" if self.pl_open else "MAIN_PLAYLIST_BUTTON_DEPRESSED")
        self._button(cr, "MAIN_PREVIOUS_BUTTON", 16, 88, p.previous, "MAIN_PREVIOUS_BUTTON_ACTIVE")
        self._button(cr, "MAIN_PLAY_BUTTON", 39, 88, p.resume, "MAIN_PLAY_BUTTON_ACTIVE")
        self._button(cr, "MAIN_PAUSE_BUTTON", 62, 88, p.toggle, "MAIN_PAUSE_BUTTON_ACTIVE")
        self._button(cr, "MAIN_STOP_BUTTON", 85, 88, lambda: (p.pause(), p.seek(0)), "MAIN_STOP_BUTTON_ACTIVE")
        self._button(cr, "MAIN_NEXT_BUTTON", 108, 88, p.next, "MAIN_NEXT_BUTTON_ACTIVE")
        self._button(cr, "MAIN_EJECT_BUTTON", 136, 89, lambda: self.app.present_window(), "MAIN_EJECT_BUTTON_ACTIVE")
        sh, rp = p.shuffle, p.repeat != REPEAT_OFF
        self._button(cr, "MAIN_SHUFFLE_BUTTON_SELECTED" if sh else "MAIN_SHUFFLE_BUTTON", 164, 89, p.toggle_shuffle,
                     "MAIN_SHUFFLE_BUTTON_SELECTED_DEPRESSED" if sh else "MAIN_SHUFFLE_BUTTON_DEPRESSED")
        self._button(cr, "MAIN_REPEAT_BUTTON_SELECTED" if rp else "MAIN_REPEAT_BUTTON", 210, 89, self._repeat,
                     "MAIN_REPEAT_BUTTON_SELECTED_DEPRESSED" if rp else "MAIN_REPEAT_BUTTON_DEPRESSED")
        # Winamp's lightning bolt: back to LiDio's big window.
        self._regions.append((253, 91, 13, 15, lambda: (self.app.present_window(), self.close()), None, None))

    def _vis(self, cr):
        """Winamp's visualizer at (24, 43), 76 × 16, as on Android: spectrum (normal/fire/line, thin/thick, peaks),
        oscilloscope (dots/lines/solid) or off; colours from viscolor.txt (0 bg, 1 dots, 2–17 bars, 18–22 scope, 23 peaks)."""
        c = self.skin.vis

        def col(i):
            return c[i] if i < len(c) else c[-1]

        def dot(x, y, w, h, colour):
            cr.set_source_rgb(*colour); cr.rectangle(24 + x, 43 + y, w, h); cr.fill()
        if self.vis == 2:
            return
        dot(0, 0, 76, 16, col(0))
        cr.set_source_rgb(*col(1))
        for y in range(1, 16, 2):
            for x in range(1, 76, 2):
                cr.rectangle(24 + x, 43 + y, 1, 1)
        cr.fill()
        if self.vis == 1:
            wave = self.player.wave if self.player.playing else [0.0] * 76
            last = None
            for x in range(76):
                y = max(0, min(15, round(8 - wave[x] * 8)))

                def shade(yy):
                    return col(18 + min(4, abs(yy - 8) // 2))
                if self.scope == 0:
                    dot(x, y, 1, 1, shade(y))
                elif self.scope == 2:
                    for yy in range(min(8, y), max(8, y) + 1):
                        dot(x, yy, 1, 1, shade(yy))
                else:
                    a = y if last is None else last
                    for yy in range(min(a, y), max(a, y) + 1):
                        dot(x, yy, 1, 1, shade(yy))
                last = y
            return
        spec = self.player.spectrum if self.player.playing else [0.0] * 75
        count = 19 if self.vis_thick else 75
        width, pitch = (3, 4) if self.vis_thick else (1, 1)
        for b in range(count):
            # the lower half of the spectrum carries the music
            lo, hi = b * 75 // count // 2, (b + 1) * 75 // count // 2 + 1
            v = max(spec[lo:hi] or [0])
            h = min(16, round(v * 16 * 1.15))
            self.peaks[b] = max(h, self.peaks[b] - 0.35)
            for y in range(16 - h, 16):
                # Normal: colour by height on screen. Fire: from each bar's top down. Line: the whole bar in its top's colour.
                i = {1: 2 + (y - (16 - h)), 2: 2 + (16 - h)}.get(self.vis_style, 2 + y)
                dot(b * pitch, y, width, 1, col(max(2, min(17, i))))
            pk = round(self.peaks[b])
            if self.vis_peaks and pk > 0:
                dot(b * pitch, max(0, min(15, 16 - pk)), width, 1, col(23))

    def _eq(self, cr, oy):
        self._sprite(cr, "EQ_WINDOW_BACKGROUND", 0, 0)
        self._sprite(cr, "EQ_TITLE_BAR_SELECTED", 0, 0)
        self._sprite(cr, "EQ_GRAPH_BACKGROUND", 86, 17)
        pre_y = 17 + 9 - round(self.preamp / 12 * 9)
        self._sprite(cr, "EQ_PREAMP_LINE", 86, max(17, min(35, pre_y)))
        pts = [(87 + i * 12.2, 26 - v / 12 * 9) for i, v in enumerate(self.bands)]
        cr.set_source_rgb(*self.skin.vis[2] if self.skin.vis else (1, 1, 1))
        for x in range(111):
            gx = 87 + x
            seg = max(0, min(len(pts) - 2, max(i for i, p in enumerate(pts) if p[0] <= gx) if any(p[0] <= gx for p in pts) else 0))
            (x0, y0), (x1, y1) = pts[seg], pts[seg + 1]
            t = max(0, min(1, (gx - x0) / (x1 - x0)))
            y = round(y0 + (y1 - y0) * (t * t * (3 - 2 * t)))
            cr.rectangle(gx, max(17, min(35, y)), 1, 1)
        cr.fill()

        def slider(x, db, key):
            f = max(0, min(27, round((db + 12) / 24 * 27)))
            self._sprite(cr, "EQ_SLIDER_BACKGROUND", x, 38, 14, 63, (f % 14) * 15, (f // 14) * 65)
            self._sprite(cr, "EQ_SLIDER_THUMB_SELECTED" if self.drag and self.drag[0] == key else "EQ_SLIDER_THUMB", x + 1, 38 + round((12 - db) / 24 * 51))
            self._regions.append((x, 38 + oy, 14, 63, ("slider", key), None, None))
        slider(21, self.preamp, "pre")
        for i, v in enumerate(self.bands):
            slider(78 + i * 18, v, i)
        self._button(cr, "EQ_ON_BUTTON_SELECTED" if self.eq_on else "EQ_ON_BUTTON", 14, 18, self._toggle_eq_on,
                     "EQ_ON_BUTTON_SELECTED_DEPRESSED" if self.eq_on else "EQ_ON_BUTTON_DEPRESSED", oy)
        self._button(cr, "EQ_AUTO_BUTTON", 40, 18, lambda: None, "EQ_AUTO_BUTTON_DEPRESSED", oy)
        self._button(cr, "EQ_PRESETS_BUTTON", 217, 18, lambda: self._menu(self._presets_menu(), 217, 18 + oy + 12), "EQ_PRESETS_BUTTON_SELECTED", oy)
        self._button(cr, "EQ_CLOSE_BUTTON", 264, 3, self._toggle_eq, "EQ_CLOSE_BUTTON_ACTIVE", oy)

    def _playlist(self, cr, oy):
        """Winamp's playlist editor, (275 + 25·m) × (116 + 29·n): frame from pledit.bmp, the list in a small unsmoothed
        font like Winamp's, the five buttons with their sprite menus, mini transport, time, the small visualizer when wide
        enough, scroll handle and the resize grip (cards 3cf2eda4, 299f6c4b)."""
        W, H = self._pl_width(), self._pl_height(); bottom = H - 38
        self._sprite(cr, "PLAYLIST_TOP_LEFT_SELECTED", 0, 0)
        for x in range(25, W - 25, 25):
            self._sprite(cr, "PLAYLIST_TOP_TILE_SELECTED", x, 0)
        self._sprite(cr, "PLAYLIST_TITLE_BAR_SELECTED", (W - 100) // 2, 0)
        self._sprite(cr, "PLAYLIST_TOP_RIGHT_CORNER_SELECTED", W - 25, 0)
        for y in range(20, bottom, 29):
            self._sprite(cr, "PLAYLIST_LEFT_TILE", 0, y, 12, min(29, bottom - y))
            self._sprite(cr, "PLAYLIST_RIGHT_TILE", W - 20, y, 20, min(29, bottom - y))
        self._sprite(cr, "PLAYLIST_BOTTOM_LEFT_CORNER", 0, bottom)
        vis_room = W >= 350          # Winamp shows its small visualizer once the list is wide enough
        for x in range(125, W - 150 - (75 if vis_room else 0), 25):
            self._sprite(cr, "PLAYLIST_BOTTOM_TILE", x, bottom)
        if vis_room:
            self._sprite(cr, "PLAYLIST_VISUALIZER_BACKGROUND", W - 225, bottom)
            self._mini_vis(cr, W - 225 + 3, bottom + 12)
        self._sprite(cr, "PLAYLIST_BOTTOM_RIGHT_CORNER", W - 150, bottom)
        bg = self.skin.colors
        lw, lh = W - 31, bottom - 20
        cr.set_source_rgb(*bg["normalbg"]); cr.rectangle(12, 20, lw, lh); cr.fill()
        p = self.player
        rows = [(i, p.queue[q]) for i, q in enumerate(p.order)]
        ROW = 13
        visible = max(1, lh // ROW)
        if self.pl_top is None:
            first = max(0, min(p.index - 2, len(rows) - visible)) if rows else 0
        else:
            first = max(0, min(self.pl_top, max(0, len(rows) - visible)))
        self._pl_first, self._pl_visible = first, visible
        # The list at 1× without smoothing, then enlarged pixel by pixel – Winamp's crisp little font.
        surface = cairo.ImageSurface(cairo.Format.ARGB32, lw, lh)
        c2 = cairo.Context(surface); c2.set_antialias(cairo.Antialias.NONE)
        from gi.repository import PangoCairo
        layout = PangoCairo.create_layout(c2)
        fo = cairo.FontOptions(); fo.set_antialias(cairo.Antialias.NONE); fo.set_hint_style(cairo.HintStyle.FULL); fo.set_hint_metrics(cairo.HintMetrics.ON)
        PangoCairo.context_set_font_options(layout.get_context(), fo)
        desc = Pango.FontDescription.from_string("Arial"); desc.set_absolute_size(10 * Pango.SCALE); layout.set_font_description(desc)
        from .window import clock
        for n, (i, t) in enumerate(rows[first:first + visible]):
            y = n * ROW
            if i in self.pl_sel:
                c2.set_source_rgb(*bg["selectedbg"]); c2.rectangle(0, y, lw, ROW); c2.fill()
            c2.set_source_rgb(*(bg["current"] if i == p.index else bg["normal"]))
            layout.set_text(clock(t.duration), -1)
            tw = layout.get_pixel_size()[0]
            c2.move_to(lw - tw - 3, y + 1); PangoCairo.show_layout(c2, layout)
            layout.set_text(f"{i + 1}. {t.artist} - {t.title}", -1)
            layout.set_width((lw - tw - 12) * Pango.SCALE); layout.set_ellipsize(Pango.EllipsizeMode.END)
            c2.move_to(3, y + 1); PangoCairo.show_layout(c2, layout)
            layout.set_width(-1); layout.set_ellipsize(Pango.EllipsizeMode.NONE)
            self._regions.append((12, 20 + y + oy, lw, ROW, ("jump", i), None, None))
        cr.save(); cr.rectangle(12, 20, lw, lh); cr.clip()
        cr.set_source_surface(surface, 12, 20); cr.get_source().set_filter(cairo.Filter.NEAREST); cr.paint(); cr.restore()
        # Scroll handle on the right frame, where the list stands.
        if len(rows) > visible:
            f = first / max(1, len(rows) - visible)
            self._sprite(cr, "PLAYLIST_SCROLL_HANDLE", W - 15, 20 + round(f * (bottom - 20 - 18)))
        # The five buttons: each opens its sprite menu (Winamp's ADD, REM, SEL, MISC, LIST OPTS).
        for x, name in ((14, "add"), (43, "rem"), (72, "sel"), (101, "misc"), (W - 46, "list")):
            self._regions.append((x, bottom + 8 + oy, 25 if name != "list" else 22, 18, ("menu", name, x, bottom + 8 + oy), None, None))
        # Mini transport: previous, play, pause, stop, next, eject (open LiDio).
        R = W - 150
        for k, (x, act) in enumerate(((R + 6, p.previous), (R + 14, p.resume), (R + 22, p.toggle), (R + 30, lambda: (p.pause(), p.seek(0))),
                                      (R + 38, p.next), (R + 45, lambda: self.app.present_window()))):
            self._regions.append((x, bottom + 22 + oy, 8 if k < 5 else 9, 8, act, None, None))
        # The small time display next to them (tap: elapsed ↔ remaining, like the big one).
        if p.current:
            shown = max(0, p.length() - p.position()) if self.remaining else p.position()
            m, sec = divmod(int(shown), 60)
            self._text(cr, (("-" if self.remaining else "") + f"{m}:{sec:02d}").rjust(6), R + 64, bottom + 23, 6)
        self._regions.append((R + 64, bottom + 22 + oy, 30, 8, self._toggle_remaining, None, None))
        # The info field above: length of the selection / of the whole list, as Winamp; tap: Titel-Info.
        total = sum(int(p.queue[q].duration or 0) for q in p.order)
        chosen = sum(int(p.queue[p.order[i]].duration or 0) for i in self.pl_sel if 0 <= i < len(p.order))
        self._text(cr, f"{clock(chosen)}/{clock(total)}"[:22], R + 8, bottom + 10, 22)
        self._regions.append((R + 6, bottom + 7 + oy, 88, 10, self._info, None, None))
        self._regions.append((W - 20, H - 20 + oy, 20, 20, "resize", None, None))
        self._regions.append((0, oy, W - 25, 14, "move", None, None))
        self._pl_oy = oy

    def _mini_vis(self, cr, x0, y0):
        """The playlist's small analyzer (72 × 16), when the list is wide: thick bars in the skin's colours."""
        c = self.skin.vis
        spec = self.player.spectrum if self.player.playing else [0.0] * 75
        for b in range(18):
            v = max(spec[b * 2: b * 2 + 3] or [0]); h = min(16, round(v * 16 * 1.15))
            for y in range(16 - h, 16):
                cr.set_source_rgb(*(c[2 + y] if 2 + y < len(c) else c[-1])); cr.rectangle(x0 + b * 4, y0 + y, 3, 1); cr.fill()

    # Winamp's sprite menus above the buttons (top → bottom), as in Webamp (card 299f6c4b).
    SPRITE_MENUS = {
        "add": ("PLAYLIST_ADD_MENU_BAR", [("PLAYLIST_ADD_URL", "aehnliche"), ("PLAYLIST_ADD_DIR", "mediathek"), ("PLAYLIST_ADD_FILE", "mediathek")]),
        "rem": ("PLAYLIST_REMOVE_MENU_BAR", [("PLAYLIST_REMOVE_MISC", "+remmisc"), ("PLAYLIST_REMOVE_ALL", "alleentfernen"),
                                             ("PLAYLIST_CROP", "zuschneiden"), ("PLAYLIST_REMOVE_SELECTED", "entfernen")]),
        "sel": ("PLAYLIST_SELECT_MENU_BAR", [("PLAYLIST_INVERT_SELECTION", "umkehren"), ("PLAYLIST_SELECT_ZERO", "keine"), ("PLAYLIST_SELECT_ALL", "alle")]),
        "misc": ("PLAYLIST_MISC_MENU_BAR", [("PLAYLIST_SORT_LIST", "+sort"), ("PLAYLIST_FILE_INFO", "info"), ("PLAYLIST_MISC_OPTIONS", "+miscopts")]),
        "list": ("PLAYLIST_LIST_BAR", [("PLAYLIST_NEW_LIST", "leeren"), ("PLAYLIST_SAVE_LIST", "sichern"), ("PLAYLIST_LOAD_LIST", "+laden")]),
    }

    def _sprite_menu(self, cr):
        name, x, y = self.pl_menu
        bar, items = self.SPRITE_MENUS[name]
        top = y + 18 - 18 * len(items)
        self._sprite(cr, bar, x - 3, top)
        px, py = self.pointer
        for i, (sprite, action) in enumerate(items):
            iy = top + i * 18
            hover = x <= px < x + 22 and iy <= py < iy + 18
            self._sprite(cr, sprite + "_SELECTED" if hover else sprite, x, iy)
            self._regions.append((x, iy, 22, 18, ("menuitem", action, x, iy), None, None))

    def _menu_item(self, action, x, y):
        self.pl_menu = None
        if not action.startswith("+"):
            self.actions.activate_action(action, None); self.area.queue_draw(); return
        sub = Gio.Menu()
        if action == "+sort":
            for label, a in ((_("Nach Titel"), "wa.nachtitel"), (_("Nach Interpret"), "wa.nachinterpret"), (_("Nach Album"), "wa.nachalbum"),
                             (_("Reihenfolge umkehren"), "wa.reihenfolge"), (_("Mischen"), "wa.mischen")):
                sub.append(label, a)
        elif action == "+remmisc":
            sub.append(_("Doppelte entfernen"), "wa.doppelte"); sub.append(_("Schon Gespieltes entfernen"), "wa.gespielt")
        elif action == "+miscopts":
            sub.append(_("HTML-Playlist erzeugen"), "wa.html")
        elif action == "+laden":
            self._load_menu(x, y); return
        self._menu(sub, x + 22, y + 9)

    def _load_menu(self, x, y):
        server = self.player.server or getattr(self.app, "server", None)
        if not server:
            return
        def work():
            try:
                lists = server.playlists()
            except Exception:
                return
            def show():
                sub = Gio.Menu()
                for pl in lists[:40]:
                    sub.append(pl.name, f"wa.laden::{pl.id}")
                self._menu(sub, x + 22, y + 9)
                return False
            GLib.idle_add(show)
        threading.Thread(target=work, daemon=True).start()

    # ---------- menus (cards 299f6c4b, presets) ----------
    def _actions(self):
        p = self.player

        def add(name, cb, kind=None, state=None):
            if kind:
                a = Gio.SimpleAction.new_stateful(name, GLib.VariantType.new(kind), state)
                a.connect("activate", lambda a, v: (a.set_state(v), cb(v)))
            else:
                a = Gio.SimpleAction.new(name, None); a.connect("activate", lambda *_: cb())
            self.actions.add_action(a)
        sel = lambda: sorted(self.pl_sel)
        add("mediathek", lambda: self.app.present_window())
        add("aehnliche", self._add_similar)
        add("entfernen", lambda: (p.remove_positions(sel()), self.pl_sel.clear()))
        add("zuschneiden", lambda: (p.keep_positions(sel()), self.pl_sel.clear()))
        add("alleentfernen", lambda: (p.keep_positions([]), self.pl_sel.clear()))
        add("alle", lambda: self.pl_sel.update(range(len(p.order))))
        add("keine", lambda: self.pl_sel.clear())
        add("umkehren", lambda: setattr(self, "pl_sel", set(range(len(p.order))) - self.pl_sel))
        add("nachtitel", lambda: p.rearrange(lambda t: (t.title or "").lower()))
        add("nachinterpret", lambda: p.rearrange(lambda t: ((t.artist or "").lower(), (t.album or "").lower())))
        add("nachalbum", lambda: p.rearrange(lambda t: ((t.album or "").lower(), getattr(t, "number", 0) or 0)))
        add("reihenfolge", lambda: p.rearrange())
        add("mischen", lambda: p.rearrange(shuffle=True))
        add("info", self._info)
        add("sichern", self._save_list)
        add("leeren", lambda: (p.keep_positions([]), self.pl_sel.clear()))
        add("preset", lambda v: self._preset(v.get_string()), "s", GLib.Variant("s", ""))
        add("laden", lambda v: self._load_playlist(v.get_string()), "s", GLib.Variant("s", ""))
        add("doppelte", self._remove_duplicates)
        add("gespielt", lambda: p.remove_positions(range(p.index)))
        add("html", self._html_playlist)
        add("flach", self._eq_reset)
        add("presetsichern", self._save_preset)
        add("anzeige", lambda v: self._vis_set(vis=int(v.get_string())), "s", GLib.Variant("s", str(self.vis)))
        add("stil", lambda v: self._vis_set(style=int(v.get_string())), "s", GLib.Variant("s", str(self.vis_style)))
        add("balken", lambda v: self._vis_set(thick=v.get_string() == "1"), "s", GLib.Variant("s", "1" if self.vis_thick else "0"))
        add("oszi", lambda v: self._vis_set(scope=int(v.get_string())), "s", GLib.Variant("s", str(self.scope)))
        add("spitzen", lambda v: self._vis_set(peaks=v.get_string() == "1"), "s", GLib.Variant("s", "1" if self.vis_peaks else "0"))

    def _menu(self, model, x, y):
        pop = Gtk.PopoverMenu.new_from_model(model)
        pop.set_parent(self.area)
        r = Gdk.Rectangle(); r.x, r.y, r.width, r.height = round(x * self.k), round(y * self.k), 1, 1
        pop.set_pointing_to(r); pop.set_has_arrow(False)
        pop.connect("closed", lambda q: (GLib.idle_add(q.unparent), self.area.queue_draw()))
        pop.popup()

    def _presets_menu(self):
        from .eqpresets import PRESETS
        m = Gio.Menu()
        m.append(_("Zurücksetzen (flach)"), "wa.flach")
        builtin = Gio.Menu()
        for name, _b in PRESETS:
            builtin.append(name, f"wa.preset::{name}")
        m.append_section(_("Winamp"), builtin)
        own = self.app._setting("eqEigene", {}) or {}
        if own:
            mine = Gio.Menu()
            for name in sorted(own):
                mine.append(name, f"wa.preset::{name}")
            m.append_section(_("Eigene"), mine)
        m.append(_("Aktuelle Einstellung sichern …"), "wa.presetsichern")
        return m

    def _vis_menu(self):
        m = Gio.Menu()
        def section(title, action, labels):
            sub = Gio.Menu()
            for i, label in enumerate(labels):
                sub.append(label, f"wa.{action}::{i}")
            m.append_section(title, sub)
        section(_("Anzeige"), "anzeige", [_("Spektrum"), _("Oszilloskop"), _("Aus")])
        section(_("Spektrum"), "stil", [_("Normal"), "Fire", "Line"])
        section(_("Balken"), "balken", [_("Dünn"), _("Dick")])
        section(_("Oszilloskop"), "oszi", ["Dots", "Lines", "Solid"])
        section(_("Spitzen"), "spitzen", [_("Aus"), _("An")])
        return m

    def _vis_set(self, vis=None, style=None, thick=None, scope=None, peaks=None):
        if vis is not None:
            self.vis = vis; self.player.want_wave = vis == 1
        if style is not None:
            self.vis_style = style
        if thick is not None:
            self.vis_thick = thick; self.peaks = [0.0] * 75
        if scope is not None:
            self.scope = scope
        if peaks is not None:
            self.vis_peaks = peaks
        for name, value in (("anzeige", str(self.vis)), ("stil", str(self.vis_style)), ("balken", "1" if self.vis_thick else "0"),
                            ("oszi", str(self.scope)), ("spitzen", "1" if self.vis_peaks else "0")):
            self.actions.lookup_action(name).set_state(GLib.Variant("s", value))
        self._save(); self.area.queue_draw()

    def _preset(self, name):
        from .eqpresets import PRESETS
        bands = dict(PRESETS).get(name) or (self.app._setting("eqEigene", {}) or {}).get(name)
        if bands is None:
            return
        self.bands = [float(b) for b in bands]; self.preamp = 0.0; self.eq_on = True
        self.player.set_eq(self.eq_on, self.preamp, self.bands); self._save(); self.area.queue_draw()

    def _ask_name(self, title, done, text=""):
        dialog = Adw.AlertDialog(heading=title)
        entry = Gtk.Entry(text=text, activates_default=True)
        dialog.set_extra_child(entry)
        dialog.add_response("cancel", _("Abbrechen")); dialog.add_response("ok", _("Sichern"))
        dialog.set_response_appearance("ok", Adw.ResponseAppearance.SUGGESTED); dialog.set_default_response("ok")
        dialog.connect("response", lambda d, r: done(entry.get_text().strip()) if r == "ok" and entry.get_text().strip() else None)
        dialog.present(self)

    def _save_preset(self):
        def keep(name):
            own = dict(self.app._setting("eqEigene", {}) or {}); own[name] = list(self.bands); self.app._set("eqEigene", own)
        self._ask_name(_("Equalizer-Einstellung sichern"), keep, _("Mein Klang"))

    def _save_list(self):
        p = self.player
        tracks = [p.queue[q] for q in p.order]
        if not tracks or not p.server:
            return
        def create(name):
            def work():
                try:
                    p.server.create_playlist(name, tracks)
                    GLib.idle_add(lambda: (self.app.notify(_("Playlist „{name}“ gesichert.").format(name=name)) if hasattr(self.app, "notify") else None, False)[-1])
                except Exception:
                    pass
            threading.Thread(target=work, daemon=True).start()
        self._ask_name(_("Liste als Playlist sichern"), create, _("Winamp-Liste"))

    def _load_playlist(self, pid):
        server = self.player.server or getattr(self.app, "server", None)
        def work():
            try:
                _pl, tracks = server.playlist(pid)
            except Exception:
                return
            GLib.idle_add(lambda: (self.player.play(server, tracks) if tracks else None, setattr(self, "pl_top", None), False)[-1])
        threading.Thread(target=work, daemon=True).start()

    def _remove_duplicates(self):
        p, seen, drop = self.player, set(), []
        for pos, q in enumerate(p.order):
            t = p.queue[q]; key = ((t.artist or "").lower(), (t.title or "").lower())
            if key in seen:
                drop.append(pos)
            seen.add(key)
        p.remove_positions(drop)

    def _html_playlist(self):
        """Winamp's "Generate HTML playlist": the list as a page, opened in the browser (written to the cache only)."""
        import html as h
        from .window import clock
        p = self.player
        rows = "".join(f"<li>{h.escape(t.artist or '')} – {h.escape(t.title or '')} <span>{clock(t.duration)}</span></li>" for t in (p.queue[q] for q in p.order))
        total = clock(sum(int(p.queue[q].duration or 0) for q in p.order))
        page = (f"<!doctype html><meta charset=utf-8><title>LiDio – Playlist</title><style>body{{background:#000;color:#0f0;font:13px Arial;padding:16px}}"
                f"h1{{color:#fff;font-size:18px}}span{{color:#888;float:right}}li{{max-width:640px}}</style><h1>Playlist</h1>"
                f"<p>{len(p.order)} {_('Titel')}, {total}</p><ol>{rows}</ol>")
        path = os.path.join(GLib.get_user_cache_dir(), "lidio", "playlist.html")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        open(path, "w", encoding="utf-8").write(page)
        Gtk.FileLauncher.new(Gio.File.new_for_path(path)).launch(self, None, None, None)

    def _add_similar(self):
        p = self.player
        if not (p.current and p.server):
            return
        known = {t.id for t in p.queue}
        def work():
            try:
                more = [t for t in p.server.similar(p.current, 15) if t.id not in known]
            except Exception:
                return
            GLib.idle_add(lambda: (p.add(more) if more else None, False)[-1])
        threading.Thread(target=work, daemon=True).start()

    def _info(self):
        p = self.player
        pos = min(self.pl_sel) if self.pl_sel else p.index
        if not (0 <= pos < len(p.order)):
            return
        t = p.queue[p.order[pos]]
        from .window import clock
        dialog = Adw.AlertDialog(heading=t.title or "", body="\n".join(x for x in (t.artist, t.album, clock(t.duration)) if x))
        dialog.add_response("ok", _("OK")); dialog.present(self)

    def _scrolled(self, c, dx, dy):
        x, y = self.pointer
        top = 116 + (116 if self.eq_open else 0)
        if self.pl_open and top + 20 <= y < top + self._pl_height() - 38:
            # Over the list: scroll it (Winamp) – not the volume.
            first = getattr(self, "_pl_first", 0)
            self.pl_top = max(0, first + (1 if dy > 0 else -1) * 3)
        else:
            self.player.set_volume(self.player.volume() - dy * 0.05)
        self.area.queue_draw()
        return True

    def _toggle_remaining(self):
        self.remaining = not self.remaining

    def _toggle_eq(self):
        self.eq_open = not self.eq_open; self._save(); self._resize()

    def _toggle_pl(self):
        self.pl_open = not self.pl_open; self._save(); self._resize()

    def _toggle_eq_on(self):
        self.eq_on = not self.eq_on; self.player.set_eq(self.eq_on, self.preamp, self.bands); self._save()

    def _eq_reset(self):
        self.preamp, self.bands = 0.0, [0.0] * 10; self.player.set_eq(self.eq_on, self.preamp, self.bands); self._save()

    def _repeat(self):
        # Winamp knows only on/off.
        self.player.repeat = REPEAT_OFF if self.player.repeat != REPEAT_OFF else REPEAT_ALL; self.player._changed()

    def _hit(self, x, y):
        sx, sy = x / self.k, y / self.k
        for r in reversed(getattr(self, "_regions", [])):
            rx, ry, rw, rh, action, down, name = r
            if rx <= sx < rx + rw and ry <= sy < ry + rh:
                return r, sx - rx, sy - ry
        return None, 0, 0

    def _press(self, g, n, x, y):
        r, lx, ly = self._hit(x, y)
        if self.pl_menu and not (r and isinstance(r[4], tuple) and r[4][0] in ("menu", "menuitem")):
            self.pl_menu = None; self.area.queue_draw()
        if not r:
            return
        action = r[4]
        if action == "move":
            g.set_state(Gtk.EventSequenceState.CLAIMED)
            self.get_surface().begin_move(g.get_device(), g.get_current_button() or 1, x, y, g.get_current_event_time()); return
        if isinstance(action, tuple) and action[0] == "slider":
            self._slide(action[1], r, lx, ly); return
        if isinstance(action, tuple) and action[0] == "jump":
            i = action[1]
            if n == 2:
                self.player.jump(self.player.order[i]); self.pl_top = None
            else:
                # Click selects, Strg+click adds/removes, like Winamp.
                state = g.get_current_event_state()
                if state & Gdk.ModifierType.CONTROL_MASK:
                    self.pl_sel ^= {i}
                else:
                    self.pl_sel = {i}
            self.area.queue_draw()
            return
        if action == "vis":
            if g.get_current_button() == 3:
                self._menu(self._vis_menu(), lx + 24, ly + 43)
            else:
                self._vis_set(vis=(self.vis + 1) % 3)
            return
        if isinstance(action, tuple) and action[0] == "menu":
            # Toggle Winamp's sprite menu above this button.
            self.pl_menu = None if self.pl_menu and self.pl_menu[0] == action[1] else (action[1], action[2], action[3])
            self.area.queue_draw(); return
        if isinstance(action, tuple) and action[0] == "menuitem":
            self._menu_item(action[1], action[2], action[3]); return
        if action == "resize":
            self._resizing = (x, y, self.pl_extra, self.pl_wide); return
        if g.get_current_button() == 3:
            self._skin_dialog(); return
        self.pressed = (r[0], r[1]); self.area.queue_draw()

    def _release(self, g, n, x, y):
        r, _, _ = self._hit(x, y)
        was, self.pressed = self.pressed, None
        if r and was == (r[0], r[1]) and callable(r[4]):
            r[4]()
        self.area.queue_draw()

    def _slide(self, key, r, lx, ly):
        rx, ry, rw, rh = r[:4]
        self._slider = (key, rx, ry, rw, rh)
        self._slide_to(lx, ly)

    def _drag_update(self, g, dx, dy):
        if getattr(self, "_resizing", None):
            # Winamp's grip: the playlist grows and shrinks in steps of 29 px.
            _x0, _y0, extra0, wide0 = self._resizing
            extra = max(0, min(20, extra0 + round(dy / self.k / 29)))
            wide = max(0, min(24, wide0 + round(dx / self.k / 25)))
            if (extra, wide) != (self.pl_extra, self.pl_wide):
                self.pl_extra, self.pl_wide = extra, wide; self._resize()
            return
        if not getattr(self, "_slider", None):
            return
        ok, x0, y0 = g.get_start_point()
        key, rx, ry, rw, rh = self._slider
        self._slide_to((x0 + dx) / self.k - rx, (y0 + dy) / self.k - ry)

    def _slide_to(self, lx, ly):
        key, rx, ry, rw, rh = self._slider
        if key in ("vol", "pos", "bal"):
            f = max(0.0, min(1.0, lx / rw))
            if key == "vol":
                self.player.set_volume(f)
            elif key == "bal":
                b = f * 2 - 1
                self.balance = 0.0 if abs(b) < 0.12 else b      # snaps to the middle, as Winamp's does
                self.drag = ("bal", self.balance)
                self.player.set_balance(self.balance)
            else:
                self.drag = ("pos", f)
        else:
            db = max(-12.0, min(12.0, round(((1 - max(0.0, min(1.0, ly / rh))) * 24 - 12) * 2) / 2))
            if key == "pre":
                self.preamp = db
            else:
                self.bands[key] = db
            self.drag = (key, db)
            self.player.set_eq(self.eq_on, self.preamp, self.bands)
        self.area.queue_draw()

    def _drag_end(self, g, dx, dy):
        if getattr(self, "_resizing", None):
            self._resizing = None; self._save(); return
        if self.drag and self.drag[0] == "pos" and self.player.current:
            self.player.seek(self.drag[1] * self.player.length())
        if self.drag and self.drag[0] != "pos":
            self._save()   # equalizer bands and balance are kept
        self.drag, self._slider = None, None
        self.area.queue_draw()

    # ---------- skins ----------
    def use_skin(self, md5):
        self.skin = load_skin(md5); self.app._set("winampSkin", md5); self.area.queue_draw()

    def _skin_dialog(self):
        SkinDialog(self).present(self)


class SkinDialog(Adw.Dialog):
    """Choose a skin: those on this computer, and the Winamp Skin Museum (search, most loved first)."""

    def __init__(self, win):
        super().__init__(title=_("Skins"), content_width=560, content_height=620)
        self.win = win
        from .window import run
        tv = Adw.ToolbarView(); hb = Adw.HeaderBar(); tv.add_top_bar(hb)
        page = Adw.PreferencesPage(); tv.set_content(page); self.set_child(tv)
        mine = Adw.PreferencesGroup(title=_("Auf diesem Computer"))
        for md5, name, path in installed():
            row = Adw.ActionRow(title=GLib.markup_escape_text(name), activatable=True)
            if md5 == win.app._setting("winampSkin", ""):
                row.add_suffix(Gtk.Image.new_from_icon_name("object-select-symbolic"))
            row.connect("activated", lambda r, m=md5: (win.use_skin(m), self.close()))
            mine.add(row)
        page.add(mine)
        size = Adw.PreferencesGroup(title="Größe")
        scale = Adw.ComboRow(title="Vergrößerung", model=Gtk.StringList.new(["1×", "1,5×", "2×", "3×"]))
        scale.set_selected([1, 1.5, 2, 3].index(win.k) if win.k in (1, 1.5, 2, 3) else 2)
        scale.connect("notify::selected", lambda r, _: (setattr(win, "k", [1, 1.5, 2, 3][r.get_selected()]), win.app._set("winampScale", win.k), win._resize()))
        size.add(scale)
        size.set_description(_("„Immer im Vordergrund“: Rechtsklick in die Titelleiste des Fensters (Alt+Leertaste) → „Immer im Vordergrund“."))
        page.add(size)
        museum = Adw.PreferencesGroup(title=_("Winamp Skin Museum"), description=_("skins.webamp.org – über 60 000 Skins, gesammelt von Jordan Eldredge. Erst beim Öffnen dieses Fensters fragt LiDio dort an."))
        search = Gtk.SearchEntry(placeholder_text=_("Skins suchen"), margin_bottom=8)
        museum.set_header_suffix(search)
        self.flow = Gtk.FlowBox(selection_mode=Gtk.SelectionMode.NONE, max_children_per_line=3, min_children_per_line=2, column_spacing=10, row_spacing=10)
        museum.add(self.flow)
        page.add(museum)

        def show(nodes):
            while (c := self.flow.get_first_child()) is not None:
                self.flow.remove(c)
            for n in nodes[:45]:
                b = Gtk.Button(css_classes=["flat"])
                box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4)
                pic = Cover(150, 4); pic.set_size_request(150, 63); pic.picture.set_size_request(150, 63); pic.placeholder.set_size_request(150, 63)
                pic.show(n.get("screenshot_url")); box.append(pic)
                l = Gtk.Label(label=n.get("filename", "").rsplit(".", 1)[0].replace("_", " "), ellipsize=Pango.EllipsizeMode.END, max_width_chars=18, css_classes=["caption"])
                box.append(l); b.set_child(box)
                b.connect("clicked", lambda w, n=n: run(lambda: Museum.install(n), lambda m: (win.use_skin(m), self.close()), win.app.window.toast if win.app.window else None))
                self.flow.append(b)
        run(lambda: Museum.page(0, 45), show)
        state = {"t": None}

        def changed(e):
            if state["t"]:
                GLib.source_remove(state["t"])
            q = e.get_text().strip()
            state["t"] = GLib.timeout_add(400, lambda: (run(lambda: Museum.search(q) if q else Museum.page(0, 45), show), state.update(t=None), False)[2])
        search.connect("search-changed", changed)
