# Ishar - Compose Multiplatform Remake

This project brings the Ishar trilogy (Silmarils, 1992-1994) and Crystals of Arborea, the game before it, to modern platforms while staying faithful to the original DOS games. Built entirely in Kotlin, it runs on Android, iOS, Desktop, and Web.

It does not rewrite the games' rules. It runs their original scripts on a reimplemented VM, with the original timing: the 57.6 Hz tick and 19.2 fps. Thanks to Claude Code there is now something to actually play, in your browser:

**[ishar.pelotasplus.dev →](https://ishar.pelotasplus.dev/)**

## Features

- A reimplemented script VM that runs the original `.io` files, for all four games
- Crystals of Arborea (in its re-release on Ishar 1's engine), Ishar 1: Legend of the Fortress, Ishar 2: Messengers of Doom and Ishar 3: The Seven Gates of Infinity, picked on a start screen
- Original graphics, the 3D view, palettes and Ishar 3's FLI cinematics
- Sound Blaster samples, the MOD music player and Ishar 2's FM tunes on an OPL2 chip
- Saves from the original games load, and parties move from game to game as they did on DOS
- A map of what the party has seen: terrain, doors, levers, buildings, ground items and people; a long press on it takes the party there
- Touch controls and the phone's keyboard at the game's prompts
- The copy protection is removed
- A debug menu: heal, gold, remove curses, free saves, a save browser
- Bugs, bugs, and more bugs. Help me squash them!

## Platforms

| Platform | Status |
|----------|--------|
| **[Web (Wasm/JS)](https://ishar.pelotasplus.dev/)** | Deployed and played |
| Desktop (JVM) | Played, and drivable by an agent |
| Android | Run on a phone |
| iOS | Run on the simulator; sound and the silent switch are not checked on a real iPhone |

Everything is one Kotlin Multiplatform codebase, built with the [Kotlin Toolchain](https://kotlin-toolchain.org). The `kotlin` and `kotlin.bat` scripts download it on first use.

## Quick Start

The games' own files are not in this repository, so bring your own copies. The project reads them from `shared/composeResources/files`, one folder a game, with lowercase names. It needs each game's `*.io`, `*.fic` and `start.stp`, flat in the folder; a game whose folder is missing stays out. Crystals of Arborea ships no `start.stp`: write one containing `RBVVSBP1J0M1KQ` (VGA, Sound Blaster).

```
$ ls -al shared/composeResources/files
arborea/   # 47 files: 41 .io, 5 .fic, start.stp
ishar1/    # 107 files: 98 .io, 8 .fic, start.stp
ishar2/    # 152 files: 141 .io, 10 .fic, start.stp
ishar3/    # 143 files: 132 .io, 10 .fic, start.stp

$ ls -al shared/composeResources/files/ishar1
-rw-r--r--    730  affobj.io
-rw-r--r--  16476  arbre.io
-rw-r--r--  25944  auteur.io
-rw-r--r--  14224  azal.io
...
```

Then run it:

```bash
./kotlin run -m desktopApp
./kotlin run -m webApp
./kotlin run -m androidApp
./kotlin run -m iosApp
```

W/S/A/D move and Q/E turn. Ishar's save prompts take letters and digits, as the originals do.

### Tests

The suite compares the engine with snapshots and recordings of the original games running under an emulator, and with a reference engine in Python. Those are not in this repository. With the game files staged, `./kotlin test -p jvm` runs the rest and skips the tests that need them (41 of 224).

Rendering is guarded by golden-image tests: each one draws a known frame and compares it pixel by pixel against a reference PNG in `shared/test@jvm/goldens/`.

## Letting an agent play

The desktop app can be driven by an AI agent through an MCP server. It only listens on `127.0.0.1`, and only when asked to:

```bash
ISHAR_MCP=8271 ISHAR_MUTE=1 ./kotlin run -m desktopApp
```

It speaks JSON-RPC over HTTP POST at `http://127.0.0.1:8271/mcp`; register that with your agent. Its tools load saves, press keys, click, take screenshots and print the map around the party as text. `pause` and `step` give frame-exact control, and `read_memory` and `write_memory` reach the DOS memory the scripts see.

## Credits

- **[Spice86](https://github.com/OpenRakis/Spice86)** — the emulator for reverse engineering DOS games. Everything here was checked against the original games running under it.

And, of course, **Silmarils**, who made the games.

## Legal

This is a fan project for educational purposes. The games' own data files are not in this repository — you need your own copies of the games to build it.

Three things derived from the games are here:

- tables and initial values from the original `start.exe` in `shared/src/engine/ExeData.kt`, so that the executable need not be shipped or unpacked;
- the golden PNGs in `shared/test@jvm/goldens/`, which are frames this renderer drew from the original artwork, compared against to catch rendering regressions;
- short strings from the games' own text, quoted in the source where a text is replaced as it loads or where the tests assert what is drawn.

*Ishar* and its sequels are the property of their respective owners; this project is not affiliated with or endorsed by them.

## License

MIT, covering the code in this repository. The tables, rendered frames and quoted strings described above are derived from the original games and are not ours to license.
