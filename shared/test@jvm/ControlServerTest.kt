package dev.pelotasplus.ishar.control

import dev.pelotasplus.ishar.data.store.MemorySaveStore
import dev.pelotasplus.ishar.features.game.GameViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The MCP server on a live, paced (muted) game, called over HTTP as a client would. */
@OptIn(ExperimentalCoroutinesApi::class)
class ControlServerTest {
    private lateinit var game: GameViewModel
    private lateinit var server: ControlServer
    private val http = HttpClient.newHttpClient()
    private var id = 0

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Default)
        game = GameViewModel(
            resourceRepository = dev.pelotasplus.ishar.engine.TestResources(),
            saveStore = MemorySaveStore().apply { write("lr.sav", dev.pelotasplus.ishar.engine.TestResources.save("LR.SAV")) }, // a row for ui_tap
            muted = true,
        )
        server = ControlServer(game, 0).start()
    }

    @AfterTest
    fun tearDown() {
        server.stop()
        game.stop()
        Dispatchers.resetMain()
    }

    private fun rpc(method: String, params: JsonObject = JsonObject(emptyMap())): JsonObject {
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", ++id)
            put("method", method)
            put("params", params)
        }
        val req = HttpRequest.newBuilder(URI("http://127.0.0.1:${server.port}/mcp"))
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()
        val res = Json.parseToJsonElement(http.send(req, HttpResponse.BodyHandlers.ofString()).body()).jsonObject
        return res["result"]?.jsonObject ?: error("rpc error: $res")
    }

    private fun call(tool: String, args: JsonObjectBuilder.() -> Unit = {}): JsonObject {
        val r = rpc("tools/call", buildJsonObject {
            put("name", tool)
            putJsonObject("arguments", args)
        })
        assertTrue(r["isError"] == null, "$tool: $r")
        return r["content"]!!.jsonArray[0].jsonObject
    }

    private fun callText(tool: String, args: JsonObjectBuilder.() -> Unit = {}) =
        Json.parseToJsonElement(call(tool, args)["text"]!!.jsonPrimitive.content).jsonObject

    private fun position(s: JsonObject) = s["position"]!!.jsonObject.let { p -> listOf("x", "y", "facing").map { p[it]!!.jsonPrimitive.int } }

    @Test
    fun `starting a game on the start screen asks for select_game instead of hanging`() {
        val choosing = GameViewModel(
            resourceRepository = dev.pelotasplus.ishar.engine.TestResources(),
            saveStore = MemorySaveStore().apply { write("lr.sav", dev.pelotasplus.ishar.engine.TestResources.save("LR.SAV")) },
            muted = true,
            build = null,
        )
        val choosingServer = ControlServer(choosing, 0).start()
        fun tool(name: String, args: JsonObjectBuilder.() -> Unit = {}): JsonObject {
            val body = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", ++id)
                put("method", "tools/call")
                put("params", buildJsonObject { put("name", name); putJsonObject("arguments", args) })
            }
            val req = HttpRequest.newBuilder(URI("http://127.0.0.1:${choosingServer.port}/mcp"))
                .timeout(java.time.Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()
            return Json.parseToJsonElement(http.send(req, HttpResponse.BodyHandlers.ofString()).body()).jsonObject["result"]!!.jsonObject
        }
        try {
            for ((name, args) in listOf<Pair<String, JsonObjectBuilder.() -> Unit>>(
                "load_save" to { put("name", "LR") }, "new_game" to {}, "skip_intro" to {},
            )) {
                val r = tool(name, args)
                assertEquals("true", r["isError"]?.jsonPrimitive?.content, "$name: $r")
                assertTrue("select_game" in r["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content, "$name: $r")
            }
            assertEquals(null, tool("select_game") { put("game", "ishar1") }["isError"])
            val loaded = tool("load_save") { put("name", "LR") }
            assertEquals(null, loaded["isError"], "$loaded")
        } finally {
            choosingServer.stop()
            choosing.stop()
        }
    }

    @Test
    fun `the minimap has its map from the moment the new game runs`() {
        while (game.state.value.loading) Thread.sleep(20)
        repeat(5) {
            game.loadSave("LR")
            val answers = LinkedHashSet<String>()
            do {
                val r = rpc("tools/call", buildJsonObject {
                    put("name", "minimap")
                    putJsonObject("arguments") { put("radius", 3) }
                })
                val text = r["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
                if (r["isError"] == null) {
                    assertEquals('@', text.lines()[3][3], text)
                    break
                }
                answers += text
            } while (true)
            assertEquals(emptySet(), answers - "no game running", "while the game was starting")
        }
    }

    @Test
    fun `an agent loads a save, walks and takes a screenshot`() {
        assertEquals("ishar-game", rpc("initialize")["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        val tools = rpc("tools/list")["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue(listOf("screenshot", "send_key", "load_save", "state", "step", "read_memory").all { it in tools }, "$tools")

        while (game.state.value.loading) Thread.sleep(20)
        val loaded = callText("load_save") { put("name", "LR") }
        assertEquals("Load LR", loaded["mode"]!!.jsonPrimitive.content)
        val before = position(loaded)

        call("pause")
        call("step") { put("frames", 20) }
        val walked = generateSequence {
            call("send_key") { put("key", "w"); put("hold_ms", 300) }
            position(callText("step") { put("frames", 40) })
        }.take(3).firstOrNull { it != before }
        assertNotEquals(null, walked, "W moved the party from $before")

        val mem = call("read_memory") { put("address", "126b:161c"); put("length", 3) }["text"]!!.jsonPrimitive.content
        assertEquals(walked!!.joinToString("") { "%02x".format(it) }, mem)

        // Ishar 1's minimap marks what blocks: the Angarahn tower and its trees west of 10,61
        call("resume")
        call("cheat") { put("action", "move"); put("x", 10); put("y", 61); put("facing", 3) }
        call("step") { put("frames", 30) }
        val mini = call("minimap") { put("radius", 3) }["text"]!!.jsonPrimitive.content
        assertTrue('#' in mini && '_' in mini, mini)
        // at X 1 of the first sheet, the three columns west of X 0 are off the map
        call("resume")
        call("cheat") { put("action", "move"); put("x", 1); put("y", 61); put("facing", 3) }
        call("step") { put("frames", 30) }
        val edge = call("minimap") { put("radius", 4) }["text"]!!.jsonPrimitive.content
        assertTrue(edge.lines().all { it.startsWith("   ") && it[3] != ' ' }, edge)
        // at Y 1, the three rows north of Y 0 are off the map, not unseen
        call("resume")
        call("cheat") { put("action", "move"); put("x", 10); put("y", 1); put("facing", 3) }
        call("step") { put("frames", 30) }
        val top = call("minimap") { put("radius", 4) }["text"]!!.jsonPrimitive.content.lines()
        assertTrue(top.take(3).all { it.isBlank() } && top[3].isNotBlank(), top.joinToString("\n"))

        // the Saves screen drawn offscreen, from calls that overlap (the server runs them on a thread pool)
        val dir = kotlin.io.path.createTempDirectory("ui").toFile()
        val results = (1..8).map { i ->
            java.util.concurrent.CompletableFuture.supplyAsync {
                rpc("tools/call", buildJsonObject {
                    put("name", "ui_saves")
                    putJsonObject("arguments") { put("path", "$dir/s$i.png"); put("height", 400) }
                })
            }
        }.map { it.get() }
        assertTrue(results.all { it["isError"] == null }, results.filter { it["isError"] != null }.toString())
        assertTrue((1..8).all { java.io.File(dir, "s$it.png").length() > 0 })
        dir.deleteRecursively()

        val shot = call("screenshot")
        assertEquals("image/png", shot["mimeType"]!!.jsonPrimitive.content)
        val img = ImageIO.read(Base64.getDecoder().decode(shot["data"]!!.jsonPrimitive.content).inputStream())
        assertEquals(320 to 200, img.width to img.height)

        // ui_tap says what its own tap did: a row starts a game, empty space after it does not
        val png = kotlin.io.path.createTempFile("tap", ".png").toFile()
        fun tap(x: Int, y: Int) = call("ui_tap") { put("x", x); put("y", y); put("path", png.path) }["text"]!!.jsonPrimitive.content
        assertEquals("tapped: the screen asked to play", tap(100, 60), "the first row")
        assertEquals("tapped", tap(790, 395), "below the rows")
        png.delete()
    }
}

