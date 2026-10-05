"""Lyrics like the Android app: an .lrc beside the music on the server (Emby hands it out as SRT), else LRCLIB (free, no
account; can be switched off). Found texts are kept in ~/.cache/lidio/liedtexte."""
import hashlib, json, os, re, urllib.parse, urllib.request

CACHE = os.path.join(os.environ.get("XDG_CACHE_HOME") or os.path.expanduser("~/.cache"), "lidio", "liedtexte")
TIME = re.compile(r"\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]")


def parse(text):
    lines = []
    for raw in text.splitlines():
        stamps = list(TIME.finditer(raw)); words = TIME.sub("", raw).strip()
        if not stamps:
            if raw.strip() and not re.fullmatch(r"\[[a-z]+:.*]", raw.strip()):
                lines.append((None, raw.strip()))
            continue
        for m in stamps:
            frac = m.group(3) or ""
            lines.append(((int(m.group(1)) * 60 + int(m.group(2))) * 1000 + (int(frac.ljust(3, "0")[:3]) if frac else 0), words))
    if any(ms is not None for ms, _ in lines):
        lines = sorted((l for l in lines if l[0] is not None), key=lambda l: l[0])
    return lines


def from_srt(text):
    out = []
    for block in re.split(r"\n\s*\n", text.lstrip("﻿").replace("\r", "")):
        b = block.strip().splitlines()
        i = next((k for k, l in enumerate(b) if "-->" in l), None)
        if i is None:
            continue
        m = re.search(r"(\d+):(\d{2}):(\d{2})[,.](\d{1,3})", b[i])
        if m:
            h, mi, s, ms = m.groups()
            out.append((((int(h) * 60 + int(mi)) * 60 + int(s)) * 1000 + int(ms.ljust(3, "0")), " ".join(b[i + 1:]).strip()))
    return out


def _ask(url):
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "LiDio (https://lisoft.goip.de)"}), timeout=10) as r:
            return json.load(r)
    except Exception:
        return None


def load(server, track, internet=True):
    key = hashlib.md5(f"{track.artist.lower()}|{track.title.lower()}".encode()).hexdigest()
    path = os.path.join(CACHE, key + ".lrc")
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            return parse(f.read())
    text = None
    if getattr(server, "kind", "") == "local":
        # Folders: an .lrc (or .txt) with the same name beside the music file.
        e = server._all().get(track.id) if hasattr(server, "_all") else None
        if e:
            base = os.path.splitext(e["path"])[0]
            for ext in (".lrc", ".txt"):
                if os.path.exists(base + ext):
                    with open(base + ext, encoding="utf-8", errors="replace") as f:
                        return parse(f.read())
    if getattr(server, "kind", "") in ("emby", "jellyfin"):
        try:
            item = server._get(f"/Users/{server.user_id}/Items/{track.id}", Fields="MediaStreams,MediaSources")
            src = (item.get("MediaSources") or [{}])[0]
            st = next((s for s in src.get("MediaStreams", []) if s.get("Type") in ("Subtitle", "Lyrics")), None)
            if st:
                raw = urllib.request.urlopen(urllib.request.Request(
                    f"{server.base}/Videos/{track.id}/{src['Id']}/Subtitles/{st['Index']}/Stream.srt", headers=server._auth()), timeout=10).read().decode("utf-8", "replace")
                lines = from_srt(raw)
                if lines:
                    text = "\n".join(f"[{ms // 60000:02d}:{ms // 1000 % 60:02d}.{ms % 1000 // 10:02d}] {w}" for ms, w in lines)
        except Exception:
            pass
    if not text and internet:
        q = {"artist_name": track.artist, "track_name": track.title}
        if track.album:
            q["album_name"] = track.album
        if track.duration:
            q["duration"] = track.duration
        hit = _ask("https://lrclib.net/api/get?" + urllib.parse.urlencode(q))
        if not hit:
            found = _ask("https://lrclib.net/api/search?" + urllib.parse.urlencode({"artist_name": track.artist, "track_name": track.title})) or []
            found = [f for f in found if not track.duration or abs((f.get("duration") or 0) - track.duration) < 4]
            found.sort(key=lambda f: f.get("syncedLyrics") is None)
            hit = found[0] if found else None
        if hit and not hit.get("instrumental"):
            text = hit.get("syncedLyrics") or hit.get("plainLyrics")
    if not text:
        return []
    os.makedirs(CACHE, exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)
    return parse(text)
