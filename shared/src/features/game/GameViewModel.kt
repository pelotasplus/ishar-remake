package dev.pelotasplus.ishar.features.game

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.pelotasplus.ishar.data.repository.ResourceRepository
import dev.pelotasplus.ishar.AudioOutput
import dev.pelotasplus.ishar.startMuted
import dev.pelotasplus.ishar.data.repository.ResourceRepositoryImpl
import dev.pelotasplus.ishar.data.store.GameSaveStore
import dev.pelotasplus.ishar.data.store.SaveStore
import dev.pelotasplus.ishar.data.store.platformSaveStore
import dev.pelotasplus.ishar.engine.Boot
import dev.pelotasplus.ishar.engine.clockSeed
import dev.pelotasplus.ishar.engine.Buildings
import dev.pelotasplus.ishar.engine.Cheats
import dev.pelotasplus.ishar.engine.Doors
import dev.pelotasplus.ishar.engine.CopyProtection
import dev.pelotasplus.ishar.engine.Creatures
import dev.pelotasplus.ishar.engine.GameBuild
import dev.pelotasplus.ishar.engine.GameFiles
import dev.pelotasplus.ishar.engine.GroundItems
import dev.pelotasplus.ishar.engine.Input
import dev.pelotasplus.ishar.engine.Intro
import dev.pelotasplus.ishar.engine.Machine
import dev.pelotasplus.ishar.engine.Palette
import dev.pelotasplus.ishar.engine.MapTeleport
import dev.pelotasplus.ishar.engine.PcmMixer
import dev.pelotasplus.ishar.engine.ProgramEndException
import dev.pelotasplus.ishar.engine.RealTimeClock
import dev.pelotasplus.ishar.engine.SaveGames
import dev.pelotasplus.ishar.engine.Scheduler
import dev.pelotasplus.ishar.engine.Session
import dev.pelotasplus.ishar.engine.SoundBlaster
import dev.pelotasplus.ishar.engine.Starts
import dev.pelotasplus.ishar.engine.UnsupportedEngineOperationException
import dev.pelotasplus.ishar.engine.Ishar3Start
import dev.pelotasplus.ishar.engine.IslandMap
import dev.pelotasplus.ishar.engine.PartyMap
import dev.pelotasplus.ishar.engine.WorldMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import kotlin.time.TimeSource

data class GameState(
    /** Which game runs: Ishar 1 or Ishar 2 ([GameViewModel.selectGame]). */
    val build: GameBuild = GameBuild.ISHAR1,
    /** The start screen asks which game to play; nothing runs yet. */
    val choosing: Boolean = false,
    val loading: Boolean = true,
    val error: String? = null,
    /** What runs: "New game", "Skip intro", "Load NAME", "Debug start PLACE". */
    val mode: String = "",
    /** The player's saves of [build], kept between runs. */
    val saves: List<String> = emptyList(),
    /** Ishar 2: the Ishar 1 saves its disk menu's I -> II button can import (type the name there). */
    val importable: List<String> = emptyList(),
    /** Ishar 2: its saves with where each party stands (null: not an Ishar 2 save). */
    val ishar2Saves: List<Pair<String, SaveGames.Header?>> = emptyList(),
    val saveSummaries: List<SaveGames.Summary> = emptyList(),
    /** Saves that cannot be loaded (damaged, or a name the prompt cannot type): the player can only delete them. */
    val unloadableSaves: List<String> = emptyList(),
    /** The last save did not reach the store; the game goes on. */
    val saveError: String? = null,
    /** Why there is no sound ([AudioOutput.error]). */
    val audioError: String? = null,
    val frames: Int = 0,
    /** The screen, 320x200 packed ARGB. */
    val frame: IntArray? = null,
    /** Why the engine stopped (an unsupported path, the program's end). */
    val stopped: String? = null,
    /** Saving costs nothing ([Cheats.freeSaves], kept on across runs). */
    val freeSaves: Boolean = false,
    /** The live party and position, for the cheats. */
    val party: List<Cheats.Member> = emptyList(),
    val position: Cheats.Position? = null,
    /** The live EN1 table ([Creatures]). */
    val creatures: List<Creatures.Creature> = emptyList(),
    val groundItems: List<GroundItems.Item> = emptyList(),
    /** Where the party has been inside ([WorldMap.visitedBuildings]). */
    val visited: List<Buildings.Building> = emptyList(),
    /** The engine holds between frames ([GameViewModel.step] still runs some). */
    val paused: Boolean = false,
    /** Where the intro is ([Intro.stage]); on the language menu a tap on a line chooses it ([Starts.languageAt]). */
    val introStage: Intro.Stage? = null,
    val muted: Boolean = false,
    /** The tunes are heard ([PcmMixer.musicOn]); the sound effects go on either way. */
    val musicOn: Boolean = true,
    /** The game asks for typed text ([Input.textPrompt]: the save name). */
    val textPrompt: Boolean = false,
    /** It asks for a save's name ([Input.namePrompt]): the phone's keyboard opens. */
    val namePrompt: Boolean = false,
    /** What the party has seen; null until the party is in the world. */
    val map: PartyMap? = null,
    /** The full map covers the game, which goes on. */
    val mapOpen: Boolean = false,
    /** The cell a long press on the map just failed on. */
    val mapRefusal: MapRefusal? = null,
) {
    val languageMenu get() = introStage == Intro.Stage.LANGUAGE
}

/**
 * The game: a new game from boot, the intro skipped ([Starts.SKIP_INTRO] up to Fragonir, then
 * the player's), a save loaded, or the developers' debug start. Always on the Sound Blaster.
 *
 * Ishar 2 ([selectGame]) runs on the same engine with its own files and saves: a new game or the
 * intro skipped. Heal and gold work there; the map, remove curses, free saves, attack-all, loading from
 * the saves screen and the debug start know only Ishar 1 yet. Its disk menu loads, saves and imports
 * Ishar 1 parties.
 */
@OptIn(ExperimentalAtomicApi::class)
@Stable
class GameViewModel(
    private val resourceRepository: ResourceRepository = ResourceRepositoryImpl(),
    private val ishar2Repository: ResourceRepository = ResourceRepositoryImpl(GameBuild.ISHAR2),
    private val engineDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val realTime: Boolean = true,
    saveStore: SaveStore = platformSaveStore(),
    muted: Boolean = startMuted(),
    /** Stop after this many frames (tests). */
    private val frameLimit: Int = Int.MAX_VALUE,
    /** The dice for each game handed to the player. */
    private val seed: () -> Int = ::clockSeed,
    /** The game to start with; null shows the start screen's choice first. */
    build: GameBuild? = GameBuild.ISHAR1,
) : ViewModel() {

    private val _state = MutableStateFlow(
        GameState(build = build ?: GameBuild.ISHAR1, choosing = build == null, loading = build != null, muted = muted),
    )

    private val saveStoreBase = saveStore

    /** The running game's view of the player's saves ([GameSaveStore]). */
    @Volatile
    private var saves: SaveStore = GameSaveStore(saveStore, build == GameBuild.ISHAR2, build == GameBuild.ISHAR3, build == GameBuild.ARBOREA)

    /** Ishar 1's saves, for Ishar 2's import list. */
    private val ishar1Saves: SaveStore = GameSaveStore(saveStore, false)
    val state: StateFlow<GameState> = _state.asStateFlow()

    @Volatile
    private var files: GameFiles? = null
    private var job: Job? = null

    /** One run at a time: a cancelled one finishes its setup first, and must not write over the next. */
    private val running = Mutex()
    @Volatile
    private var input: Input? = null
    @Volatile
    private var intro: Intro? = null
    private val audio by lazy { AudioOutput(AUDIO_RATE) }

    /** Work for the engine's thread between frames, paused or not. */
    private val calls = Channel<() -> Unit>(Channel.UNLIMITED)

    /** Frames still to run while paused. */
    private val stepping = AtomicInt(0)

    /** The player's saves; a save from the game refreshes the list. */
    internal val store = object : SaveStore {
        override fun names() = saves.names()

        override fun read(name: String) = saves.read(name)

        override fun importFrom(game: Int?) = saves.importFrom(game)

        override fun write(name: String, data: ByteArray) {
            if (name.endsWith(".sav")) savesWritten++
            val failed = runCatching {
                saves.write(name, data)
                if (name.endsWith(".sav")) worldMap?.let { saves.write(mapName(name), it.encode()) }
            }.exceptionOrNull()
            _state.update { it.copy(saveError = failed?.let { e -> "Could not save ${name.substringBefore('.').uppercase()}: ${e.message ?: e}" }) }
            refreshSaves()
        }

        override fun delete(name: String) {
            saves.delete(name)
            saves.delete(mapName(name))
            refreshSaves()
        }
    }

    private var loadJob: Job? = null

    init {
        refreshSaves()
        if (build != null) loadFiles(build)
    }

    /** Loads [build]'s files, then a new game. */
    private fun loadFiles(build: GameBuild) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch(engineDispatcher) {
            val repo = when (build) {
                GameBuild.ARBOREA -> ResourceRepositoryImpl(GameBuild.ARBOREA)
                GameBuild.ISHAR1 -> resourceRepository
                GameBuild.ISHAR2 -> ishar2Repository
                GameBuild.ISHAR3 -> ResourceRepositoryImpl(GameBuild.ISHAR3)
            }
            // Ishar 2's own saves are another size than Ishar 1's, which it also opens to import a party
            val saveSize = if (build == GameBuild.ISHAR1) SaveGames.SIZE else null
            val saveNames = if (build == GameBuild.ARBOREA) GameFiles.ARBOREA_SAVES else null
            runCatching { files = GameFiles.fromResourceRepository(repo, store = store, saveSize = saveSize, saveNames = saveNames) }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message ?: e.toString()) } }
                .onSuccess { newGame() }
        }
    }

    /** Switches to [build]: its files and saves, and a new game. */
    fun selectGame(build: GameBuild) {
        if (build == _state.value.build && !_state.value.choosing && files != null) return newGame()
        job?.cancel()
        input = null
        intro = null
        machine = null
        files = null
        worldMap = null
        saves = GameSaveStore(saveStoreBase, build == GameBuild.ISHAR2, build == GameBuild.ISHAR3, build == GameBuild.ARBOREA)
        _state.update { GameState(build = build, loading = true, freeSaves = it.freeSaves, muted = it.muted, musicOn = it.musicOn) }
        refreshSaves()
        loadFiles(build)
    }

    fun newGame() = start("New game") { f, mixer ->
        val m = Machine(build = _state.value.build)
        Boot.boot(m, f, soundOverride = 'B')
        val vm = Scheduler.withStatements(m, f)
        val sb = SoundBlaster(m, out = mixer)
        vm.sound = sb
        vm.interrupts = true
        vm.runPass()
        Run(vm, sb, 1)
    }

    /** [Starts.SKIP_INTRO] (the logo, Space, keypad 1 twice) runs unpaced to Fragonir, then the player's. */
    fun skipIntro() = start("Skip intro") { f, mixer ->
        if (_state.value.build == GameBuild.ISHAR2) {
            val s = Starts.ISHAR2_SKIP_INTRO_MUSIC
            val vm = s.boot(f, Machine(build = GameBuild.ISHAR2), sound = 'B', sink = { SoundBlaster(it, out = mixer) })
            for (frame in 1 until Starts.ISHAR2_SKIP_INTRO_FRAMES) s.step(vm, frame)
            return@start Run(vm, vm.sound as SoundBlaster, Starts.ISHAR2_SKIP_INTRO_FRAMES)
        }
        val vm = toFragonir(f, mixer)
        Run(vm, vm.sound as SoundBlaster, SKIP_TO)
    }

    /** Skip intro, then the disk menu's LOAD with [name] typed (unpaced), then the player's turn. */
    fun loadSave(name: String) = start("Load $name") { f, mixer ->
        if (_state.value.build == GameBuild.ISHAR2) return@start loadIshar2(name, f, mixer)
        if (_state.value.build == GameBuild.ISHAR3) return@start loadIshar3(name, f, mixer)
        var opened = false
        val onLoaded = f.onSaveLoaded
        f.onSaveLoaded = {
            opened = true
            onLoaded?.invoke(it)
        }
        val vm = toFragonir(f, mixer)
        val events = SaveGames.loadEvents(name, SKIP_TO)
        val end = SKIP_TO + SaveGames.loadFrames(name)
        for (frame in SKIP_TO until end) {
            events[frame]?.forEach { Session.apply(vm, it) }
            CopyProtection.remove(vm.machine)
            vm.frame()
        }
        if (!opened) throw LoadFailedException("Could not load $name") // not the game we would hand over as it
        Run(vm, vm.sound as SoundBlaster, end)
    }

    /** Ishar 2: the intro skipped, then its disk menu's LOAD with [name] typed (unpaced). */
    private fun loadIshar2(name: String, f: GameFiles, mixer: PcmMixer): Run {
        var opened = false
        val onLoaded = f.onSaveLoaded // restores the save's map
        f.onSaveLoaded = {
            opened = true
            onLoaded?.invoke(it)
        }
        val s = Starts.ISHAR2_SKIP_INTRO_MUSIC
        val start = Starts.ISHAR2_SKIP_INTRO_FRAMES
        val vm = s.boot(f, Machine(build = GameBuild.ISHAR2), sound = 'B', sink = { SoundBlaster(it, out = mixer) })
        for (frame in 1 until start) s.step(vm, frame)
        val events = SaveGames.ishar2LoadEvents(name, start)
        val end = start + SaveGames.ishar2LoadFrames(name)
        for (frame in start until end) {
            events[frame]?.forEach { Session.apply(vm, it) }
            vm.frame()
        }
        if (!opened) throw LoadFailedException("Could not load $name")
        return Run(vm, vm.sound as SoundBlaster, end)
    }

    /** Ishar 3: its menus to LOAD's "Ishar 3" with [name] typed ([Ishar3Start]), unpaced. */
    private fun loadIshar3(name: String, f: GameFiles, mixer: PcmMixer): Run {
        var opened = false
        val onLoaded = f.onSaveLoaded
        f.onSaveLoaded = {
            opened = true
            onLoaded?.invoke(it)
        }
        val m = Machine(build = GameBuild.ISHAR3)
        Boot.boot(m, f, soundOverride = 'B')
        val vm = Scheduler.withStatements(m, f)
        val sb = SoundBlaster(m, out = mixer)
        vm.sound = sb
        vm.interrupts = true
        vm.runPass()
        val toMenu = Ishar3Start.toMenu(vm)
        val loaded = Ishar3Start.load(vm, name)
        if (loaded == null || !opened) throw LoadFailedException("Could not load $name")
        return Run(vm, sb, toMenu + loaded)
    }

    /** The developers' debug start at [place] (unpaced), then the player's turn. */
    fun debugStart(place: Cheats.Place) = start("Debug start ${place.name}") { f, mixer ->
        ishar1Only("The debug start")
        val s = Cheats.debugStart(place)
        val vm = s.boot(f, sound = 'B', sink = { SoundBlaster(it, out = mixer) })
        for (frame in 1 until Cheats.DEBUG_START_FRAMES) s.step(vm, frame)
        Run(vm, vm.sound as SoundBlaster, Cheats.DEBUG_START_FRAMES)
    }

    private fun ishar1Only(what: String) {
        if (_state.value.build != GameBuild.ISHAR1) throw LoadFailedException("$what is Ishar 1 only for now: use the game's disk menu")
    }

    fun setFreeSaves(free: Boolean) {
        _state.update { it.copy(freeSaves = free) }
        if (!free) cheat { Cheats.freeSaves(it, false) }
    }

    /**
     * The party to X/Y/facing in the loaded contree, with the view redrawn: a poke alone leaves the
     * old view until a step or turn, so it lands one turn left of [facing] and turns right (keypad 3).
     */
    fun teleport(x: Int, y: Int, facing: Int) {
        val f = facing.coerceIn(1, 4)
        cheat { Cheats.moveParty(it, x, y, (f + 2) % 4 + 1) }
        turnRightSoon()
    }

    /**
     * A long press on the map: the party to cell [x] (across the sheets), [y], if [MapTeleport] allows it, keeping its
     * facing. The game decides at its next tick, on what is there then; a refusal shows on the map for a moment.
     */
    fun teleportOnMap(x: Int, y: Int) {
        cheat { m ->
            val map = partyMap() ?: return@cheat
            val p = Cheats.position(m)
            val refusal = MapTeleport.go(m, map, x, y, (p.facing + 2) % 4 + 1)
            if (refusal == null) turnRightSoon()
            mapTeleportDone?.invoke(refusal)
            _state.update { it.copy(mapRefusal = refusal?.let { r -> MapRefusal(x, y, r) }) }
            if (refusal != null) viewModelScope.launch {
                delay(REFUSAL_SHOWN_MS)
                _state.update { s -> if (s.mapRefusal?.x == x && s.mapRefusal.y == y) s.copy(mapRefusal = null) else s }
            }
        }
    }

    /** What the next [teleportOnMap] decided (the MCP tool, tests). */
    @Volatile
    var mapTeleportDone: ((MapTeleport.Refusal?) -> Unit)? = null

    private fun turnRightSoon() {
        val turnRight = if (_state.value.build == GameBuild.ISHAR1) TURN_RIGHT else ISHAR2_TURN_RIGHT
        viewModelScope.launch {
            delay(100)
            keyDown(turnRight)
            delay(150)
            keyUp(turnRight)
        }
    }

    /** A [Cheats] edit of the running game, at its next tick. */
    fun cheat(edit: (Machine) -> Unit) {
        input?.poke(edit)
    }

    fun healAll() { input?.heal() }

    /** Saves the game has written since start; [saveGame]'s caller watches it. */
    @Volatile
    var savesWritten = 0
        private set

    /** The game's own SAVE from the disk menu, clicked and typed frame by frame; it takes [saveFrames]. */
    fun saveGame(name: String) {
        input?.schedule(
            when (_state.value.build) {
                GameBuild.ARBOREA -> emptyMap() // its saves are the game's own S1..S4.FIC slots
                GameBuild.ISHAR1 -> SaveGames.saveEvents(name, 0)
                GameBuild.ISHAR2 -> SaveGames.ishar2SaveEvents(name, 0)
                GameBuild.ISHAR3 -> SaveGames.ishar3SaveEvents(name, 0)
            },
        )
    }

    fun saveFrames(name: String) = if (_state.value.build != GameBuild.ISHAR1) SaveGames.ishar2SaveFrames(name) else SaveGames.saveFrames(name)

    fun addGold() = cheat { Cheats.addGold(it, 10000) }

    /**
     * The last ten seconds of the mixed sound, muted or not (MCP `audio`). Written on the engine thread, read
     * without a lock: a read may catch a few samples of the tick being written, which a level meter does not mind.
     */
    private val audioRing = ShortArray(AUDIO_RATE * 10)
    private var audioAt = 0L

    private fun keepAudio(pcm: ShortArray) {
        for (s in pcm) audioRing[(audioAt % audioRing.size).toInt()] = s.also { audioAt++ }
    }

    /** The mixed sound of the last [seconds]. */
    fun recentAudio(seconds: Int): ShortArray {
        val end = audioAt
        val n = minOf(seconds * AUDIO_RATE.toLong(), end, audioRing.size.toLong()).toInt()
        return ShortArray(n) { audioRing[((end - n + it) % audioRing.size).toInt()] }
    }

    /** Sound effects the Sound Blaster started in this run (MCP `sound`). */
    fun soundEffects(): Int = soundBlaster?.plays ?: 0

    fun removeCurses() = cheat(Cheats::removeCurses)

    private fun toFragonir(f: GameFiles, mixer: PcmMixer): Scheduler =
        Starts.skipIntro(f, sound = 'B', sink = { SoundBlaster(it, out = mixer) })

    /** Forgets the save and what its party had seen. */
    fun deleteSave(name: String) = store.delete(name.lowercase() + ".sav")

    private fun refreshSaves() {
        fun names(s: SaveStore) = s.names().filter { it.endsWith(".sav") }.map { it.substringBefore('.').uppercase() }.sorted()
        val all = names(store)
        if (_state.value.build != GameBuild.ISHAR1) {
            // Ishar 2's and 3's saves have another layout: version, X, Y, facing, contree
            val version = if (_state.value.build == GameBuild.ISHAR3) 3 else 2
            val headers = all.map { n -> n to runCatching { store.read(n.lowercase() + ".sav") }.getOrNull()?.let { SaveGames.ishar2Header(it, version) } }
            val importable = if (_state.value.build == GameBuild.ISHAR2) names(ishar1Saves).filter(SaveGames::typeable) else emptyList()
            _state.update { it.copy(saves = all, saveSummaries = emptyList(), unloadableSaves = emptyList(), importable = importable, ishar2Saves = headers) }
            return
        }
        val summaries = all.filter(SaveGames::typeable).mapNotNull { n ->
            runCatching { store.read(n.lowercase() + ".sav") }.getOrNull()?.let { SaveGames.summary(n, it) }
        }
        _state.update { it.copy(saves = all, saveSummaries = summaries, unloadableSaves = all - summaries.map { s -> s.name }.toSet(), importable = emptyList(), ishar2Saves = emptyList()) }
    }

    /** Web: audio starts on a user gesture. */
    fun resumeAudio() {
        if (realTime) audio.resume()
    }

    fun setMapOpen(open: Boolean) = _state.update { it.copy(mapOpen = open) }

    fun setMuted(muted: Boolean) = _state.update { it.copy(muted = muted) }

    fun setMusicOn(on: Boolean) = _state.update { it.copy(musicOn = on) }

    /** The app is out of sight (home screen, app switch, a minimised window): the game holds, apart from [pause]. */
    fun setVisible(visible: Boolean) {
        hidden = !visible
        if (hidden) input?.releaseAll()
    }

    /** The keyboard's focus left the game (another window, the debug menu): the keys held go up. */
    fun focusLost() {
        input?.releaseKeys()
    }

    @Volatile
    private var hidden = false

    /** [audio] was paused for [hidden]; the engine's thread only. */
    private var audioHeld = false

    fun pause() = _state.update { it.copy(paused = true) }

    fun resume() = _state.update { it.copy(paused = false) }

    /** While paused: run [frames] more frames and return once they ran. */
    suspend fun step(frames: Int) {
        if (!_state.value.paused) return
        stepping.addAndFetch(frames)
        while (stepping.load() > 0 && job?.isActive == true) delay(5)
    }

    /** [block] on the engine's thread between frames, with the machine of the running game. */
    suspend fun <T> onEngine(block: (Machine) -> T): T? {
        val m = machine ?: return null
        val done = CompletableDeferred<T>()
        calls.send { runCatching { block(m) }.fold(done::complete, done::completeExceptionally) }
        return done.await()
    }

    @Volatile
    private var machine: Machine? = null

    @Volatile
    private var worldMap: PartyMap? = null

    /** The running game's map, none during the intro. Read in [onEngine], so that it goes with that block's machine. */
    fun partyMap(): PartyMap? = worldMap.takeIf { intro?.active != true }

    /** The running game's Sound Blaster ([soundEffects]). */
    private var soundBlaster: SoundBlaster? = null

    /** Ends the running game (tests). */
    fun stop() {
        job?.cancel()
    }

    /** During the intro a key is a tap; 1..4 on the keypad or the top row pick a language. */
    fun keyDown(scancode: Int) {
        val intro = intro?.takeIf { it.active }
        if (intro != null) return intro.tap(LANGUAGE_KEYS.indexOf(scancode).takeIf { it >= 0 }?.let { it % 4 + 1 })
        playerInput()?.keyDown(scancode)
    }

    fun keyUp(scancode: Int) = playerInput()?.keyUp(scancode)

    /** A key from the phone's keyboard at a prompt ([Input.type]). */
    fun type(scancode: Int) = playerInput()?.type(scancode)

    /** [touch]: from a finger, so the pointer is not drawn. */
    fun pointer(x: Int, y: Int, touch: Boolean = false) {
        lastPointer = x to y
        playerInput()?.pointer(x, y, touch)
    }

    /** Clicks go to the game; during the intro each is a tap for [Intro], on the language menu the line it hits ([Starts.languageAt]). */
    fun buttons(buttons: Int) {
        val intro = intro?.takeIf { it.active }
        if (intro != null) {
            if (buttons != 0) intro.tap(lastPointer.let { (x, y) -> Starts.languageAt(x, y) })
            return
        }
        playerInput()?.buttons(buttons)
    }

    private var lastPointer = 0 to 0

    private fun playerInput() = input

    private class LoadFailedException(message: String) : Exception(message)

    private class Run(val vm: Scheduler, val sb: SoundBlaster, val firstFrame: Int)

    private fun start(mode: String, setup: suspend (GameFiles, PcmMixer) -> Run) {
        val f = files ?: return
        job?.cancel()
        input = null
        intro = null
        machine = null
        stepping.store(0)
        _state.update {
            GameState(build = it.build, loading = true, mode = mode, saves = it.saves, importable = it.importable, ishar2Saves = it.ishar2Saves, saveSummaries = it.saveSummaries, unloadableSaves = it.unloadableSaves, freeSaves = it.freeSaves, muted = it.muted, musicOn = it.musicOn)
        }
        job = viewModelScope.launch(engineDispatcher) {
            try {
                running.withLock { play(mode, f, setup) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: LoadFailedException) {
                ensureActive()
                machine = null
                _state.update { it.copy(loading = false, error = e.message) }
            } catch (e: Exception) {
                ensureActive() // a newer run owns the state now
                e.printStackTrace()
                machine = null // nothing drains [calls] any more
                _state.update { it.copy(loading = false, error = "The game stopped: $e") }
            }
        }
    }

    private suspend fun CoroutineScope.play(mode: String, f: GameFiles, setup: suspend (GameFiles, PcmMixer) -> Run) {
        val me = coroutineContext[Job]!!
        val clock = RealTimeClock()
        val mixer = PcmMixer(AUDIO_RATE)
        val runFiles = f.fresh()
        val ishar1 = _state.value.build == GameBuild.ISHAR1
        // Ishar 2's map knows its islands' terrain only: no creatures, items or buildings yet
        // Arborea's world (init.fic, carte.io) is not read yet: no map
        val map: PartyMap? = when (_state.value.build) {
            GameBuild.ARBOREA -> null
            GameBuild.ISHAR1 -> WorldMap.fromFiles(runFiles)
            GameBuild.ISHAR2 -> IslandMap.fromFiles(runFiles)
            GameBuild.ISHAR3 -> IslandMap.fromIshar3Files(runFiles)
        }
        worldMap = map
        var saveOpened = false
        runFiles.onSaveLoaded = { name ->
            map?.restore(store.read(mapName(name)))
            saveOpened = true
        }
        val run = setup(runFiles, mixer)
        ensureActive() // setup does not notice a cancel, and the input is about to be handed over
        val vm = run.vm
        val sb = run.sb
        soundBlaster = sb
        // Here and not at boot: the replays up to this point must stay exact, and main.io 0596 seeds from this clock word
        seed().let {
            vm.machine.ww(0x0040, 0x006C, it)
            vm.machine.sg(0x0C4E, it)
        }
        mixer.take(sb.time) // what an unpaced start produced
        clock.sync(sb.time)
        if (realTime) audio.write(ShortArray(AUDIO_RATE / 12)) // a little headroom
        var held = false
        var wasMuted = false
        vm.tickPace = {
            if (realTime) {
                if (intro?.fastForward == true) {
                    mixer.take(sb.time)
                    held = true
                } else {
                    if (held) clock.sync(sb.time).also { held = false }
                    clock.paceTo(sb.time)
                    val pcm = mixer.take(sb.time)
                    keepAudio(pcm)
                    val muted = _state.value.muted
                    mixer.musicOn = _state.value.musicOn
                    if (wasMuted && !muted) audio.write(ShortArray(AUDIO_RATE / 12)) // nothing was queued
                    wasMuted = muted
                    if (!muted) audio.write(pcm)
                }
            }
            if (!me.isActive) throw CancellationException("stopped")
        }
        input = Input(vm).also { it.install() }
        intro = if (ishar1 && mode == "New game") Intro(vm, input!!) else null
        machine = vm.machine
        var frame = run.firstFrame
        val end = run.firstFrame.toLong() + frameLimit
        var shown = TimeSource.Monotonic.markNow()
        var idle = false
        saveOpened = false
        // Only main.io 0864 (Ishar 2: 08b6) asks for it, on every new start: after a wipe, the disk menu's new game, or a load
        vm.onAssetAsked = { name ->
            if (name.equals("gerdep.io", ignoreCase = true)) {
                if (!saveOpened) map?.restore(null)
                saveOpened = false
            }
            // Ishar 2 knows its buildings only by the interior main.io loads on the way in
            if (map is IslandMap) {
                ISHAR2_INTERIORS[name.lowercase()]?.let { kind -> map.enter(Cheats.position(vm.machine), kind) }
                if (name.lowercase() in IslandMap.SCENE_SCRIPTS) map.enterScene(Cheats.position(vm.machine))
            }
        }
        _state.update { it.copy(loading = false) }
        try {
            while (frame < end) {
                ensureActive()
                while (true) (calls.tryReceive().getOrNull() ?: break)()
                val stepped = _state.value.paused
                if ((hidden || stepped) && stepping.load() <= 0) {
                    if (!idle) publish(vm, frame)
                    idle = true
                    if (realTime && hidden && !audioHeld) audio.pause().also { audioHeld = true }
                    delay(if (hidden) 50 else 5)
                    continue
                }
                if (idle) {
                    idle = false
                    if (realTime) {
                        if (audioHeld) audio.resume().also { audioHeld = false }
                        clock.sync(sb.time)
                        audio.write(ShortArray(AUDIO_RATE / 12)) // the device's buffer ran dry
                    }
                }
                if (ishar1 && _state.value.freeSaves) Cheats.freeSaves(vm.machine, true)
                vm.frame()
                CopyProtection.remove(vm.machine)
                intro?.frame()
                if (intro?.active != true && map != null) Cheats.position(vm.machine).let {
                    map.see(it, if (ishar1) Doors.of(vm.machine, it.contree) else (map as? IslandMap)?.doors(vm.machine, it.contree).orEmpty())
                }
                frame++
                val last = stepped && stepping.load() == 1
                val ff = intro?.fastForward == true
                if (last || !ff && shown.elapsedNow().inWholeMilliseconds >= 30 || frame.toLong() == end) {
                    shown = TimeSource.Monotonic.markNow()
                    publish(vm, frame)
                }
                if (stepped) stepping.addAndFetch(-1)
                if (realTime) clock.settle()
                yield()
            }
        } catch (e: UnsupportedEngineOperationException) {
            publish(vm, frame, "stopped: ${e.message}")
        } catch (e: ProgramEndException) {
            publish(vm, frame, "the program ended")
        }
    }

    private var wasInside = false

    private fun publish(vm: Scheduler, frame: Int, stopped: String? = null) {
        val argb = Palette.frameArgb(vm.machine)
        val m = vm.machine
        val position = Cheats.position(m)
        val introStage = if (intro != null) Intro.stage(m) else null
        val ishar1 = m.build == GameBuild.ISHAR1
        val creatures = Creatures.all(m)
        val groundItems = GroundItems.all(m)
        if (intro?.active != true) worldMap?.spot(position, creatures)
        if (ishar1 && intro?.active != true) {
            val world = worldMap as? WorldMap
            world?.spotItems(position, groundItems, GroundItems.zone(m))
            if (Buildings.inside(m) && !wasInside) Buildings.at(position, Buildings.all(m))?.let { world?.visit(it) }
        }
        wasInside = ishar1 && Buildings.inside(m)
        _state.update {
            it.copy(
                introStage = introStage,
                textPrompt = Input.textPrompt(m),
                namePrompt = Input.namePrompt(m),
                audioError = if (realTime) audio.error else null,
                frames = frame, frame = argb, stopped = stopped,
                party = Cheats.party(m), position = position, creatures = creatures, groundItems = groundItems, visited = worldMap?.visitedBuildings.orEmpty(),
                map = partyMap(),
            )
        }
    }

    override fun onCleared() {
        val running = job
        job?.cancel()
        if (!realTime) return
        // After the engine's thread let go of it: it may be inside a write
        if (running == null) audio.close() else running.invokeOnCompletion { audio.close() }
    }

    private companion object {
        /** The party walks in Fragonir from frame 900. */
        const val SKIP_TO = Starts.SKIP_INTRO_FRAMES
        const val REFUSAL_SHOWN_MS = 1500L
        const val TURN_RIGHT = 0x51

        /** Ishar 2's interiors that tell what a building is (main.io 2d68, 2dc0). */
        val ISHAR2_INTERIORS = mapOf("taverne.io" to Buildings.Kind.TAVERN, "marchand.io" to Buildings.Kind.SHOP)

        /** Ishar 2 turns on keypad 6 (its keypad 3 strafes). */
        const val ISHAR2_TURN_RIGHT = 0x4D

        /** Keypad 1..4, then the top row's 1..4. */
        val LANGUAGE_KEYS = listOf(0x4F, 0x50, 0x51, 0x4B, 0x02, 0x03, 0x04, 0x05)
        const val AUDIO_RATE = 22050

        /** What the party had seen, beside `lr.sav`. */
        fun mapName(save: String) = save.substringBefore('.') + ".map"
    }
}
