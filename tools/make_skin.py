#!/usr/bin/env python3
"""LiDio's own Winamp 2 skin ("LiDio Graphit"), drawn pixel by pixel – Winamp's original skin belongs to Nullsoft/AOL
and may not be shipped. Same layout as every Winamp 2 skin, so any .wsz works next to it.

    python3 tools/make_skin.py  →  android/app/src/main/assets/skins/lidio.wsz

Coordinates come from android/.../Sprites.kt (generated from Webamp's skinSprites.ts, MIT).
Copyright (C) 2026 Olaf Winkler – GPL-3.0.
"""
import io, re, zipfile, os
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
SPRITES = os.path.join(HERE, "../android/app/src/main/java/io/github/veritasx1/lidio/Sprites.kt")
OUT = os.path.join(HERE, "../android/app/src/main/assets/skins/lidio.wsz")

S = {}
for m in re.finditer(r'all\["([A-Z0-9_]+)"\] = Sprite\("([a-z_]+)", (\d+), (\d+), (\d+), (\d+)\)', open(SPRITES).read()):
    S[m.group(1)] = (m.group(2), *map(int, m.groups()[2:]))
FONT = {}
for m in re.finditer(r"'(\\.|.)' to \((\d+) to (\d+)\)", open(SPRITES).read()):
    ch = m.group(1).replace("\\\\", "\\").replace("\\'", "'").replace("\\$", "$")
    FONT[ch] = (int(m.group(2)), int(m.group(3)))

# --- colours: Apple's dark greys, LiDio pink ---
BG0, BG1, BG2 = (28, 28, 30), (44, 44, 46), (58, 58, 60)
EDGE, LCD = (72, 72, 74), (10, 10, 12)
TEXT, DIM = (235, 235, 245), (120, 120, 128)
PINK, PINK2, VIOLET = (255, 55, 95), (255, 120, 150), (140, 80, 230)

SIZES = {"main": (275, 116), "cbuttons": (136, 36), "titlebar": (344, 87), "numbers": (99, 13), "nums_ex": (108, 13),
         "text": (155, 18), "posbar": (307, 10), "volume": (68, 433), "balance": (68, 433), "shufrep": (92, 85),
         "monoster": (58, 24), "playpaus": (42, 9), "eqmain": (275, 315), "eq_ex": (275, 82), "pledit": (280, 186)}
IMG = {k: Image.new("RGB", v, BG0) for k, v in SIZES.items()}

def box(name):
    sheet, x, y, w, h = S[name]
    return IMG[sheet], x, y, w, h

def fill(name, colour):
    im, x, y, w, h = box(name); ImageDraw.Draw(im).rectangle([x, y, x + w - 1, y + h - 1], fill=colour)

def panel(im, x0, y0, x1, y1, top=BG2, bottom=BG1, edge=EDGE):
    d = ImageDraw.Draw(im)
    for yy in range(y0, y1 + 1):
        t = (yy - y0) / max(1, y1 - y0)
        d.line([x0, yy, x1, yy], fill=tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3)))
    d.rectangle([x0, y0, x1, y1], outline=edge)

# --- 3×5 pixel font for text.bmp (5×6 cells) ---
G = {
 "A": "010101111101101", "B": "110101110101110", "C": "011100100100011", "D": "110101101101110", "E": "111100110100111",
 "F": "111100110100100", "G": "011100101101011", "H": "101101111101101", "I": "111010010010111", "J": "001001001101010",
 "K": "101101110101101", "L": "100100100100111", "M": "101111111101101", "N": "110101101101101", "O": "010101101101010",
 "P": "110101110100100", "Q": "010101101110011", "R": "110101110101101", "S": "011100010001110", "T": "111010010010010",
 "U": "101101101101111", "V": "101101101101010", "W": "101101111111101", "X": "101101010101101", "Y": "101101010010010",
 "Z": "111001010100111", "0": "111101101101111", "1": "010110010010111", "2": "110001010100111", "3": "110001010001110",
 "4": "101101111001001", "5": "111100110001110", "6": "011100111101111", "7": "111001010010010", "8": "111101111101111",
 "9": "111101111001110", " ": "000000000000000", ".": "000000000000010", ":": "000010000010000", "(": "001010010010001",
 ")": "100010010010100", "-": "000000111000000", "'": "010010000000000", "!": "010010010000010", "_": "000000000000111",
 "+": "000010111010000", "\\": "100100010001001", "/": "001001010100100", "[": "011010010010011", "]": "110010010010110",
 "^": "010101000000000", "&": "010101010101011", "%": "101001010100101", ",": "000000000010100", "=": "000111000111000",
 "$": "011110010011110", "#": "101111101111101", "\"": "101101000000000", "@": "111101111100011", "?": "110001010000010",
 "*": "000101010101000", "<": "001010100010001", ">": "100010001010100", "…": "000000000000101",
 "Å": "010000010101111", "Ö": "101000010101010", "Ä": "101000010101111",
}

def glyph(im, ch, x, y, colour):
    bits = G.get(ch.upper()) or G.get(ch) or G[" "]
    for i, b in enumerate(bits):
        if b == "1": im.putpixel((x + 1 + i % 3, y + i // 3), colour)

def write(im, text, x, y, colour=TEXT, step=5):
    for i, ch in enumerate(text): glyph(im, ch, x + i * step, y, colour)

def text_bmp():
    im = IMG["text"]
    ImageDraw.Draw(im).rectangle([0, 0, 154, 17], fill=LCD)
    for ch, (row, col) in FONT.items():
        glyph(im, ch, col * 5, row * 6, PINK2 if ch.isdigit() else TEXT)

# --- 7-segment digits (numbers.bmp, 9×13) ---
SEG = {0: "abcdef", 1: "bc", 2: "abged", 3: "abgcd", 4: "fgbc", 5: "afgcd", 6: "afgedc", 7: "abc", 8: "abcdefg", 9: "abcdfg"}
def digit(im, x, n, colour, off=(40, 40, 44)):
    d = ImageDraw.Draw(im)
    seg = {"a": [(x + 2, 1), (x + 6, 1)], "d": [(x + 2, 11), (x + 6, 11)], "g": [(x + 2, 6), (x + 6, 6)],
           "f": [(x + 1, 2), (x + 1, 5)], "b": [(x + 7, 2), (x + 7, 5)], "e": [(x + 1, 7), (x + 1, 10)], "c": [(x + 7, 7), (x + 7, 10)]}
    for k, line in seg.items():
        d.line(line, fill=colour if n is not None and k in SEG[n] else off)

def numbers_bmp():
    for name in ("numbers", "nums_ex"):
        im = IMG[name]; ImageDraw.Draw(im).rectangle([0, 0, im.width - 1, im.height - 1], fill=LCD)
        for n in range(10): digit(im, n * 9, n, PINK)
        digit(im, 90, None, PINK)
    ImageDraw.Draw(IMG["numbers"]).line([20, 6, 24, 6], fill=PINK)
    ImageDraw.Draw(IMG["nums_ex"]).line([101, 6, 105, 6], fill=PINK)

# --- main window ---
def main_bmp():
    im = IMG["main"]; d = ImageDraw.Draw(im)
    panel(im, 0, 0, 274, 115, BG1, BG0, (90, 90, 94))
    d.rounded_rectangle([20, 20, 105, 63], 3, fill=LCD, outline=EDGE)          # time + visualizer
    d.rounded_rectangle([108, 20, 268, 53], 3, fill=LCD, outline=EDGE)         # title, kbps, khz, mono/stereo
    write(im, "KBPS", 127, 43, DIM); write(im, "KHZ", 167, 43, DIM)
    for x0 in (16, 136):
        pass
    d.line([10, 66, 264, 66], fill=EDGE)

def titlebar_bmp():
    im = IMG["titlebar"]; d = ImageDraw.Draw(im)
    for name, active in (("MAIN_TITLE_BAR", False), ("MAIN_TITLE_BAR_SELECTED", True),
                         ("MAIN_EASTER_EGG_TITLE_BAR", False), ("MAIN_EASTER_EGG_TITLE_BAR_SELECTED", True),
                         ("MAIN_SHADE_BACKGROUND", False), ("MAIN_SHADE_BACKGROUND_SELECTED", True)):
        _, x, y, w, h = box(name)
        panel(im, x, y, x + w - 1, y + h - 1, BG2 if active else BG1, BG1 if active else BG0)
        if "SHADE" not in name:
            write(im, "LIDIO", x + w // 2 - 12, y + 4, PINK if active else DIM)
            for i in range(3): d.line([x + 30, y + 4 + i * 3, x + w // 2 - 20, y + 4 + i * 3], fill=EDGE)
            for i in range(3): d.line([x + w // 2 + 18, y + 4 + i * 3, x + w - 40, y + 4 + i * 3], fill=EDGE)
    def tiny(name, kind, pressed):
        _, x, y, w, h = box(name)
        d.rounded_rectangle([x, y, x + 8, y + 8], 2, fill=BG0 if pressed else BG2, outline=EDGE)
        c = PINK if pressed else TEXT
        if kind == "close": d.line([x + 2, y + 2, x + 6, y + 6], fill=c); d.line([x + 6, y + 2, x + 2, y + 6], fill=c)
        elif kind == "min": d.line([x + 2, y + 6, x + 6, y + 6], fill=c)
        elif kind == "shade": d.rectangle([x + 2, y + 2, x + 6, y + 4], outline=c)
        else: d.rectangle([x + 3, y + 3, x + 5, y + 5], fill=c)
    for n, k in (("MAIN_OPTIONS_BUTTON", "opt"), ("MAIN_MINIMIZE_BUTTON", "min"), ("MAIN_SHADE_BUTTON", "shade"), ("MAIN_CLOSE_BUTTON", "close"),
                 ("MAIN_SHADE_BUTTON_SELECTED", "shade")):
        tiny(n, k, False)
        if n + "_DEPRESSED" in S: tiny(n + "_DEPRESSED", k, True)
    for n in ("MAIN_CLUTTER_BAR_BACKGROUND", "MAIN_CLUTTER_BAR_BACKGROUND_DISABLED"):
        _, x, y, w, h = box(n); panel(im, x, y, x + w - 1, y + h - 1, BG1, BG0)
        for i, ch in enumerate("OAIDV"): glyph(im, ch, x + 1, y + 4 + i * 7, DIM if "DISABLED" in n else TEXT)
    for i, ch in enumerate("OAIDV"):
        _, x, y, w, h = box(f"MAIN_CLUTTER_BAR_BUTTON_{ch}_SELECTED")
        d.rectangle([x, y, x + w - 1, y + h - 1], fill=BG0); glyph(im, ch, x + 1, y + 1, PINK)
    for n in ("MAIN_SHADE_POSITION_BACKGROUND",): fill(n, LCD)
    for n in ("MAIN_SHADE_POSITION_THUMB", "MAIN_SHADE_POSITION_THUMB_LEFT", "MAIN_SHADE_POSITION_THUMB_RIGHT"): fill(n, PINK)

def cbuttons_bmp():
    im = IMG["cbuttons"]; d = ImageDraw.Draw(im)
    for base in ("PREVIOUS", "PLAY", "PAUSE", "STOP", "NEXT", "EJECT"):
        for pressed in (False, True):
            name = f"MAIN_{base}_BUTTON" + ("_ACTIVE" if pressed else "")
            _, x, y, w, h = box(name)
            d.rounded_rectangle([x, y, x + w - 1, y + h - 1], 3, fill=BG0 if pressed else BG2, outline=EDGE)
            c = PINK if pressed else TEXT; cx, cy = x + w // 2, y + h // 2
            if base == "PLAY": d.polygon([(cx - 3, cy - 4), (cx - 3, cy + 4), (cx + 4, cy)], fill=c)
            elif base == "PAUSE": d.rectangle([cx - 4, cy - 4, cx - 2, cy + 4], fill=c); d.rectangle([cx + 1, cy - 4, cx + 3, cy + 4], fill=c)
            elif base == "STOP": d.rectangle([cx - 3, cy - 3, cx + 3, cy + 3], fill=c)
            elif base == "PREVIOUS": d.polygon([(cx + 1, cy - 4), (cx + 1, cy + 4), (cx - 4, cy)], fill=c); d.polygon([(cx + 6, cy - 4), (cx + 6, cy + 4), (cx + 1, cy)], fill=c)
            elif base == "NEXT": d.polygon([(cx - 1, cy - 4), (cx - 1, cy + 4), (cx + 4, cy)], fill=c); d.polygon([(cx - 6, cy - 4), (cx - 6, cy + 4), (cx - 1, cy)], fill=c)
            else: d.polygon([(cx - 4, cy + 1), (cx + 4, cy + 1), (cx, cy - 4)], fill=c); d.rectangle([cx - 4, cy + 3, cx + 4, cy + 4], fill=c)

def posbar_bmp():
    im = IMG["posbar"]; d = ImageDraw.Draw(im)
    _, x, y, w, h = box("MAIN_POSITION_SLIDER_BACKGROUND")
    d.rectangle([x, y, x + w - 1, y + h - 1], fill=BG0); d.rounded_rectangle([x + 1, y + 3, x + w - 2, y + 6], 2, fill=LCD, outline=EDGE)
    for n, c in (("MAIN_POSITION_SLIDER_THUMB", TEXT), ("MAIN_POSITION_SLIDER_THUMB_SELECTED", PINK)):
        _, x, y, w, h = box(n); d.rectangle([x, y, x + w - 1, y + h - 1], fill=BG0); d.rounded_rectangle([x + 1, y + 1, x + w - 2, y + h - 2], 4, fill=c)

def slider_frames(sheet, x0, width, centred):
    im = IMG[sheet]; d = ImageDraw.Draw(im)
    for f in range(28):
        y = f * 15
        d.rectangle([x0, y, x0 + width - 1, y + 12], fill=BG0)
        d.rounded_rectangle([x0 + 1, y + 4, x0 + width - 2, y + 8], 2, fill=LCD, outline=EDGE)
        t = f / 27
        if centred:
            c = tuple(int(BG2[i] + (PINK[i] - BG2[i]) * t) for i in range(3)); d.rounded_rectangle([x0 + 2, y + 5, x0 + width - 3, y + 7], 1, fill=c)
        else:
            end = x0 + 2 + int((width - 5) * t)
            c = tuple(int(VIOLET[i] + (PINK[i] - VIOLET[i]) * t) for i in range(3))
            if f: d.rectangle([x0 + 2, y + 5, end, y + 7], fill=c)

def volume_balance_bmp():
    slider_frames("volume", 0, 68, False); slider_frames("balance", 9, 38, True)
    for sheet in ("volume", "balance"):
        d = ImageDraw.Draw(IMG[sheet])
        pre = "MAIN_VOLUME" if sheet == "volume" else "MAIN_BALANCE"
        for n, c in ((pre + "_THUMB", TEXT), (pre + ("_THUMB_SELECTED" if sheet == "volume" else "_THUMB_ACTIVE"), PINK)):
            _, x, y, w, h = box(n); d.rectangle([x, y, x + w - 1, y + h - 1], fill=BG0); d.rounded_rectangle([x, y + 1, x + w - 1, y + h - 2], 4, fill=c)

def toggle(sheet_name, base, label, w_label_x=None):
    im, x, y, w, h = box(base); d = ImageDraw.Draw(im)
    states = [("", False, False), ("_DEPRESSED", False, True), ("_SELECTED", True, False), ("_SELECTED_DEPRESSED", True, True),
              ("_DEPRESSED_SELECTED", True, True), ("_ACTIVE", False, True)]
    for suffix, on, pressed in states:
        n = base + suffix
        if n not in S: continue
        _, x, y, w, h = box(n)
        d.rounded_rectangle([x, y, x + w - 1, y + h - 1], 3, fill=BG0 if pressed else BG2, outline=PINK if on else EDGE)
        write(im, label, x + (w - len(label) * 5) // 2, y + (h - 5) // 2, PINK if on else TEXT)

def shufrep_bmp():
    toggle("shufrep", "MAIN_SHUFFLE_BUTTON", "SHUF"); toggle("shufrep", "MAIN_REPEAT_BUTTON", "REP")
    toggle("shufrep", "MAIN_EQ_BUTTON", "EQ"); toggle("shufrep", "MAIN_PLAYLIST_BUTTON", "PL")

def monoster_bmp():
    im = IMG["monoster"]; d = ImageDraw.Draw(im)
    for n, label in (("MAIN_STEREO", "STEREO"), ("MAIN_MONO", "MONO")):
        for sel in ("", "_SELECTED"):
            _, x, y, w, h = box(n + sel); d.rectangle([x, y, x + w - 1, y + h - 1], fill=LCD)
            write(im, label, x + (w - len(label) * 4) // 2 - 1, y + 3, PINK if sel else (50, 50, 56), step=4)

def playpaus_bmp():
    im = IMG["playpaus"]; d = ImageDraw.Draw(im); d.rectangle([0, 0, 41, 8], fill=LCD)
    _, x, y, w, h = box("MAIN_PLAYING_INDICATOR"); d.polygon([(x + 2, y + 1), (x + 2, y + 7), (x + 7, y + 4)], fill=PINK)
    _, x, y, w, h = box("MAIN_PAUSED_INDICATOR"); d.rectangle([x + 2, y + 1, x + 3, y + 7], fill=PINK2); d.rectangle([x + 5, y + 1, x + 6, y + 7], fill=PINK2)
    _, x, y, w, h = box("MAIN_STOPPED_INDICATOR"); d.rectangle([x + 2, y + 2, x + 6, y + 6], fill=DIM)
    _, x, y, w, h = box("MAIN_WORKING_INDICATOR"); d.rectangle([x, y + 3, x + 1, y + 5], fill=PINK)

# --- equalizer ---
def eqmain_bmp():
    im = IMG["eqmain"]; d = ImageDraw.Draw(im)
    _, x, y, w, h = box("EQ_WINDOW_BACKGROUND"); panel(im, x, y, x + w - 1, y + h - 1, BG1, BG0, (90, 90, 94))
    d.rounded_rectangle([84, 15, 200, 37], 2, fill=LCD, outline=EDGE)
    write(im, "+12", 46, 37, DIM); write(im, "0", 52, 65, DIM); write(im, "-12", 46, 96, DIM)
    for i, label in enumerate(("60", "170", "310", "600", "1K", "3K", "6K", "12K", "14K", "16K")):
        write(im, label, 78 + i * 18 + (14 - len(label) * 4) // 2 - 1, 104, DIM)
    write(im, "PRE", 18, 104, DIM)
    for n, active in (("EQ_TITLE_BAR", False), ("EQ_TITLE_BAR_SELECTED", True)):
        _, x, y, w, h = box(n); panel(im, x, y, x + w - 1, y + h - 1, BG2 if active else BG1, BG1 if active else BG0)
        write(im, "EQUALIZER", x + w // 2 - 22, y + 4, PINK if active else DIM)
    _, x0, y0, w, h = box("EQ_SLIDER_BACKGROUND")
    for f in range(28):
        x = x0 + (f % 14) * 15; y = y0 + (f // 14) * 65
        d.rectangle([x, y, x + 13, y + 62], fill=BG0); d.rounded_rectangle([x + 5, y + 1, x + 8, y + 61], 2, fill=LCD, outline=EDGE)
        t = f / 27                          # frame 0 = -12 dB, frame 27 = +12 dB (Webamp: floor(value/100·27))
        c = tuple(int(VIOLET[i] + (PINK[i] - VIOLET[i]) * t) for i in range(3))
        top = y + 1 + int(60 * (1 - t))
        d.rectangle([x + 6, min(top, y + 31), x + 7, max(top, y + 31)], fill=c)
    for n, c in (("EQ_SLIDER_THUMB", TEXT), ("EQ_SLIDER_THUMB_SELECTED", PINK)):
        _, x, y, w, h = box(n); d.rectangle([x, y, x + w - 1, y + h - 1], fill=BG0); d.rounded_rectangle([x, y + 2, x + w - 1, y + h - 3], 3, fill=c)
    toggle("eqmain", "EQ_ON_BUTTON", "ON"); toggle("eqmain", "EQ_AUTO_BUTTON", "AUTO")
    for n, pressed in (("EQ_PRESETS_BUTTON", False), ("EQ_PRESETS_BUTTON_SELECTED", True)):
        _, x, y, w, h = box(n); d.rounded_rectangle([x, y, x + w - 1, y + h - 1], 3, fill=BG0 if pressed else BG2, outline=EDGE)
        write(im, "PRESETS", x + 4, y + 4, PINK if pressed else TEXT)
    _, x, y, w, h = box("EQ_GRAPH_BACKGROUND"); d.rectangle([x, y, x + w - 1, y + h - 1], fill=LCD)
    for yy in (y + 9,): d.line([x, yy, x + w - 1, yy], fill=(40, 40, 44))
    _, x, y, w, h = box("EQ_GRAPH_LINE_COLORS")
    for i in range(h): im.putpixel((x, y + i), tuple(int(PINK[c] + (VIOLET[c] - PINK[c]) * i / (h - 1)) for c in range(3)))
    fill("EQ_PREAMP_LINE", DIM)
    for n in ("EQ_CLOSE_BUTTON", "EQ_CLOSE_BUTTON_ACTIVE", "EQ_MAXIMIZE_BUTTON_ACTIVE_FALLBACK"):
        _, x, y, w, h = box(n); d.rounded_rectangle([x, y, x + 8, y + 8], 2, fill=BG2, outline=EDGE)
        d.line([x + 2, y + 2, x + 6, y + 6], fill=PINK if "ACTIVE" in n else TEXT); d.line([x + 6, y + 2, x + 2, y + 6], fill=PINK if "ACTIVE" in n else TEXT)

def eq_ex_bmp():
    for n in [k for k, v in S.items() if v[0] == "eq_ex"]:
        im, x, y, w, h = box(n)
        if "BACKGROUND" in n: panel(im, x, y, x + w - 1, y + h - 1, BG2 if "SELECTED" in n else BG1, BG0)
        else: ImageDraw.Draw(im).rectangle([x, y, x + w - 1, y + h - 1], fill=PINK if "SLIDER" in n else BG2)

# --- playlist ---
def pledit_bmp():
    im = IMG["pledit"]; d = ImageDraw.Draw(im)
    for n in [k for k, v in S.items() if v[0] == "pledit"]:
        _, x, y, w, h = box(n); sel = "SELECTED" in n
        if any(t in n for t in ("TOP_", "TITLE_BAR", "SHADE_BACKGROUND")):
            panel(im, x, y, x + w - 1, y + h - 1, BG2 if sel else BG1, BG1 if sel else BG0)
            if "TITLE_BAR" in n: write(im, "PLAYLIST", x + w // 2 - 20, y + 7, PINK if sel else DIM)
        elif n in ("PLAYLIST_LEFT_TILE", "PLAYLIST_RIGHT_TILE"):
            panel(im, x, y, x + w - 1, y + h - 1, BG1, BG1, BG1); d.line([x + (w - 3 if "LEFT" in n else 2), y, x + (w - 3 if "LEFT" in n else 2), y + h - 1], fill=EDGE)
        elif "BOTTOM" in n:
            panel(im, x, y, x + w - 1, y + h - 1, BG1, BG0)
        elif "VISUALIZER_BACKGROUND" in n:
            d.rectangle([x, y, x + w - 1, y + h - 1], fill=BG0); d.rounded_rectangle([x + 2, y + 12, x + w - 3, y + h - 8], 2, fill=LCD)
        elif "SCROLL_HANDLE" in n:
            d.rectangle([x, y, x + w - 1, y + h - 1], fill=BG1); d.rounded_rectangle([x + 1, y, x + w - 2, y + h - 1], 3, fill=PINK if sel else BG2)
        elif "MENU_BAR" in n or n == "PLAYLIST_LIST_BAR":
            d.rectangle([x, y, x + w - 1, y + h - 1], fill=PINK)
        elif w == 22 and h == 18:
            label = {"ADD_URL": "URL", "ADD_DIR": "DIR", "ADD_FILE": "ADD", "REMOVE_ALL": "ALL", "CROP": "CROP", "REMOVE_SELECTED": "DEL",
                     "REMOVE_MISC": "MISC", "INVERT_SELECTION": "INV", "SELECT_ZERO": "NONE", "SELECT_ALL": "ALL", "SORT_LIST": "SORT",
                     "FILE_INFO": "INFO", "MISC_OPTIONS": "OPT", "NEW_LIST": "NEW", "SAVE_LIST": "SAVE", "LOAD_LIST": "LOAD"}
            key = n.replace("PLAYLIST_", "").replace("_SELECTED", "")
            text = label.get(key, key[:3])[:4]
            d.rounded_rectangle([x, y, x + w - 1, y + h - 1], 3, fill=BG0 if sel else BG2, outline=EDGE)
            write(im, text, x + (w - len(text) * 5) // 2, y + 6, PINK if sel else TEXT)
        elif w == 9 and h == 9:
            d.rounded_rectangle([x, y, x + 8, y + 8], 2, fill=BG2, outline=EDGE); d.line([x + 2, y + 4, x + 6, y + 4], fill=PINK)
    # The frame's buttons in the bottom corners (ADD REM SEL MISC … LIST), as Winamp draws them into the corners.
    _, x, y, w, h = box("PLAYLIST_BOTTOM_LEFT_CORNER")
    for i, t in enumerate(("ADD", "REM", "SEL", "MISC")):
        bx = x + 14 + i * 29; d.rounded_rectangle([bx, y + 8, bx + 21, y + 25], 3, fill=BG2, outline=EDGE); write(im, t, bx + (22 - len(t) * 5) // 2, y + 14, TEXT)
    _, x, y, w, h = box("PLAYLIST_BOTTOM_RIGHT_CORNER")
    bx = x + w - 44; d.rounded_rectangle([bx, y + 8, bx + 21, y + 25], 3, fill=BG2, outline=EDGE); write(im, "LIST", bx + 1, y + 14, TEXT)
    d.rounded_rectangle([x + 7, y + 9, x + 62, y + 19], 2, fill=LCD)   # running time display

VIS = [(0, 0, 0), (24, 24, 26)] + [tuple(int(PINK[c] + (VIOLET[c] - PINK[c]) * i / 15) for c in range(3)) for i in range(16)] + \
      [(255, 255, 255), (90, 90, 94), (120, 120, 128), (150, 150, 158), (180, 180, 188), (255, 120, 150)]

def preview():
    """A picture like the museum's screenshots (275×348: main window, equalizer, playlist)."""
    out = Image.new("RGB", (275, 348), (0, 0, 0))
    def put(name, x, y):
        sheet, sx, sy, w, h = S[name]; out.paste(IMG[sheet].crop((sx, sy, sx + w, sy + h)), (x, y))
    put("MAIN_WINDOW_BACKGROUND", 0, 0); put("MAIN_TITLE_BAR_SELECTED", 0, 0); put("MAIN_CLUTTER_BAR_BACKGROUND", 10, 22)
    put("MAIN_PLAYING_INDICATOR", 26, 28)
    for d, x in zip((0, 1, 2, 3), (48, 60, 78, 90)): put(f"DIGIT_{(1, 0, 4, 2)[d]}", x, 26)
    t = IMG["main"]; img = out
    for i, ch in enumerate("LIDIO - WINAMP-MODUS"):
        if ch.lower() in FONT or ch in FONT:
            r, c = FONT.get(ch.lower(), FONT.get(ch))
            img.paste(IMG["text"].crop((c * 5, r * 6, c * 5 + 5, r * 6 + 6)), (111 + i * 5, 24))
    put("MAIN_STEREO_SELECTED", 239, 41); put("MAIN_MONO", 212, 41)
    out.paste(IMG["volume"].crop((0, 20 * 15, 68, 20 * 15 + 13)), (107, 57)); put("MAIN_VOLUME_THUMB", 107 + 40, 58)
    out.paste(IMG["balance"].crop((9, 0, 47, 13)), (177, 57)); put("MAIN_BALANCE_THUMB", 189, 58)
    put("MAIN_EQ_BUTTON_SELECTED", 219, 58); put("MAIN_PLAYLIST_BUTTON_SELECTED", 242, 58)
    put("MAIN_POSITION_SLIDER_BACKGROUND", 16, 72); put("MAIN_POSITION_SLIDER_THUMB", 90, 72)
    for n, x in (("PREVIOUS", 16), ("PLAY", 39), ("PAUSE", 62), ("STOP", 85), ("NEXT", 108)): put(f"MAIN_{n}_BUTTON", x, 88)
    put("MAIN_EJECT_BUTTON", 136, 89); put("MAIN_SHUFFLE_BUTTON", 164, 89); put("MAIN_REPEAT_BUTTON", 210, 89)
    put("EQ_WINDOW_BACKGROUND", 0, 116); put("EQ_TITLE_BAR_SELECTED", 0, 116)
    put("EQ_ON_BUTTON_SELECTED", 14, 134); put("EQ_AUTO_BUTTON", 40, 134); put("EQ_PRESETS_BUTTON", 217, 134); put("EQ_GRAPH_BACKGROUND", 86, 133)
    _, ex, ey, _, _ = S["EQ_SLIDER_BACKGROUND"]
    for i, (x, f) in enumerate(zip([21] + [78 + 18 * b for b in range(10)], (14, 18, 20, 17, 13, 11, 13, 16, 19, 21, 22))):
        out.paste(IMG["eqmain"].crop((ex + (f % 14) * 15, ey + (f // 14) * 65, ex + (f % 14) * 15 + 14, ey + (f // 14) * 65 + 63)), (x, 154))
        put("EQ_SLIDER_THUMB", x + 1, 154 + round((27 - f) / 27 * 51))
    put("PLAYLIST_TOP_LEFT_SELECTED", 0, 232)
    for x in range(25, 250, 25): put("PLAYLIST_TOP_TILE_SELECTED", x, 232)
    put("PLAYLIST_TITLE_BAR_SELECTED", 87, 232); put("PLAYLIST_TOP_RIGHT_CORNER_SELECTED", 250, 232)
    for y in (252, 281): put("PLAYLIST_LEFT_TILE", 0, y); put("PLAYLIST_RIGHT_TILE", 255, y)
    ImageDraw.Draw(out).rectangle([12, 252, 254, 309], fill=BG0)
    for i, line in enumerate(("1. LIDIO - GRAPHIT", "2. WINAMP - CLASSIC", "3. SKIN - MUSEUM")):
        for j, ch in enumerate(line):
            if ch.lower() in FONT or ch in FONT:
                r, c = FONT.get(ch.lower(), FONT.get(ch))
                out.paste(IMG["text"].crop((c * 5, r * 6, c * 5 + 5, r * 6 + 6)), (16 + j * 5, 258 + i * 12))
    put("PLAYLIST_BOTTOM_LEFT_CORNER", 0, 310); put("PLAYLIST_BOTTOM_RIGHT_CORNER", 125, 310)
    return out

def build():
    text_bmp(); numbers_bmp(); main_bmp(); titlebar_bmp(); cbuttons_bmp(); posbar_bmp(); volume_balance_bmp(); shufrep_bmp()
    monoster_bmp(); playpaus_bmp(); eqmain_bmp(); eq_ex_bmp(); pledit_bmp()
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as z:
        for name, im in IMG.items():
            buf = io.BytesIO(); im.save(buf, "BMP"); z.writestr(name + ".bmp", buf.getvalue())
        z.writestr("viscolor.txt", "\n".join(f"{r},{g},{b}" for r, g, b in VIS) + "\n")
        z.writestr("pledit.txt", "[Text]\nNormal=#EBEBF5\nCurrent=#FF375F\nNormalBG=#1C1C1E\nSelectedBG=#3A3A3C\nFont=Inter\n")
        z.writestr("readme.txt", "LiDio Graphit – LiDio's own Winamp 2 skin. GPL-3.0, (C) 2026 Olaf Winkler.\n")
    preview().save(OUT.replace(".wsz", ".png"))
    return OUT

if __name__ == "__main__":
    print(build())
