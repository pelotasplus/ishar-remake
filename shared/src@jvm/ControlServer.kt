package dev.pelotasplus.ishar.control

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.pelotasplus.ishar.engine.Cheats
import dev.pelotasplus.ishar.engine.GameBuild
import dev.pelotasplus.ishar.engine.Input
import dev.pelotasplus.ishar.engine.Frames
import dev.pelotasplus.ishar.engine.Items
import dev.pelotasplus.ishar.engine.SaveGames
import dev.pelotasplus.ishar.features.game.GameViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.Executors
import javax.imageio.ImageIO

/**
 * An MCP server (JSON-RPC over HTTP POST at `/mcp`, the shape of Spice86's) on 127.0.0.1 that
 * drives the running game: screen, input, state, saves, cheats, memory, pause/step. Input goes
 * through [GameViewModel] like the keyboard's.
 */
class ControlServer(private val game: GameViewModel, port: Int) {
    /** The app's window, for window_shot / window_click (set by the desktop main). */
    var window: java.awt.Window? = null

    private val ui = UiScene(game)

    /** 16-bit mono PCM as a WAV file. */
    private fun wav(pcm: ShortArray, rate: Int): ByteArray {
        val b = java.nio.ByteBuffer.allocate(44 + 2 * pcm.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + 2 * pcm.size).put("WAVEfmt ".toByteArray()).putInt(16)
        b.putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(2 * pcm.size)
        for (s in pcm) b.putShort(s)
        return b.array()
    }

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0).apply {
        executor = Executors.newCachedThreadPool { Thread(it, "mcp").apply { isDaemon = true } }
        createContext("/mcp", ::handle)
    }

    val port: Int get() = server.address.port

    fun start() = apply { server.start() }

    fun stop() = server.stop(0)

    private fun handle(ex: HttpExchange) = try {
        respond(ex)
    } finally {
        ex.close()
    }

    private fun respond(ex: HttpExchange) {
        if (ex.requestMethod != "POST") return ex.reply(405, "")
        val req = runCatching { Json.parseToJsonElement(ex.requestBody.readBytes().decodeToString()).jsonObject }
            .getOrElse { return ex.reply(400, rpcError(JsonNull, -32700, "parse error").toString()) }
        val id = req["id"] ?: return ex.reply(202, "") // a notification
        val method = req["method"]?.jsonPrimitive?.contentOrNull
        val params = req["params"] as? JsonObject ?: JsonObject(emptyMap())
        val reply = runCatching {
            when (method) {
                "initialize" -> rpcResult(id, initialize())
                "ping" -> rpcResult(id, JsonObject(emptyMap()))
                "tools/list" -> rpcResult(id, buildJsonObject { put("tools", JsonArray(TOOLS.map { it.json() })) })
                "tools/call" -> rpcResult(id, call(params))
                else -> rpcError(id, -32601, "no method $method")
            }
        }.getOrElse { rpcError(id, -32603, it.message ?: it.toString()) }
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.reply(200, reply.toString())
    }

    private fun HttpExchange.reply(code: Int, body: String) {
        val b = body.encodeToByteArray()
        sendResponseHeaders(code, if (b.isEmpty()) -1 else b.size.toLong())
        if (b.isNotEmpty()) responseBody.write(b)
    }

    private fun initialize() = buildJsonObject {
        put("protocolVersion", "2025-06-18")
        putJsonObject("capabilities") { putJsonObject("tools") {} }
        putJsonObject("serverInfo") {
            put("name", "ishar-game")
            put("version", "0.1")
        }
    }

    private fun call(params: JsonObject): JsonObject {
        val name = params["name"]?.jsonPrimitive?.content
        val tool = TOOLS.firstOrNull { it.name == name } ?: error("no tool $name")
        val args = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        return runCatching { runBlocking { tool.run(Args(args)) } }.fold(
            onSuccess = { it },
            onFailure = { e -> text(e.message ?: e.toString(), error = true) },
        )
    }

    private class Args(val o: JsonObject) {
        fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        fun int(k: String) = (o[k] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
        fun bool(k: String) = (o[k] as? JsonPrimitive)?.booleanOrNull
        fun need(k: String) = str(k) ?: error("missing $k")
        fun needInt(k: String) = int(k) ?: error("missing $k")
    }

    private class Param(val name: String, val type: String, val description: String, val required: Boolean = true)

    private class Tool(
        val name: String,
        val description: String,
        val params: List<Param> = emptyList(),
        val run: suspend (Args) -> JsonObject,
    ) {
        fun json() = buildJsonObject {
            put("name", name)
            put("description", description)
            putJsonObject("inputSchema") {
                put("type", "object")
                putJsonObject("properties") {
                    for (p in params) putJsonObject(p.name) {
                        put("type", p.type)
                        put("description", p.description)
                    }
                }
                putJsonArray("required") { params.filter { it.required }.forEach { add(JsonPrimitive(it.name)) } }
            }
        }
    }

    private val TOOLS = listOf(
        Tool("state", "Mode, frame, paused/muted, stop reason, party position (contree, X, Y, facing 1 N 2 E 3 S 4 W) and members.") {
            text(stateJson().toString())
        },
        Tool(
            "map",
            "Open (open=true) or close (open=false) the full map; the game goes on behind it. The window shows it, screenshot does not.",
            listOf(Param("open", "boolean", "open or close")),
        ) { a ->
            game.setMapOpen(a.need("open") == "true")
            text("ok")
        },
        Tool(
            "minimap",
            "The mini map's cells around the party as text, from the app's map: @ the party, # obstacle, D closed door (shut on one side), _ seen ground, . unseen, space off the map.",
            listOf(Param("radius", "integer", "cells each way (default 6)", false)),
        ) { a ->
            val r = (a.int("radius") ?: 6).coerceIn(1, 30)
            // on the engine thread, which is where see() changes the map
            text(game.onEngine { m ->
                val map = game.partyMap() ?: error("no map")
                val p = Cheats.position(m)
                val x0 = map.sheetX(p) ?: error("the party is on no sheet: $p")
                (p.y - r..p.y + r).joinToString("\n") { y ->
                    (x0 - r..x0 + r).joinToString("") { x ->
                        when {
                            x == x0 && y == p.y -> "@"
                            !map.onMap(x, y) || !map.sameSheet(x, x0) -> " "
                            map.colour(x, y) == null -> "."
                            map.closedDoor(x, y) -> "D"
                            map.blocks(x, y) -> "#"
                            else -> "_"
                        }
                    }
                }
            } ?: error("no game running"))
        },
        Tool("saves", "The Saves screen's rows, as it shows them: name and what it says under it (load_save is its tap).") {
            val st = game.state.value
            val rows = if (st.build == GameBuild.ISHAR1) {
                st.saveSummaries.map { "${it.name}: ${SaveGames.summaryLine(it)}" } + st.unloadableSaves.map { "$it: Cannot be loaded" }
            } else {
                st.ishar2Saves.map { (n, h) -> "$n: ${SaveGames.headerLine(st.build, h)}" }
            }
            text(rows.joinToString("\n"))
        },
        Tool("sound", "The music player (on: a tune plays, ss:0cd8) and the sound effects started since the game began.") {
            text(game.onEngine { m -> "music ${if (m.gb(0x0CD8) != 0) "on" else "off"}, effects started ${game.soundEffects()}" } ?: error("no game running"))
        },
        Tool(
            "window_shot",
            "The app window as it is on screen (the Skia layer's own pixels) to a PNG file.",
            listOf(Param("path", "string", "where to write the PNG")),
        ) { a ->
            val png = WindowAccess.shot(window ?: error("no window"))
            java.io.File(a.need("path")).writeBytes(png)
            text("wrote ${a.need("path")}")
        },
        Tool(
            "window_click",
            "A mouse click in the app window at x, y (window content pixels, as window_shot): the press and release go through the window's own event queue.",
            listOf(Param("x", "integer", "x"), Param("y", "integer", "y"), Param("hold_ms", "integer", "press length (default 120)", false)),
        ) { a ->
            text(WindowAccess.click(window ?: error("no window"), a.needInt("x"), a.needInt("y"), (a.int("hold_ms") ?: 120).toLong()))
        },
        Tool(
            "window_scroll",
            "Mouse-wheel notches in the app window at x, y (window_shot pixels); positive scrolls down.",
            listOf(Param("x", "integer", "x"), Param("y", "integer", "y"), Param("notches", "integer", "notches")),
        ) { a ->
            text(WindowAccess.scroll(window ?: error("no window"), a.needInt("x"), a.needInt("y"), a.needInt("notches")))
        },
        Tool(
            "window_key",
            "A key press in the app window (through its event queue): a letter, digit, or a VK name such as ESCAPE, ENTER, SPACE.",
            listOf(Param("key", "string", "key"), Param("hold_ms", "integer", "press length (default 150)", false)),
        ) { a ->
            text(WindowAccess.key(window ?: error("no window"), a.need("key"), (a.int("hold_ms") ?: 150).toLong()))
        },
        Tool(
            "ui_saves",
            "The app's Saves screen (the real composable with this app's state) drawn offscreen to a PNG file.",
            listOf(Param("path", "string", "where to write the PNG"), Param("height", "integer", "pixels (default 600)", false)),
        ) { a ->
            java.io.File(a.need("path")).writeBytes(ui.saves(height = (a.int("height") ?: 600).coerceIn(200, 4000)))
            text("wrote ${a.need("path")}")
        },
        Tool(
            "ui_tap",
            "Tap that Saves screen at x, y (pixels of its PNG): the row's own click runs; the screen after it to the PNG file.",
            listOf(Param("x", "integer", "x"), Param("y", "integer", "y"), Param("path", "string", "where to write the PNG")),
        ) { a ->
            val tap = ui.tap(a.needInt("x").toFloat(), a.needInt("y").toFloat())
            java.io.File(a.need("path")).writeBytes(tap.png)
            text(if (tap.played) "tapped: the screen asked to play" else "tapped")
        },
        Tool(
            "audio",
            "The game's mixed sound over the last seconds (what goes to the speaker, muted or not): peak, RMS, " +
                "the share of non-silent samples; with path, also a WAV of it.",
            listOf(Param("seconds", "integer", "how far back (default 3, at most 10)", false), Param("path", "string", "WAV file", false)),
        ) { a ->
            val pcm = game.recentAudio((a.int("seconds") ?: 3).coerceIn(1, 10))
            val peak = pcm.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
            val rms = if (pcm.isEmpty()) 0.0 else kotlin.math.sqrt(pcm.sumOf { it.toDouble() * it } / pcm.size)
            val loud = pcm.count { kotlin.math.abs(it.toInt()) > 64 }
            a.str("path")?.let { java.io.File(it).writeBytes(wav(pcm, 22050)) }
            text("samples ${pcm.size}, peak $peak, rms ${"%.0f".format(rms)}, non-silent ${if (pcm.isEmpty()) 0 else 100 * loud / pcm.size}%")
        },
        Tool("screenshot", "The game screen as a 320x200 PNG (source pixels; the display is 4:3).") {
            val argb = game.state.value.frame ?: error("no frame yet")
            val img = BufferedImage(320, 200, BufferedImage.TYPE_INT_RGB)
            img.setRGB(0, 0, 320, 200, argb, 0, 320)
            val png = ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
            buildJsonObject {
                putJsonArray("content") {
                    add(buildJsonObject {
                        put("type", "image")
                        put("mimeType", "image/png")
                        put("data", Base64.getEncoder().encodeToString(png))
                    })
                }
            }
        },
        Tool(
            "send_key",
            "Press and release a key (while paused, held for the frames hold_ms makes): a character (w, a, s, d, q, e move; letters type at prompts), up/down/left/right, " +
                "enter, space, backspace, tab, kp0..kp9 (the same keys as the digits 0..9), or a scancode number of two digits or more.",
            listOf(Param("key", "string", "the key"), Param("hold_ms", "integer", "how long it is held (default 150)", false)),
        ) { a ->
            val sc = scancode(a.need("key"))
            hold(a.int("hold_ms") ?: 150, { game.keyDown(sc) }, { game.keyUp(sc) })
            text("ok")
        },
        Tool("type", "Type text one key at a time (\\n is Enter), e.g. a save name at the save/load prompt.", listOf(Param("text", "string", "the text"))) { a ->
            for (c in a.need("text")) {
                val sc = if (c == '\n') Input.ENTER else scancode(c.toString())
                hold(150, { game.keyDown(sc) }, { game.keyUp(sc) })
                hold(100, {}, {})
            }
            text("ok")
        },
        Tool(
            "click",
            "Move the pointer to (x, y) in source pixels (320x200) and click: button 1 left, 2 right.",
            listOf(
                Param("x", "integer", "0..319"), Param("y", "integer", "0..199"),
                Param("button", "integer", "1 left (default), 2 right", false),
                Param("hold_ms", "integer", "how long it is held (default 200)", false),
            ),
        ) { a ->
            game.pointer(a.needInt("x"), a.needInt("y"))
            hold(60, {}, {})
            hold(a.int("hold_ms") ?: 200, { game.buttons(a.int("button") ?: 1) }, { game.buttons(0) })
            text("ok")
        },
        Tool("mouse_move", "Move the pointer to (x, y) in source pixels.", listOf(Param("x", "integer", "0..319"), Param("y", "integer", "0..199"))) { a ->
            game.pointer(a.needInt("x"), a.needInt("y"))
            text("ok")
        },
        Tool("select_game", "Switch to ishar1 or ishar2 and boot a new game of it.", listOf(Param("game", "string", "ishar1 or ishar2"))) { a ->
            val b = GameBuild.of(a.need("game")) ?: error("no game ${a.need("game")}: ishar1 or ishar2")
            started(needsGame = false) { game.selectGame(b) }
        },
        Tool("new_game", "Boot a new game (the intro plays).") { started(start = game::newGame) },
        Tool("skip_intro", "A new game walking in Fragonir (the intro skipped).") { started(start = game::skipIntro) },
        Tool("load_save", "Skip the intro and load a save by name (the player's, list_saves).", listOf(Param("name", "string", "save name"))) { a ->
            started { game.loadSave(a.need("name").uppercase()) }
        },
        Tool(
            "save_game",
            "Save the running game under a name (1..8 of A..Z, 0..9) through the game's own disk menu. " +
                "Fails while a panel or the inventory is open, or an item is on the pointer.",
            listOf(Param("name", "string", "save name")),
        ) { a ->
            val name = a.need("name").uppercase()
            require(SaveGames.typeable(name)) { "a name is 1..8 of A..Z and 0..9" }
            val held = game.onEngine { m -> if (m.build == GameBuild.ISHAR2) m.rw(m.build.globals, POINTER_ITEM) else 0 } ?: error("no game running")
            if (held != 0) {
                text("not saved: something is on the pointer (an item or a spell); put it away first", error = true)
            } else {
                val before = game.savesWritten
                game.saveGame(name)
                val frames = game.saveFrames(name) + 40
                if (game.state.value.paused) game.step(frames) else delay(frames * 1000L / 19)
                if (game.savesWritten == before) text("not saved: the disk menu did not take it (a panel or the inventory open?)", error = true)
                else text("saved $name")
            }
        },
        Tool("inventory", "Each member's items (Ishar 2): slot (9/10 hands, 11 body, 12 helmet, 13 shield, 14 neck, else pack), name, code, worn.") {
            val party = game.onEngine { m -> if (m.build != GameBuild.ISHAR1) Items.ishar2(m) else null } ?: error("Ishar 2 and 3 only, with a game running")
            text(
                JsonArray(
                    party.map { p ->
                        buildJsonObject {
                            put("slot", p.slot)
                            put("name", p.name)
                            putJsonArray("items") {
                                for (i in p.items) add(buildJsonObject {
                                    put("slot", i.slot)
                                    put("where", Items.slotName(i.slot))
                                    put("code", i.code.toString(16))
                                    put("name", i.name)
                                    put("worn", i.worn)
                                })
                            }
                        }
                    },
                ).toString(),
            )
        },
        Tool("list_saves", "The saves load_save takes.") {
            text(JsonArray(game.state.value.saves.map { JsonPrimitive(it) }).toString())
        },
        Tool(
            "debug_start",
            "The developers' debug start: a new game with their five-member party at X/Y/contree/region/zone.",
            listOf(
                Param("x", "integer", "X"), Param("y", "integer", "Y"), Param("contree", "integer", "1..6"),
                Param("region", "integer", "region (default 0)", false), Param("zone", "integer", "zone (default 0)", false),
            ),
        ) { a ->
            val p = Cheats.Place("mcp", a.needInt("x"), a.needInt("y"), a.needInt("contree"), a.int("region") ?: 0, a.int("zone") ?: 0)
            started { game.debugStart(p) }
        },
        Tool(
            "cheat",
            "Edit the running game: heal (life to max, poison/blindness off), " +
                "gold (slot, value), stat (slot, stat STR/CON/WIS/INT/AGI/MENTAL/PHYSICAL, value), move (x, y, facing; in the loaded contree), " +
                "free_saves (on: saving costs nothing), remove_curses.",
            listOf(
                Param("action", "string", "heal, gold, stat, move, free_saves, remove_curses"),
                Param("slot", "integer", "party slot 0..4", false), Param("stat", "string", "stat name", false),
                Param("value", "integer", "gold or stat value", false), Param("x", "integer", "X", false),
                Param("y", "integer", "Y", false), Param("facing", "integer", "1 N, 2 E, 3 S, 4 W", false),
                Param("on", "boolean", "free_saves on/off", false),
            ),
        ) { a ->
            when (val action = a.need("action")) {
                "heal" -> game.healAll()
                "gold" -> a.needInt("slot").let { s -> a.needInt("value").let { v -> game.cheat { Cheats.setGold(it, s, v) } } }
                "stat" -> {
                    val i = Cheats.STATS.indexOf(a.need("stat").uppercase()).takeIf { it >= 0 } ?: error("stat is one of ${Cheats.STATS}")
                    val s = a.needInt("slot")
                    val v = a.needInt("value")
                    game.cheat { Cheats.setStat(it, s, i, v) }
                }
                "move" -> {
                    val (x, y) = a.needInt("x") to a.needInt("y")
                    val f = a.int("facing") ?: game.state.value.position?.facing ?: 1
                    game.teleport(x, y, f)
                }
                "free_saves" -> game.setFreeSaves(a.bool("on") ?: true)
                "remove_curses" -> game.removeCurses()
                else -> error("no action $action")
            }
            text("ok")
        },
        Tool(
            "read_memory",
            "Read bytes from the DOS memory as hex. Address seg:ofs hex (126b:161c = party X/Y/facing; script global G is 126b:G+2a0).",
            listOf(Param("address", "string", "seg:ofs in hex"), Param("length", "integer", "bytes (default 16, at most 4096)", false)),
        ) { a ->
            val (seg, ofs) = address(a.need("address"))
            val n = (a.int("length") ?: 16).coerceIn(1, 4096)
            val hex = game.onEngine { m -> (0 until n).joinToString("") { "%02x".format(m.rb(seg, ofs + it)) } } ?: error("no game running")
            text(hex)
        },
        Tool(
            "write_memory",
            "Write hex bytes into the DOS memory at seg:ofs (between frames).",
            listOf(Param("address", "string", "seg:ofs in hex"), Param("hex", "string", "bytes, e.g. 2e1002")),
        ) { a ->
            val (seg, ofs) = address(a.need("address"))
            val bytes = a.need("hex").filterNot { it.isWhitespace() }.chunked(2).map { it.toInt(16) }
            game.onEngine { m -> bytes.forEachIndexed { i, b -> m.wb(seg, ofs + i, b) } } ?: error("no game running")
            text("wrote ${bytes.size} bytes")
        },
        Tool("frames", "The live script frames: asset id (hex) and where each resumes, as `id@offset`.") {
            text(game.onEngine { m -> (0 until 0x100).mapNotNull { a -> Frames.resume(m, a)?.let { "%x@%x".format(a, it) } }.joinToString(" ") } ?: error("no game running"))
        },
        Tool(
            "post_event",
            "Post event words to a script frame's queue (asset id hex; Ishar 3 frise.io 0d: 27 redraws the effect icons, 32 revives).",
            listOf(Param("asset", "string", "asset id, hex"), Param("words", "string", "event words, decimal, comma separated")),
        ) { a ->
            val asset = a.need("asset").toInt(16)
            val words = a.need("words").split(",").map { it.trim().toInt() }
            text(if (game.onEngine { m -> Frames.post(m, asset, words) } == true) "posted" else "no such frame")
        },
        Tool("pause", "Hold the game between frames.") {
            game.pause()
            text("paused")
        },
        Tool("resume", "Run the game again in real time.") {
            game.resume()
            text("running")
        },
        Tool("step", "Pause (if running) and run N frames (19.2 per second of game time), then return.", listOf(Param("frames", "integer", "frames (default 1)", false))) { a ->
            game.pause()
            game.step((a.int("frames") ?: 1).coerceIn(1, 100_000))
            text(stateJson().toString())
        },
        Tool("mute", "Silence the sound (on: true) or let it play.", listOf(Param("on", "boolean", "mute (default true)", false))) { a ->
            game.setMuted(a.bool("on") ?: true)
            text(if (game.state.value.muted) "muted" else "sound on")
        },
    )

    /** Input held for [ms]: in real time, or while paused for as many frames (at least 2). */
    private suspend fun hold(ms: Int, down: () -> Unit, up: () -> Unit) {
        down()
        if (game.state.value.paused) game.step(maxOf(2, ms * 192 / 10_000)) else delay(ms.toLong())
        up()
    }

    /** Starts a game and waits until it is in the player's hands. */
    /** [needsGame]: a game must be chosen, as on the start screen nothing starts and no frame would come. */
    private suspend fun started(needsGame: Boolean = true, start: () -> Unit): JsonObject {
        if (needsGame && game.state.value.choosing) error("no game chosen yet: call select_game first")
        start()
        while (game.state.value.let { it.loading || (it.frame == null && it.error == null && it.stopped == null) }) delay(20)
        return text(stateJson().toString())
    }

    private fun stateJson() = game.state.value.let { s ->
        buildJsonObject {
            put("game", s.build.key)
            put("mode", s.mode)
            put("frame", s.frames)
            put("loading", s.loading)
            put("paused", s.paused)
            put("muted", s.muted)
            put("free_saves", s.freeSaves)
            putJsonArray("visited") { s.map?.visitedBuildings?.forEach { add(JsonPrimitive("${it.kind} c${it.contree} ${it.x},${it.y}")) } }
            put("language_menu", s.languageMenu)
            put("intro", s.introStage?.name)
            s.error?.let { put("error", it) }
            s.stopped?.let { put("stopped", it) }
            s.position?.let { p ->
                putJsonObject("position") {
                    put("contree", p.contree)
                    put("x", p.x)
                    put("y", p.y)
                    put("facing", p.facing)
                }
            }
            putJsonArray("party") {
                for (m in s.party) add(buildJsonObject {
                    put("slot", m.slot)
                    put("name", m.name)
                    put("life", m.life)
                    put("max_life", m.maxLife)
                    put("gold", m.gold)
                    putJsonObject("stats") { Cheats.STATS.zip(m.stats).forEach { (n, v) -> put(n, v) } }
                })
            }
        }
    }

    private fun text(s: String, error: Boolean = false) = buildJsonObject {
        putJsonArray("content") {
            add(buildJsonObject {
                put("type", "text")
                put("text", s)
            })
        }
        if (error) put("isError", true)
    }

    private fun rpcResult(id: JsonElement, result: JsonElement) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }

    private fun rpcError(id: JsonElement, code: Int, message: String) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        putJsonObject("error") {
            put("code", code)
            put("message", message)
        }
    }

    companion object {
        /** Ishar 2's glob 2d16, what the pointer carries (an item, a spell's cursor; frise.io 07e3). */
        private const val POINTER_ITEM = 0x2D16 + 0x2A0

        private val NAMED = mapOf(
            "up" to Input.UP, "down" to Input.DOWN, "left" to Input.LEFT, "right" to Input.RIGHT,
            "enter" to Input.ENTER, "space" to Input.SPACE, "backspace" to Input.BACKSPACE,
            "tab" to Input.TAB,
        ) + (0..9).associate { "kp$it" to Input.KEYPAD_DIGITS[it] }

        fun scancode(key: String): Int {
            val k = key.lowercase()
            NAMED[k]?.let { return it }
            if (k.length == 1) Input.scancodeFor(k[0])?.let { return it }
            return k.toIntOrNull() ?: error("unknown key $key")
        }

        fun address(a: String): Pair<Int, Int> {
            val (seg, ofs) = a.split(':').takeIf { it.size == 2 } ?: error("address is seg:ofs in hex")
            return seg.toInt(16) to ofs.toInt(16)
        }
    }
}
