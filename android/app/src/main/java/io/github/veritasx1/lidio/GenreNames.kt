package io.github.veritasx1.lidio

import io.github.veritasx1.lidio.i18n.I18n

/** Genres stay English in the files (card aa4935bb); LiDio shows them in its language – the same tables as
 *  Ubuntu's genres.py. German tags (other people's files) count as their English genre. */
object GenreNames {
    private val DE = mapOf("rock" to "Rock", "pop" to "Pop", "hip-hop" to "Hip-Hop", "hip hop" to "Hip-Hop", "rap" to "Rap", "electronic" to "Elektronisch", "dance" to "Dance", "classical" to "Klassik", "jazz" to "Jazz", "blues" to "Blues", "country" to "Country", "folk" to "Folk", "metal" to "Metal", "punk" to "Punk", "alternative" to "Alternative", "indie" to "Indie", "soul" to "Soul", "r&b" to "R&B", "reggae" to "Reggae", "soundtrack" to "Soundtrack", "world" to "Weltmusik", "latin" to "Latin", "children's music" to "Kindermusik", "christmas" to "Weihnachten", "easy listening" to "Easy Listening", "ambient" to "Ambient", "german pop" to "Deutsch-Pop", "schlager" to "Schlager", "singer/songwriter" to "Singer/Songwriter", "comedy" to "Comedy", "spoken word" to "Hörbuch & Wort", "new age" to "New Age", "funk" to "Funk", "disco" to "Disco", "techno" to "Techno", "house" to "House", "trance" to "Trance", "chanson" to "Chanson", "oldies" to "Oldies", "instrumental" to "Instrumental", "lounge" to "Lounge", "chill out" to "Chill-out")
    private val FR = mapOf("electronic" to "Électronique", "classical" to "Classique", "world" to "Musiques du monde", "children's music" to "Musique pour enfants", "christmas" to "Noël", "german pop" to "Pop allemande", "spoken word" to "Livres audio", "soundtrack" to "Bande originale", "singer/songwriter" to "Auteur-compositeur", "chanson" to "Chanson française", "alternative" to "Alternatif")
    private val EN = mapOf("rock" to "Rock", "pop" to "Pop", "hip-hop" to "Hip-Hop", "hip hop" to "Hip-Hop", "rap" to "Rap", "electronic" to "Electronic", "dance" to "Dance", "classical" to "Classical", "jazz" to "Jazz", "blues" to "Blues", "country" to "Country", "folk" to "Folk", "metal" to "Metal", "punk" to "Punk", "alternative" to "Alternative", "indie" to "Indie", "soul" to "Soul", "r&b" to "R&B", "reggae" to "Reggae", "soundtrack" to "Soundtrack", "world" to "World", "latin" to "Latin", "children's music" to "Children's Music", "christmas" to "Christmas", "easy listening" to "Easy Listening", "ambient" to "Ambient", "german pop" to "German Pop", "schlager" to "Schlager", "singer/songwriter" to "Singer/Songwriter", "comedy" to "Comedy", "spoken word" to "Spoken Word", "new age" to "New Age", "funk" to "Funk", "disco" to "Disco", "techno" to "Techno", "house" to "House", "trance" to "Trance", "chanson" to "Chanson", "oldies" to "Oldies", "instrumental" to "Instrumental", "lounge" to "Lounge", "chill out" to "Chill-out")
    /** Other spellings and languages found in files (e.g. Russian tags from shop downloads) → the table's key. */
    private val ALIAS = mapOf("поп" to "pop", "рок" to "rock", "электронная музыка" to "electronic", "электроника" to "electronic",
        "танцевальная" to "dance", "хип-хоп" to "hip-hop", "рэп" to "rap", "джаз" to "jazz", "классика" to "classical",
        "фильмы" to "soundtrack", "саундтреки к фильмам" to "soundtrack", "саундтреки" to "soundtrack", "films" to "soundtrack",
        "film scores" to "soundtrack", "games & film scores" to "soundtrack", "игры" to "games", "electro" to "electronic",
        "pop & rock" to "pop/rock")
    private val EXTRA = mapOf("de" to mapOf("games" to "Spielemusik", "soundtrack" to "Filmmusik", "pop/rock" to "Pop & Rock"),
        "fr" to mapOf("games" to "Musique de jeux vidéo", "pop/rock" to "Pop & Rock"),
        "en" to mapOf("games" to "Video Game Music", "soundtrack" to "Film Music", "pop/rock" to "Pop & Rock"))
    private val FROM_DE = DE.entries.filter { it.value.lowercase() != it.key }.associate { it.value.lowercase() to it.key }

    fun local(name: String?): String {
        val raw = name?.trim() ?: ""
        val key = raw.lowercase().let { ALIAS[it] ?: FROM_DE[it] ?: it }
        val language = I18n.language()
        return EXTRA[language]?.get(key) ?: when (language) {
            "de" -> DE[key]
            "fr" -> FR[key] ?: EN[key]
            else -> EN[key]
        } ?: (EXTRA["en"]?.get(key) ?: raw)
    }

    /** The genres to show: one tile per shown name (several tags can mean the same), none in a script nobody here reads. */
    fun shown(genres: List<Genre>): List<Genre> = genres
        .filter { local(it.name).none { c -> Character.UnicodeScript.of(c.code) == Character.UnicodeScript.CYRILLIC } }
        .distinctBy { local(it.name).lowercase() }
}
