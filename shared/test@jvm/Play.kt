package dev.pelotasplus.ishar.engine


/** Test games: the intro skipped, then input scripts (loads, saves) run frame by frame. */
object Play {
    const val START = Starts.SKIP_INTRO_FRAMES

    suspend fun files(store: dev.pelotasplus.ishar.data.store.SaveStore? = null) =
        GameFiles.fromResourceRepository(TestResources(), store = store)

    /** [events] from [from] for [count] frames; [each] before every frame. Returns the next frame. */
    fun run(vm: Scheduler, events: Map<Int, List<Session.Event>>, from: Int, count: Int, each: () -> Unit = {}): Int {
        for (f in from until from + count) {
            each()
            events[f]?.forEach { Session.apply(vm, it) }
            vm.frame()
        }
        return from + count
    }

    /** The intro skipped, then [name] loaded through the disk menu; returns the game and the next frame. */
    fun loaded(files: GameFiles, name: String): Pair<Scheduler, Int> {
        val vm = Starts.skipIntro(files)
        return vm to run(vm, SaveGames.loadEvents(name, START), START, SaveGames.loadFrames(name))
    }

    fun position(m: Machine) =
        SaveGames.Header(m.rb(0x126B, 0x161C), m.rb(0x126B, 0x161D), m.rb(0x126B, 0x161E), m.rb(0x126B, 0x17A8))
}
