package io.github.veritasx1.lidio

/* Winamp 2 skin sprite coordinates, generated from Webamp's skinSprites.ts
 * (https://github.com/captbaritone/webamp, MIT License, Copyright (c) 2015 Jordan Eldredge). Do not edit by hand. */

/** Where each part of a Winamp 2 skin sits in its bitmap (sheet = file name without .bmp). */
class Sprite(val sheet: String, val x: Int, val y: Int, val w: Int, val h: Int)

object Sprites {
    private val all = HashMap<String, Sprite>(400)
    operator fun get(name: String): Sprite = all.getValue(name)
    fun find(name: String): Sprite? = all[name]

    /** text.bmp: 5×6 letters; row, column of each character. */
    val FONT: Map<Char, Pair<Int, Int>> = mapOf(
        'a' to (0 to 0),
        'b' to (0 to 1),
        'c' to (0 to 2),
        'd' to (0 to 3),
        'e' to (0 to 4),
        'f' to (0 to 5),
        'g' to (0 to 6),
        'h' to (0 to 7),
        'i' to (0 to 8),
        'j' to (0 to 9),
        'k' to (0 to 10),
        'l' to (0 to 11),
        'm' to (0 to 12),
        'n' to (0 to 13),
        'o' to (0 to 14),
        'p' to (0 to 15),
        'q' to (0 to 16),
        'r' to (0 to 17),
        's' to (0 to 18),
        't' to (0 to 19),
        'u' to (0 to 20),
        'v' to (0 to 21),
        'w' to (0 to 22),
        'x' to (0 to 23),
        'y' to (0 to 24),
        'z' to (0 to 25),
        '"' to (0 to 26),
        '@' to (0 to 27),
        ' ' to (0 to 30),
        '0' to (1 to 0),
        '1' to (1 to 1),
        '2' to (1 to 2),
        '3' to (1 to 3),
        '4' to (1 to 4),
        '5' to (1 to 5),
        '6' to (1 to 6),
        '7' to (1 to 7),
        '8' to (1 to 8),
        '9' to (1 to 9),
        '…' to (1 to 10),
        '.' to (1 to 11),
        ':' to (1 to 12),
        '(' to (1 to 13),
        ')' to (1 to 14),
        '-' to (1 to 15),
        '\'' to (1 to 16),
        '!' to (1 to 17),
        '_' to (1 to 18),
        '+' to (1 to 19),
        '\\' to (1 to 20),
        '/' to (1 to 21),
        '[' to (1 to 22),
        ']' to (1 to 23),
        '^' to (1 to 24),
        '&' to (1 to 25),
        '%' to (1 to 26),
        ',' to (1 to 27),
        '=' to (1 to 28),
        '\$' to (1 to 29),
        '#' to (1 to 30),
        'Å' to (2 to 0),
        'Ö' to (2 to 1),
        'Ä' to (2 to 2),
        '?' to (2 to 3),
        '*' to (2 to 4),
        '<' to (1 to 22),
        '>' to (1 to 23),
        '{' to (1 to 22),
        '}' to (1 to 23)
    )

    init {
        all["MAIN_BALANCE_BACKGROUND"] = Sprite("balance", 9, 0, 38, 420)
        all["MAIN_BALANCE_THUMB"] = Sprite("balance", 15, 422, 14, 11)
        all["MAIN_BALANCE_THUMB_ACTIVE"] = Sprite("balance", 0, 422, 14, 11)
        all["MAIN_PREVIOUS_BUTTON"] = Sprite("cbuttons", 0, 0, 23, 18)
        all["MAIN_PREVIOUS_BUTTON_ACTIVE"] = Sprite("cbuttons", 0, 18, 23, 18)
        all["MAIN_PLAY_BUTTON"] = Sprite("cbuttons", 23, 0, 23, 18)
        all["MAIN_PLAY_BUTTON_ACTIVE"] = Sprite("cbuttons", 23, 18, 23, 18)
        all["MAIN_PAUSE_BUTTON"] = Sprite("cbuttons", 46, 0, 23, 18)
        all["MAIN_PAUSE_BUTTON_ACTIVE"] = Sprite("cbuttons", 46, 18, 23, 18)
        all["MAIN_STOP_BUTTON"] = Sprite("cbuttons", 69, 0, 23, 18)
        all["MAIN_STOP_BUTTON_ACTIVE"] = Sprite("cbuttons", 69, 18, 23, 18)
        all["MAIN_NEXT_BUTTON"] = Sprite("cbuttons", 92, 0, 23, 18)
        all["MAIN_NEXT_BUTTON_ACTIVE"] = Sprite("cbuttons", 92, 18, 22, 18)
        all["MAIN_EJECT_BUTTON"] = Sprite("cbuttons", 114, 0, 22, 16)
        all["MAIN_EJECT_BUTTON_ACTIVE"] = Sprite("cbuttons", 114, 16, 22, 16)
        all["MAIN_WINDOW_BACKGROUND"] = Sprite("main", 0, 0, 275, 116)
        all["MAIN_STEREO"] = Sprite("monoster", 0, 12, 29, 12)
        all["MAIN_STEREO_SELECTED"] = Sprite("monoster", 0, 0, 29, 12)
        all["MAIN_MONO"] = Sprite("monoster", 29, 12, 27, 12)
        all["MAIN_MONO_SELECTED"] = Sprite("monoster", 29, 0, 27, 12)
        all["NO_MINUS_SIGN"] = Sprite("numbers", 9, 6, 5, 1)
        all["MINUS_SIGN"] = Sprite("numbers", 20, 6, 5, 1)
        all["DIGIT_0"] = Sprite("numbers", 0, 0, 9, 13)
        all["DIGIT_1"] = Sprite("numbers", 9, 0, 9, 13)
        all["DIGIT_2"] = Sprite("numbers", 18, 0, 9, 13)
        all["DIGIT_3"] = Sprite("numbers", 27, 0, 9, 13)
        all["DIGIT_4"] = Sprite("numbers", 36, 0, 9, 13)
        all["DIGIT_5"] = Sprite("numbers", 45, 0, 9, 13)
        all["DIGIT_6"] = Sprite("numbers", 54, 0, 9, 13)
        all["DIGIT_7"] = Sprite("numbers", 63, 0, 9, 13)
        all["DIGIT_8"] = Sprite("numbers", 72, 0, 9, 13)
        all["DIGIT_9"] = Sprite("numbers", 81, 0, 9, 13)
        all["NO_MINUS_SIGN_EX"] = Sprite("nums_ex", 90, 0, 9, 13)
        all["MINUS_SIGN_EX"] = Sprite("nums_ex", 99, 0, 9, 13)
        all["DIGIT_0_EX"] = Sprite("nums_ex", 0, 0, 9, 13)
        all["DIGIT_1_EX"] = Sprite("nums_ex", 9, 0, 9, 13)
        all["DIGIT_2_EX"] = Sprite("nums_ex", 18, 0, 9, 13)
        all["DIGIT_3_EX"] = Sprite("nums_ex", 27, 0, 9, 13)
        all["DIGIT_4_EX"] = Sprite("nums_ex", 36, 0, 9, 13)
        all["DIGIT_5_EX"] = Sprite("nums_ex", 45, 0, 9, 13)
        all["DIGIT_6_EX"] = Sprite("nums_ex", 54, 0, 9, 13)
        all["DIGIT_7_EX"] = Sprite("nums_ex", 63, 0, 9, 13)
        all["DIGIT_8_EX"] = Sprite("nums_ex", 72, 0, 9, 13)
        all["DIGIT_9_EX"] = Sprite("nums_ex", 81, 0, 9, 13)
        all["MAIN_PLAYING_INDICATOR"] = Sprite("playpaus", 0, 0, 9, 9)
        all["MAIN_PAUSED_INDICATOR"] = Sprite("playpaus", 9, 0, 9, 9)
        all["MAIN_STOPPED_INDICATOR"] = Sprite("playpaus", 18, 0, 9, 9)
        all["MAIN_NOT_WORKING_INDICATOR"] = Sprite("playpaus", 36, 0, 9, 9)
        all["MAIN_WORKING_INDICATOR"] = Sprite("playpaus", 39, 0, 9, 9)
        all["PLAYLIST_TOP_TILE"] = Sprite("pledit", 127, 21, 25, 20)
        all["PLAYLIST_TOP_LEFT_CORNER"] = Sprite("pledit", 0, 21, 25, 20)
        all["PLAYLIST_TITLE_BAR"] = Sprite("pledit", 26, 21, 100, 20)
        all["PLAYLIST_TOP_RIGHT_CORNER"] = Sprite("pledit", 153, 21, 25, 20)
        all["PLAYLIST_TOP_TILE_SELECTED"] = Sprite("pledit", 127, 0, 25, 20)
        all["PLAYLIST_TOP_LEFT_SELECTED"] = Sprite("pledit", 0, 0, 25, 20)
        all["PLAYLIST_TITLE_BAR_SELECTED"] = Sprite("pledit", 26, 0, 100, 20)
        all["PLAYLIST_TOP_RIGHT_CORNER_SELECTED"] = Sprite("pledit", 153, 0, 25, 20)
        all["PLAYLIST_LEFT_TILE"] = Sprite("pledit", 0, 42, 12, 29)
        all["PLAYLIST_RIGHT_TILE"] = Sprite("pledit", 31, 42, 20, 29)
        all["PLAYLIST_BOTTOM_TILE"] = Sprite("pledit", 179, 0, 25, 38)
        all["PLAYLIST_BOTTOM_LEFT_CORNER"] = Sprite("pledit", 0, 72, 125, 38)
        all["PLAYLIST_BOTTOM_RIGHT_CORNER"] = Sprite("pledit", 126, 72, 150, 38)
        all["PLAYLIST_VISUALIZER_BACKGROUND"] = Sprite("pledit", 205, 0, 75, 38)
        all["PLAYLIST_SHADE_BACKGROUND"] = Sprite("pledit", 72, 57, 25, 14)
        all["PLAYLIST_SHADE_BACKGROUND_LEFT"] = Sprite("pledit", 72, 42, 25, 14)
        all["PLAYLIST_SHADE_BACKGROUND_RIGHT"] = Sprite("pledit", 99, 57, 50, 14)
        all["PLAYLIST_SHADE_BACKGROUND_RIGHT_SELECTED"] = Sprite("pledit", 99, 42, 50, 14)
        all["PLAYLIST_SCROLL_HANDLE_SELECTED"] = Sprite("pledit", 61, 53, 8, 18)
        all["PLAYLIST_SCROLL_HANDLE"] = Sprite("pledit", 52, 53, 8, 18)
        all["PLAYLIST_ADD_URL"] = Sprite("pledit", 0, 111, 22, 18)
        all["PLAYLIST_ADD_URL_SELECTED"] = Sprite("pledit", 23, 111, 22, 18)
        all["PLAYLIST_ADD_DIR"] = Sprite("pledit", 0, 130, 22, 18)
        all["PLAYLIST_ADD_DIR_SELECTED"] = Sprite("pledit", 23, 130, 22, 18)
        all["PLAYLIST_ADD_FILE"] = Sprite("pledit", 0, 149, 22, 18)
        all["PLAYLIST_ADD_FILE_SELECTED"] = Sprite("pledit", 23, 149, 22, 18)
        all["PLAYLIST_REMOVE_ALL"] = Sprite("pledit", 54, 111, 22, 18)
        all["PLAYLIST_REMOVE_ALL_SELECTED"] = Sprite("pledit", 77, 111, 22, 18)
        all["PLAYLIST_CROP"] = Sprite("pledit", 54, 130, 22, 18)
        all["PLAYLIST_CROP_SELECTED"] = Sprite("pledit", 77, 130, 22, 18)
        all["PLAYLIST_REMOVE_SELECTED"] = Sprite("pledit", 54, 149, 22, 18)
        all["PLAYLIST_REMOVE_SELECTED_SELECTED"] = Sprite("pledit", 77, 149, 22, 18)
        all["PLAYLIST_REMOVE_MISC"] = Sprite("pledit", 54, 168, 22, 18)
        all["PLAYLIST_REMOVE_MISC_SELECTED"] = Sprite("pledit", 77, 168, 22, 18)
        all["PLAYLIST_INVERT_SELECTION"] = Sprite("pledit", 104, 111, 22, 18)
        all["PLAYLIST_INVERT_SELECTION_SELECTED"] = Sprite("pledit", 127, 111, 22, 18)
        all["PLAYLIST_SELECT_ZERO"] = Sprite("pledit", 104, 130, 22, 18)
        all["PLAYLIST_SELECT_ZERO_SELECTED"] = Sprite("pledit", 127, 130, 22, 18)
        all["PLAYLIST_SELECT_ALL"] = Sprite("pledit", 104, 149, 22, 18)
        all["PLAYLIST_SELECT_ALL_SELECTED"] = Sprite("pledit", 127, 149, 22, 18)
        all["PLAYLIST_SORT_LIST"] = Sprite("pledit", 154, 111, 22, 18)
        all["PLAYLIST_SORT_LIST_SELECTED"] = Sprite("pledit", 177, 111, 22, 18)
        all["PLAYLIST_FILE_INFO"] = Sprite("pledit", 154, 130, 22, 18)
        all["PLAYLIST_FILE_INFO_SELECTED"] = Sprite("pledit", 177, 130, 22, 18)
        all["PLAYLIST_MISC_OPTIONS"] = Sprite("pledit", 154, 149, 22, 18)
        all["PLAYLIST_MISC_OPTIONS_SELECTED"] = Sprite("pledit", 177, 149, 22, 18)
        all["PLAYLIST_NEW_LIST"] = Sprite("pledit", 204, 111, 22, 18)
        all["PLAYLIST_NEW_LIST_SELECTED"] = Sprite("pledit", 227, 111, 22, 18)
        all["PLAYLIST_SAVE_LIST"] = Sprite("pledit", 204, 130, 22, 18)
        all["PLAYLIST_SAVE_LIST_SELECTED"] = Sprite("pledit", 227, 130, 22, 18)
        all["PLAYLIST_LOAD_LIST"] = Sprite("pledit", 204, 149, 22, 18)
        all["PLAYLIST_LOAD_LIST_SELECTED"] = Sprite("pledit", 227, 149, 22, 18)
        all["PLAYLIST_ADD_MENU_BAR"] = Sprite("pledit", 48, 111, 3, 54)
        all["PLAYLIST_REMOVE_MENU_BAR"] = Sprite("pledit", 100, 111, 3, 72)
        all["PLAYLIST_SELECT_MENU_BAR"] = Sprite("pledit", 150, 111, 3, 54)
        all["PLAYLIST_MISC_MENU_BAR"] = Sprite("pledit", 200, 111, 3, 54)
        all["PLAYLIST_LIST_BAR"] = Sprite("pledit", 250, 111, 3, 54)
        all["PLAYLIST_CLOSE_SELECTED"] = Sprite("pledit", 52, 42, 9, 9)
        all["PLAYLIST_COLLAPSE_SELECTED"] = Sprite("pledit", 62, 42, 9, 9)
        all["PLAYLIST_EXPAND_SELECTED"] = Sprite("pledit", 150, 42, 9, 9)
        all["EQ_SHADE_BACKGROUND_SELECTED"] = Sprite("eq_ex", 0, 0, 275, 14)
        all["EQ_SHADE_BACKGROUND"] = Sprite("eq_ex", 0, 15, 275, 14)
        all["EQ_SHADE_VOLUME_SLIDER_LEFT"] = Sprite("eq_ex", 1, 30, 3, 7)
        all["EQ_SHADE_VOLUME_SLIDER_CENTER"] = Sprite("eq_ex", 4, 30, 3, 7)
        all["EQ_SHADE_VOLUME_SLIDER_RIGHT"] = Sprite("eq_ex", 7, 30, 3, 7)
        all["EQ_SHADE_BALANCE_SLIDER_LEFT"] = Sprite("eq_ex", 11, 30, 3, 7)
        all["EQ_SHADE_BALANCE_SLIDER_CENTER"] = Sprite("eq_ex", 14, 30, 3, 7)
        all["EQ_SHADE_BALANCE_SLIDER_RIGHT"] = Sprite("eq_ex", 17, 30, 3, 7)
        all["EQ_MAXIMIZE_BUTTON_ACTIVE"] = Sprite("eq_ex", 1, 38, 9, 9)
        all["EQ_MINIMIZE_BUTTON_ACTIVE"] = Sprite("eq_ex", 1, 47, 9, 9)
        all["EQ_SHADE_CLOSE_BUTTON"] = Sprite("eq_ex", 11, 38, 9, 9)
        all["EQ_SHADE_CLOSE_BUTTON_ACTIVE"] = Sprite("eq_ex", 11, 47, 9, 9)
        all["EQ_WINDOW_BACKGROUND"] = Sprite("eqmain", 0, 0, 275, 116)
        all["EQ_TITLE_BAR"] = Sprite("eqmain", 0, 149, 275, 14)
        all["EQ_TITLE_BAR_SELECTED"] = Sprite("eqmain", 0, 134, 275, 14)
        all["EQ_SLIDER_BACKGROUND"] = Sprite("eqmain", 13, 164, 209, 129)
        all["EQ_SLIDER_THUMB"] = Sprite("eqmain", 0, 164, 11, 11)
        all["EQ_SLIDER_THUMB_SELECTED"] = Sprite("eqmain", 0, 176, 11, 11)
        all["EQ_CLOSE_BUTTON"] = Sprite("eqmain", 0, 116, 9, 9)
        all["EQ_CLOSE_BUTTON_ACTIVE"] = Sprite("eqmain", 0, 125, 9, 9)
        all["EQ_MAXIMIZE_BUTTON_ACTIVE_FALLBACK"] = Sprite("eqmain", 254, 152, 9, 9)
        all["EQ_ON_BUTTON"] = Sprite("eqmain", 10, 119, 26, 12)
        all["EQ_ON_BUTTON_DEPRESSED"] = Sprite("eqmain", 128, 119, 26, 12)
        all["EQ_ON_BUTTON_SELECTED"] = Sprite("eqmain", 69, 119, 26, 12)
        all["EQ_ON_BUTTON_SELECTED_DEPRESSED"] = Sprite("eqmain", 187, 119, 26, 12)
        all["EQ_AUTO_BUTTON"] = Sprite("eqmain", 36, 119, 32, 12)
        all["EQ_AUTO_BUTTON_DEPRESSED"] = Sprite("eqmain", 154, 119, 32, 12)
        all["EQ_AUTO_BUTTON_SELECTED"] = Sprite("eqmain", 95, 119, 32, 12)
        all["EQ_AUTO_BUTTON_SELECTED_DEPRESSED"] = Sprite("eqmain", 213, 119, 32, 12)
        all["EQ_GRAPH_BACKGROUND"] = Sprite("eqmain", 0, 294, 113, 19)
        all["EQ_GRAPH_LINE_COLORS"] = Sprite("eqmain", 115, 294, 1, 19)
        all["EQ_PRESETS_BUTTON"] = Sprite("eqmain", 224, 164, 44, 12)
        all["EQ_PRESETS_BUTTON_SELECTED"] = Sprite("eqmain", 224, 176, 44, 12)
        all["EQ_PREAMP_LINE"] = Sprite("eqmain", 0, 314, 113, 1)
        all["MAIN_POSITION_SLIDER_BACKGROUND"] = Sprite("posbar", 0, 0, 248, 10)
        all["MAIN_POSITION_SLIDER_THUMB"] = Sprite("posbar", 248, 0, 29, 10)
        all["MAIN_POSITION_SLIDER_THUMB_SELECTED"] = Sprite("posbar", 278, 0, 29, 10)
        all["MAIN_SHUFFLE_BUTTON"] = Sprite("shufrep", 28, 0, 47, 15)
        all["MAIN_SHUFFLE_BUTTON_DEPRESSED"] = Sprite("shufrep", 28, 15, 47, 15)
        all["MAIN_SHUFFLE_BUTTON_SELECTED"] = Sprite("shufrep", 28, 30, 47, 15)
        all["MAIN_SHUFFLE_BUTTON_SELECTED_DEPRESSED"] = Sprite("shufrep", 28, 45, 47, 15)
        all["MAIN_REPEAT_BUTTON"] = Sprite("shufrep", 0, 0, 28, 15)
        all["MAIN_REPEAT_BUTTON_DEPRESSED"] = Sprite("shufrep", 0, 15, 28, 15)
        all["MAIN_REPEAT_BUTTON_SELECTED"] = Sprite("shufrep", 0, 30, 28, 15)
        all["MAIN_REPEAT_BUTTON_SELECTED_DEPRESSED"] = Sprite("shufrep", 0, 45, 28, 15)
        all["MAIN_EQ_BUTTON"] = Sprite("shufrep", 0, 61, 23, 12)
        all["MAIN_EQ_BUTTON_SELECTED"] = Sprite("shufrep", 0, 73, 23, 12)
        all["MAIN_EQ_BUTTON_DEPRESSED"] = Sprite("shufrep", 46, 61, 23, 12)
        all["MAIN_EQ_BUTTON_DEPRESSED_SELECTED"] = Sprite("shufrep", 46, 73, 23, 12)
        all["MAIN_PLAYLIST_BUTTON"] = Sprite("shufrep", 23, 61, 23, 12)
        all["MAIN_PLAYLIST_BUTTON_SELECTED"] = Sprite("shufrep", 23, 73, 23, 12)
        all["MAIN_PLAYLIST_BUTTON_DEPRESSED"] = Sprite("shufrep", 69, 61, 23, 12)
        all["MAIN_PLAYLIST_BUTTON_DEPRESSED_SELECTED"] = Sprite("shufrep", 69, 73, 23, 12)
        all["MAIN_TITLE_BAR"] = Sprite("titlebar", 27, 15, 275, 14)
        all["MAIN_TITLE_BAR_SELECTED"] = Sprite("titlebar", 27, 0, 275, 14)
        all["MAIN_EASTER_EGG_TITLE_BAR"] = Sprite("titlebar", 27, 72, 275, 14)
        all["MAIN_EASTER_EGG_TITLE_BAR_SELECTED"] = Sprite("titlebar", 27, 57, 275, 14)
        all["MAIN_OPTIONS_BUTTON"] = Sprite("titlebar", 0, 0, 9, 9)
        all["MAIN_OPTIONS_BUTTON_DEPRESSED"] = Sprite("titlebar", 0, 9, 9, 9)
        all["MAIN_MINIMIZE_BUTTON"] = Sprite("titlebar", 9, 0, 9, 9)
        all["MAIN_MINIMIZE_BUTTON_DEPRESSED"] = Sprite("titlebar", 9, 9, 9, 9)
        all["MAIN_SHADE_BUTTON"] = Sprite("titlebar", 0, 18, 9, 9)
        all["MAIN_SHADE_BUTTON_DEPRESSED"] = Sprite("titlebar", 9, 18, 9, 9)
        all["MAIN_CLOSE_BUTTON"] = Sprite("titlebar", 18, 0, 9, 9)
        all["MAIN_CLOSE_BUTTON_DEPRESSED"] = Sprite("titlebar", 18, 9, 9, 9)
        all["MAIN_CLUTTER_BAR_BACKGROUND"] = Sprite("titlebar", 304, 0, 8, 43)
        all["MAIN_CLUTTER_BAR_BACKGROUND_DISABLED"] = Sprite("titlebar", 312, 0, 8, 43)
        all["MAIN_CLUTTER_BAR_BUTTON_O_SELECTED"] = Sprite("titlebar", 304, 47, 8, 8)
        all["MAIN_CLUTTER_BAR_BUTTON_A_SELECTED"] = Sprite("titlebar", 312, 55, 8, 7)
        all["MAIN_CLUTTER_BAR_BUTTON_I_SELECTED"] = Sprite("titlebar", 320, 62, 8, 7)
        all["MAIN_CLUTTER_BAR_BUTTON_D_SELECTED"] = Sprite("titlebar", 328, 69, 8, 8)
        all["MAIN_CLUTTER_BAR_BUTTON_V_SELECTED"] = Sprite("titlebar", 336, 77, 8, 7)
        all["MAIN_SHADE_BACKGROUND"] = Sprite("titlebar", 27, 42, 275, 14)
        all["MAIN_SHADE_BACKGROUND_SELECTED"] = Sprite("titlebar", 27, 29, 275, 14)
        all["MAIN_SHADE_BUTTON_SELECTED"] = Sprite("titlebar", 0, 27, 9, 9)
        all["MAIN_SHADE_BUTTON_SELECTED_DEPRESSED"] = Sprite("titlebar", 9, 27, 9, 9)
        all["MAIN_SHADE_POSITION_BACKGROUND"] = Sprite("titlebar", 0, 36, 17, 7)
        all["MAIN_SHADE_POSITION_THUMB"] = Sprite("titlebar", 20, 36, 3, 7)
        all["MAIN_SHADE_POSITION_THUMB_LEFT"] = Sprite("titlebar", 17, 36, 3, 7)
        all["MAIN_SHADE_POSITION_THUMB_RIGHT"] = Sprite("titlebar", 23, 36, 3, 7)
        all["MAIN_VOLUME_BACKGROUND"] = Sprite("volume", 0, 0, 68, 420)
        all["MAIN_VOLUME_THUMB"] = Sprite("volume", 15, 422, 14, 11)
        all["MAIN_VOLUME_THUMB_SELECTED"] = Sprite("volume", 0, 422, 14, 11)
        all["GEN_TOP_LEFT_SELECTED"] = Sprite("gen", 0, 0, 25, 20)
        all["GEN_TOP_LEFT_END_SELECTED"] = Sprite("gen", 26, 0, 25, 20)
        all["GEN_TOP_CENTER_FILL_SELECTED"] = Sprite("gen", 52, 0, 25, 20)
        all["GEN_TOP_RIGHT_END_SELECTED"] = Sprite("gen", 78, 0, 25, 20)
        all["GEN_TOP_LEFT_RIGHT_FILL_SELECTED"] = Sprite("gen", 104, 0, 25, 20)
        all["GEN_TOP_RIGHT_SELECTED"] = Sprite("gen", 130, 0, 25, 20)
        all["GEN_TOP_LEFT"] = Sprite("gen", 0, 21, 25, 20)
        all["GEN_TOP_LEFT_END"] = Sprite("gen", 26, 21, 25, 20)
        all["GEN_TOP_CENTER_FILL"] = Sprite("gen", 52, 21, 25, 20)
        all["GEN_TOP_RIGHT_END"] = Sprite("gen", 78, 21, 25, 20)
        all["GEN_TOP_LEFT_RIGHT_FILL"] = Sprite("gen", 104, 21, 25, 20)
        all["GEN_TOP_RIGHT"] = Sprite("gen", 130, 21, 25, 20)
        all["GEN_BOTTOM_LEFT"] = Sprite("gen", 0, 42, 125, 14)
        all["GEN_BOTTOM_RIGHT"] = Sprite("gen", 0, 57, 125, 14)
        all["GEN_BOTTOM_FILL"] = Sprite("gen", 127, 72, 25, 14)
        all["GEN_MIDDLE_LEFT"] = Sprite("gen", 127, 42, 11, 29)
        all["GEN_MIDDLE_LEFT_BOTTOM"] = Sprite("gen", 158, 42, 11, 24)
        all["GEN_MIDDLE_RIGHT"] = Sprite("gen", 139, 42, 8, 29)
        all["GEN_MIDDLE_RIGHT_BOTTOM"] = Sprite("gen", 170, 42, 8, 24)
        all["GEN_CLOSE_SELECTED"] = Sprite("gen", 148, 42, 9, 9)
    }
}
