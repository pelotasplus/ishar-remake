package dev.pelotasplus.ishar.features.game

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.pelotasplus.ishar.engine.Buildings
import dev.pelotasplus.ishar.engine.Cheats
import dev.pelotasplus.ishar.engine.Creatures
import dev.pelotasplus.ishar.engine.GroundItems
import dev.pelotasplus.ishar.engine.PartyMap

private val UNSEEN = Color(0xFF101018)
private const val AROUND = 10

/** Cells across the [MiniMap]. */
const val MINI_CELLS = 2 * AROUND + 1

/** The cells around the party, north up; a tap opens [FullMap]. */
@Composable
fun MiniMap(map: PartyMap, position: Cheats.Position, creatures: List<Creatures.Creature>, items: List<GroundItems.Item>, visited: List<Buildings.Building>, side: Dp, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val x = map.sheetX(position) ?: return
    val letters = rememberTextMeasurer()
    Canvas(
        modifier
            .size(side)
            .background(UNSEEN)
            .border(1.dp, Color(0xFFC8A860))
            .clickable(onClickLabel = "Open the map", onClick = onOpen),
    ) {
        drawCells(map, x - AROUND..x + AROUND, position.y - AROUND..position.y + AROUND, size.width / MINI_CELLS, Offset.Zero, x, position, map.known(creatures), map.knownItems(items), visited, letters)
    }
}

/**
 * Everything seen on the party's sheet, full screen, at one zoom ([FULL_CELL]) around the party; a
 * drag moves it when it does not fit. A tap or Esc closes it; other keys go to [onKey], so the party
 * walks with the map open.
 */
@Composable
fun FullMap(map: PartyMap, position: Cheats.Position, creatures: List<Creatures.Creature>, items: List<GroundItems.Item>, visited: List<Buildings.Building>, onClose: () -> Unit, onKey: (KeyEvent) -> Boolean, modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    val letters = rememberTextMeasurer()
    LaunchedEffect(Unit) { focus.requestFocus() }
    var drag by remember { mutableStateOf(Offset.Zero) }
    val x = map.sheetX(position)
    Box(
        modifier
            .fillMaxSize()
            .background(UNSEEN)
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { e ->
                if (e.key != Key.Escape) return@onKeyEvent onKey(e)
                if (e.type == KeyEventType.KeyUp) onClose()
                true
            }
            .pointerInput(Unit) { detectDragGestures { _, by -> drag += by } }
            .clickable(onClickLabel = "Close the map", onClick = onClose)
            .safeContentPadding(),
    ) {
        if (x != null) Canvas(Modifier.fillMaxSize().clipToBounds()) {
            val (seenX, seenY) = map.seenBounds(x) ?: (x..x to position.y..position.y)
            val xs = minOf(seenX.first, x) - MARGIN..maxOf(seenX.last, x) + MARGIN
            val ys = minOf(seenY.first, position.y) - MARGIN..maxOf(seenY.last, position.y) + MARGIN
            val columns = xs.last - xs.first + 1
            val rows = ys.last - ys.first + 1
            val cell = FULL_CELL.toPx()
            val left = origin(size.width, cell * columns, size.width / 2 - (x - xs.first + 0.5f) * cell + drag.x)
            val top = origin(size.height, cell * rows, size.height / 2 - (position.y - ys.first + 0.5f) * cell + drag.y)
            drawCells(map, xs, ys, cell, Offset(left, top), x, position, map.known(creatures), map.knownItems(items), visited, letters)
        }
    }
}

/** Centred when the map fits, else [wanted] kept from leaving a gap at either end. */
private fun origin(room: Float, length: Float, wanted: Float) =
    if (length <= room) (room - length) / 2 else wanted.coerceIn(room - length, 0f)

private const val MARGIN = 2
private val FULL_CELL = 24.dp

/** [xs] by [ys] from [at], with [seenItems] ([PartyMap.knownItems]; gold diamonds money, white other items), [visited] as a letter on their door ([Buildings.Kind.letter]), [seen] (the creatures [PartyMap.known]; red hostile, yellow peaceful) and the party's arrow on top. */
private fun DrawScope.drawCells(map: PartyMap, xs: IntRange, ys: IntRange, cell: Float, at: Offset, partyX: Int, p: Cheats.Position, seen: List<Creatures.Creature>, seenItems: List<GroundItems.Item>, visited: List<Buildings.Building>, letters: TextMeasurer) {
    val square = Size(cell + 0.5f, cell + 0.5f)
    for (x in xs) for (y in ys) {
        if (!map.sameSheet(partyX, x)) continue
        val cx = at.x + (x - xs.first) * cell
        val cy = at.y + (y - ys.first) * cell
        if (cx + cell < 0 || cy + cell < 0 || cx > size.width || cy > size.height) continue
        val rgb = map.colour(x, y) ?: continue
        drawRect(Color(0xFF000000 or rgb.toLong()), Offset(cx, cy), square)
    }
    for (b in visited) {
        val letter = b.kind.letter ?: continue
        val x = map.sheetX(b.contree, b.x, b.y) ?: continue
        if (x !in xs || b.y !in ys || !map.sameSheet(partyX, x)) continue
        val side = maxOf(cell, 11.dp.toPx())
        val corner = Offset(at.x + (x - xs.first + 0.5f) * cell - side / 2, at.y + (b.y - ys.first + 0.5f) * cell - side / 2)
        drawRect(buildingColour(b.kind), corner, Size(side, side))
        drawRect(Color.Black, corner, Size(side, side), style = Stroke(1.dp.toPx()))
        val text = letters.measure(letter, TextStyle(color = Color.Black, fontSize = (side * 0.75f).toSp(), fontWeight = FontWeight.Bold))
        drawText(text, topLeft = corner + Offset((side - text.size.width) / 2, (side - text.size.height) / 2))
    }
    for (i in seenItems) {
        val x = map.sheetX(i.contree, i.x, i.y) ?: continue
        if (x !in xs || i.y !in ys || !map.sameSheet(partyX, x)) continue
        val centre = Offset(at.x + (x - xs.first + 0.5f) * cell, at.y + (i.y - ys.first + 0.5f) * cell)
        val r = maxOf(cell * 0.4f, 3.dp.toPx())
        val diamond = Path().apply {
            moveTo(centre.x, centre.y - r)
            lineTo(centre.x + r, centre.y)
            lineTo(centre.x, centre.y + r)
            lineTo(centre.x - r, centre.y)
            close()
        }
        drawPath(diamond, if (i.money) Color(0xFFFFC800) else Color.White)
        drawPath(diamond, Color.Black, style = Stroke(1.dp.toPx()))
    }
    for (c in seen) {
        val x = map.sheetX(c.contree, c.x, c.y) ?: continue
        if (x !in xs || c.y !in ys || !map.sameSheet(partyX, x)) continue
        val centre = Offset(at.x + (x - xs.first + 0.5f) * cell, at.y + (c.y - ys.first + 0.5f) * cell)
        val r = maxOf(cell * 0.4f, 3.dp.toPx())
        drawCircle(if (c.hostile) Color(0xFFE61414) else Color(0xFFFFE100), r, centre)
        drawCircle(Color.Black, r, centre, style = Stroke(1.dp.toPx()))
    }
    drawParty(Offset(at.x + (partyX - xs.first + 0.5f) * cell, at.y + (p.y - ys.first + 0.5f) * cell), maxOf(cell, 6.dp.toPx()), p.facing)
}

private fun buildingColour(kind: Buildings.Kind) = when (kind) {
    Buildings.Kind.SHOP -> Color(0xFF6EE06E)
    Buildings.Kind.TAVERN -> Color(0xFFFFB347)
    Buildings.Kind.SPELL_HALL -> Color(0xFFC890FF)
    Buildings.Kind.COURSE -> Color(0xFF7FC8FF)
    else -> Color(0xFFF0F0F0)
}

private fun DrawScope.drawParty(at: Offset, cell: Float, facing: Int) {
    val (dx, dy) = listOf(0f to -1f, 1f to 0f, 0f to 1f, -1f to 0f).getOrElse(facing - 1) { 0f to -1f }
    val r = cell * 0.8f
    val arrow = Path().apply {
        moveTo(at.x + dx * r, at.y + dy * r)
        lineTo(at.x - dx * r * 0.7f - dy * r * 0.75f, at.y - dy * r * 0.7f + dx * r * 0.75f)
        lineTo(at.x - dx * r * 0.7f + dy * r * 0.75f, at.y - dy * r * 0.7f - dx * r * 0.75f)
        close()
    }
    drawPath(arrow, Color(0xFFFFEB3B))
    drawPath(arrow, Color.Black, style = Stroke(maxOf(1f, cell * 0.12f)))
}
