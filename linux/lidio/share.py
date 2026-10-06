"""Shared titles (card 287d55cf), the same links as the Android app: https://lisoftware.de/lidio/t#s=…&t=…&n=…&a=…&al=…
The details sit after the "#", which a browser never sends to the web server. On the receiving side LiDio plays that title
from the same server when there is an account on it, else the same song from the own library."""
import urllib.parse
from dataclasses import dataclass
from .i18n import _

HOST, PATH = "lisoftware.de", "/lidio/t"
# Card a19168e0: links sent before the move to lisoftware.de still say lisoft.goip.de.
HOSTS = {HOST, "lisoft.goip.de"}


def _enc(v):
    return urllib.parse.quote(v, safe="!~'()*-_.")


@dataclass
class Shared:
    server: str
    id: str
    title: str
    artist: str
    album: str = ""

    def link(self):
        parts = [("s", self.server), ("t", self.id), ("n", self.title), ("a", self.artist), ("al", self.album)]
        return f"https://{HOST}{PATH}#" + "&".join(f"{k}={_enc(v)}" for k, v in parts if v)

    def text(self):
        return _("„{title}“ von {artist} – in LiDio anhören:\n{link}", title=self.title, artist=self.artist, link=self.link())

    @staticmethod
    def parse(uri):
        u = urllib.parse.urlsplit(uri or "")
        ok = (u.scheme == "https" and u.hostname in HOSTS and u.path.startswith(PATH)) or (u.scheme == "lidio" and u.netloc == "t")
        if not ok or not u.fragment:
            return None
        values = {}
        for part in u.fragment.split("&"):
            if "=" in part:
                k, v = part.split("=", 1)
                values[k] = urllib.parse.unquote(v)
        if "n" not in values or values.get("k") == "p":
            return None
        return Shared(values.get("s", ""), values.get("t", ""), values["n"], values.get("a", ""), values.get("al", ""))


@dataclass
class SharedList:
    """Card c9b15c67: a playlist as a link ("Mixtape") – the same format as the Android app: name and the titles (artist, title),
    packed (zlib + base64url) after the "#"."""
    server: str
    id: str
    name: str
    items: list   # [(artist, title)]

    @staticmethod
    def pack(items):
        import base64, json, zlib
        return base64.urlsafe_b64encode(zlib.compress(json.dumps([[a, t] for a, t in items], ensure_ascii=False).encode(), 9)).decode().rstrip("=")

    @staticmethod
    def unpack(data):
        import base64, json, zlib
        try:
            return [(e[0], e[1]) for e in json.loads(zlib.decompress(base64.urlsafe_b64decode(data + "=" * (-len(data) % 4))))]
        except Exception:      # noqa: BLE001
            return []

    def link(self):
        parts = [("k", "p"), ("s", self.server), ("p", self.id), ("n", self.name), ("l", self.pack(self.items))]
        return f"https://{HOST}{PATH}#" + "&".join(f"{k}={_enc(v)}" for k, v in parts if v)

    def text(self):
        return _("Ein Mixtape für dich: „{name}“ – {n} Titel, in LiDio anhören:\n{link}", name=self.name, n=len(self.items), link=self.link())

    @staticmethod
    def parse(uri):
        u = urllib.parse.urlsplit(uri or "")
        ok = (u.scheme == "https" and u.hostname in HOSTS and u.path.startswith(PATH)) or (u.scheme == "lidio" and u.netloc == "t")
        if not ok or not u.fragment:
            return None
        values = dict(part.split("=", 1) for part in u.fragment.split("&") if "=" in part)
        values = {k: urllib.parse.unquote(v) for k, v in values.items()}
        if values.get("k") != "p":
            return None
        items = SharedList.unpack(values.get("l", ""))
        return SharedList(values.get("s", ""), values.get("p", ""), values.get("n", "Mixtape"), items) if items else None
