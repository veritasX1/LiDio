"""Playback with GStreamer's playbin: a queue of titles from one server, next/previous like the iPhone (back within 3 s =
previous title), shuffle, repeat, Autoplay (similar titles at the end), gapless (about-to-finish), a 10-band equalizer and
the spectrum for Winamp's analyser. Listeners are told on every change (the window and MPRIS)."""
import random, re, threading
import gi
gi.require_version("Gst", "1.0")
from gi.repository import GLib, Gst
from .i18n import _

Gst.init(None)

REPEAT_OFF, REPEAT_ALL, REPEAT_ONE = 0, 1, 2
EQ_BANDS = [60, 170, 310, 600, 1000, 3000, 6000, 12000, 14000, 16000]


class Player:
    def __init__(self):
        self.server = None
        self.queue, self.order, self.index = [], [], -1
        self.shuffle, self.repeat, self.autoplay = False, REPEAT_OFF, True
        self.autoplayed = set()
        self.listeners = []
        self.spectrum = [0.0] * 75
        self.playbin = Gst.ElementFactory.make("playbin", "lidio")
        self.playbin.set_property("flags", 0x02 | 0x10)      # audio + soft volume, no video
        # equalizer → spectrum → sink
        bin_ = Gst.Bin.new("filter")
        self.eq = Gst.ElementFactory.make("equalizer-10bands", "eq")
        conv = Gst.ElementFactory.make("audioconvert", None)
        # Float samples from here on: Milkdrop reads them right after the spectrum (card ef3a3dfb).
        caps = Gst.ElementFactory.make("capsfilter", None)
        caps.set_property("caps", Gst.Caps.from_string("audio/x-raw,format=F32LE"))
        spec = Gst.ElementFactory.make("spectrum", "spectrum")
        spec.set_property("bands", 75); spec.set_property("interval", 50_000_000); spec.set_property("threshold", -80)
        spec.set_property("post-messages", True); spec.set_property("message-magnitude", True)
        for e in (self.eq, conv, caps, spec):
            bin_.add(e)
        self.eq.link(conv); conv.link(caps); caps.link(spec)
        self.pcm_listeners = []          # f(bytes of interleaved float32, channels) – only while Milkdrop is open
        spec.get_static_pad("src").add_probe(Gst.PadProbeType.BUFFER, self._pcm)
        bin_.add_pad(Gst.GhostPad.new("sink", self.eq.get_static_pad("sink")))
        bin_.add_pad(Gst.GhostPad.new("src", spec.get_static_pad("src")))
        self.playbin.set_property("audio-filter", bin_)
        self.playbin.connect("about-to-finish", self._about_to_finish)
        bus = self.playbin.get_bus()
        bus.add_signal_watch()
        bus.connect("message", self._message)
        self.playing = False
        self.error = None
        self.offline = None          # set by the application: a title on this computer plays from its file

    def uri(self, t):
        path = self.offline.path(t) if self.offline else None
        return GLib.filename_to_uri(path) if path else self.server.stream_url(t)

    # ---------- state ----------
    @property
    def current(self):
        return self.queue[self.order[self.index]] if 0 <= self.index < len(self.order) else None

    def position(self):
        ok, pos = self.playbin.query_position(Gst.Format.TIME)
        return pos / Gst.SECOND if ok else 0.0

    def length(self):
        ok, dur = self.playbin.query_duration(Gst.Format.TIME)
        return dur / Gst.SECOND if ok and dur > 0 else float(self.current.duration if self.current else 0)

    def _changed(self, what="state"):
        for f in list(self.listeners):
            f(what)

    # ---------- playing ----------
    def play(self, server, tracks, start=0, shuffled=False):
        if not tracks:
            return
        self.server, self.queue, self.autoplayed = server, list(tracks), set()
        self.shuffle = shuffled
        self._reorder(start)
        self._load()

    def _reorder(self, start):
        self.order = list(range(len(self.queue)))
        if self.shuffle:
            rest = [i for i in self.order if i != start]
            random.shuffle(rest)
            self.order = [start] + rest
            self.index = 0
        else:
            self.index = start

    def _load(self, play=True):
        t = self.current
        if not t:
            return
        self.playbin.set_state(Gst.State.NULL)
        self.playbin.set_property("uri", self.uri(t))
        self.playbin.set_state(Gst.State.PLAYING if play else Gst.State.PAUSED)
        self.playing, self.error = play, None
        threading.Thread(target=self.server.played, args=(t,), daemon=True).start()
        self._changed("track")
        self._maybe_autoplay()

    def toggle(self):
        if not self.current:
            return
        self.playing = not self.playing
        self.playbin.set_state(Gst.State.PLAYING if self.playing else Gst.State.PAUSED)
        self._changed()

    def pause(self):
        if self.playing:
            self.toggle()

    def resume(self):
        if not self.playing:
            self.toggle()

    def stop(self):
        self.playbin.set_state(Gst.State.NULL); self.playing = False; self._changed()

    def next(self):
        if self.index + 1 < len(self.order):
            self.index += 1
        elif self.repeat == REPEAT_ALL:
            self.index = 0
        else:
            return
        self._load(self.playing or True)

    def previous(self):
        if self.position() > 3 or self.index == 0:
            self.seek(0)
        else:
            self.index -= 1; self._load()

    def jump(self, queue_index):
        self.index = self.order.index(queue_index); self._load()

    def seek(self, seconds):
        self.playbin.seek_simple(Gst.Format.TIME, Gst.SeekFlags.FLUSH | Gst.SeekFlags.KEY_UNIT, int(seconds * Gst.SECOND))
        self._changed("position")

    def set_volume(self, v):
        self.playbin.set_property("volume", max(0.0, min(1.0, v))); self._changed("volume")

    def volume(self):
        return self.playbin.get_property("volume")

    def toggle_shuffle(self):
        cur = self.order[self.index] if self.current else 0
        self.shuffle = not self.shuffle
        self._reorder(cur)
        self._changed()

    def cycle_repeat(self):
        self.repeat = (self.repeat + 1) % 3; self._changed()

    def play_next(self, tracks):
        if not self.current:
            return self.play(self.server, tracks) if self.server else None
        at = len(self.queue)
        self.queue += tracks
        for k, _ in enumerate(tracks):
            self.order.insert(self.index + 1 + k, at + k)
        self._changed("queue")

    def add(self, tracks):
        at = len(self.queue)
        self.queue += tracks; self.order += list(range(at, at + len(tracks))); self._changed("queue")

    def remove(self, position):
        """position in the play order (after the current one)."""
        if position > self.index:
            del self.order[position]; self._changed("queue")

    def move(self, src, dst):
        if src > self.index and dst > self.index:
            self.order.insert(dst, self.order.pop(src)); self._changed("queue")

    def upcoming(self):
        return [(p, self.queue[i]) for p, i in enumerate(self.order) if p > self.index]

    def history(self):
        return [(p, self.queue[i]) for p, i in enumerate(self.order) if p < self.index]

    # ---------- equalizer ----------
    def set_eq(self, on, preamp, bands):
        for i, g in enumerate(bands):
            self.eq.set_property(f"band{i}", (g + preamp) if on else 0.0)

    # ---------- autoplay ----------
    def _maybe_autoplay(self):
        t = self.current
        if not (self.autoplay and t and self.repeat == REPEAT_OFF and self.index == len(self.order) - 1 and self.server):
            return
        known = {x.id for x in self.queue}

        def work():
            try:
                more = [x for x in self.server.similar(t, 25) if x.id not in known]
            except Exception:
                return
            if more:
                GLib.idle_add(lambda: (self.add(more), self.autoplayed.update(x.id for x in more), False)[-1])
        threading.Thread(target=work, daemon=True).start()

    # ---------- GStreamer ----------
    def _pcm(self, pad, info):
        if self.pcm_listeners:
            buf = info.get_buffer()
            caps = pad.get_current_caps()
            ch = caps.get_structure(0).get_value("channels") if caps else 2
            ok, m = buf.map(Gst.MapFlags.READ)
            if ok:
                data = bytes(m.data); buf.unmap(m)
                for f in list(self.pcm_listeners):
                    f(data, ch)
        return Gst.PadProbeReturn.OK

    def _about_to_finish(self, playbin):
        # Gapless: the next title's address goes in before this one ends.
        if self.repeat == REPEAT_ONE:
            playbin.set_property("uri", self.uri(self.current)); return
        nxt = self.index + 1 if self.index + 1 < len(self.order) else (0 if self.repeat == REPEAT_ALL else None)
        if nxt is None:
            return
        self.index = nxt
        playbin.set_property("uri", self.uri(self.current))
        GLib.idle_add(self._gapless_done)

    def _gapless_done(self):
        threading.Thread(target=self.server.played, args=(self.current,), daemon=True).start()
        self._changed("track"); self._maybe_autoplay()
        return False

    def _message(self, bus, msg):
        if msg.type == Gst.MessageType.EOS:
            self.playing = False; self.playbin.set_state(Gst.State.NULL); self._changed()
        elif msg.type == Gst.MessageType.ERROR:
            err, _dbg = msg.parse_error()
            self.error = _("Wiedergabe nicht möglich – ") + (_("keine Verbindung zum Server") if "Could not" in err.message or "resolve" in err.message else err.message)
            self.playing = False; self._changed("error")
        elif msg.type == Gst.MessageType.ELEMENT and msg.get_structure() and msg.get_structure().get_name() == "spectrum":
            # PyGObject can't hand out GstValueList – the numbers come from the structure's text form.
            text = msg.get_structure().to_string()
            part = text[text.find("magnitude"):]
            part = part[part.find("{") + 1:part.find("}")]
            mags = [float(x) for x in re.findall(r"-?\d+(?:\.\d+)?(?:e[-+]?\d+)?", part)]
            if mags:
                self.spectrum = [max(0.0, min(1.0, (m + 80) / 80)) for m in mags]
            self._changed("spectrum")
