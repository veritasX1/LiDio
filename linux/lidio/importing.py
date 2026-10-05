"""Playlists from elsewhere (cards 726c9233, 561fa339, 146a2d74 – the same rules as the Android app's Import.kt/Remote.kt):
files (M3U/M3U8, PLS, XSPF, CSV, text), public Deezer playlists (no account), Spotify with the user's own app key; compared
tolerantly with the own server. Nothing is downloaded from anywhere – what is missing is only written down."""
import base64, csv, io, json, re, unicodedata, urllib.parse, urllib.request
from dataclasses import dataclass, field

from .servers import ServerError
from .i18n import _

FOUND, UNSURE, MISSING = "found", "unsure", "missing"


@dataclass
class Wanted:
    title: str
    artist: str = ""
    album: str = ""
    seconds: int = 0
    cover: str | None = None

    def __str__(self):
        return self.title if not self.artist else f"{self.artist} – {self.title}"


@dataclass
class Result:
    wanted: Wanted
    match: str
    track: object = None
    choices: list = field(default_factory=list)


@dataclass
class RemoteList:
    source: str          # Deezer | Spotify
    id: str
    name: str
    owner: str = ""
    count: int = 0
    cover: str | None = None

    @property
    def link(self):
        return f"https://www.deezer.com/playlist/{self.id}" if self.source == "Deezer" else f"https://open.spotify.com/playlist/{self.id}"


# ---------- files ----------
def read(text, name=""):
    clean = text.lstrip("﻿").replace("\r\n", "\n").replace("\r", "\n")
    lower = name.lower()
    if lower.endswith(".xspf") or clean.lstrip().startswith("<?xml") or ("<playlist" in clean and "<trackList" in clean):
        out = _xspf(clean)
    elif lower.endswith(".pls") or clean.lstrip().lower().startswith("[playlist]"):
        out = _pls(clean)
    elif lower.endswith((".m3u", ".m3u8")) or clean.lstrip().startswith("#EXTM3U"):
        out = _m3u(clean)
    elif lower.endswith(".csv") or _looks_like_csv(clean):
        out = _csv(clean)
    else:
        out = [split(re.sub(r"^\s*\d{1,3}[.)]\s+", "", l.strip())) for l in clean.split("\n")
               if l.strip() and not l.strip().startswith(("#", "//"))]
    return [w for w in out if w.title.strip()]


def split(line):
    """"Artist – Title" (dash, en dash, em dash or tab); without a separator the whole line is the title."""
    parts = re.split(r"\s+[-–—]\s+|\t", line, maxsplit=1)
    return Wanted(parts[1].strip(), parts[0].strip()) if len(parts) == 2 else Wanted(line.strip())


def _from_path(path):
    f = re.split(r"[/\\]", path)[-1]
    f = f.rsplit(".", 1)[0] if "." in f else f
    return split(re.sub(r"^\d{1,3}[ ._-]+", "", urllib.parse.unquote(f)))


def _m3u(text):
    out, info = [], None
    for raw in text.split("\n"):
        line = raw.strip()
        if line.upper().startswith("#EXTINF"):
            body = line.split(":", 1)[1] if ":" in line else ""
            sec = body.split(",", 1)[0].strip()
            info = split(body.split(",", 1)[1] if "," in body else "")
            info.seconds = max(0, int(sec)) if re.fullmatch(r"-?\d+", sec) else 0
        elif not line or line.startswith("#"):
            continue
        else:
            out.append(info or _from_path(line)); info = None
    return out


def _pls(text):
    files, titles, lengths = {}, {}, {}
    for line in text.split("\n"):
        m = re.match(r"^(File|Title|Length)(\d+)=(.*)$", line.strip(), re.I)
        if m:
            n, v = int(m.group(2)), m.group(3).strip()
            {"file": files, "title": titles, "length": lengths}[m.group(1).lower()][n] = v
    out = []
    for n in sorted(set(files) | set(titles)):
        w = split(titles[n]) if n in titles else _from_path(files.get(n, ""))
        w.seconds = max(0, int(lengths[n])) if re.fullmatch(r"-?\d+", lengths.get(n, "")) else 0
        out.append(w)
    return out


def _xspf(text):
    def unesc(s):
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", '"').replace("&apos;", "'")
    out = []
    for m in re.finditer(r"<track>(.*?)</track>", text, re.S):
        def tag(n):
            t = re.search(rf"<{n}>(.*?)</{n}>", m.group(1), re.S)
            return unesc(t.group(1)).strip() if t else ""
        title = tag("title") or _from_path(tag("location")).title
        out.append(Wanted(title, tag("creator"), tag("album"), int(tag("duration") or 0) // 1000 if tag("duration").isdigit() else 0))
    return out


def _looks_like_csv(text):
    head = text.split("\n", 1)[0].lower()
    return ("," in head or ";" in head) and any(x in head for x in ("track", "title", "titel")) and any(x in head for x in ("artist", "interpret"))


def _csv(text):
    rows = [r for r in text.split("\n") if r.strip()]
    if not rows:
        return []
    sep = ";" if rows[0].count(";") > rows[0].count(",") else ","
    table = list(csv.reader(io.StringIO("\n".join(rows)), delimiter=sep))
    head = [h.lower() for h in table[0]]

    def col(*names):
        return next((i for i, h in enumerate(head) if any(n in h for n in names)), -1)
    ti, ar, al, ms = col("track name", "title", "titel", "name"), col("artist", "interpret", "künstler"), col("album"), col("duration (ms)", "dauer (ms)")

    def at(c, i):
        return c[i].strip() if 0 <= i < len(c) else ""
    return [Wanted(at(c, ti), at(c, ar).replace(";", ", "), at(c, al), int(at(c, ms)) // 1000 if at(c, ms).isdigit() else 0) for c in table[1:]]


# ---------- comparing ----------
_NOISE = re.compile(r"\s*[(\[]\s*(feat\.?|ft\.?|featuring|with|mit|remaster(ed)?|live|radio edit|single version|album version|explicit|mono|stereo|"
                    r"\d{4} remaster(ed)?)[^)\]]*[)\]]", re.I)
_DASH = re.compile(r"\s+-\s+(remaster(ed)?|\d{4} remaster(ed)?|live|radio edit|single version|mono|stereo|bonus track)\b.*$", re.I)


def normal(text):
    s = _DASH.sub("", _NOISE.sub("", (text or "").lower()))
    s = s.replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss").replace("&", " und ").replace(" and ", " und ")
    s = "".join(c for c in unicodedata.normalize("NFD", s) if not unicodedata.combining(c))
    s = re.sub(r"[^a-z0-9 ]", " ", s)
    s = re.sub(r"\b(the|die|der|das)\b", " ", s)
    return re.sub(r"\s+", " ", s).strip()


def similar(a, b):
    x, y = normal(a), normal(b)
    if x == y:
        return 1.0
    if not x or not y:
        return 0.0
    d = list(range(len(y) + 1))
    for i in range(1, len(x) + 1):
        prev, d[0] = d[0], i
        for j in range(1, len(y) + 1):
            keep = d[j]
            d[j] = min(d[j] + 1, d[j - 1] + 1, prev + (x[i - 1] != y[j - 1])); prev = keep
    return 1.0 - d[len(y)] / max(len(x), len(y))


def artist_score(wanted, have):
    if not wanted.strip():
        return 0.8
    names = [n for n in (normal(p) for p in re.split(r",|&|\bfeat\.?|\bft\.?|\bx\b|/", wanted, flags=re.I)) if n]
    h = normal(have)
    if any(n in h or (h and h in n) for n in names):
        return 1.0
    return max((similar(n, have) for n in names), default=0.0)


def score(w, t):
    length = 0.85 if w.seconds > 0 and t.duration > 0 and abs(w.seconds - t.duration) > 15 else 1.0
    return (similar(w.title, t.title) * 0.65 + artist_score(w.artist, t.artist) * 0.35) * length


def judge(w, candidates):
    seen, ranked = set(), []
    for t in candidates:
        if t.id not in seen:
            seen.add(t.id); ranked.append((t, score(w, t)))
    ranked.sort(key=lambda x: -x[1])
    if not ranked:
        return Result(w, MISSING)
    best, s = ranked[0]
    if s >= 0.9:
        return Result(w, FOUND, best)
    if s >= 0.6:
        return Result(w, UNSURE, best, [t for t, _ in ranked[:5]])
    return Result(w, MISSING, None, [t for t, x in ranked[:5] if x >= 0.5])


def match(server, wanted, progress=None, workers=4):
    """Each line on its own; four at a time (the server answers each search in about a second)."""
    from concurrent.futures import ThreadPoolExecutor
    done = [0]

    def one(w):
        r = _match_one(server, w)
        done[0] += 1
        if progress:
            progress(done[0] - 1)
        return r
    with ThreadPoolExecutor(workers) as pool:
        return list(pool.map(one, wanted))


def _match_one(server, w):
    try:
        hits = server.search(normal(w.title) or w.title).tracks
    except ServerError:
        hits = []
    r = judge(w, hits)
    if r.match != FOUND and w.artist:
        more = []
        for q in (f"{w.artist} {w.title}", w.artist.split(",")[0].strip()):
            try:
                more += server.search(q).tracks
            except ServerError:
                pass
        r = judge(w, hits + more)
    return r


def missing_text(results):
    return "\n".join(str(r.wanted) for r in results if r.match == MISSING)


def without_duplicates(tracks):
    """The same title by the same artist with about the same length – once (setting "Doppelte ausblenden")."""
    groups, out = {}, []
    for t in tracks:
        g = groups.setdefault(normal(t.title) + "|" + normal(t.artist), [])
        if any(x.id == t.id or not x.duration or not t.duration or abs(x.duration - t.duration) <= 2 for x in g):
            continue
        g.append(t); out.append(t)
    return out


# ---------- public playlists ----------
def _get(url, headers=None):
    req = urllib.request.Request(url, headers={"User-Agent": "LiDio/0.1", **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return json.loads(r.read())
    except (OSError, ValueError) as e:
        raise ServerError(_("Keine Verbindung zum Dienst.")) from e


class Deezer:
    """Deezer's public API – no account, no key."""
    BASE = "https://api.deezer.com"
    source = "Deezer"

    def _get(self, url):
        j = _get(url)
        if isinstance(j, dict) and j.get("error"):
            raise ServerError("Deezer: " + (j["error"].get("message") or _("Fehler")))
        return j

    def search(self, query):
        return [RemoteList("Deezer", str(p["id"]), p.get("title", ""), (p.get("user") or {}).get("name", ""), p.get("nb_tracks", 0),
                           p.get("picture_xl") or p.get("picture_medium"))
                for p in self._get(f"{self.BASE}/search/playlist?q={urllib.parse.quote(query)}&limit=25").get("data", [])]

    def info(self, pid):
        p = self._get(f"{self.BASE}/playlist/{urllib.parse.quote(pid)}")
        return RemoteList("Deezer", pid, p.get("title", ""), (p.get("creator") or {}).get("name", ""), p.get("nb_tracks", 0),
                          p.get("picture_xl") or p.get("picture_medium"))

    def tracks(self, pid):
        out, url, pages = [], f"{self.BASE}/playlist/{urllib.parse.quote(pid)}/tracks?limit=500", 0
        while url and pages < 20:
            page = self._get(url); pages += 1
            for t in page.get("data", []):
                al = t.get("album") or {}
                out.append(Wanted(t.get("title", ""), (t.get("artist") or {}).get("name", ""), al.get("title", ""), t.get("duration", 0), al.get("cover_medium")))
            url = page.get("next")
        return out


class Spotify:
    """Spotify's Web API with the user's own app (client id + secret from developer.spotify.com) – nothing of the kind ships
    with LiDio. Spotify's own editorial playlists are closed to new apps since 11/2024; playlists made by users work."""
    source = "Spotify"

    def __init__(self, client_id, secret):
        self.client_id, self.secret, self.token = client_id, secret, None

    def _bearer(self):
        if not self.token:
            basic = base64.b64encode(f"{self.client_id}:{self.secret}".encode()).decode()
            req = urllib.request.Request("https://accounts.spotify.com/api/token", data=b"grant_type=client_credentials",
                                         headers={"Authorization": "Basic " + basic, "Content-Type": "application/x-www-form-urlencoded"})
            try:
                with urllib.request.urlopen(req, timeout=20) as r:
                    self.token = json.loads(r.read()).get("access_token")
            except OSError as e:
                raise ServerError(_("Spotify hat die Client-ID abgelehnt.")) from e
            if not self.token:
                raise ServerError(_("Spotify hat die Client-ID abgelehnt."))
        return self.token

    def _get(self, url):
        return _get(url, {"Authorization": "Bearer " + self._bearer()})

    def search(self, query):
        items = (self._get(f"https://api.spotify.com/v1/search?type=playlist&limit=25&q={urllib.parse.quote(query)}").get("playlists") or {}).get("items", [])
        return [RemoteList("Spotify", p["id"], p.get("name", ""), (p.get("owner") or {}).get("display_name", ""), (p.get("tracks") or {}).get("total", 0),
                           ((p.get("images") or [{}])[0] or {}).get("url")) for p in items if p]

    def tracks(self, pid):
        fields = urllib.parse.quote("items(track(name,duration_ms,artists(name),album(name))),next")
        out, url, pages = [], f"https://api.spotify.com/v1/playlists/{pid}/tracks?limit=100&fields={fields}", 0
        while url and pages < 50:
            page = self._get(url); pages += 1
            for it in page.get("items", []):
                t = it.get("track")
                if t:
                    out.append(Wanted(t.get("name", ""), ", ".join(a.get("name", "") for a in t.get("artists", [])), (t.get("album") or {}).get("name", ""),
                                      t.get("duration_ms", 0) // 1000))
            url = page.get("next")
        return out


def parse_link(text):
    """Playlist links pasted anywhere: deezer.com/…/playlist/123, open.spotify.com/playlist/ID, spotify:playlist:ID."""
    for pattern, src in ((r"deezer\.com/(?:[a-z]{2}/)?playlist/(\d+)", "Deezer"), (r"open\.spotify\.com/(?:intl-[a-z]+/)?playlist/([A-Za-z0-9]{22})", "Spotify"),
                         (r"spotify:playlist:([A-Za-z0-9]{22})", "Spotify")):
        m = re.search(pattern, text or "")
        if m:
            return src, m.group(1)
    return None
