"""LiDio for Ubuntu: what can be tested without a window – lyrics, server answers, the queue."""
import os, sys, unittest
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from lidio import lyrics
from lidio.servers import MediaBrowser, Subsonic, Track, normal


class Lyrics(unittest.TestCase):
    def test_lrc(self):
        lines = lyrics.parse("[ar:X]\n[00:26.31] Eins\n[00:30.7]Zwei\n[01:02.05][02:10.00] Refrain")
        self.assertEqual([ms for ms, _ in lines], [26310, 30700, 62050, 130000])

    def test_srt_from_emby(self):
        lines = lyrics.from_srt("﻿1\n00:00:00,030 --> 00:00:07,310\nOoh\n\n2\n00:00:07,310 --> 00:00:14,570\nNow listen\n")
        self.assertEqual(lines, [(30, "Ooh"), (7310, "Now listen")])


class Servers(unittest.TestCase):
    def test_emby_track(self):
        t = MediaBrowser._track({"Id": "1", "Name": "Lied", "Artists": ["A", "B"], "Album": "Al", "AlbumId": "9", "RunTimeTicks": 1_850_000_000,
                                  "IndexNumber": 3, "AlbumPrimaryImageTag": "x", "UserData": {"IsFavorite": True}})
        self.assertEqual((t.artist, t.duration, t.cover_id, t.favorite), ("A, B", 185, "9", True))

    def test_navidrome_track(self):
        t = Subsonic._track({"id": "5", "title": "T", "artist": "A", "album": "Al", "duration": 200, "coverArt": "al-1", "starred": "2026"})
        self.assertEqual((t.title, t.cover_id, t.favorite), ("T", "al-1", True))

    def test_urls(self):
        self.assertEqual(normal("192.168.1.2:8096/"), "http://192.168.1.2:8096")
        e = MediaBrowser("emby", "x:8096", "u", "tok", "dev")
        self.assertTrue(e.stream_url(Track("7", "t", "a")).startswith("http://x:8096/emby/Audio/7/universal?"))
        self.assertIn("api_key=tok", e.cover_url("7"))
        j = MediaBrowser("jellyfin", "x:8096", "u", "tok", "dev")
        self.assertIn("ApiKey=tok", j.cover_url("7")); self.assertTrue(j.base.endswith(":8096"))
        s = Subsonic("x:4533", "olaf", "pw")
        self.assertNotIn("pw", s.stream_url(Track("1", "t", "a")))      # salted token, never the password


class Queue(unittest.TestCase):
    def test_order(self):
        from lidio.player import Player
        p = Player()
        p._load = lambda play=True: None                                 # no GStreamer playing in tests
        tracks = [Track(str(i), f"T{i}", "A") for i in range(5)]
        p.play(object(), tracks, 2)
        self.assertEqual(p.current.id, "2")
        p.play_next([Track("x", "X", "A")])
        self.assertEqual([t.id for _, t in p.upcoming()][:2], ["x", "3"])
        p.toggle_shuffle()
        self.assertEqual(p.current.id, "2")                              # shuffling keeps the playing title
        self.assertEqual(len(p.order), 6)


if __name__ == "__main__":
    unittest.main()


class MoreTest(unittest.TestCase):
    def test_missing_note_roundtrip(self):
        from lidio.servers import MissingNote
        text = MissingNote.write("Meine Liste", ["3 · 2Pac – California Love", "Nena – 99 Luftballons"])
        self.assertTrue(text.startswith("Meine Liste"))
        self.assertEqual(MissingNote.read(text), ["3 · 2Pac – California Love", "Nena – 99 Luftballons"])
        self.assertEqual(MissingNote.write(text, []), "Meine Liste")
        o = MissingNote.with_origin(text, "https://www.deezer.com/playlist/1")
        self.assertEqual(MissingNote.origin(o), "https://www.deezer.com/playlist/1")

    def test_offline_index(self):
        import tempfile
        from lidio import offline
        from lidio.servers import Track
        with tempfile.TemporaryDirectory() as d:
            offline.ROOT = d
            o = offline.Offline(); o.use({"id": "konto-1"}, None)
            t = Track("x1", "Titel", "Interpret", "Album")
            self.assertIsNone(o.state(t))
            os.makedirs(o.folder); open(os.path.join(o.folder, "x1.flac"), "wb").write(b"abc")
            from dataclasses import asdict
            o.index["x1"] = {"file": "x1.flac", "size": 3, "track": asdict(t)}; o._write()
            o2 = offline.Offline(); o2.use({"id": "konto-1"}, None)
            self.assertEqual(o2.state(t), "local")
            self.assertEqual(o2.tracks()[0].title, "Titel")
            o2.remove([t])
            self.assertIsNone(o2.state(t))

    def test_genre_names(self):
        from lidio import genres
        genres._lang = lambda: "de"
        self.assertEqual(genres.local("Classical"), "Klassik")
        self.assertEqual(genres.local("Synthwave"), "Synthwave")


class ImportTest(unittest.TestCase):
    def test_formats(self):
        from lidio import importing as I
        m = I.read("#EXTM3U\n#EXTINF:215,2Pac - California Love\nx.mp3\n/a/03 Nena - 99 Luftballons.flac\n", "a.m3u")
        self.assertEqual([(w.artist, w.title, w.seconds) for w in m], [("2Pac", "California Love", 215), ("Nena", "99 Luftballons", 0)])
        c = I.read('Track Name,Artist Name(s),Album Name,Duration (ms)\n"Hey, Jude",The Beatles,1,431000\n', "x.csv")
        self.assertEqual((c[0].title, c[0].seconds), ("Hey, Jude", 431))
        self.assertEqual(I.read("1. Kraftwerk – Das Model")[0].artist, "Kraftwerk")
        self.assertEqual(I.parse_link("https://open.spotify.com/intl-de/playlist/37i9dQZF1DXcBWIGoYBM5M"), ("Spotify", "37i9dQZF1DXcBWIGoYBM5M"))

    def test_judge(self):
        from lidio import importing as I
        t = Track("1", "California Love", "2Pac, Dr. Dre", duration=284)
        self.assertEqual(I.judge(I.Wanted("California Love (feat. Roger Troutman) - 2011 Remaster", "2Pac"), [t]).match, I.FOUND)
        self.assertEqual(I.judge(I.Wanted("Changes", "2Pac"), [t]).match, I.MISSING)
        self.assertEqual(len(I.without_duplicates([t, t, Track("2", "California love", "2pac, Dr. Dre", duration=285)])), 1)


class LocalTest(unittest.TestCase):
    def test_scan_and_play(self):
        import tempfile, subprocess, shutil
        if not shutil.which("gst-launch-1.0"):
            self.skipTest("gst-launch fehlt")
        from lidio import local
        with tempfile.TemporaryDirectory() as d:
            local.ROOT = os.path.join(d, "index")
            music = os.path.join(d, "Musik", "Testtöne"); os.makedirs(music)
            f = os.path.join(music, "01 Quinte.ogg")
            subprocess.run(["gst-launch-1.0", "-q", "audiotestsrc", "num-buffers=50", "!", "audioconvert", "!", "vorbisenc", "!", "oggmux", "!",
                            "filesink", f"location={f}"], check=True)
            import mutagen
            m = mutagen.File(f); m["title"] = "Quinte"; m["artist"] = "Die Testtöne"; m["album"] = "Testtöne"; m["tracknumber"] = "1"; m["genre"] = "Electronic"; m.save()
            lib = local.LocalLibrary("k1", [os.path.join(d, "Musik")])
            self.assertEqual(lib.scan(), 1)
            al = lib.albums("title")[0]
            self.assertEqual((al.title, al.artist), ("Testtöne", "Die Testtöne"))
            a, tracks = lib.album(al.id)
            self.assertEqual(tracks[0].title, "Quinte")
            self.assertTrue(lib.stream_url(tracks[0]).startswith("file://"))
            p = lib.create_playlist("Meine", tracks); self.assertEqual(lib.playlist(p.id)[1][0].title, "Quinte")
            lib.set_favorite(tracks[0], True); self.assertEqual(len(lib.favorites()), 1)
            self.assertEqual(lib.search("quin").tracks[0].title, "Quinte")
            self.assertEqual(lib.genres()[0].name, "Electronic")


class ShareTest(unittest.TestCase):
    def test_round_trip_matches_android(self):
        from lidio.share import Shared
        s = Shared("ae0c2c1c", "t42", "Weißes Rauschen & mehr", "Anna Analog", "Rauschen #1")
        self.assertTrue(s.link().startswith("https://lisoft.goip.de/lidio/t#s=ae0c2c1c&t=t42&n=Wei%C3%9Fes%20Rauschen%20%26%20mehr"))
        self.assertEqual(Shared.parse(s.link()), s)
        self.assertEqual(Shared.parse("lidio://t#n=Quinte&a=Die%20Testt%C3%B6ne").artist, "Die Testtöne")
        self.assertIsNone(Shared.parse("https://example.com/t#n=x"))
