"""The connected servers: address, user, kind in ~/.config/lidio/konten.json; the secret (Emby/Jellyfin token, Navidrome
password) in the GNOME keyring – never in a file."""
import json, os, uuid
import gi
gi.require_version("Secret", "1")
from gi.repository import Secret

from .servers import MediaBrowser, Subsonic
from .i18n import _

SCHEMA = Secret.Schema.new("io.github.veritasx1.LiDio", Secret.SchemaFlags.NONE, {"account": Secret.SchemaAttributeType.STRING})
FOLDER = os.path.join(os.environ.get("XDG_CONFIG_HOME") or os.path.expanduser("~/.config"), "lidio")
FILE = os.path.join(FOLDER, "konten.json")


def _load():
    try:
        with open(FILE) as f:
            return json.load(f)
    except (OSError, ValueError):
        return {"accounts": [], "active": None, "device": str(uuid.uuid4())}


def _save(data):
    os.makedirs(FOLDER, exist_ok=True)
    tmp = FILE + ".neu"
    with open(tmp, "w") as f:
        json.dump(data, f, indent=1)
    os.chmod(tmp, 0o600)
    os.replace(tmp, FILE)


def all_accounts():
    return _load()["accounts"]


def device_id():
    data = _load()
    if not data.get("device"):
        data["device"] = str(uuid.uuid4()); _save(data)
    return data["device"]


def active():
    data = _load()
    return next((a for a in data["accounts"] if a["id"] == data.get("active")), (data["accounts"] or [None])[0])


def set_active(account_id):
    data = _load(); data["active"] = account_id; _save(data)


def secret(account):
    return Secret.password_lookup_sync(SCHEMA, {"account": account["id"]}, None) or ""


def add(kind, address, external, user, secret_value, name, user_id=""):
    data = _load()
    account = {"id": str(uuid.uuid4()), "kind": kind, "address": address, "external": external, "user": user, "name": name, "user_id": user_id}
    Secret.password_store_sync(SCHEMA, {"account": account["id"]}, Secret.COLLECTION_DEFAULT, _("LiDio – {name}", name=name), secret_value, None)
    data["accounts"].append(account); data["active"] = account["id"]; _save(data)
    return account


def remove(account_id):
    data = _load()
    data["accounts"] = [a for a in data["accounts"] if a["id"] != account_id]
    if data.get("active") == account_id:
        data["active"] = (data["accounts"] or [{}])[0].get("id")
    _save(data)
    Secret.password_clear_sync(SCHEMA, {"account": account_id}, None)


def server_for(account, address=None):
    if account["kind"] == "local":
        from .local import LocalLibrary
        return LocalLibrary(account["id"], account["address"].split("\n"))
    a = address or account["address"]
    if account["kind"] == "navidrome":
        return Subsonic(a, account["user"], secret(account))
    return MediaBrowser(account["kind"], a, account.get("user_id", ""), secret(account), device_id())


def reachable(account):
    """Home or away: the WLAN address if it answers within 1.5 s, else the one from outside."""
    if account["kind"] == "local":
        return account["address"]
    import urllib.request
    from .servers import normal
    probe = "/rest/ping.view" if account["kind"] == "navidrome" else ("/emby" if account["kind"] == "emby" else "") + "/System/Info/Public"
    for a in [account["address"], account.get("external") or ""]:
        if not a:
            continue
        try:
            urllib.request.urlopen(normal(a) + probe, timeout=1.5)
            return a
        except urllib.error.HTTPError:
            return a
        except Exception:
            continue
    return account["address"]
