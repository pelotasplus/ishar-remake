package dev.pelotasplus.ishar.data.repository

import dev.pelotasplus.ishar.engine.GameBuild
import dev.pelotasplus.ishar.resources.Res

interface ResourceRepository {
    val ioFiles: List<String>
    val ficFiles: List<String>
    val programFiles: List<String>
    val allFiles: List<String>

    suspend fun readBytes(fileName: String): Result<ByteArray>
}

/** The files of [build], under `files/` plus its folder. */
class ResourceRepositoryImpl(
    private val build: GameBuild = GameBuild.ISHAR1,
    private val loader: suspend (String) -> ByteArray = { path -> Res.readBytes(path) },
) : ResourceRepository {

    override val ioFiles: List<String> = when (build) {
        GameBuild.ARBOREA -> ARBOREA_IO_FILES
        GameBuild.ISHAR1 -> IO_FILES
        GameBuild.ISHAR2 -> ISHAR2_IO_FILES
        GameBuild.ISHAR3 -> ISHAR3_IO_FILES
    }
    override val ficFiles: List<String> = when (build) {
        GameBuild.ARBOREA -> ARBOREA_FIC_FILES
        GameBuild.ISHAR1 -> FIC_FILES
        GameBuild.ISHAR2 -> ISHAR2_FIC_FILES
        GameBuild.ISHAR3 -> ISHAR3_FIC_FILES
    }
    override val programFiles: List<String> = PROGRAM_FILES
    override val allFiles: List<String> = ioFiles + ficFiles + programFiles

    private val canonicalNames: Map<String, String> =
        allFiles.associateBy { it.lowercase() }

    override suspend fun readBytes(fileName: String): Result<ByteArray> = runCatching {
        val clean = fileName.substringAfterLast('/').substringAfterLast('\\')
        val resolved = canonicalNames[clean.lowercase()] ?: clean
        loader("files/${build.folder}$resolved")
    }

    companion object {
        val IO_FILES = listOf(
            "affobj.io",
            "arbre.io",
            "auteur.io",
            "azal.io",
            "barbare.io",
            "blancpc.io",
            "bormin.io",
            "buste.io",
            "colcave.io",
            "darkm.io",
            "darkwiz.io",
            "dead.io",
            "dealer.io",
            "dplt.io",
            "dragon.io",
            "dwarrior.io",
            "en1.io",
            "encont.io",
            "fbuis.io",
            "fcave.io",
            "fcave2.io",
            "fond.io",
            "fontaine.io",
            "foret.io",
            "fragorn.io",
            "frise.io",
            "ftemple.io",
            "fville.io",
            "gaz.io",
            "geant.io",
            "gerdep.io",
            "geren.io",
            "goul.io",
            "iboishar.io",
            "incave.io",
            "intmais.io",
            "inville.io",
            "itaverne.io",
            "kiriela.io",
            "knight.io",
            "krog.io",
            "lacustre.io",
            "logo.io",
            "loup.io",
            "main.io",
            "map.io",
            "marchand.io",
            "mcave.io",
            "medus.io",
            "message.io",
            "messaged.io",
            "messagee.io",
            "messagei.io",
            "minotor.io",
            "momo.io",
            "monstre.io",
            "morgu.io",
            "naim.io",
            "objet.io",
            "objint.io",
            "orc.io",
            "pabo.io",
            "param.io",
            "pcave.io",
            "plaine.io",
            "predator.io",
            "presen.io",
            "preson.io",
            "presti.io",
            "rampart.io",
            "rplaine.io",
            "samb.io",
            "saub.io",
            "scave.io",
            "scomb.io",
            "skelet.io",
            "sorcier.io",
            "sos.io",
            "sosd.io",
            "sose.io",
            "sosi.io",
            "souris.io",
            "spectre.io",
            "spider.io",
            "stage.io",
            "stel.io",
            "telep.io",
            "temple.io",
            "textin.io",
            "textind.io",
            "textine.io",
            "textini.io",
            "theend.io",
            "village.io",
            "ville.io",
            "wardog.io",
            "wiz1.io",
            "zombi.io",
        )

        val FIC_FILES = listOf(
            "cont1.fic",
            "cont2.fic",
            "cont3.fic",
            "cont4.fic",
            "cont5.fic",
            "cont6.fic",
            "en1.fic",
            "tab1.fic",
        )

        val ISHAR2_IO_FILES = listOf(
            "affobj.io", "aigle.io", "animals.io", "arbo.io", "aveugle5.io", "boat.io", "bob4.io", "buste.io",
            "buste2.io", "chaosgu5.io", "dj1.io", "dj2.io", "djcat.io", "djcol.io", "djdec.io", "djfont.io",
            "djtrap.io", "dragon5.io", "druide2.io", "druide4.io", "dwizard5.io", "dwoman2.io", "eaff.io", "eatt.io",
            "edplt.io", "elmtair5.io", "elmtfeu5.io", "ent2.io", "ewok2.io", "farbo.io", "fdj1.io", "fdj2.io",
            "fdjcat.io", "fly1.io", "fmont.io", "fond1.io", "foret1.io", "frise.io", "fville.io", "geant.io",
            "gerdep.io", "gesort.io", "golem1.io", "guard3.io", "guard3hi.io", "guard4.io", "homois2.io", "horny4.io",
            "iboishar.io", "io.io", "krog.io", "lezard1.io", "lionman5.io", "logo.io", "lostgame.io", "lutin3.io",
            "main.io", "map.io", "marchand.io", "marin.io", "medusa5.io", "message.io", "messaged.io", "messagee.io",
            "momy5.io", "monster5.io", "montagne.io", "nain4.io", "objet.io", "odwoman5.io", "olbar.io", "ork5.io",
            "param.io", "pcave.io", "pcmusic1.io", "pcmusic2.io", "pcmusic3.io", "people1.io", "people2.io",
            "piaf4.io", "plaine1.io", "pours1.io", "pp11.io", "pp12.io", "pp2i.io", "pp3ban.io", "pp3mn.io",
            "pp3tb.io", "ppechap.io", "ppsac.io", "pptravel.io", "present.io", "present1.io", "present2.io",
            "presound.io", "puit.io", "relicman.io", "rhino4.io", "saub.io", "scite.io", "scomb.io", "sdonjons.io",
            "shandar.io", "sile1.io", "skelett5.io", "smer.io", "smontagn.io", "sorcier5.io", "sortmor5.io",
            "souris.io", "sparoles.io", "sville.io", "tableau.io", "taverne.io", "textin.io", "textind.io",
            "textine.io", "theend1.io", "trouba3.io", "varcade.io", "varche.io", "vbibli.io", "vdec1.io", "vdec2.io",
            "vdec3.io", "vhouse1.io", "vhouse2.io", "village1.io", "ville.io", "vjail.io", "vlinge.io", "vmairie.io",
            "vnight.io", "voleur3.io", "vpassage.io", "vpont.io", "vport.io", "vtemple.io", "witch5.io", "wizard1.io",
            "wizard5.io",
        )

        val ISHAR2_FIC_FILES = listOf(
            "cont1.fic", "cont2.fic", "cont3.fic", "cont4.fic", "cont5.fic", "cont6.fic", "cont7.fic",
            "en1.fic", "present.fic", "raz.fic",
        )

        val ISHAR3_IO_FILES = listOf(
            "accueil.io", "actors.io", "affclock.io", "affobj.io", "affpv.io", "alstar.io", "bee5.io",
            "bigstone.io", "buste.io", "buste2.io", "chaos5.io", "chaos7.io", "coffre.io", "create.io",
            "dead.io", "dj1.io", "dj2.io", "dj3.io", "dj4.io", "djclock.io", "djdec.io", "djdec2.io",
            "djlave.io", "djtrap.io", "dragon7.io", "eaff.io", "eatt.io", "edplt.io", "fdj1.io", "fdj2.io",
            "fdj3.io", "fdj4.io", "fdjlave.io", "fee5.io", "fforet.io", "fireball.io", "fjungle.io",
            "fly5.io", "fmont.io", "foret.io", "frise.io", "fville.io", "geant9.io", "generat9.io",
            "gerdep.io", "gesort.io", "gptav.io", "greem5.io", "guard1.io", "guard2.io", "intro1.io",
            "intro2.io", "intro3.io", "intro4.io", "io.io", "jungle.io", "leo3.io", "lezard1.io", "logo.io",
            "loup9.io", "mage5.io", "main.io", "map.io", "marchand.io", "message.io", "moltus.io",
            "montagne.io", "music1.io", "music2.io", "objet.io", "ours5.io", "ours9.io", "param.io",
            "pcave.io", "plasma7.io", "pp3ban.io", "ppbibli.io", "ppechap.io", "ppint.io", "ppmatter.io",
            "ppobs.io", "pppalais.io", "ppth.io", "princess.io", "redfi7.io", "runner.io", "scomb.io",
            "sforet.io", "sjungle.io", "skelet7.io", "slime7.io", "smontagn.io", "snake3.io", "sorcier7.io",
            "souris.io", "space.io", "space2pc.io", "spider7.io", "sville.io", "sville2.io", "taverne.io",
            "textin.io", "thefin.io", "typhus.io", "varcade.io", "varche.io", "varche2.io", "vbank.io",
            "vbibli.io", "vcasino.io", "vdec1.io", "vdec2.io", "vhouse1.io", "vhouse2.io", "vhouse3.io",
            "vjardin.io", "vmatter.io", "vobs.io", "voleur1.io", "vpalais.io", "vperso1.io", "vperso2.io",
            "vperso3.io", "vperso4.io", "vperso5.io", "vrampart.io", "vtheatre.io", "vwall.io", "witch7.io",
            "wizard7.io", "zoltar.io", "zouav3.io",
        )

        val ISHAR3_FIC_FILES = listOf(
            "cont1-3.fic", "cont2-3.fic", "cont3-3.fic", "cont4-3.fic", "cont5-3.fic", "cont6-3.fic",
            "en1.fic", "init.fic", "initpv.fic", "raz.fic",
        )

        /** Crystals of Arborea: its intro (diam, present, boarbo.io) and the editor (tableau.io) are not shipped. */
        val ARBOREA_IO_FILES = listOf(
            "arbre.io", "blancpc.io", "carte.io", "cavint.io", "cielsol.io", "combat.io", "death.io",
            "debuter.io", "ebrain.io", "ecran.io", "ecrans.io", "emove.io", "endiv.io", "exca.io",
            "gemecav.io", "gemeext.io", "gersom.io", "ghabitat.io", "gugus.io", "habitat.io", "ht.io",
            "intmais.io", "lafin.io", "logo.io", "main.io", "manuel.io", "message2.io", "messages.io",
            "morgot.io", "nad.io", "nplaine.io", "orc_elf.io", "plages.io", "puit.io", "samb.io", "scomb.io",
            "shamnir.io", "solcave.io", "souris.io", "tour.io", "zdiam.io",
        )
        val ARBOREA_FIC_FILES = listOf("init.fic", "s1.fic", "s2.fic", "s3.fic", "s4.fic")

        val PROGRAM_FILES = listOf(
            "start.stp",
        )
    }
}
