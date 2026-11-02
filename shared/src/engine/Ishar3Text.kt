package dev.pelotasplus.ishar.engine

/**
 * Ishar 3's start menu (accueil.io) is German in a game whose other texts are English. Its lines are inline
 * strings in the script, so each English one keeps the German one's length, padded on both sides (the menu
 * centres them). A space after a capital is how the game's font is written ("S piel").
 */
object Ishar3Text {
    private val MENU = listOf(
        "S pielstand laden" to "L oad game",
        "A utoren" to "C redits",
        "M usik: O N" to "M usic: O N",
        "M usik: O F F" to "M usic: O F F",
        "G ruppe zusammenstellen" to "C reate the party",
        "S piel beginnen" to "S tart game",
        "S piel beenden" to "Q uit game",
        "I shar 1 zu I shar 3" to "I shar 1 to I shar 3",
        "I shar 2 zu I shar 3" to "I shar 2 to I shar 3",
        "S iegfrieds G ef#hrten" to "S iegfried's party",
        "Z ur&ck zum M en&" to "B ack to M enu",
    )

    /** Patches accueil.io just loaded at linear [at], up to [end]. */
    fun fix(m: Machine, name: String, at: Int, end: Int) {
        if (GameFiles.normalizeName(name) != "accueil.io") return
        for ((old, new) in MENU) {
            val o = (old + "\u0000").encodeToByteArray()
            val pad = old.length - new.length
            require(pad >= 0) { new }
            val text = " ".repeat(pad / 2) + new + " ".repeat(pad - pad / 2)
            var p = at
            while (p <= end - o.size) {
                if (o.indices.all { m.m[p + it] == o[it] }) text.encodeToByteArray().copyInto(m.m, p)
                p++
            }
        }
    }
}
