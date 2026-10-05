"""Small pieces the pages share: where a title is (computer, server, waiting, loading – like the Android app, card 2aaf09ce),
the download button of albums and playlists, horizontal rows of tiles."""
import math
import gi
gi.require_version("Gtk", "4.0")
from gi.repository import Gtk
from .i18n import _


class Ring(Gtk.DrawingArea):
    """A filling circle for the title that loads; a calm dotted one for those that wait."""

    def __init__(self, size=18):
        super().__init__(content_width=size, content_height=size, valign=Gtk.Align.CENTER)
        self.progress, self.waiting = 0.0, False
        self.set_draw_func(self._draw)

    def set(self, progress=None, waiting=False):
        self.progress, self.waiting = progress or 0.0, waiting
        self.queue_draw()

    def _draw(self, area, cr, w, h):
        color = self.get_color()
        r = min(w, h) / 2 - 1.5
        cr.set_line_width(2)
        if self.waiting:
            cr.set_source_rgba(color.red, color.green, color.blue, 0.45)
            for i in range(12):
                a = i * math.pi / 6
                cr.arc(w / 2 + r * math.cos(a), h / 2 + r * math.sin(a), 0.9, 0, 2 * math.pi); cr.fill()
            return
        cr.set_source_rgba(color.red, color.green, color.blue, 0.2)
        cr.arc(w / 2, h / 2, r, 0, 2 * math.pi); cr.stroke()
        cr.set_source_rgba(0.98, 0.18, 0.28, 1)        # LiDio red
        cr.arc(w / 2, h / 2, r, -math.pi / 2, -math.pi / 2 + 2 * math.pi * max(0.02, self.progress)); cr.stroke()


class Where(Gtk.Stack):
    """The small symbol at the end of a title row: computer = on this computer, server = only on the server (click loads it),
    a ring while it loads or waits."""

    def __init__(self, offline, track, server_getter):
        super().__init__(valign=Gtk.Align.CENTER, transition_type=Gtk.StackTransitionType.CROSSFADE)
        self.offline, self.track = offline, track
        local = Gtk.Image.new_from_icon_name("computer-symbolic"); local.add_css_class("where")
        local.set_tooltip_text(_("Auf diesem Computer"))
        self.add_named(local, "local")
        load = Gtk.Button(icon_name="network-server-symbolic", css_classes=["flat", "where-button"], tooltip_text=_("Auf diesem Computer laden"))
        load.update_property([Gtk.AccessibleProperty.LABEL], ["Auf diesem Computer laden"])
        load.connect("clicked", lambda *_: offline.download([track]))
        self.add_named(load, "server")
        self.ring = Ring(); self.add_named(self.ring, "ring")
        self.update()

    def update(self):
        s = self.offline.state(self.track)
        if s == "local":
            self.set_visible_child_name("local")
        elif s in ("loading", "waiting"):
            self.ring.set(self.offline.live.get(self.track.id), waiting=s == "waiting")
            self.ring.set_tooltip_text(_("Wird geladen …") if s == "loading" else _("Wartet aufs Laden"))
            self.set_visible_child_name("ring")
        else:
            self.set_visible_child_name("server")


class DownloadButton(Gtk.Button):
    """Album and playlist: load everything onto this computer. Shows how many are there; while loading a click asks to stop."""

    def __init__(self, window, tracks):
        super().__init__(css_classes=["flat", "circular", "download-button"], valign=Gtk.Align.CENTER)
        self.window, self.tracks = window, tracks
        self.ring = Ring(22)
        self.connect("clicked", self._clicked)
        self.update()

    def update(self):
        off = self.window.app.offline
        ids = {t.id for t in self.tracks}
        there = sum(1 for i in ids if i in off.index)
        busy = any(i in off.waiting or i in off.live for i in ids)
        total = len(ids)
        if busy:
            done = there + sum(off.live.get(i, 0) for i in ids)
            self.ring.set(done / max(1, total)); self.set_child(self.ring)
            tip = _("Wird geladen – {there} von {total}. Klicken zum Stoppen.", there=there, total=total)
        elif total and there == total:
            self.set_icon_name("object-select-symbolic"); tip = _("Alle {total} Titel auf diesem Computer", total=total)
        else:
            self.set_icon_name("folder-download-symbolic"); tip = _("Auf diesem Computer laden") + (_(" ({there} von {total} schon da)", there=there, total=total) if there else "")
        self.set_tooltip_text(tip)
        self.update_property([Gtk.AccessibleProperty.LABEL], [tip])

    def _clicked(self, *_):
        import gi
        gi.require_version("Adw", "1")
        from gi.repository import Adw
        off = self.window.app.offline
        ids = {t.id for t in self.tracks}
        if any(i in off.waiting or i in off.live for i in ids):
            d = Adw.AlertDialog(heading=_("Laden abbrechen?"), body=_("Was schon da ist, bleibt auf diesem Computer."))
            d.add_response("weiter", _("Weiter laden")); d.add_response("stop", _("Laden stoppen"))
            d.set_response_appearance("stop", Adw.ResponseAppearance.DESTRUCTIVE)
            d.connect("response", lambda d, r: off.cancel() if r == "stop" else None)
            d.present(self.window)
        elif all(i in off.index for i in ids):
            d = Adw.AlertDialog(heading=_("Von diesem Computer entfernen?"), body=_("Die Titel bleiben auf dem Server und lassen sich jederzeit wieder laden."))
            d.add_response("nein", _("Abbrechen")); d.add_response("weg", _("Entfernen"))
            d.set_response_appearance("weg", Adw.ResponseAppearance.DESTRUCTIVE)
            d.connect("response", lambda d, r: off.remove(self.tracks) if r == "weg" else None)
            d.present(self.window)
        else:
            off.download(self.tracks)


def shelf(children):
    """A horizontal row of tiles that scrolls sideways (Start page, like Music's "Home")."""
    box = Gtk.Box(spacing=18, margin_start=28, margin_end=28)
    for c in children:
        box.append(c)
    s = Gtk.ScrolledWindow(vscrollbar_policy=Gtk.PolicyType.NEVER, hscrollbar_policy=Gtk.PolicyType.AUTOMATIC)
    s.set_child(box); s.set_propagate_natural_height(True)
    return s
