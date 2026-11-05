package dev.pelotasplus.ishar

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.pelotasplus.ishar.control.ControlServer
import dev.pelotasplus.ishar.engine.GameBuild
import dev.pelotasplus.ishar.features.game.GameViewModel

/** `ISHAR_MCP=port` starts the MCP server on 127.0.0.1 (ControlServer); `ISHAR_MUTE=1` starts muted; `ISHAR_GAME=ishar2` starts Ishar 2 without the start screen. */
fun main() {
    // ISHAR_GAME=arborea|ishar1|ishar2|ishar3 starts that game; otherwise the start screen asks
    val game = GameViewModel(build = System.getenv("ISHAR_GAME")?.let(GameBuild::of))
    val server = System.getenv("ISHAR_MCP")?.toIntOrNull()?.let {
        val server = ControlServer(game, it).start()
        println("MCP server on http://127.0.0.1:${server.port}/mcp")
        server
    }
    application {
        Window(onCloseRequest = ::exitApplication, title = "Ishar") {
            server?.window = window
            App(game)
        }
    }
}
