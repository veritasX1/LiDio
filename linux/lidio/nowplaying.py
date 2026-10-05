"""The full-screen player, like Music on the Mac (View → Full Screen Player): the cover large, its colours blurred behind
everything, title, position and controls below; the lyrics beside it when there are some. Esc or the round button closes."""
import gi
gi.require_version("Gtk", "4.0")
from gi.repository import GLib, Gtk, Gdk, Pango

from .covers import Cover, load
from .player import REPEAT_OFF, REPEAT_ONE


class NowPlaying(Gtk.Overlay):
    def __init__(self, window):
        super().__init__(css_classes=["now-playing"])
        self.window, self.player = window, window.player
        self.backdrop = Gtk.Picture(content_fit=Gtk.ContentFit.COVER, can_shrink=True, css_classes=["np-backdrop"])
        self.set_child(self.backdrop)
        shade = Gtk.Box(css_classes=["np-shade"], hexpand=True, vexpand=True)
        self.add_overlay(shade)

        body = Gtk.Box(spacing=60, halign=Gtk.Align.CENTER, valign=Gtk.Align.CENTER, margin_start=60, margin_end=60)
        left = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=10, valign=Gtk.Align.CENTER)
        self.cover = Cover(420, 14); self.cover.add_css_class("np-cover"); left.append(self.cover)
        self.title = Gtk.Label(xalign=0, css_classes=["np-title"], ellipsize=Pango.EllipsizeMode.END, max_width_chars=30, margin_top=18)
        self.artist = Gtk.Label(xalign=0, css_classes=["np-artist"], ellipsize=Pango.EllipsizeMode.END, max_width_chars=40)
        left.append(self.title); left.append(self.artist)
        self.progress = Gtk.Scale.new_with_range(Gtk.Orientation.HORIZONTAL, 0, 1, 0.001)
        self.progress.set_draw_value(False); self.progress.add_css_class("np-progress")
        self.progress.connect("change-value", lambda s, sc, v: (self.player.seek(v), False)[1])
        left.append(self.progress)
        times = Gtk.Box()
        self.pos = Gtk.Label(css_classes=["np-time"], xalign=0, hexpand=True); self.left = Gtk.Label(css_classes=["np-time"], xalign=1)
        times.append(self.pos); times.append(self.left); left.append(times)
        controls = Gtk.Box(spacing=26, halign=Gtk.Align.CENTER, margin_top=8)

        def button(icon, tip, cb, css="np-button"):
            b = Gtk.Button(icon_name=icon, tooltip_text=tip, css_classes=["flat", "circular", css]); b.connect("clicked", lambda *_: cb())
            b.update_property([Gtk.AccessibleProperty.LABEL], [tip])
            controls.append(b); return b
        self.shuffle = button("media-playlist-shuffle-symbolic", "Zufall", self.player.toggle_shuffle)
        button("media-skip-backward-symbolic", "Zurück", self.player.previous)
        self.play = button("media-playback-start-symbolic", "Wiedergabe", self.player.toggle, "np-play")
        button("media-skip-forward-symbolic", "Weiter", self.player.next)
        self.repeat = button("media-playlist-repeat-symbolic", "Wiederholen", self.player.cycle_repeat)
        left.append(controls)
        vol = Gtk.Box(spacing=8, margin_top=10)
        vol.append(Gtk.Image.new_from_icon_name("audio-volume-low-symbolic"))
        self.volume = Gtk.Scale.new_with_range(Gtk.Orientation.HORIZONTAL, 0, 1, 0.01); self.volume.set_draw_value(False); self.volume.set_hexpand(True)
        self.volume.connect("value-changed", lambda s: self.player.set_volume(s.get_value()) if not self._sync else None)
        vol.append(self.volume); vol.append(Gtk.Image.new_from_icon_name("audio-volume-high-symbolic"))
        left.append(vol)
        body.append(left)

        self.lyrics = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=14, width_request=460, margin_top=200, margin_bottom=260)
        self.lyrics_scroll = Gtk.ScrolledWindow(hscrollbar_policy=Gtk.PolicyType.NEVER, min_content_width=460, min_content_height=520,
                                                css_classes=["np-lyrics"], visible=False)
        self.lyrics_scroll.set_child(self.lyrics)
        body.append(self.lyrics_scroll)
        self.add_overlay(body)

        from .window import PRIVAT
        if PRIVAT:
            # Optional extension: a music video for the title.
            vid = Gtk.Button(icon_name="video-display-symbolic", tooltip_text="Musikvideo", css_classes=["circular", "np-close"],
                             halign=Gtk.Align.END, valign=Gtk.Align.START, margin_end=18, margin_top=18)
            from .window import PRIVAT_UI
            vid.connect("clicked", lambda *_: self.player.current and PRIVAT_UI.VideoWindow(window, self.player.current).present())
            self.add_overlay(vid)
        close = Gtk.Button(icon_name="go-down-symbolic", tooltip_text="Schließen (Esc)", css_classes=["circular", "np-close"],
                           halign=Gtk.Align.START, valign=Gtk.Align.START, margin_start=18, margin_top=18)
        close.connect("clicked", lambda *_: window.close_now_playing())
        self.add_overlay(close)
        self._sync = False
        self._labels = []
        self.player.listeners.append(self._changed)
        self._changed("track")

    def _changed(self, what):
        if what == "spectrum":
            return
        p, t = self.player, self.player.current
        self._sync = True
        self.play.set_icon_name("media-playback-pause-symbolic" if p.playing else "media-playback-start-symbolic")
        for b, on in ((self.shuffle, p.shuffle), (self.repeat, p.repeat != REPEAT_OFF)):
            (b.add_css_class if on else b.remove_css_class)("np-on")
        self.repeat.set_icon_name("media-playlist-repeat-song-symbolic" if p.repeat == REPEAT_ONE else "media-playlist-repeat-symbolic")
        self.volume.set_value(p.volume())
        self._sync = False
        if what in ("track", "state") and t:
            self.title.set_text(t.title); self.artist.set_text(" — ".join(x for x in (t.artist, t.album) if x))
            url = p.server.cover_url(t.cover_id, 900) if t.cover_id and p.server else None
            self.cover.show(url)
            self.backdrop.set_paintable(None)
            if url:
                load(url, lambda tex: self.backdrop.set_paintable(tex) if self.player.current is t else None)

    def show_lyrics(self, lines):
        while (c := self.lyrics.get_first_child()) is not None:
            self.lyrics.remove(c)
        self._labels = []
        self.lyrics_scroll.set_visible(bool(lines))
        for ms, text in lines or []:
            l = Gtk.Label(label=text or "♪", xalign=0, wrap=True, css_classes=["np-line"], max_width_chars=22, width_chars=22, hexpand=True)
            if ms is not None:
                click = Gtk.GestureClick(); click.connect("released", lambda *_, ms=ms: self.player.seek(ms / 1000)); l.add_controller(click)
            self.lyrics.append(l); self._labels.append((ms, l))

    def tick(self):
        p = self.player
        if not p.current:
            return
        pos, length = p.position(), p.length()
        self.progress.set_range(0, max(1, length)); self.progress.set_value(pos)
        from .window import clock
        self.pos.set_text(clock(pos)); self.left.set_text("-" + clock(length - pos))
        for i, (ms, l) in enumerate(self._labels):
            nxt = self._labels[i + 1][0] if i + 1 < len(self._labels) else None
            now = ms is not None and ms <= pos * 1000 + 250 and (nxt is None or nxt > pos * 1000 + 250)
            if now and not l.has_css_class("now"):
                l.add_css_class("now")
                ok, rect = l.compute_bounds(self.lyrics)
                if ok:
                    adj = self.lyrics_scroll.get_vadjustment(); adj.set_value(max(0, rect.get_y() - adj.get_page_size() / 3))
            elif not now:
                l.remove_css_class("now")
