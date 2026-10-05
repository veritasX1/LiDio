"""MPRIS: GNOME's media controls (top bar, lock screen) and the media keys talk to LiDio over D-Bus – also while the window
is closed and LiDio plays in the background (card b1768b44)."""
from gi.repository import Gio, GLib

XML = """<node>
<interface name="org.mpris.MediaPlayer2">
  <method name="Raise"/><method name="Quit"/>
  <property name="CanQuit" type="b" access="read"/><property name="CanRaise" type="b" access="read"/>
  <property name="HasTrackList" type="b" access="read"/><property name="Identity" type="s" access="read"/>
  <property name="DesktopEntry" type="s" access="read"/>
  <property name="SupportedUriSchemes" type="as" access="read"/><property name="SupportedMimeTypes" type="as" access="read"/>
</interface>
<interface name="org.mpris.MediaPlayer2.Player">
  <method name="Next"/><method name="Previous"/><method name="Pause"/><method name="PlayPause"/><method name="Stop"/><method name="Play"/>
  <method name="Seek"><arg direction="in" name="Offset" type="x"/></method>
  <method name="SetPosition"><arg direction="in" name="TrackId" type="o"/><arg direction="in" name="Position" type="x"/></method>
  <property name="PlaybackStatus" type="s" access="read"/><property name="Metadata" type="a{sv}" access="read"/>
  <property name="Volume" type="d" access="readwrite"/><property name="Position" type="x" access="read"/>
  <property name="Rate" type="d" access="read"/><property name="MinimumRate" type="d" access="read"/><property name="MaximumRate" type="d" access="read"/>
  <property name="CanGoNext" type="b" access="read"/><property name="CanGoPrevious" type="b" access="read"/>
  <property name="CanPlay" type="b" access="read"/><property name="CanPause" type="b" access="read"/>
  <property name="CanSeek" type="b" access="read"/><property name="CanControl" type="b" access="read"/>
  <property name="Shuffle" type="b" access="readwrite"/><property name="LoopStatus" type="s" access="readwrite"/>
</interface>
</node>"""


class Mpris:
    def __init__(self, app, player):
        self.app, self.player = app, player
        self.node = Gio.DBusNodeInfo.new_for_xml(XML)
        self.conn = None
        Gio.bus_own_name(Gio.BusType.SESSION, "org.mpris.MediaPlayer2.lidio", Gio.BusNameOwnerFlags.NONE, self._acquired, None, None)
        player.listeners.append(self._changed)

    def _acquired(self, conn, name):
        self.conn = conn
        for iface in self.node.interfaces:
            conn.register_object("/org/mpris/MediaPlayer2", iface, self._call, self._get, self._set)

    def _metadata(self):
        t = self.player.current
        if not t:
            return GLib.Variant("a{sv}", {"mpris:trackid": GLib.Variant("o", "/org/mpris/MediaPlayer2/TrackList/NoTrack")})
        m = {"mpris:trackid": GLib.Variant("o", "/io/github/veritasx1/LiDio/track/" + "".join(c if c.isalnum() else "_" for c in t.id)),
             "mpris:length": GLib.Variant("x", int(self.player.length() * 1_000_000)),
             "xesam:title": GLib.Variant("s", t.title), "xesam:artist": GLib.Variant("as", [t.artist]),
             "xesam:album": GLib.Variant("s", t.album)}
        if t.cover_id and self.player.server:
            m["mpris:artUrl"] = GLib.Variant("s", self.player.server.cover_url(t.cover_id, 512))
        return GLib.Variant("a{sv}", m)

    def _get(self, conn, sender, path, iface, prop):
        p = self.player
        values = {
            "CanQuit": GLib.Variant("b", True), "CanRaise": GLib.Variant("b", True), "HasTrackList": GLib.Variant("b", False),
            "Identity": GLib.Variant("s", "LiDio"), "DesktopEntry": GLib.Variant("s", "io.github.veritasx1.LiDio"),
            "SupportedUriSchemes": GLib.Variant("as", []), "SupportedMimeTypes": GLib.Variant("as", []),
            "PlaybackStatus": GLib.Variant("s", "Playing" if p.playing else ("Paused" if p.current else "Stopped")),
            "Metadata": self._metadata(), "Volume": GLib.Variant("d", p.volume()), "Position": GLib.Variant("x", int(p.position() * 1_000_000)),
            "Rate": GLib.Variant("d", 1.0), "MinimumRate": GLib.Variant("d", 1.0), "MaximumRate": GLib.Variant("d", 1.0),
            "CanGoNext": GLib.Variant("b", True), "CanGoPrevious": GLib.Variant("b", True), "CanPlay": GLib.Variant("b", True),
            "CanPause": GLib.Variant("b", True), "CanSeek": GLib.Variant("b", True), "CanControl": GLib.Variant("b", True),
            "Shuffle": GLib.Variant("b", p.shuffle), "LoopStatus": GLib.Variant("s", ["None", "Playlist", "Track"][p.repeat]),
        }
        return values.get(prop)

    def _set(self, conn, sender, path, iface, prop, value):
        if prop == "Volume":
            self.player.set_volume(value.unpack())
        elif prop == "Shuffle" and value.unpack() != self.player.shuffle:
            self.player.toggle_shuffle()
        elif prop == "LoopStatus":
            self.player.repeat = {"None": 0, "Playlist": 1, "Track": 2}.get(value.unpack(), 0); self.player._changed()
        return True

    def _call(self, conn, sender, path, iface, method, params, invocation):
        p = self.player
        if method == "Raise":
            self.app.present_window()
        elif method == "Quit":
            self.app.quit()
        elif method in ("PlayPause",):
            p.toggle()
        elif method == "Play":
            p.resume()
        elif method == "Pause":
            p.pause()
        elif method == "Stop":
            p.stop()
        elif method == "Next":
            p.next()
        elif method == "Previous":
            p.previous()
        elif method == "Seek":
            p.seek(max(0, p.position() + params.unpack()[0] / 1_000_000))
        elif method == "SetPosition":
            p.seek(params.unpack()[1] / 1_000_000)
        invocation.return_value(None)

    def _changed(self, what):
        if not self.conn or what == "spectrum" or what == "position":
            return
        props = {"PlaybackStatus": self._get(None, None, None, None, "PlaybackStatus"), "Metadata": self._metadata(),
                 "Shuffle": GLib.Variant("b", self.player.shuffle), "LoopStatus": GLib.Variant("s", ["None", "Playlist", "Track"][self.player.repeat]),
                 "Volume": GLib.Variant("d", self.player.volume())}
        self.conn.emit_signal(None, "/org/mpris/MediaPlayer2", "org.freedesktop.DBus.Properties", "PropertiesChanged",
                              GLib.Variant("(sa{sv}as)", ("org.mpris.MediaPlayer2.Player", props, [])))
