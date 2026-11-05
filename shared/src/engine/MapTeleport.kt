package dev.pelotasplus.ishar.engine

/**
 * A long press on the map: the party goes to that cell if it could walk there over what it has seen.
 * Nothing the party could not do on foot: no unseen cell, no jump over water, a wall or a shut door,
 * no gate or town mouth, no other contree, no creature's cell. A landing is checked on the live game.
 */
object MapTeleport {
    enum class Refusal(val text: String) {
        NOT_IN_THIS_GAME("Not in this game"),
        BUSY("Not right now"),
        UNSEEN("Not seen yet"),
        BLOCKED("Can't go there"),
        NOT_REACHABLE("Not reachable on foot"),
        OCCUPIED("Someone is there"),
    }

    private val STEPS = listOf(0 to -1, 1 to 0, 0 to 1, -1 to 0)

    /** Why the party at [p] cannot go to cell [x] (across the sheets), [y]; null when it can. */
    fun refusal(m: Machine, map: PartyMap, p: Cheats.Position, x: Int, y: Int): Refusal? {
        if (m.build == GameBuild.ISHAR3) return Refusal.NOT_IN_THIS_GAME
        if (busy(m)) return Refusal.BUSY
        return refusal(map, p, x, y, liveCode(m, map, p.contree, x, y), Creatures.all(m))
    }

    /** The rule without the machine: [live] is the running game's code of the target cell. */
    fun refusal(map: PartyMap, p: Cheats.Position, x: Int, y: Int, live: Int, creatures: List<Creatures.Creature>): Refusal? {
        val from = map.sheetX(p) ?: return Refusal.BUSY
        if (!map.onMap(x, y) || !map.sameSheet(from, x) || map.colour(x, y) == null) return Refusal.UNSEEN
        if (from == x && p.y == y) return Refusal.BLOCKED
        if (live >= 0x80 || !map.standable(p, x, y)) return Refusal.BLOCKED
        if (route(map, p, from, x, y) == null) return Refusal.NOT_REACHABLE
        if (creatures.any { !it.inParty && it.life > 0 && it.y == y && map.sheetX(it.contree, it.x, it.y) == x }) return Refusal.OCCUPIED
        return null
    }

    /**
     * [refusal], and when there is none the move: the place, and in Ishar 2 the zone the walk there would have set
     * (main.io 0c1d loads a zone's assets when glob 4ef2 changes). [facing] is what the party lands with.
     */
    fun go(m: Machine, map: PartyMap, x: Int, y: Int, facing: Int): Refusal? {
        val p = Cheats.position(m)
        refusal(m, map, p, x, y)?.let { return it }
        val route = route(map, p, map.sheetX(p)!!, x, y)!!
        if (m.build == GameBuild.ISHAR2) {
            val zone = m.build.globBase + 0x4EF2
            map.zoneAfter(p.contree, m.rb(m.build.globals, zone), route)?.let { m.wb(m.build.globals, zone, it) }
        }
        Cheats.moveWithinContree(m, map.ownX(x), y, facing)
        return null
    }

    /** The shortest walk from [fromX], the party's Y to the cell over seen cells, without the start; null when there is none. */
    private fun route(map: PartyMap, p: Cheats.Position, fromX: Int, toX: Int, toY: Int): List<Pair<Int, Int>>? {
        val start = fromX to p.y
        val cameFrom = HashMap<Pair<Int, Int>, Pair<Int, Int>>()
        val todo = ArrayDeque(listOf(start))
        while (todo.isNotEmpty()) {
            val c = todo.removeFirst()
            if (c == (toX to toY)) return generateSequence(c) { cameFrom[it] }.takeWhile { it != start }.toList().reversed()
            for ((dx, dy) in STEPS) {
                val q = c.first + dx to c.second + dy
                if (q == start || q in cameFrom || !map.onMap(q.first, q.second) || map.colour(q.first, q.second) == null) continue
                if (!map.standable(p, q.first, q.second)) continue
                cameFrom[q] = c
                todo.addLast(q)
            }
        }
        return null
    }

    /** gerdep.io refuses every step now (Ishar 1 049e, Ishar 2 0572), something is on the pointer, or every living member is bound. */
    private fun busy(m: Machine): Boolean {
        val g = m.build.globals
        val base = m.build.globBase
        fun b(glob: Int) = m.rb(g, glob + base)
        fun w(glob: Int) = m.rw(g, glob + base)
        // gerdep.io 0c42 / Ishar 2 0b72: nobody can move if every living member is bound
        fun allBound(bound: Int) = Cheats.party(m).filter { it.life > 0 }.let { alive -> alive.isEmpty() || alive.all { w(bound + 2 * it.slot) != 0 } }
        return if (m.build == GameBuild.ISHAR1) {
            b(0x137F) != 0 || w(0x1506) != 0 || b(0x399E) != 0 || b(0x426E) != 0 || b(0x403A) != 0 || allBound(0x15EC)
        } else {
            ISHAR2_BUSY.any { b(it) != 0 } || w(0x2D16) != 0 || allBound(0x2E26)
        }
    }

    // gerdep.io 0572
    private val ISHAR2_BUSY = listOf(0x2AB7, 0x4AA0, 0x4FD0, 0x4FCF)

    /**
     * The target's code in the running game's own grid, which the scripts can change: glob 80h, X * rows + Y. Ishar 1 keeps
     * two grids, an even contree's at 234ah (gerdep.io 0984 / 09a5, main.io 4452).
     */
    private fun liveCode(m: Machine, map: PartyMap, contree: Int, x: Int, y: Int): Int {
        val grid = if (m.build == GameBuild.ISHAR1 && contree % 2 == 0) 0x234A else 0x80
        return m.rb(m.build.globals, grid + m.build.globBase + map.ownX(x) * map.rows + y)
    }
}
