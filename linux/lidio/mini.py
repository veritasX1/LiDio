"""The small player for Ubuntu (card 9861adfa): by default Music's MiniPlayer in Apple's design (the cover, title and controls
on glass), in the settings the Winamp mode with real Winamp 2 skins (.wsz) – main window, equalizer, playlist – like the
Android app. Both are a window of their own; the big window can close meanwhile, the music plays on.
"Always on top" is the desktop's business under Wayland: right-click the title area → "Immer im Vordergrund" (GNOME)."""
import json, math, os, unicodedata, urllib.request, zipfile
import gi
gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
gi.require_version("GdkPixbuf", "2.0")
from gi.repository import Adw, GdkPixbuf, GLib, Gtk, Gdk, Pango

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
        self.area = Gtk.DrawingArea()
        self.area.set_draw_func(self._draw)
        self.set_child(self.area)
        click = Gtk.GestureClick(button=0)
        click.connect("pressed", self._press); click.connect("released", self._release)
        self.area.add_controller(click)
        drag = Gtk.GestureDrag(); drag.connect("drag-update", self._drag_update); drag.connect("drag-end", self._drag_end)
        self.area.add_controller(drag)
        scroll = Gtk.EventControllerScroll(flags=Gtk.EventControllerScrollFlags.VERTICAL)
        scroll.connect("scroll", lambda c, dx, dy: (self.player.set_volume(self.player.volume() - dy * 0.05), self.area.queue_draw(), True)[2])
        self.area.add_controller(scroll)
        self.player.listeners.append(self._changed)
        self._frame = GLib.timeout_add(33, self._tick)
        self._scroll = GLib.timeout_add(220, self._marquee)
        self.connect("close-request", self._closing)
        self.player.set_eq(self.eq_on, self.preamp, self.bands)
        self._resize()

    # ---------- layout ----------
    def _height(self):
        return 116 + (116 if self.eq_open else 0) + (116 if self.pl_open else 0)

    def _resize(self):
        self.area.set_content_width(round(275 * self.k)); self.area.set_content_height(round(self._height() * self.k))
        self.area.queue_draw()

    def _save(self):
        for k, v in (("winampEq", self.eq_open), ("winampPl", self.pl_open), ("eqAn", self.eq_on), ("eqPre", self.preamp), ("eqBands", self.bands)):
            self.app._set(k, v)

    def _closing(self, *_):
        if self._changed in self.player.listeners:
            self.player.listeners.remove(self._changed)
        GLib.source_remove(self._frame); GLib.source_remove(self._scroll)
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
        cr.get_source().set_filter(1)      # cairo.FILTER_NEAREST: crisp pixels
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
            Gdk.cairo_set_source_pixbuf(cr, pb, cx - col * 5, y - row * 6); cr.get_source().set_filter(1); cr.paint(); cr.restore()

    def _draw(self, area, cr, w, h):
        cr.scale(self.k, self.k)
        self._regions = []
        self._main(cr)
        y = 116
        if self.eq_open:
            cr.save(); cr.translate(0, y); self._eq(cr, y); cr.restore(); y += 116
        if self.pl_open:
            cr.save(); cr.translate(0, y); self._playlist(cr, y); cr.restore()

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
        self._sprite(cr, "MAIN_BALANCE_BACKGROUND", 177, 57, 38, 13, 0, 0)
        self._sprite(cr, "MAIN_BALANCE_THUMB", 177 + 12, 58)
        self._sprite(cr, "MAIN_POSITION_SLIDER_BACKGROUND", 16, 72)
        if t and p.length() > 0:
            f = self.drag[1] if self.drag and self.drag[0] == "pos" else min(1, p.position() / p.length())
            self._sprite(cr, "MAIN_POSITION_SLIDER_THUMB_SELECTED" if self.drag else "MAIN_POSITION_SLIDER_THUMB", 16 + round(f * 219), 72)
        self._regions += [(107, 57, 68, 13, ("slider", "vol"), None, None), (16, 72, 248, 10, ("slider", "pos"), None, None),
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
        c = self.skin.vis

        def col(i):
            return c[i] if i < len(c) else c[-1]
        cr.set_source_rgb(*col(0)); cr.rectangle(24, 43, 76, 16); cr.fill()
        cr.set_source_rgb(*col(1))
        for y in range(1, 16, 2):
            for x in range(1, 76, 2):
                cr.rectangle(24 + x, 43 + y, 1, 1)
        cr.fill()
        spec = self.player.spectrum if self.player.playing else [0.0] * 75
        # 19 thick bars of 3 pixels, as Winamp's default.
        for b in range(19):
            part = spec[b * 75 // 19 // 2: (b + 1) * 75 // 19 // 2 + 1] or [0]     # the lower half of the spectrum carries the music
            v = max(part)
            h = min(16, round(v * 16 * 1.15))
            self.peaks[b] = max(h, self.peaks[b] - 0.35)
            for y in range(16 - h, 16):
                cr.set_source_rgb(*col(2 + y)); cr.rectangle(24 + b * 4, 43 + y, 3, 1); cr.fill()
            pk = round(self.peaks[b])
            if pk > 0:
                cr.set_source_rgb(*col(23)); cr.rectangle(24 + b * 4, 43 + 16 - pk, 3, 1); cr.fill()

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
        self._button(cr, "EQ_PRESETS_BUTTON", 217, 18, self._eq_reset, "EQ_PRESETS_BUTTON_SELECTED", oy)
        self._button(cr, "EQ_CLOSE_BUTTON", 264, 3, self._toggle_eq, "EQ_CLOSE_BUTTON_ACTIVE", oy)

    def _playlist(self, cr, oy):
        """Winamp's playlist editor, 275 × 116: the frame from pledit.bmp, the queue in the skin's colours."""
        self._sprite(cr, "PLAYLIST_TOP_LEFT_SELECTED", 0, 0)
        for x in range(25, 250, 25):
            self._sprite(cr, "PLAYLIST_TOP_TILE_SELECTED", x, 0)
        self._sprite(cr, "PLAYLIST_TITLE_BAR_SELECTED", 87, 0)
        self._sprite(cr, "PLAYLIST_TOP_RIGHT_CORNER_SELECTED", 250, 0)
        for y in range(20, 78, 29):
            self._sprite(cr, "PLAYLIST_LEFT_TILE", 0, y, 12, min(29, 78 - y))
            self._sprite(cr, "PLAYLIST_RIGHT_TILE", 255, y, 20, min(29, 78 - y))
        self._sprite(cr, "PLAYLIST_BOTTOM_LEFT_CORNER", 0, 78)
        self._sprite(cr, "PLAYLIST_BOTTOM_RIGHT_CORNER", 125, 78)
        bg = self.skin.colors
        cr.set_source_rgb(*bg["normalbg"]); cr.rectangle(12, 20, 243, 58); cr.fill()
        p = self.player
        rows = [(i, p.queue[q]) for i, q in enumerate(p.order)]
        first = max(0, min(p.index - 2, len(rows) - 6)) if rows else 0
        layout = Pango.Layout.new(self.area.get_pango_context())
        layout.set_font_description(Pango.FontDescription.from_string("Sans 6.5"))
        from .window import clock
        for n, (i, t) in enumerate(rows[first:first + 6]):
            y = 21 + n * 9.5
            if i == p.index:
                cr.set_source_rgb(*bg["selectedbg"]); cr.rectangle(13, y, 241, 9.5); cr.fill()
            cr.set_source_rgb(*(bg["current"] if i == p.index else bg["normal"]))
            layout.set_text(f"{i + 1}. {t.artist} - {t.title}"[:46], -1)
            cr.move_to(15, y); from gi.repository import PangoCairo; PangoCairo.show_layout(cr, layout)
            layout.set_text(clock(t.duration), -1); cr.move_to(232, y); PangoCairo.show_layout(cr, layout)
            self._regions.append((13, y + oy, 241, 9.5, ("jump", i), None, None))
        self._regions.append((0, oy, 250, 14, "move", None, None))

    # ---------- actions ----------
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
        if not r:
            return
        action = r[4]
        if action == "move":
            g.set_state(Gtk.EventSequenceState.CLAIMED)
            self.get_surface().begin_move(g.get_device(), g.get_current_button() or 1, x, y, g.get_current_event_time()); return
        if isinstance(action, tuple) and action[0] == "slider":
            self._slide(action[1], r, lx, ly); return
        if isinstance(action, tuple) and action[0] == "jump":
            if n == 2:
                self.player.jump(self.player.order[action[1]])
            return
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
        if not getattr(self, "_slider", None):
            return
        ok, x0, y0 = g.get_start_point()
        key, rx, ry, rw, rh = self._slider
        self._slide_to((x0 + dx) / self.k - rx, (y0 + dy) / self.k - ry)

    def _slide_to(self, lx, ly):
        key, rx, ry, rw, rh = self._slider
        if key in ("vol", "pos"):
            f = max(0.0, min(1.0, lx / rw))
            if key == "vol":
                self.player.set_volume(f)
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
        if self.drag and self.drag[0] == "pos" and self.player.current:
            self.player.seek(self.drag[1] * self.player.length())
        if self.drag and self.drag[0] != "pos":
            self._save()
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
