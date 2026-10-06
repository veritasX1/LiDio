"""Music on this computer (like the Android app's Offline, cards 39768ef4, 2f209192, 2aaf09ce): the original files of titles,
albums and playlists, one after the other (Olaf: never all at once), each with its progress. Files and the list of what is
there live in ~/.local/share/lidio/offline/<account>/; the player takes the file instead of the stream when there is one."""
import json, os, re, threading, urllib.request
from collections import OrderedDict
from dataclasses import asdict

from gi.repository import GLib

from .servers import Track

ROOT = os.path.join(os.environ.get("XDG_DATA_HOME") or os.path.expanduser("~/.local/share"), "lidio", "offline")
TYPES = {"audio/flac": "flac", "audio/x-flac": "flac", "audio/mpeg": "mp3", "audio/mp4": "m4a", "audio/x-m4a": "m4a", "audio/ogg": "ogg",
         "audio/opus": "opus", "audio/wav": "wav", "audio/x-wav": "wav", "audio/aac": "aac", "audio/webm": "webm"}


class Offline:
    def __init__(self):
        self.account, self.server = None, None
        self.index = {}                      # track id → {"file", "track": {…}, "size"}
        self.waiting = OrderedDict()         # track id → Track, in order
        self.live = {}                       # track id → progress 0…1 (only the one loading)
        self.listeners = []
        self._stop = threading.Event()
        self._lock = threading.Lock()
        self._worker = None

    # ---------- account ----------
    def use(self, account, server):
        if self.account and account and self.account["id"] != account["id"]:
            self.cancel()
        self.account, self.server = account, server
        self.index = self._read()
        self._changed()

    @property
    def folder(self):
        return os.path.join(ROOT, re.sub(r"[^\w-]", "_", self.account["id"])) if self.account else None

    def _read(self):
        try:
            with open(os.path.join(self.folder, "index.json")) as f:
                data = json.load(f)
            return {k: v for k, v in data.items() if os.path.exists(os.path.join(self.folder, v["file"]))}
        except (OSError, ValueError, TypeError):
            return {}

    def _write(self):
        os.makedirs(self.folder, exist_ok=True)
        tmp = os.path.join(self.folder, "index.json.neu")
        with open(tmp, "w") as f:
            json.dump(self.index, f)
        os.replace(tmp, os.path.join(self.folder, "index.json"))

    # ---------- asking ----------
    def has(self, track):
        return track.id in self.index or getattr(self.server, "kind", "") == "local"

    # LiDio privat (card f436a1f7): a server title whose song was loaded from the net onto this computer – its file.
    extra = None

    def path(self, track):
        e = self.index.get(track.id)
        if e:
            return os.path.join(self.folder, e["file"])
        return self.extra(track) if self.extra else None

    def state(self, track):
        """local | loading | waiting | None (only on the server)."""
        if track.id in self.index or getattr(self.server, "kind", "") == "local" or (self.extra and self.extra(track)):
            return "local"
        if track.id in self.live:
            return "loading"
        if track.id in self.waiting:
            return "waiting"
        return None

    def tracks(self):
        out = []
        for e in self.index.values():
            try:
                out.append(Track(**e["track"]))
            except TypeError:
                pass
        return sorted(out, key=lambda t: (t.artist.lower(), t.album.lower(), t.disc or 0, t.number or 0, t.title.lower()))

    def size(self):
        return sum(e.get("size", 0) for e in self.index.values())

    def busy(self):
        return bool(self.waiting or self.live)

    # ---------- doing ----------
    def download(self, tracks):
        added = False
        with self._lock:
            for t in tracks:
                if t.id not in self.index and t.id not in self.waiting and t.id not in self.live:
                    self.waiting[t.id] = t; added = True
        if added:
            self._changed()
        if not (self._worker and self._worker.is_alive()):
            self._stop.clear()
            self._worker = threading.Thread(target=self._work, daemon=True); self._worker.start()

    def cancel(self):
        with self._lock:
            self.waiting.clear()
        self._stop.set()
        self._changed()

    def remove(self, tracks):
        for t in tracks:
            e = self.index.pop(t.id, None)
            if e:
                try:
                    os.remove(os.path.join(self.folder, e["file"]))
                except OSError:
                    pass
        self._write(); self._changed()

    def _work(self):
        while not self._stop.is_set():
            with self._lock:
                if not self.waiting:
                    break
                tid, t = self.waiting.popitem(last=False)
            self.live[tid] = 0.0
            GLib.idle_add(self._changed_idle)
            try:
                self._fetch(t)
            except Exception:       # noqa: BLE001 – one failed title must not stop the rest
                pass
            self.live.pop(tid, None)
            GLib.idle_add(self._changed_idle)
        self.live.clear()

    def _fetch(self, t):
        server, folder = self.server, self.folder
        req = urllib.request.Request(server.download_url(t))
        with urllib.request.urlopen(req, timeout=30) as r:
            total = int(r.headers.get("Content-Length") or 0)
            disp = r.headers.get("Content-Disposition") or ""
            m = re.search(r'filename\*?=(?:UTF-8\'\')?"?([^";]+)', disp)
            ext = (m.group(1).rsplit(".", 1)[-1].lower() if m and "." in m.group(1) else
                   TYPES.get((r.headers.get("Content-Type") or "").split(";")[0].strip(), "audio"))
            name = f"{re.sub(r'[^\w-]', '_', t.id)}.{ext}"
            os.makedirs(folder, exist_ok=True)
            part = os.path.join(folder, name + ".teil")
            done, last = 0, 0.0
            with open(part, "wb") as f:
                while True:
                    if self._stop.is_set():
                        f.close(); os.remove(part); return
                    chunk = r.read(256 * 1024)
                    if not chunk:
                        break
                    f.write(chunk); done += len(chunk)
                    if total:
                        p = done / total
                        self.live[t.id] = p
                        if p - last > 0.02:
                            last = p; GLib.idle_add(self._changed_idle)
        os.replace(part, os.path.join(folder, name))
        self.index[t.id] = {"file": name, "size": done, "track": asdict(t)}
        self._write()

    def _changed_idle(self):
        self._changed(); return False

    def _changed(self):
        for f in list(self.listeners):
            f()
