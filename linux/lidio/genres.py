"""Genres stay English in the files (one spelling each, card aa4935bb); LiDio shows them in the system's language."""
import locale

_DE = {"rock": "Rock", "pop": "Pop", "hip-hop": "Hip-Hop", "hip hop": "Hip-Hop", "rap": "Rap", "electronic": "Elektronisch", "dance": "Dance",
       "classical": "Klassik", "jazz": "Jazz", "blues": "Blues", "country": "Country", "folk": "Folk", "metal": "Metal", "punk": "Punk",
       "alternative": "Alternative", "indie": "Indie", "soul": "Soul", "r&b": "R&B", "reggae": "Reggae", "soundtrack": "Soundtrack",
       "world": "Weltmusik", "latin": "Latin", "children's music": "Kindermusik", "christmas": "Weihnachten", "easy listening": "Easy Listening",
       "ambient": "Ambient", "german pop": "Deutsch-Pop", "schlager": "Schlager", "singer/songwriter": "Singer/Songwriter", "comedy": "Comedy",
       "spoken word": "Hörbuch & Wort", "new age": "New Age", "funk": "Funk", "disco": "Disco", "techno": "Techno", "house": "House",
       "trance": "Trance", "chanson": "Chanson", "oldies": "Oldies", "instrumental": "Instrumental", "lounge": "Lounge", "chill out": "Chill-out"}
_FR = {"electronic": "Électronique", "classical": "Classique", "world": "Musiques du monde", "children's music": "Musique pour enfants",
       "christmas": "Noël", "german pop": "Pop allemande", "spoken word": "Livres audio", "soundtrack": "Bande originale",
       "singer/songwriter": "Auteur-compositeur", "chanson": "Chanson française", "alternative": "Alternatif"}


# German tags (other people's files, or written before card aa4935bb) count as their English genre.
_FROM_DE = {german.lower(): english for english, german in _DE.items() if german.lower() != english}


def _lang():
    """LiDio's language (settings or system), not just the system's."""
    try:
        from .i18n import language
        return language()
    except Exception:
        try:
            return (locale.getlocale(locale.LC_MESSAGES)[0] or "de")[:2]
        except (ValueError, AttributeError):
            return "de"


# The English display names (the files' spelling, nicely capitalised).
_EN = {key: {"hip-hop": "Hip-Hop", "hip hop": "Hip-Hop", "r&b": "R&B", "singer/songwriter": "Singer/Songwriter",
             "children's music": "Children's Music", "chill out": "Chill-out"}.get(key, key.title()) for key in _DE}


def local(name):
    raw = (name or "").strip()
    key = raw.lower()
    key = _FROM_DE.get(key, key)
    lang = _lang()
    if lang == "de":
        return _DE.get(key) or raw
    if lang == "fr":
        return _FR.get(key) or _EN.get(key) or raw
    return _EN.get(key) or raw
