package dev.pelotasplus.ishar.engine

/** The party's items: Ishar 2's inventories (glob 2d8e, 15 words a member: 0..8 pack, 9 and 10 hands, 11..14 worn) and the names its messages give them. */
object Items {
    data class Slot(val slot: Int, val code: Int, val worn: Boolean, val name: String)

    data class Member(val slot: Int, val name: String, val items: List<Slot>)

    /** What each living member carries; Ishar 2 only. Worn slots hold code + 1 (frise.io 29b4). */
    fun ishar2(m: Machine): List<Member> = Cheats.party(m).map { p ->
        val items = (0 until SLOTS).mapNotNull { s ->
            val inventory = if (m.build == GameBuild.ISHAR3) ISHAR3_INVENTORY else INVENTORY
            val raw = m.rw(m.build.globals, inventory + m.build.globBase + 2 * (SLOTS * p.slot + s)) and 0xFF
            if (raw == 0) return@mapNotNull null
            val worn = s in WORN
            val code = if (worn) raw - 1 else raw
            Slot(s, code, worn, if (m.build == GameBuild.ISHAR3) ISHAR3_NAMES[code] ?: "object ${code.toString(16).padStart(2, '0')}h" else name(code))
        }
        Member(p.slot, p.name, items)
    }

    /** Slot labels: 9 and 10 are the hands (frise.io 4b2f), 11..14 body, helmet, shield, neck (frise.io 2810). */
    fun slotName(slot: Int) = when (slot) {
        9, 10 -> "hand"
        11 -> "body"
        12 -> "helmet"
        13 -> "shield"
        14 -> "neck"
        else -> "pack"
    }

    /** messagee.io 17ef: a select on the signed item byte; codes it has no line for show "Ouille !!!" in the game. */
    fun name(code: Int): String = when (code) {
        in 0x02..0x06 -> "relic"
        else -> NAMES[code] ?: "object ${code.toString(16).padStart(2, '0')}h"
    }

    private const val INVENTORY = 0x2D8E

    /** Ishar 3: 1722h, the same 15 words a member (frise.io 22d7 clears them as a character joins). */
    private const val ISHAR3_INVENTORY = 0x1722
    private const val SLOTS = 15
    private val WORN = 11..14

    private val NAMES = mapOf(
        0x09 to "necklace", 0x10 to "magic cauldron", 0xf2 to "eagle", 0xf1 to "idol", 0xf0 to "Commander-in-Chief's key",
        0xef to "prison key", 0xeb to "prison key", 0xee to "horn", 0xed to "baboon", 0xec to "magpie", 0xea to "parrot",
        0xe9 to "mouse", 0xe8 to "plant", 0xe7 to "shield (+4)", 0xe6 to "shield (+1)", 0xe5 to "shield (+2)",
        0xe4 to "shield (+3)", 0xe3 to "shield (+6)", 0xdd to "pendant (dd)", 0xdf to "pendant (df)", 0xe1 to "pendant (e1)",
        0xd7 to "rope", 0xcf to "arrows", 0xce to "food", 0xcd to "white mushrooms", 0xcc to "oil of salamander",
        0xcb to "spider's web", 0xca to "dried mistletoe", 0xc9 to "rat's brain", 0xc8 to "gargoyle claws",
        0xc7 to "black mushrooms", 0xc6 to "edelweiss", 0xc5 to "bale of dandelions", 0xc3 to "armour (+2)",
        0xc1 to "coat of chain mail (+3)", 0xbf to "armour (+5)", 0xbd to "armour (+7)", 0xbb to "armour (+9)",
        0xb9 to "armour (+12)", 0xb7 to "evening tunic", 0xb5 to "armour (+2)", 0xb3 to "armour (+6)", 0xb1 to "fur coat",
        0xaf to "homespun", 0xad to "helmet (+1)", 0xab to "helmet (+2)", 0xa9 to "helmet (+3)", 0xa7 to "helmet (+4)",
        0xa6 to "throwing dagger (+2)", 0xa5 to "spear (+6)", 0xa4 to "sabre (+8)", 0xa3 to "dagger (+6)",
        0xa2 to "dagger (+2)", 0xa1 to "heavy axe (+12)", 0x9f to "crossbow (+9)", 0x9e to "bow (+6)", 0x9d to "flail (+8)",
        0x9c to "mace (+10)", 0x9b to "sword (+15)", 0x9a to "sword (+11)", 0x99 to "sword (+5)", 0x98 to "sword (+4)",
        0x97 to "bone bow (+8)", 0x96 to "club (+5)", 0x95 to "living sword (+20)", 0x94 to "living chaos sword (+20)",
        0x93 to "sword (+12)", 0x92 to "stick (+1)", 0x91 to "stick (+5)", 0x90 to "flail (+9)", 0x8f to "axe (+25)",
        0x8e to "trident (+6)", 0x8d to "whip (+2)", 0x8c to "dagger (+10)",
    )

    /** Ishar 3's names, message.io 2bc3 (a select on the signed item byte). */
    private val ISHAR3_NAMES = mapOf(
        0x02 to "crystal",
        0x03 to "crystal",
        0x04 to "meteor piece",
        0x09 to "leopard skin",
        0x10 to "magic flask",
        0x89 to "sword (+5)",
        0x8a to "fire axe (+30)",
        0x8b to "notched sword (+10)",
        0x8c to "dagger (+10)",
        0x8d to "whip (+2)",
        0x8e to "trident (+6)",
        0x8f to "axe (+25)",
        0x90 to "morning star (+9)",
        0x91 to "staff (+5)",
        0x92 to "staff (+1)",
        0x93 to "sword (+12)",
        0x94 to "living chaos sword (+20)",
        0x95 to "living sword (+20)",
        0x96 to "club (+5)",
        0x97 to "bow (+8)",
        0x98 to "sword (+4)",
        0x99 to "sword (+5)",
        0x9a to "sword (+11)",
        0x9b to "sword (+15)",
        0x9c to "mace (+10)",
        0x9d to "morning star (+8)",
        0x9e to "bow (+6)",
        0x9f to "crossbow (+9)",
        0xa1 to "heavy axe (+12)",
        0xa2 to "dagger (+2)",
        0xa3 to "dagger (+6)",
        0xa4 to "sabre (+8)",
        0xa5 to "spear (+6)",
        0xa6 to "throwing dagger (+2)",
        0xa7 to "helmet (+4)",
        0xa9 to "helmet (+3)",
        0xab to "helmet (+2)",
        0xad to "helmet (+7)",
        0xaf to "armour (+18)",
        0xb1 to "fur",
        0xb3 to "armour (+6)",
        0xb5 to "armour (+2)",
        0xb7 to "evening tunic",
        0xb9 to "armour (+12)",
        0xbb to "armour (+9)",
        0xbd to "armour (+7)",
        0xbf to "armour (+5)",
        0xc1 to "chain mail (+3)",
        0xc3 to "armour (+2)",
        0xc5 to "Kelonia powder",
        0xc6 to "edelweiss",
        0xc7 to "black mushrooms",
        0xc8 to "gargoyle claws",
        0xc9 to "rat brain",
        0xca to "dried mistletoe",
        0xcb to "black widow web",
        0xcc to "salamander oil",
        0xcd to "white mushrooms",
        0xce to "food",
        0xcf to "arrows",
        0xd5 to "magic belt",
        0xd7 to "rope",
        0xdd to "pendant (dd)",
        0xdf to "pendant (df)",
        0xe1 to "pendant (e1)",
        0xe3 to "shield (+6)",
        0xe4 to "shield (+3)",
        0xe5 to "shield (+2)",
        0xe6 to "shield (+1)",
        0xe7 to "shield (+4)",
        0xe8 to "house key",
        0xe9 to "leopard skin",
        0xea to "letter",
        0xec to "message",
        0xed to "key",
        0xf0 to "playing cards",
        0xf1 to "parcel",
        0xf2 to "lime liquor",
    )
}
