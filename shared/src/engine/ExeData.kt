package dev.pelotasplus.ishar.engine

/**
 * Hardcoded constant tables and initial engine globals from the original executable, eliminating the
 * need to ship or unpack `start.exe`.
 */
object ExeData {
    /** `int9_handler` scancode translation table at `cs:04be` (112 bytes; `0xfe` = none). */
    val KBD_TABLE: ByteArray = hex(
        "1b2682222728608a218785292d080971776572747975696f705e240dfe617364" +
            "6667686a6b6c3b97e6fe3c7a786376626e6d3b2e2ffe2afe20febbbcbdbebfc0" +
            "c1c2c3c4ffff3738392d3435362b313233302effffffffffffffffffffffffff" +
            "ffffffffffffffffffffffffffffffff",
    )

    /** 4-bit Fibonacci delta table at `cs:16bc` (`[-34, -21, -13, -8, -5, -3, -2, -1, 1, 2, 3, 5, 8, 13, 21, 34]`). */
    val FIBONACCI_DELTAS: ByteArray = hex("deebf3f8fbfdfeff01020305080d1522")

    /** steer_directions, `cs:6866` in Ishar 1 ([GameBuild.steer]; read by `s_step_check` `94h`). */
    val CS_6866: ByteArray = hex("19010000010100000100ff0100ff0000ffff0000ff0001ff0001000101010100")


    /** `ss:0e35..0e42` (14 bytes of initial engine table data). */
    val SS_0E35: ByteArray = hex("cd9a7b66584d443d38332f2c2926")

    /** Speaker/DAC output curve at `0941:001a` (`0abe:001a`, 256 bytes). */
    val DRV_CURVE: ByteArray = hex(
        "0101010101010202020202020202020202020203030303030303030303030404" +
            "0404040404040404040404050505050505050505050506060606060606060606" +
            "0606060707070707070707070707080808080808080808080808080909090909" +
            "09090a0a0a0a0b0b0c0c0d0e0e0f101111121314151617181a1b1c1d1e1f2122" +
            "2324262728292b2c2d2e3031323334353637393a3a3b3c3d3e3f404041424243" +
            "4343434343444444444444444444444444444545454545454545454545464646" +
            "4646464646464646464647474747474747474747474848484848484848484848" +
            "484849494949494949494949494a4a4a4a4a4a4a4a4a4a4a4a4a4b4b4b4b4b4b",
    )

    /** SB output stub at `0941:1280` (`0abe:1280`, 24 bytes). */
    /** `cs:22c2`: A0h / B0h of the 36 notes, for the FM tunes. */
    val DRV_FM_NOTES: ByteArray = hex(
        "340d470d5a0d6f0d840d9c0db40dce0de90d070e250e460e341147115a116f1184119c11b411ce11e911071225124612341547155a156f1584159c15b415ce15e915071625164616",
    )

    val DRV_SB_STUB: ByteArray = hex("601e06ba3412ec0ac078fbb010eeec0ac078fb2ea00814ee")

    /** MOD player funk table (`1409`, 16B), sine table (`1419`, 32B) and 16 finetunes × 36 periods (`1439`, 1152B). */
    val DRV_FUNK_SINE_PERIODS: ByteArray = hex(
        "00050607080a0b0d1013161a202b40800018314a61788da1b4c5d4e0ebf4fafd" +
            "fffdfaf4ebe0d4c5b4a18d78614a311858032803fa02d002a60280025c023a02" +
            "1a02fc01e001c501ac0194017d016801530140012e011d010d01fe00f000e200" +
            "d600ca00be00b400aa00a00097008f0087007f007800710052032203f502cb02" +
            "a2027d02590237021702f901dd01c201a90191017b01650151013e012c011c01" +
            "0c01fd00ef00e100d500c900bd00b300a9009f0096008e0086007e0077007100" +
            "4c031c03f002c5029e027802550233021402f601da01bf01a6018e0178016301" +
            "4f013c012a011a010a01fb00ed00e000d300c700bc00b100a7009e0095008d00" +
            "85007d007600700046031703ea02c0029902740250022f021002f201d601bc01" +
            "a3018b01750160014c013a01280118010801f900eb00de00d100c600bb00b000" +
            "a6009d0094008c0084007d0076006f0040031103e502bb0294026f024c022b02" +
            "0c02ef01d301b901a001880172015e014a013801260116010601f700e900dc00" +
            "d000c400b900af00a5009c0093008b0083007c0075006e003a030b03e002b602" +
            "8f026b02480227020802eb01cf01b5019d01860170015b014801350124011401" +
            "0401f500e800db00ce00c300b800ae00a4009b0092008a0082007b0074006d00" +
            "34030603da02b1028b026602440223020402e701cc01b2019a0183016d015901" +
            "45013301220112010201f400e600d900cd00c100b700ac00a3009a0091008900" +
            "81007a0073006d002e030003d502ac02860262023f021f020102e401c901af01" +
            "970180016b01560143013101200110010001f200e400d800cc00c000b500ab00" +
            "a1009800900088008000790072006c008b0358032803fa02d002a60280025c02" +
            "3a021a02fc01e001c501ac0194017d016801530140012e011d010d01fe00f000" +
            "e200d600ca00be00b400aa00a00097008f0087007f007800840352032203f502" +
            "cb02a3027c02590237021702f901dd01c201a90191017b01650151013e012c01" +
            "1c010c01fd00ee00e100d400c800bd00b300a9009f0096008e0086007e007700" +
            "7e034c031c03f002c5029e027802550233021402f601da01bf01a6018e017801" +
            "63014f013c012a011a010a01fb00ed00df00d300c700bc00b100a7009e009500" +
            "8d0085007d007600770346031703ea02c0029902740250022f021002f201d601" +
            "bc01a3018b01750160014c013a01280118010801f900eb00de00d100c600bb00" +
            "b000a6009d0094008c0084007d007600710340031103e502bb0294026f024c02" +
            "2b020c02ee01d301b901a001880172015e014a013801260116010601f700e900" +
            "dc00d000c400b900af00a5009c0093008b0083007b0075006b033a030b03e002" +
            "b6028f026b02480227020802eb01cf01b5019d01860170015b01480135012401" +
            "14010401f500e800db00ce00c300b800ae00a4009b0092008a0082007b007400" +
            "640334030603da02b1028b026602440223020402e701cc01b2019a0183016d01" +
            "590145013301220112010201f400e600d900cd00c100b700ac00a3009a009100" +
            "890081007a0073005e032e030003d502ac02860262023f021f020102e401c901" +
            "af01970180016b01560143013101200110010001f200e400d800cb00c000b500" +
            "ab00a100980090008800800079007200",
    )

    /**
     * Populates [machine] with the hardcoded executable tables and initial
     * `seg_0c0b` (SS) globals, where [Machine.build] keeps them.
     */
    fun load(machine: Machine) {
        fun copy(seg: Int, ofs: Int, data: ByteArray) {
            data.copyInto(machine.m, destinationOffset = (seg shl 4) + ofs)
        }

        val b = machine.build
        val ss = machine.ss

        // CS (0x017D) tables
        copy(Machine.CS, 0x04BE, KBD_TABLE)
        copy(Machine.CS, b.fibonacci, FIBONACCI_DELTAS)
        copy(Machine.CS, b.steer, CS_6866)

        // SS initial data
        // 0b60 = 04 00 00 00 01 (solid-box sprite header at ss:0b64 used by stmt 7ch)
        machine.wb(ss, 0x0B60, 0x04)
        machine.wb(ss, 0x0B64, 0x01)
        machine.sg(0x0BA0, 0xFFFF)
        machine.sg(0x0BBA, 319)
        machine.sg(0x0BBC, 199)
        machine.sgb(0x0CC9, 0x80)
        machine.sgb(0x0E0B, 0x80)
        copy(ss, 0x0E35, SS_0E35)
        // 1143..1442 = ffh (the DAC shadow, 768 bytes)
        val dacShadowBase = (ss shl 4) + 0x1143
        machine.m.fill(0xFF.toByte(), dacShadowBase, dacShadowBase + 0x0300)
        machine.sgb(0x1D63, '\\'.code)
        machine.sgb(0x1E3D, 0x90)
        // Exe residue that frame_create leaves in BP-20h (ss:6867)
        copy(ss, 0x6867, hex(b.ss6867))

        // Sound & MOD driver tables in the driver (0x0ABE) and SB data (0x0D29) segments
        copy(b.snd, 0x001A, DRV_CURVE)
        copy(b.snd, 0x1280, DRV_SB_STUB)
        copy(b.snd, 0x1409, DRV_FUNK_SINE_PERIODS)
        copy(b.snd, 0x22C2, DRV_FM_NOTES)
        machine.ww(b.sbd, 0x00D3, 12_000)
    }

    private fun hex(s: String): ByteArray {
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            val hi = s[i * 2].digitToInt(16)
            val lo = s[i * 2 + 1].digitToInt(16)
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}
