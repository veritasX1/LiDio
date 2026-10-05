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


def _lang():
    try:
        return (locale.getlocale(locale.LC_MESSAGES)[0] or "de")[:2]
    except (ValueError, AttributeError):
        return "de"


def local(name):
    key = (name or "").strip().lower()
    table = {"de": _DE, "fr": _FR}.get(_lang(), {})
    return table.get(key) or (name or "").strip()
