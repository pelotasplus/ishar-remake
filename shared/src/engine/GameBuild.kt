package dev.pelotasplus.ishar.engine

/**
 * Ishar 1 and Ishar 2 run one engine built at other addresses: the handlers, the engine data and the
 * sound driver are byte-identical, only the layout moved.
 * Run-time segments are for the load segment 017d. One [Machine] runs one build.
 */
enum class GameBuild(
    val title: String,
    /** Where its files sit under `composeResources/files`. */
    val folder: String,
    /** `seg_0c0b`: the engine globals, `DS = SS`. */
    val ss: Int,
    /** `seg_0e97`: the VGA driver. */
    val vgaDrv: Int,
    /** `seg_0941`: the sound and MOD driver. */
    val snd: Int,
    /** `seg_0bac`: the SB driver's data. */
    val sbd: Int,
    /** The first free paragraph after the program. */
    val freePara: Int,
    /** `blancpc.io` is loaded at boot (`builtin_samples_load`); the heap then starts 148h higher. */
    val builtinSamples: Boolean,
    /** `cs:` x, y, buttons words of the INT 33h callback. */
    val mouse: Int,
    /** `cs:` the statement 61h/66h words (`5d0c..5d18` in Ishar 1). */
    val cs5d0c: Int,
    /** `cs:` steer_directions. */
    val steer: Int,
    /** `cs:` the 4-bit Fibonacci deltas of `sound_bank_prepare`. */
    val fibonacci: Int,
    /** `cs:` the music player's byte. */
    val music: Int,
    /** `cs:` the MOD player's words: +0, +2 (= +34h), the +3eh flag. */
    val mod: Int,
    /** Script globals, run time `globals:G+2a0`. */
    val globals: Int,
    /** Script glob of the party's X, Y, facing bytes. */
    val partyPos: Int,
    /** Exe residue at `ss:6867` that `frame_create` leaves in `BP-20h`. */
    val ss6867: String,
    /** The launcher's short name. */
    val short: String = "",
    /** The VGA screen's segment ([0b86]). */
    val screen: Int = 0xA000,
    /** The last paragraph of the heap ([0b96]): DOS's 640 KB. */
    val heapEnd: Int = 0x9FFF,
    /**
     * Bytes kept after the frame memory, in the frames' segment, for the arrays a 32-bit engine allocates
     * (Ishar 3: one, main.io's second map layer, 14aeh: 4,674 + 800h bytes from statement e9h).
     */
    val pointerArrayBytes: Int = 0,
    /** Where main.io's frame (its locals are the script globals) has its BP: script global G is at [globals]:G + this. */
    val globBase: Int = 0x2A0,
) {
    /**
     * Crystals of Arborea (1990), the trilogy's first game, as re-released on Ishar 1's engine: its START2.EXE
     * is Ishar 1's with SS data identical (plus 220h zero bytes), the type-7f draw path removed (as Ishar 2),
     * a rewritten SB part of the sound driver, and frame periods of at least 2 (statement 6ah).
     */
    ARBOREA(
        title = "Crystals of Arborea",
        short = "Arborea",
        folder = "arborea/",
        ss = 0x0DB1, vgaDrv = 0x105F, snd = 0x0ACD, sbd = 0x0D52,
        freePara = 0x111D, builtinSamples = true,
        mouse = 0x1266, cs5d0c = 0x5DFD, steer = 0x695E, fibonacci = 0x1890, music = 0x87C1, mod = 0x8A09,
        // the 3D view's X, Y, facing (samb.io 00bd, ecran.io); main.io's frame is at BP 2dch (ish frames, live)
        globals = 0x1265, partyPos = 0x2B38,
        ss6867 = "06860b00b8c3c35053515257561e065583faff740883fafe740eeb1490b40bb7",
        globBase = 0x2DC,
    ),
    ISHAR1(
        title = "Ishar: Legend of the Fortress",
        short = "Ishar 1",
        folder = "ishar1/",
        ss = 0x0D88, vgaDrv = 0x1014, snd = 0x0ABE, sbd = 0x0D29,
        freePara = 0x1123, builtinSamples = true,
        mouse = 0x11BD, cs5d0c = 0x5D0C, steer = 0x6866, fibonacci = 0x16BC, music = 0x86C1, mod = 0x8909,
        globals = 0x126B, partyPos = 0x137C,
        ss6867 = "e4740143b828002bc3d1e0d1e02ea330002ea12a00d1e3d1e303c32ea33200c3",
    ),
    ISHAR2(
        title = "Ishar 2: Messengers of Doom",
        short = "Ishar 2",
        folder = "ishar2/",
        ss = 0x0D04, vgaDrv = 0x0F90, snd = 0x0A3A, sbd = 0x0CA5,
        freePara = 0x104E, builtinSamples = false, // no blancpc.io: the heap starts at freePara
        mouse = 0x1093, cs5d0c = 0x54CA, steer = 0x6024, fibonacci = 0x14FB, music = 0x7E81, mod = 0x80C9,
        globals = 0x104E, partyPos = 0x2AB4,
        ss6867 = "368916e11d36c606340c0036c606350c00268b450c25030036a3eb1d743c268b",
    ),
    ISHAR3(
        title = "Ishar 3: The Seven Gates of Infinity",
        short = "Ishar 3",
        folder = "ishar3/",
        // A 32-bit engine (X-32 DOS extender) with no 16-bit layout of its own: the scripts address their
        // own globals and frames, so Ishar 2's layout serves (an experiment)
        ss = 0x0D04, vgaDrv = 0x0F90, snd = 0x0A3A, sbd = 0x0CA5,
        freePara = 0x104E, builtinSamples = false,
        mouse = 0x1093, cs5d0c = 0x54CA, steer = 0x6024, fibonacci = 0x14FB, music = 0x7E81, mod = 0x80C9,
        globals = 0x104E, partyPos = 0x2AB4,
        ss6867 = "368916e11d36c606340c0036c606350c00268b450c25030036a3eb1d743c268b",
        // The 32-bit engine has megabytes; its intro alone holds 822 KB at once (main, runner, intro1, intro2).
        // The screen moves above the heap (F000:0000..F9FF) so the heap runs up to the draw page at E000.
        screen = 0xF000,
        heapEnd = 0xDFFF,
        pointerArrayBytes = 0x1A50,
        globBase = 0x3F4, // 80 frame slots (main.io's boot block), not 50
    ),
    ;

    val heap: Int get() = freePara + if (builtinSamples) 0x148 else 0

    /** A short name for menus and the control server. */
    val key: String get() = name.lowercase()

    companion object {
        fun of(key: String): GameBuild? = entries.firstOrNull { it.key == key.lowercase() }
    }
}
