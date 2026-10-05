"""Shared titles (card 287d55cf), the same links as the Android app: https://lisoft.goip.de/lidio/t#s=…&t=…&n=…&a=…&al=…
The details sit after the "#", which a browser never sends to the web server. On the receiving side LiDio plays that title
from the same server when there is an account on it, else the same song from the own library."""
import urllib.parse
from dataclasses import dataclass

HOST, PATH = "lisoft.goip.de", "/lidio/t"


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
        return f"„{self.title}“ von {self.artist} – in LiDio anhören:\n{self.link()}"

    @staticmethod
    def parse(uri):
        u = urllib.parse.urlsplit(uri or "")
        ok = (u.scheme == "https" and u.hostname == HOST and u.path.startswith(PATH)) or (u.scheme == "lidio" and u.netloc == "t")
        if not ok or not u.fragment:
            return None
        values = {}
        for part in u.fragment.split("&"):
            if "=" in part:
                k, v = part.split("=", 1)
                values[k] = urllib.parse.unquote(v)
        if "n" not in values:
            return None
        return Shared(values.get("s", ""), values.get("t", ""), values["n"], values.get("a", ""), values.get("al", ""))
