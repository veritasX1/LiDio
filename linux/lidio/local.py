"""Folders on this computer as a source of their own (card 2d0c9407, like the Android app's "Auf diesem Gerät"): the chosen
folders are read with mutagen (title, artist, album, number, year, genre, cover), the index lives in ~/.local/share/lidio/lokal/;
a new scan reads only changed files. Playlists are M3U8 files in LiDio's own folder; favourites and plays are kept beside.
Same calls as a server, so every page works the same."""
import hashlib, json, os, random, threading, time, urllib.parse

from gi.repository import GLib

from .servers import Album, Artist, Genre, Playlist, SearchResult, Track, Sharing, ServerError
from .i18n import _

ROOT = os.path.join(os.environ.get("XDG_DATA_HOME") or os.path.expanduser("~/.local/share"), "lidio", "lokal")
AUDIO = {".mp3", ".flac", ".m4a", ".aac", ".ogg", ".oga", ".opus", ".wav", ".wma", ".aiff", ".aif", ".alac", ".ape", ".wv", ".mka", ".webm"}
PICTURES = ("cover.jpg", "folder.jpg", "front.jpg", "cover.png", "folder.png", "album.jpg")


def _id(text):
    return hashlib.md5(text.encode()).hexdigest()[:16]


def read_tags(path):
    """(title, artist, album artist, album, number, disc, year, genre, seconds, picture bytes) – None when not readable."""
    import mutagen
    try:
        f = mutagen.File(path)
    except Exception:          # noqa: BLE001 – broken files are left out
        return None
    if f is None:
        return None
    seconds = int(getattr(f.info, "length", 0) or 0)
    tags = f.tags or {}

    def get(*keys):
        for k in keys:
            try:
                v = tags.get(k)
            except (ValueError, KeyError):
                v = None
            if v:
                v = v[0] if isinstance(v, list) else v
                v = getattr(v, "text", [v])[0] if hasattr(v, "text") else v
                s = str(v).strip()
                if s:
                    return s
        return None

    def num(*keys):
        v = get(*keys)
        if isinstance(v, str):
            v = v.split("/")[0].strip()
        try:
            return int(v) if v else None
        except ValueError:
            return None
    picture = None
    try:
        if hasattr(f, "pictures") and f.pictures:
            picture = f.pictures[0].data
        elif tags:
            for k in list(tags.keys()):
                if k.startswith("APIC"):
                    picture = tags[k].data; break
                if k == "covr":
                    picture = bytes(tags[k][0]); break
    except Exception:          # noqa: BLE001
        picture = None
    year = get("TDRC", "date", "DATE", "\xa9day", "YEAR", "year")
    return (get("TIT2", "title", "TITLE", "\xa9nam"), get("TPE1", "artist", "ARTIST", "\xa9ART"),
            get("TPE2", "albumartist", "ALBUMARTIST", "album artist", "aART"), get("TALB", "album", "ALBUM", "\xa9alb"),
            num("TRCK", "tracknumber", "TRACKNUMBER", "trkn"), num("TPOS", "discnumber", "DISCNUMBER", "disk"),
            int(str(year)[:4]) if year and str(year)[:4].isdigit() else None, get("TCON", "genre", "GENRE", "\xa9gen"), seconds, picture)


class LocalLibrary:
    kind = "local"

    def __init__(self, account_id, folders):
        self.account_id, self.folders = account_id, [f for f in folders if f]
        self.dir = os.path.join(ROOT, account_id)
        self.covers = os.path.join(self.dir, "cover")
        self.lists = os.path.join(self.dir, "playlists")
        for d in (self.dir, self.covers, self.lists):
            os.makedirs(d, exist_ok=True)
        self._index = None
        self._lock = threading.Lock()
        self.address = ", ".join(self.folders)

    # ---------- index ----------
    def _file(self, name):
        return os.path.join(self.dir, name)

    def _json(self, name, default):
        try:
            with open(self._file(name)) as f:
                return json.load(f)
        except (OSError, ValueError):
            return default

    def _store(self, name, data):
        tmp = self._file(name + ".neu")
        with open(tmp, "w") as f:
            json.dump(data, f)
        os.replace(tmp, self._file(name))

    def scan(self, progress=None):
        """Reads new and changed files; keeps what is unchanged."""
        old = self._json("index.json", {})
        new, n = {}, 0
        for folder in self.folders:
            for root, _, files in os.walk(folder):
                pictures = [p for p in PICTURES if p in {x.lower() for x in files}]
                for name in files:
                    if os.path.splitext(name)[1].lower() not in AUDIO:
                        continue
                    path = os.path.join(root, name)
                    try:
                        mtime = int(os.path.getmtime(path))
                    except OSError:
                        continue
                    tid = _id(path)
                    e = old.get(tid)
                    if not e or e.get("mtime") != mtime:
                        t = read_tags(path)
                        if not t:
                            continue
                        title, artist, aartist, album, number, disc, year, genre, seconds, picture = t
                        cover = None
                        if picture:
                            cover = _id(album or path)
                            cp = os.path.join(self.covers, cover)
                            if not os.path.exists(cp):
                                with open(cp, "wb") as f:
                                    f.write(picture)
                        elif pictures:
                            real = next(x for x in files if x.lower() == pictures[0])
                            cover = "datei:" + os.path.join(root, real)
                        e = {"path": path, "mtime": mtime, "title": title or os.path.splitext(name)[0], "artist": artist or aartist or _("Unbekannt"),
                             "albumartist": aartist or artist or _("Unbekannt"), "album": album or os.path.basename(root), "number": number, "disc": disc,
                             "year": year, "genre": genre, "seconds": seconds, "cover": cover}
                    new[tid] = e
                    n += 1
                    if progress and n % 50 == 0:
                        progress(n)
        self._store("index.json", new)
        with self._lock:
            self._index = new
        return len(new)

    def _all(self):
        with self._lock:
            if self._index is None:
                self._index = self._json("index.json", {})
                if not self._index:
                    pass
            return self._index

    def _track(self, tid, e):
        favs = self._json("favoriten.json", [])
        return Track(tid, e["title"], e["artist"], e["album"], _id(e["albumartist"].lower() + "|" + e["album"].lower()), e.get("seconds") or 0,
                     e.get("number"), e.get("disc"), e.get("year"), e.get("cover"), tid in favs)

    def _tracks(self):
        return [self._track(k, e) for k, e in self._all().items()]

    def _albums(self):
        groups = {}
        for k, e in self._all().items():
            aid = _id(e["albumartist"].lower() + "|" + e["album"].lower())
            g = groups.setdefault(aid, {"e": e, "ids": [], "mtime": 0})
            g["ids"].append(k); g["mtime"] = max(g["mtime"], e.get("mtime", 0))
        return groups

    def _album(self, aid, g):
        e = g["e"]
        return Album(aid, e["album"], e["albumartist"], _id(e["albumartist"].lower()), e.get("year"), e.get("cover"), e.get("genre"))

    # ---------- the server calls ----------
    def check(self):
        if not self._all():
            self.scan()
        return _("Auf diesem Computer · {count} Titel", count=len(self._all()))

    def albums(self, order="newest", size=100, offset=0):
        groups = self._albums()
        plays = self._json("gespielt.json", {})
        items = list(groups.items())
        key = {"newest": lambda x: -x[1]["mtime"], "title": lambda x: x[1]["e"]["album"].lower(),
               "artist": lambda x: (x[1]["e"]["albumartist"].lower(), x[1]["e"]["album"].lower()),
               "year": lambda x: -(x[1]["e"].get("year") or 0),
               "recent": lambda x: -max((plays.get(i, {}).get("last", 0) for i in x[1]["ids"]), default=0),
               "frequent": lambda x: -sum(plays.get(i, {}).get("count", 0) for i in x[1]["ids"])}[order]
        items.sort(key=key)
        if order in ("recent", "frequent"):
            items = [x for x in items if any(i in plays for i in x[1]["ids"])]
        return [self._album(a, g) for a, g in items[offset:offset + size]]

    def album(self, album_id):
        g = self._albums().get(album_id)
        if not g:
            raise ServerError(_("Dieses Album gibt es hier nicht mehr."))
        idx = self._all()
        tracks = sorted((self._track(i, idx[i]) for i in g["ids"]), key=lambda t: (t.disc or 0, t.number or 0, t.title.lower()))
        return self._album(album_id, g), tracks

    def artists(self):
        names = {}
        for e in self._all().values():
            names.setdefault(_id(e["albumartist"].lower()), e["albumartist"])
        return sorted((Artist(k, v) for k, v in names.items()), key=lambda a: a.name.lower())

    def artist(self, artist_id):
        albums = [self._album(a, g) for a, g in self._albums().items() if _id(g["e"]["albumartist"].lower()) == artist_id]
        name = albums[0].artist if albums else _("Unbekannt")
        return Artist(artist_id, name), sorted(albums, key=lambda a: -(a.year or 0))

    def tracks(self, size=500, offset=0):
        return sorted(self._tracks(), key=lambda t: t.title.lower())[offset:offset + size]

    def search(self, query):
        q = query.lower()
        tracks = [t for t in self._tracks() if q in t.title.lower() or q in t.artist.lower() or q in t.album.lower()][:60]
        albums = [self._album(a, g) for a, g in self._albums().items() if q in g["e"]["album"].lower() or q in g["e"]["albumartist"].lower()][:24]
        artists = [a for a in self.artists() if q in a.name.lower()][:12]
        return SearchResult(artists, albums, tracks, [p for p in self.playlists() if q in p.name.lower()])

    def stream_url(self, track):
        e = self._all().get(track.id)
        if not e:
            raise ServerError(_("Die Datei ist nicht mehr da."))
        return GLib.filename_to_uri(e["path"])

    def download_url(self, track):
        return self.stream_url(track)

    def cover_url(self, cover_id, size=300):
        if not cover_id:
            return None
        path = cover_id[6:] if cover_id.startswith("datei:") else os.path.join(self.covers, cover_id)
        return GLib.filename_to_uri(path) if os.path.exists(path) else None

    def album_cover(self, album_id, size=300):
        return None

    def played(self, track):
        plays = self._json("gespielt.json", {})
        p = plays.setdefault(track.id, {"count": 0, "last": 0}); p["count"] += 1; p["last"] = int(time.time())
        self._store("gespielt.json", plays)

    # ---------- favourites, mixes, genres ----------
    def favorites(self):
        favs = set(self._json("favoriten.json", []))
        return [t for t in self._tracks() if t.id in favs]

    def set_favorite(self, track, on):
        favs = [f for f in self._json("favoriten.json", []) if f != track.id] + ([track.id] if on else [])
        self._store("favoriten.json", favs)

    def similar(self, track, count=30):
        e = self._all().get(track.id, {})
        same = [t for k, t in ((k, x) for k, x in self._all().items()) if k != track.id and (t.get("genre") == e.get("genre") or t["artist"] == e.get("artist"))]
        random.shuffle(same)
        return [self._track(_id(t["path"]), t) for t in same[:count]]

    def discover(self, count=50):
        plays = self._json("gespielt.json", {})
        fresh = [t for t in self._tracks() if t.id not in plays]
        random.shuffle(fresh)
        return fresh[:count]

    def genres(self):
        count = {}
        for e in self._all().values():
            if e.get("genre"):
                count[e["genre"]] = count.get(e["genre"], 0) + 1
        return [Genre(g, g) for g, _ in sorted(count.items(), key=lambda x: -x[1])[:24]]

    def genre_albums(self, genre):
        return [self._album(a, g) for a, g in self._albums().items() if g["e"].get("genre") == genre.id]

    # ---------- playlists: M3U8 files ----------
    def _pl_path(self, pid):
        return os.path.join(self.lists, pid + ".m3u8")

    def playlists(self):
        out = []
        for f in sorted(os.listdir(self.lists)):
            if f.endswith(".m3u8"):
                pid = f[:-5]
                name, ids = self._read_pl(pid)
                out.append(Playlist(pid, name, len(ids)))
        return sorted(out, key=lambda p: p.name.lower())

    def _read_pl(self, pid):
        name, paths = pid, []
        try:
            with open(self._pl_path(pid), encoding="utf-8") as f:
                for line in f:
                    line = line.strip()
                    if line.startswith("#PLAYLIST:"):
                        name = line[10:].strip()
                    elif line and not line.startswith("#"):
                        paths.append(line)
        except OSError:
            pass
        return name, [_id(p) for p in paths]

    def _write_pl(self, pid, name, ids):
        idx = self._all()
        with open(self._pl_path(pid), "w", encoding="utf-8") as f:
            f.write(f"#EXTM3U\n#PLAYLIST:{name}\n")
            for i in ids:
                e = idx.get(i)
                if e:
                    f.write(f"#EXTINF:{e.get('seconds') or 0},{e['artist']} - {e['title']}\n{e['path']}\n")

    def playlist(self, playlist_id):
        name, ids = self._read_pl(playlist_id)
        idx = self._all()
        tracks = []
        for n, i in enumerate(ids):
            if i in idx:
                t = self._track(i, idx[i]); t.entry_id = str(n); tracks.append(t)
        return Playlist(playlist_id, name, len(tracks)), tracks

    def create_playlist(self, name, tracks):
        pid = _id(name + str(time.time()))
        self._write_pl(pid, name, [t.id for t in tracks])
        return Playlist(pid, name, len(tracks))

    def add_to_playlist(self, playlist_id, tracks, ids=None):
        name, have = self._read_pl(playlist_id)
        self._write_pl(playlist_id, name, have + (ids or [t.id for t in tracks]))

    def remove_from_playlist(self, playlist_id, tracks):
        name, have = self._read_pl(playlist_id)
        drop = {int(t.entry_id) for t in tracks if getattr(t, "entry_id", None) is not None}
        self._write_pl(playlist_id, name, [i for n, i in enumerate(have) if n not in drop])

    def rename_playlist(self, playlist_id, name):
        _, have = self._read_pl(playlist_id); self._write_pl(playlist_id, name, have)

    def delete_playlist(self, playlist_id):
        os.remove(self._pl_path(playlist_id))

    def note_missing(self, playlist_id, missing):
        pass

    def wait_filled(self, playlist_id, count, seconds=0):
        return True

    def set_playlist_cover(self, playlist_id, jpeg):
        pass

    def sharing(self, playlist_id):
        return None

    def share(self, playlist_id, users, everyone):
        raise ServerError(_("Ordner auf diesem Computer lassen sich nicht freigeben."))

    def server_id(self):
        return ""

    def track(self, track_id):
        e = self._all().get(track_id)
        return self._track(track_id, e) if e else None
