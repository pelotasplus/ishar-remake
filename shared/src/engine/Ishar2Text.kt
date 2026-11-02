package dev.pelotasplus.ishar.engine

/**
 * A translation slip in Ishar 2's English messagee.io: LOAD, SAVE and the I -> II import share
 * message 118/124 (select at 0213), which says "...the file to be transfered", so LOAD and SAVE
 * read like the import (the French file has one neutral sentence for all three, "Entrez le nom de
 * votre sauvegarde"), and message 86h says "not in Ishar I format" also where LOAD refuses an Ishar 1
 * save. The text is inline in the script, so each fix keeps its length.
 */
object Ishar2Text {
    private val FIXES = mapOf(
        "messagee.io" to listOf(
            "enter the name of the file to be transfered" to "enter the name of the saved game file below",
            // 86h: LOAD refuses an Ishar 1 save (io.io 0406) and I -> II an Ishar 2 save (0681); one line for both
            "F ile not in I shar I  format" to "F ile is for the other I shar",
        ),
    )

    /** Patches the asset [name] just loaded at linear [at], up to [end]. */
    fun fix(m: Machine, name: String, at: Int, end: Int) {
        val fixes = FIXES[GameFiles.normalizeName(name)] ?: return
        for ((old, new) in fixes) {
            require(old.length == new.length)
            val o = old.encodeToByteArray()
            val i = (at until end - o.size).firstOrNull { p -> o.indices.all { m.m[p + it] == o[it] } } ?: continue
            new.encodeToByteArray().copyInto(m.m, i)
        }
    }
}
