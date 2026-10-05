"""LiDio for Ubuntu – the music servers (card b1768b44): Emby, Jellyfin (the same "MediaBrowser" API) and Navidrome (Subsonic).
The same few things for all three, like the Android app's MusicServer, so the window never asks which one it talks to.
Every call blocks – the window runs them in a thread."""
import base64, hashlib, json, random, string, urllib.parse, urllib.request, uuid
from dataclasses import dataclass, field

CLIENT, VERSION = "LiDio", "0.1"


class ServerError(Exception):
    pass


@dataclass
class Track:
    id: str
    title: str
    artist: str
    album: str = ""
    album_id: str | None = None
    duration: int = 0
    number: int | None = None
    disc: int | None = None
    year: int | None = None
    cover_id: str | None = None
    favorite: bool = False


@dataclass
class Album:
    id: str
    title: str
    artist: str
    artist_id: str | None = None
    year: int | None = None
    cover_id: str | None = None
    genre: str | None = None


@dataclass
class Artist:
    id: str
    name: str
    cover_id: str | None = None


@dataclass
class Playlist:
    id: str
    name: str
    count: int = 0
    cover_id: str | None = None
    missing: list = field(default_factory=list)   # "Interpret – Titel" the server lacks, from the description (like Android)
    origin: str | None = None                     # Deezer/Spotify link the playlist came from


@dataclass
class Genre:
    id: str
    name: str


@dataclass
class ShareUser:
    id: str
    name: str
    level: str          # none | read | write


@dataclass
class Sharing:
    users: list
    everyone: bool
    per_user: bool
    can_manage: bool = True


class MissingNote:
    """What a playlist lacks lives in its description on the server (Emby/Jellyfin "Overview", Navidrome "comment") – the same
    block the Android app writes: a heading line, then one "♪ " line each."""
    HEADING = "Fehlt auf dem Server (LiDio):"
    ORIGIN = "Quelle: "

    @classmethod
    def read(cls, text):
        if not text or cls.HEADING not in text:
            return []
        return [l.strip()[2:].strip() for l in text.split(cls.HEADING, 1)[1].splitlines() if l.strip().startswith("♪ ") and l.strip()[2:].strip()]

    @classmethod
    def write(cls, text, missing):
        rest = (text or "").split(cls.HEADING, 1)[0].strip()
        return rest if not missing else (rest + "\n\n" + cls.HEADING + "\n" + "\n".join("♪ " + m for m in missing)).strip()

    @classmethod
    def origin(cls, text):
        return next((l.strip()[len(cls.ORIGIN):].strip() for l in (text or "").splitlines() if l.strip().startswith(cls.ORIGIN)), None)

    @classmethod
    def with_origin(cls, text, origin):
        lines = [l for l in (text or "").splitlines() if not l.strip().startswith(cls.ORIGIN)]
        return "\n".join([cls.ORIGIN + origin] + lines).strip()


@dataclass
class SearchResult:
    artists: list = field(default_factory=list)
    albums: list = field(default_factory=list)
    tracks: list = field(default_factory=list)
    playlists: list = field(default_factory=list)


def _request(url, headers=None, data=None, method=None, timeout=20):
    body = json.dumps(data).encode() if isinstance(data, (dict, list)) else data
    req = urllib.request.Request(url, data=body, method=method or ("POST" if body is not None else "GET"),
                                 headers={"Accept": "application/json", "Content-Type": "application/json", **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read()
            return json.loads(raw) if raw[:1] in (b"{", b"[") else raw
    except urllib.error.HTTPError as e:
        if e.code in (401, 403):
            raise ServerError("Anmeldung abgelehnt – Benutzername oder Passwort stimmen nicht.") from e
        raise ServerError(f"Der Server antwortet mit Fehler {e.code}.") from e
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        raise ServerError("Der Server ist nicht erreichbar.") from e


def normal(address):
    a = address.strip().rstrip("/")
    return a if not a or "://" in a else "http://" + a


class MediaBrowser:
    """Emby and Jellyfin. Emby lives under /emby and takes X-Emby-Token; Jellyfin under / with "Authorization: MediaBrowser …"."""
    # Lists stay light: title counts, paths and media data cost Emby seconds (measured on Olaf's server, 05.10.2026).
    TRACK_FIELDS = "ParentIndexNumber,IndexNumber,ProductionYear,AlbumArtist"
    ALBUM_FIELDS = "ProductionYear,Genres,AlbumArtist,AlbumArtists"

    def __init__(self, kind, address, user_id="", token="", device=""):
        self.kind, self.address, self.user_id, self.token = kind, normal(address), user_id, token
        self.device = device or str(uuid.uuid4())
        self.base = self.address + ("/emby" if kind == "emby" and not self.address.endswith("/emby") else "")

    def _auth(self):
        info = f'MediaBrowser Client="{CLIENT}", Device="Ubuntu", DeviceId="{self.device}", Version="{VERSION}"'
        if self.token:
            info += f', Token="{self.token}"'
        h = {"X-Emby-Authorization": info, "Authorization": info}
        if self.token:
            h["X-Emby-Token"] = self.token
        return h

    def _get(self, path, **params):
        q = urllib.parse.urlencode({k: v for k, v in params.items() if v is not None})
        return _request(f"{self.base}{path}{'?' + q if q else ''}", self._auth())

    def login(self, user, password):
        r = _request(f"{self.base}/Users/AuthenticateByName", self._auth(), {"Username": user, "Pw": password})
        self.token, self.user_id = r["AccessToken"], r["User"]["Id"]
        return self.token, self.user_id

    def check(self):
        info = self._get("/System/Info")
        return f"{'Emby' if self.kind == 'emby' else 'Jellyfin'} {info.get('Version', '')}".strip()

    def _items(self, **params):
        return self._get(f"/Users/{self.user_id}/Items", Recursive="true", **params).get("Items", [])

    @staticmethod
    def _track(j):
        artists = j.get("Artists") or []
        cover = j["Id"] if "Primary" in (j.get("ImageTags") or {}) else (j.get("AlbumId") if j.get("AlbumPrimaryImageTag") else None)
        return Track(j["Id"], j.get("Name") or "Unbekannt", ", ".join(artists) or j.get("AlbumArtist") or "Unbekannt", j.get("Album") or "",
                     j.get("AlbumId"), int((j.get("RunTimeTicks") or 0) / 10_000_000), j.get("IndexNumber"), j.get("ParentIndexNumber"),
                     j.get("ProductionYear"), cover, bool((j.get("UserData") or {}).get("IsFavorite")))

    @staticmethod
    def _album(j):
        aa = (j.get("AlbumArtists") or [{}])[0]
        return Album(j["Id"], j.get("Name") or "Unbekanntes Album", j.get("AlbumArtist") or aa.get("Name") or "Unbekannt", aa.get("Id"),
                     j.get("ProductionYear"), j["Id"] if "Primary" in (j.get("ImageTags") or {}) else None, (j.get("Genres") or [None])[0])

    def albums(self, order="newest", size=100, offset=0):
        sort = {"newest": ("DateCreated", "Descending"), "recent": ("DatePlayed", "Descending"), "title": ("SortName", "Ascending"),
                "frequent": ("PlayCount", "Descending"), "artist": ("AlbumArtist,SortName", "Ascending"), "year": ("ProductionYear,SortName", "Descending")}[order]
        return [self._album(j) for j in self._items(IncludeItemTypes="MusicAlbum", Fields=self.ALBUM_FIELDS, SortBy=sort[0], SortOrder=sort[1],
                                                    Limit=size, StartIndex=offset)]

    def album(self, album_id):
        a = self._album(self._get(f"/Users/{self.user_id}/Items/{album_id}"))
        tracks = [self._track(j) for j in self._items(ParentId=album_id, IncludeItemTypes="Audio", Fields=self.TRACK_FIELDS,
                                                       SortBy="ParentIndexNumber,IndexNumber,SortName")]
        return a, tracks

    def artists(self):
        r = self._get("/Artists/AlbumArtists", UserId=self.user_id, Recursive="true", SortBy="SortName")
        return [Artist(j["Id"], j.get("Name") or "?", j["Id"] if "Primary" in (j.get("ImageTags") or {}) else None) for j in r.get("Items", [])]

    def artist(self, artist_id):
        a = self._get(f"/Users/{self.user_id}/Items/{artist_id}")
        albums = [self._album(j) for j in self._items(IncludeItemTypes="MusicAlbum", AlbumArtistIds=artist_id, Fields=self.ALBUM_FIELDS,
                                                       SortBy="ProductionYear,SortName", SortOrder="Descending")]
        return Artist(a["Id"], a.get("Name") or "?", a["Id"] if "Primary" in (a.get("ImageTags") or {}) else None), albums

    def tracks(self, size=500, offset=0):
        return [self._track(j) for j in self._items(IncludeItemTypes="Audio", Fields=self.TRACK_FIELDS, SortBy="SortName", Limit=size, StartIndex=offset)]

    def playlists(self):
        views = self._get(f"/Users/{self.user_id}/Views").get("Items", [])
        folder = next((v["Id"] for v in views if v.get("CollectionType") == "playlists"), None)
        items = self._items(ParentId=folder, SortBy="SortName", Fields="MediaType,Overview") if folder else \
            self._items(IncludeItemTypes="Playlist", SortBy="SortName", Fields="MediaType,Overview")
        return [self._playlist(j) for j in items if j.get("MediaType") != "Video" and j.get("Type", "Playlist") == "Playlist"]

    @staticmethod
    def _playlist(j, count=0):
        return Playlist(j["Id"], j.get("Name") or "Playlist", count, j["Id"] if "Primary" in (j.get("ImageTags") or {}) else None,
                        MissingNote.read(j.get("Overview")), MissingNote.origin(j.get("Overview")))

    def playlist(self, playlist_id):
        p = self._get(f"/Users/{self.user_id}/Items/{playlist_id}")
        items = self._get(f"/Playlists/{playlist_id}/Items", UserId=self.user_id, Fields=self.TRACK_FIELDS).get("Items", [])
        tracks = []
        for j in items:
            t = self._track(j); t.entry_id = j.get("PlaylistItemId"); tracks.append(t)
        return self._playlist(p, len(tracks)), tracks

    # ---------- playlists: create, add, remove, rename, delete, share ----------
    def create_playlist(self, name, tracks):
        ids = [t.id for t in tracks]
        first, rest = ids[:100], [ids[i:i + 100] for i in range(100, len(ids), 100)]
        q = urllib.parse.urlencode({"Name": name, "Ids": ",".join(first), "UserId": self.user_id, "MediaType": "Audio"})
        r = _request(f"{self.base}/Playlists?{q}", self._auth(), {"Name": name, "Ids": first, "UserId": self.user_id, "MediaType": "Audio"})
        pid = (r or {}).get("Id") if isinstance(r, dict) else None
        if not pid:
            raise ServerError("Die Playlist wurde nicht angelegt.")
        for chunk in rest:
            self.add_to_playlist(pid, [], chunk)
        return Playlist(pid, name, len(ids))

    def add_to_playlist(self, playlist_id, tracks, ids=None):
        ids = ids or [t.id for t in tracks]
        for i in range(0, len(ids), 100):
            _request(f"{self.base}/Playlists/{playlist_id}/Items?Ids={','.join(ids[i:i + 100])}&UserId={self.user_id}", self._auth(), b"{}")

    def remove_from_playlist(self, playlist_id, tracks):
        # Emby/Jellyfin remove by the entry id – the same title twice stays once (card 29c6affc).
        entries = [t.entry_id for t in tracks if getattr(t, "entry_id", None)]
        if entries:
            _request(f"{self.base}/Playlists/{playlist_id}/Items?EntryIds={','.join(entries)}", self._auth(), None, "DELETE")

    def rename_playlist(self, playlist_id, name):
        # Emby writes its own playlist files from the database – only ever change them through the API (lesson of 05.10.2026).
        item = self._get(f"/Users/{self.user_id}/Items/{playlist_id}")
        item["Name"] = name
        _request(f"{self.base}/Items/{playlist_id}", self._auth(), item)

    def delete_playlist(self, playlist_id):
        _request(f"{self.base}/Items/{playlist_id}", self._auth(), None, "DELETE")

    def track(self, track_id):
        try:
            return self._track(self._get(f"/Users/{self.user_id}/Items/{track_id}", Fields=self.TRACK_FIELDS))
        except (ServerError, KeyError):
            return None

    def wait_filled(self, playlist_id, count, seconds=40):
        """Emby writes a playlist's file from its database whenever the item is saved – save description or picture only once
        it has all titles, or it writes the playlist back empty (lesson of 05.10.2026)."""
        import time
        end = time.time() + seconds
        while time.time() < end:
            n = (self._get(f"/Users/{self.user_id}/Items/{playlist_id}", Fields="ChildCount").get("ChildCount") or 0)
            if n >= count:
                return True
            time.sleep(1.5)
        return False

    def set_playlist_cover(self, playlist_id, jpeg):
        _request(f"{self.base}/Items/{playlist_id}/Images/Primary", {**self._auth(), "Content-Type": "image/jpeg"}, base64.b64encode(jpeg))

    def note_missing(self, playlist_id, missing):
        item = self._get(f"/Users/{self.user_id}/Items/{playlist_id}")
        item["Overview"] = MissingNote.write(item.get("Overview"), missing)
        locked = item.get("LockedFields") or []
        if "Overview" not in locked:
            locked.append("Overview")
        item["LockedFields"] = locked
        _request(f"{self.base}/Items/{playlist_id}", self._auth(), item)

    def sharing(self, playlist_id):
        """Emby 4.9: access per item; Jellyfin 10.9: the playlist's Shares + OpenAccess."""
        if self.kind == "emby":
            everyone = self._get("/Users/ItemAccess", ItemId=playlist_id).get("Items", [])
            me = next((u.get("UserItemShareLevel") for u in everyone if u.get("Id") == self.user_id), "None") or "None"
            users = [ShareUser(u["Id"], u.get("Name") or "?", {"Read": "read", "None": "none", "": "none"}.get(u.get("UserItemShareLevel") or "", "write"))
                     for u in everyone if u.get("Id") != self.user_id]
            return Sharing(users, bool(users) and all(u.level != "none" for u in users), True, me.startswith("Manage"))
        p = self._get(f"/Playlists/{playlist_id}")
        shares = {s.get("UserId"): ("write" if s.get("CanEdit") else "read") for s in p.get("Shares") or []}
        try:
            people = _request(f"{self.base}/Users", self._auth())
        except ServerError:
            people = _request(f"{self.base}/Users/Public", self._auth())
        return Sharing([ShareUser(u["Id"], u.get("Name") or "?", shares.get(u["Id"], "none")) for u in people if u["Id"] != self.user_id],
                       bool(p.get("OpenAccess")), True)

    def share(self, playlist_id, users, everyone):
        """users: {user id: none|read|write}."""
        if self.kind == "emby":
            wanted = {k: ("read" if everyone and v == "none" else v) for k, v in users.items()}
            for level in set(wanted.values()):
                ids = [k for k, v in wanted.items() if v == level]
                _request(f"{self.base}/Items/Access", self._auth(), {"ItemIds": [playlist_id], "UserIds": ids,
                                                                      "ItemAccess": {"read": "Read", "write": "Write"}.get(level, "None")})
        else:
            _request(f"{self.base}/Playlists/{playlist_id}", self._auth(),
                     {"OpenAccess": everyone, "Users": [{"UserId": k, "CanEdit": v == "write"} for k, v in users.items() if v != "none"]})

    # ---------- start page ----------
    def discover(self, count=50):
        return [self._track(j) for j in self._items(IncludeItemTypes="Audio", Filters="IsUnplayed", SortBy="Random", Limit=count, Fields=self.TRACK_FIELDS)]

    def genres(self):
        return [Genre(j["Id"], j.get("Name") or "") for j in self._get("/MusicGenres", UserId=self.user_id, Recursive="true", Limit=24).get("Items", [])]

    def genre_albums(self, genre):
        return [self._album(j) for j in self._items(IncludeItemTypes="MusicAlbum", Fields=self.ALBUM_FIELDS, GenreIds=genre.id, SortBy="DateCreated",
                                                    SortOrder="Descending", Limit=200)]

    def download_url(self, track):
        """The original file, for keeping it on this computer."""
        return f"{self.base}/Items/{track.id}/Download?{'api_key' if self.kind == 'emby' else 'ApiKey'}={self.token}"

    def server_id(self):
        try:
            return _request(f"{self.base}/System/Info/Public").get("Id", "")
        except ServerError:
            return ""

    def favorites(self):
        return [self._track(j) for j in self._items(IncludeItemTypes="Audio", Filters="IsFavorite", Fields=self.TRACK_FIELDS, SortBy="SortName")]

    def set_favorite(self, track, on):
        _request(f"{self.base}/Users/{self.user_id}/FavoriteItems/{track.id}", self._auth(), b"" if on else None, "POST" if on else "DELETE")

    def similar(self, track, count=30):
        return [self._track(j) for j in self._get(f"/Items/{track.id}/InstantMix", UserId=self.user_id, Limit=count, Fields=self.TRACK_FIELDS)
                .get("Items", []) if j["Id"] != track.id]

    def search(self, query):
        artists = [Artist(j["Id"], j.get("Name") or "?", j["Id"] if "Primary" in (j.get("ImageTags") or {}) else None)
                   for j in self._get("/Artists/AlbumArtists", UserId=self.user_id, SearchTerm=query, Limit=12).get("Items", [])]
        albums = [self._album(j) for j in self._items(IncludeItemTypes="MusicAlbum", SearchTerm=query, Limit=24, Fields=self.ALBUM_FIELDS)]
        tracks = [self._track(j) for j in self._items(IncludeItemTypes="Audio", SearchTerm=query, Limit=60, Fields=self.TRACK_FIELDS)]
        playlists = [p for p in self.playlists() if query.lower() in p.name.lower()]
        return SearchResult(artists, albums, tracks, playlists)

    def stream_url(self, track):
        # The universal stream: the original file when GStreamer can play it, else the server converts.
        q = urllib.parse.urlencode({"UserId": self.user_id, "DeviceId": self.device, "api_key" if self.kind == "emby" else "ApiKey": self.token,
                                    "Container": "opus,webm|opus,mp3,aac,m4a|aac,m4a|alac,m4b|aac,flac,webma,webm|webma,wav,ogg",
                                    "TranscodingContainer": "mp3", "TranscodingProtocol": "http", "AudioCodec": "mp3", "MaxStreamingBitrate": 140000000})
        return f"{self.base}/Audio/{track.id}/universal?{q}"

    def cover_url(self, cover_id, size=300):
        return f"{self.base}/Items/{cover_id}/Images/Primary?maxHeight={size}&maxWidth={size}&quality=90&{'api_key' if self.kind == 'emby' else 'ApiKey'}={self.token}"

    def album_cover(self, album_id, size=300):
        """An album without its own picture: the embedded cover of one of its titles."""
        items = self._items(ParentId=album_id, IncludeItemTypes="Audio", Limit=1, Fields="ParentIndexNumber", SortBy="IndexNumber")
        return self.cover_url(items[0]["Id"], size) if items and "Primary" in (items[0].get("ImageTags") or {}) else None

    def played(self, track):
        try:
            _request(f"{self.base}/Users/{self.user_id}/PlayedItems/{track.id}", self._auth(), b"")
        except ServerError:
            pass


class Subsonic:
    """Navidrome over the Subsonic API (salted token, JSON)."""

    def __init__(self, address, user, password):
        self.kind, self.address, self.user, self.password = "navidrome", normal(address), user, password

    def _params(self, **extra):
        salt = "".join(random.choices(string.ascii_lowercase + string.digits, k=12))
        token = hashlib.md5((self.password + salt).encode()).hexdigest()
        return {"u": self.user, "t": token, "s": salt, "v": "1.16.1", "c": CLIENT, "f": "json", **{k: v for k, v in extra.items() if v is not None}}

    def _url(self, method, **params):
        return f"{self.address}/rest/{method}.view?" + urllib.parse.urlencode(self._params(**params), doseq=True)

    def _call(self, method, **params):
        r = _request(self._url(method, **params))["subsonic-response"]
        if r.get("status") != "ok":
            code = (r.get("error") or {}).get("code")
            raise ServerError("Anmeldung abgelehnt – Benutzername oder Passwort stimmen nicht." if code in (40, 41) else
                              (r.get("error") or {}).get("message") or "Der Server hat abgelehnt.")
        return r

    def check(self):
        r = self._call("ping")
        return f"Navidrome {r.get('serverVersion', '')}".strip()

    @staticmethod
    def _track(j):
        return Track(j["id"], j.get("title") or "Unbekannt", j.get("artist") or "Unbekannt", j.get("album") or "", j.get("albumId"),
                     j.get("duration") or 0, j.get("track"), j.get("discNumber"), j.get("year") or None, j.get("coverArt"), "starred" in j)

    @staticmethod
    def _album(j):
        return Album(j["id"], j.get("name") or j.get("title") or "?", j.get("artist") or "?", j.get("artistId"), j.get("year") or None,
                     j.get("coverArt"), j.get("genre"))

    def albums(self, order="newest", size=100, offset=0):
        t = {"newest": "newest", "recent": "recent", "frequent": "frequent", "title": "alphabeticalByName", "artist": "alphabeticalByArtist", "year": "byYear"}[order]
        extra = {"fromYear": 3000, "toYear": 0} if order == "year" else {}
        return [self._album(j) for j in self._call("getAlbumList2", type=t, size=size, offset=offset, **extra)["albumList2"].get("album", [])]

    def album(self, album_id):
        a = self._call("getAlbum", id=album_id)["album"]
        return self._album(a), [self._track(j) for j in a.get("song", [])]

    def artists(self):
        out = []
        for idx in self._call("getArtists")["artists"].get("index", []):
            out += [Artist(j["id"], j["name"], j.get("coverArt")) for j in idx.get("artist", [])]
        return out

    def artist(self, artist_id):
        a = self._call("getArtist", id=artist_id)["artist"]
        return Artist(a["id"], a["name"], a.get("coverArt")), [self._album(j) for j in a.get("album", [])]

    def tracks(self, size=500, offset=0):
        return [self._track(j) for j in self._call("search3", query="", songCount=size, songOffset=offset, artistCount=0, albumCount=0)
                ["searchResult3"].get("song", [])]

    @staticmethod
    def _playlist(j):
        return Playlist(j["id"], j["name"], j.get("songCount", 0), j.get("coverArt"), MissingNote.read(j.get("comment")), MissingNote.origin(j.get("comment")))

    def playlists(self):
        return [self._playlist(j) for j in self._call("getPlaylists")["playlists"].get("playlist", [])]

    def playlist(self, playlist_id):
        p = self._call("getPlaylist", id=playlist_id)["playlist"]
        tracks = []
        for i, j in enumerate(p.get("entry", [])):
            t = self._track(j); t.entry_id = str(i); tracks.append(t)
        return self._playlist(p), tracks

    def create_playlist(self, name, tracks):
        ids = [t.id for t in tracks]
        r = self._call("createPlaylist", name=name, songId=ids[:100])
        pid = (r.get("playlist") or {}).get("id") or next((p.id for p in reversed(self.playlists()) if p.name == name), None)
        if not pid:
            raise ServerError("Die Playlist wurde nicht angelegt.")
        for i in range(100, len(ids), 100):
            self._call("updatePlaylist", playlistId=pid, songIdToAdd=ids[i:i + 100])
        return Playlist(pid, name, len(ids))

    def add_to_playlist(self, playlist_id, tracks, ids=None):
        ids = ids or [t.id for t in tracks]
        for i in range(0, len(ids), 100):
            self._call("updatePlaylist", playlistId=playlist_id, songIdToAdd=ids[i:i + 100])

    def remove_from_playlist(self, playlist_id, tracks):
        idx = sorted({int(t.entry_id) for t in tracks if getattr(t, "entry_id", None) is not None})
        if idx:
            self._call("updatePlaylist", playlistId=playlist_id, songIndexToRemove=idx)

    def rename_playlist(self, playlist_id, name):
        self._call("updatePlaylist", playlistId=playlist_id, name=name)

    def delete_playlist(self, playlist_id):
        self._call("deletePlaylist", id=playlist_id)

    def note_missing(self, playlist_id, missing):
        old = self._call("getPlaylist", id=playlist_id)["playlist"].get("comment")
        self._call("updatePlaylist", playlistId=playlist_id, comment=MissingNote.write(old, missing))

    def wait_filled(self, playlist_id, count, seconds=0):
        return True

    def track(self, track_id):
        try:
            return self._track(self._call("getSong", id=track_id)["song"])
        except (ServerError, KeyError):
            return None

    def set_playlist_cover(self, playlist_id, jpeg):
        pass       # Subsonic has no way to set a playlist picture

    def sharing(self, playlist_id):
        """Navidrome knows only "public" (everyone on this server may listen)."""
        return Sharing([], bool(self._call("getPlaylist", id=playlist_id)["playlist"].get("public")), False)

    def share(self, playlist_id, users, everyone):
        self._call("updatePlaylist", playlistId=playlist_id, public="true" if everyone else "false")

    def discover(self, count=50):
        return [self._track(j) for j in self._call("getRandomSongs", size=count)["randomSongs"].get("song", [])]

    def genres(self):
        g = sorted(self._call("getGenres")["genres"].get("genre", []), key=lambda x: -x.get("albumCount", 0))[:24]
        return [Genre(x["value"], x["value"]) for x in g]

    def genre_albums(self, genre):
        return [self._album(j) for j in self._call("getAlbumList2", type="byGenre", genre=genre.id, size=200)["albumList2"].get("album", [])]

    def download_url(self, track):
        return self._url("download", id=track.id)

    def server_id(self):
        return "nd-" + hashlib.md5(self.address.encode()).hexdigest()[:16]

    def favorites(self):
        return [self._track(j) for j in self._call("getStarred2")["starred2"].get("song", [])]

    def set_favorite(self, track, on):
        self._call("star" if on else "unstar", id=track.id)

    def similar(self, track, count=30):
        try:
            return [self._track(j) for j in self._call("getSimilarSongs2", id=track.id, count=count)["similarSongs2"].get("song", [])]
        except ServerError:
            return []

    def search(self, query):
        r = self._call("search3", query=query, artistCount=12, albumCount=24, songCount=60)["searchResult3"]
        return SearchResult([Artist(j["id"], j["name"], j.get("coverArt")) for j in r.get("artist", [])],
                            [self._album(j) for j in r.get("album", [])], [self._track(j) for j in r.get("song", [])],
                            [p for p in self.playlists() if query.lower() in p.name.lower()])

    def album_cover(self, album_id, size=300):
        return self.cover_url(album_id, size)

    def stream_url(self, track):
        return self._url("stream", id=track.id)

    def cover_url(self, cover_id, size=300):
        return self._url("getCoverArt", id=cover_id, size=size)

    def played(self, track):
        try:
            self._call("scrobble", id=track.id, submission="true")
        except ServerError:
            pass
