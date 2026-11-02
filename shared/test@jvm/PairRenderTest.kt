package dev.pelotasplus.ishar.engine

import dev.pelotasplus.ishar.data.repository.ResourceRepositoryImpl
import kotlinx.coroutines.test.runTest
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Each recorded pair with the [Renderer] installed, without fades or palette statements
 * (stop at the first), against the reference engine doing the same:
 * CRCs of the memory regions, the E000 page and the A000 screen; first without the pointer
 * (4.2), then with [Present] (4.3). Where the run reaches N frames the regions, the page and
 * (with the pointer, when B holds one) the screen are also compared with the live snapshot B.
 */
class PairRenderTest {

    private class Rendered(
        val frames: Int,
        val statements: Long,
        val regions: Long,
        val page: Long,
        val screen: Long,
        val regionsB: Boolean,
        val pageB: Boolean,
        val screenB: Boolean = false,
    )

    private class Stop : RuntimeException()

    private val expected = mapOf(
        "a" to Rendered(20, 16307, 0xCC552EA1L, 0xC986E7FAL, 0x7DC291ABL, true, true),
        "w" to Rendered(30, 37494, 0x052E768EL, 0x03DD8317L, 0xC9C94F60L, true, true),
        "c" to Rendered(40, 33176, 0xDDB582BBL, 0xCA3038F4L, 0x88B03204L, true, true),
        "t" to Rendered(1, 691, 0x79CC6C52L, 0x90B3E529L, 0xBEC932C1L, false, false),
        "l" to Rendered(40, 6142, 0x7BBDD42FL, 0x7870AD68L, 0x663528F0L, true, true),
        "m" to Rendered(60, 47101, 0x63351FDBL, 0xD1DED3D9L, 0x0956DA77L, true, true),
        "s" to Rendered(40, 30823, 0xA635B4C0L, 0x3C2131E2L, 0x1F72964BL, true, true),
        "i" to Rendered(40, 722, 0xC0885576L, 0x33F7F0E7L, 0x4D962383L, true, true),
        "k" to Rendered(1, 28, 0x1DC48734L, 0x2255ADB5L, 0x4B8A6CC2L, false, false),
        "v" to Rendered(40, 30104, 0x1CB76AD4L, 0x340555F9L, 0x340555F9L, true, true),
        "n" to Rendered(40, 29329, 0xCD436989L, 0x8EDA64E0L, 0x8EDA64E0L, true, true),
        "h" to Rendered(40, 29595, 0xA362DCB5L, 0xD37701C6L, 0x708C897BL, true, true),
        "o" to Rendered(30, 37945, 0x3DC918CBL, 0x03DD8317L, 0x03DD8317L, true, true),
        "mp" to Rendered(30, 44631, 0xE9D9E61AL, 0x33E36E8DL, 0x33E36E8DL, true, true),
        "kt" to Rendered(30, 44259, 0xD5DDA056L, 0x374DD767L, 0x374DD767L, true, true),
        "fk" to Rendered(40, 29975, 0xFC33D43CL, 0xEE6A068AL, 0x507BC937L, true, true),
        "am" to Rendered(60, 7083, 0x7AF0D3B9L, 0x70F4B9D2L, 0x8E4B4EE8L, true, true),
        "mx" to Rendered(40, 30132, 0x35904F82L, 0x1F91FAD7L, 0x1F91FAD7L, true, true),
        "rc" to Rendered(30, 21880, 0x8A9F4193L, 0xC2557834L, 0xC2557834L, true, true),
        "nm" to Rendered(40, 26663, 0x2D16D134L, 0x6984B0E9L, 0x7F2AA32BL, true, true),
        "is" to Rendered(30, 20107, 0x44B0C181L, 0xE1EC9B59L, 0x19B26B91L, true, true),
        "ie" to Rendered(40, 26260, 0x66B46025L, 0x6EB3B5E1L, 0x6EB3B5E1L, true, true),
        "ix" to Rendered(30, 21897, 0x6C014923L, 0x539B640FL, 0x539B640FL, true, true),
        "vd" to Rendered(40, 33068, 0x715D4965L, 0x4BA256B1L, 0x4BA256B1L, true, true),
        "pm" to Rendered(40, 29697, 0xD359AE86L, 0xC2557834L, 0xC2557834L, true, true),
        "fm" to Rendered(30, 22456, 0x3B3479EBL, 0xD8A49E08L, 0xE2346C58L, true, true),
        "fd" to Rendered(20, 14911, 0xDFE338AFL, 0xF38DAD3CL, 0xD0031E28L, true, true),
        "fx" to Rendered(20, 14639, 0x4D4A8444L, 0xC2557834L, 0xC2557834L, true, true),
        "dk" to Rendered(40, 5399, 0xD2011B6EL, 0x84E6AFC6L, 0x65FD44DFL, true, true),
        "dx" to Rendered(40, 32600, 0x48B2C495L, 0xC2557834L, 0x5FBD756DL, true, true),
        "mc" to Rendered(30, 24534, 0x6FE90A9DL, 0x1C2E2828L, 0x167D50EDL, true, true),
        "vc" to Rendered(40, 32831, 0x8E13D962L, 0x1C2E2828L, 0x46942F7FL, true, true),
        "vt" to Rendered(60, 42583, 0x02452791L, 0x5EF0FA93L, 0x5EF0FA93L, true, true),
        "ro" to Rendered(80, 58661, 0xB79CD0E7L, 0xB2AE3B40L, 0x953086BAL, true, true),
        "te" to Rendered(40, 15028, 0x50E7B0D6L, 0xF8F1A920L, 0x5B3B377CL, true, true),
        "tr" to Rendered(2, 818, 0x88567744L, 0x66BDA2F4L, 0x7974CE4BL, false, false),
        "tq" to Rendered(3, 1080, 0x463AF68BL, 0x2DB2F845L, 0xFC937E8CL, false, false),
        "tx" to Rendered(3, 1523, 0x4423709FL, 0xE77C853AL, 0x358F1573L, false, false),
        "sb" to Rendered(60, 20171, 0xE310732FL, 0x122B8E43L, 0x6D35548DL, true, true),
        "sm" to Rendered(60, 21411, 0x3A4EAD0BL, 0x122B8E43L, 0x122B8E43L, true, true),
        "sx" to Rendered(60, 21793, 0x0327F3CEL, 0x85568D06L, 0x766A1786L, true, true),
        "hc" to Rendered(60, 20454, 0xD7FF8324L, 0xBBCAEFB7L, 0xBBCAEFB7L, true, true),
        "hy" to Rendered(60, 49101, 0x49326E02L, 0xEFE1CCE9L, 0xADE205C6L, true, true),
        "hqr" to Rendered(40, 4556, 0xD1052A7CL, 0xBCB457B8L, 0x45387F63L, true, true),
        "h3r" to Rendered(2, 876, 0x277A24CDL, 0x898A96B3L, 0x3CE9C9ACL, false, false),
        "csr" to Rendered(40, 29805, 0xE9850648L, 0x4A98C540L, 0x0960DCB8L, true, true),
        "srr" to Rendered(60, 43902, 0xAABFBD14L, 0x77A2FE6AL, 0x77A2FE6AL, true, true),
        "hlr" to Rendered(40, 29324, 0x6B92895EL, 0x4CD86D97L, 0xD51CB961L, true, true),
        "kp" to Rendered(30, 25309, 0x5FBDABEBL, 0x9466D39AL, 0x9466D39AL, true, true),
        "ks" to Rendered(20, 14915, 0x016B1178L, 0x9466D39AL, 0x98D5E996L, true, true),
        "ka" to Rendered(20, 16612, 0x8D166F4DL, 0x9466D39AL, 0x98D5E996L, true, true),
        "pd" to Rendered(30, 20071, 0x36B3A383L, 0x7F58E925L, 0x7F47A123L, true, true),
        "gs" to Rendered(30, 19756, 0x32A606A1L, 0x709AC902L, 0x6AC3B0A6L, true, true),
        "gp" to Rendered(40, 27053, 0x6D52BEE7L, 0x36D5B9B5L, 0xA5DAA118L, true, true),
        "gm" to Rendered(40, 26417, 0x244133A4L, 0xC4D4627AL, 0x89CCF351L, true, true),
        "gl" to Rendered(30, 21548, 0xBA8B3BB3L, 0x22C2FFEAL, 0xDF18AE32L, true, true),
        "gx" to Rendered(30, 21880, 0x6341D010L, 0x22C2FFEAL, 0xDF18AE32L, true, true),
        "dr" to Rendered(30, 22259, 0xB25E9FA7L, 0x591C38F5L, 0x77E83731L, true, true),
        "de" to Rendered(30, 22254, 0x6611018BL, 0x591C38F5L, 0x77E83731L, true, true),
        "da" to Rendered(30, 22402, 0x88D5F53AL, 0x9E6078E6L, 0xAB3531FDL, true, true),
        "kl" to Rendered(30, 22673, 0x02792DA6L, 0xF0607759L, 0xF0607759L, true, true),
        "bd" to Rendered(30, 19969, 0xB4EF75A1L, 0x57530BAAL, 0x34147456L, true, true),
        "bu" to Rendered(30, 19983, 0x0CBD5A7FL, 0x38EC1FFDL, 0xFE406146L, true, true),
        "bn" to Rendered(4, 2749, 0x52D760CAL, 0xC64295EBL, 0xC64295EBL, true, true),
        "bnr" to Rendered(30, 21803, 0x683D0244L, 0x6A09D22CL, 0x6A09D22CL, true, true),
        "sf" to Rendered(30, 21823, 0x90FEA565L, 0x7040B450L, 0x7040B450L, true, true),
        "cm" to Rendered(20, 15032, 0xE681BCEFL, 0x7040B450L, 0xE1BF0E90L, true, true),
        "ap" to Rendered(20, 15098, 0x758BB874L, 0x7040B450L, 0xE1BF0E90L, true, true),
        "wr" to Rendered(30, 21954, 0x43BF7473L, 0x297418EFL, 0x6D9A06CCL, true, true),
        "dl" to Rendered(3, 2173, 0xD9422CF7L, 0x17262DA5L, 0x3566C70CL, false, false),
        "rg" to Rendered(3, 1729, 0x29545C8AL, 0xBA6FB236L, 0x982F589FL, false, false),
        "rk" to Rendered(3, 8086, 0xE69E3D03L, 0x15E20547L, 0x37A2EFEEL, false, false),
        "lv" to Rendered(30, 24519, 0x40B1B0B5L, 0x3B560566L, 0xA345D7DFL, true, true),
        "lw" to Rendered(30, 24582, 0xD9C7A610L, 0x32879F4AL, 0xAA944DF3L, true, true),
        "lx" to Rendered(30, 24449, 0xB4D426FEL, 0x3B323637L, 0xA321E48EL, true, true),
        "ly" to Rendered(30, 24519, 0x3C9BE70CL, 0x32E3AC1BL, 0xAAF07EA2L, true, true),
        "go" to Rendered(30, 493, 0xECF84B89L, 0xFC293EB1L, 0xFC293EB1L, false, false),
        "gk" to Rendered(102, 5759, 0xAC6B5358L, 0x0C3ACCE4L, 0xFC293EB1L, false, false),
        "ld" to Rendered(7, 2357, 0xA8B898F7L, 0x4DB8C28DL, 0x3EFEBE02L, false, false),
        "sw" to Rendered(40, 27193, 0xFB28003FL, 0x791BF0B4L, 0x9B7E11BCL, true, true),
    )

    private val expectedPresent = mapOf(
        "a" to Rendered(20, 16307, 0xCC552EA1L, 0xC986E7FAL, 0x7DC291ABL, true, true, false),
        "w" to Rendered(30, 37494, 0x052E768EL, 0x03DD8317L, 0xB933BCE5L, true, true, false),
        "c" to Rendered(40, 33176, 0xDDB582BBL, 0xCA3038F4L, 0x88B03204L, true, true, false),
        "t" to Rendered(1, 691, 0x79CC6C52L, 0x90B3E529L, 0x1BB590FDL, false, false, false),
        "l" to Rendered(40, 6142, 0x7BBDD42FL, 0x7870AD68L, 0xB7390AE1L, true, true, false),
        "m" to Rendered(60, 47101, 0x63351FDBL, 0xD1DED3D9L, 0x7E2A5608L, true, true, false),
        "s" to Rendered(40, 30823, 0xA635B4C0L, 0x3C2131E2L, 0x30AD0EB0L, true, true, false),
        "i" to Rendered(40, 722, 0xC0885576L, 0x33F7F0E7L, 0x4D962383L, true, true, false),
        "k" to Rendered(1, 28, 0x1DC48734L, 0x2255ADB5L, 0x4B8A6CC2L, false, false, false),
        "v" to Rendered(40, 30104, 0x1CB76AD4L, 0x340555F9L, 0x340555F9L, true, true, true),
        "n" to Rendered(40, 29329, 0xCD436989L, 0x8EDA64E0L, 0x481E698FL, true, true, true),
        "h" to Rendered(40, 29595, 0xA362DCB5L, 0xD37701C6L, 0x708C897BL, true, true, true),
        "o" to Rendered(30, 37945, 0x3DC918CBL, 0x03DD8317L, 0xF4B5D561L, true, true, true),
        "mp" to Rendered(30, 44631, 0xE9D9E61AL, 0x33E36E8DL, 0x466D0987L, true, true, true),
        "kt" to Rendered(30, 44259, 0xD5DDA056L, 0x374DD767L, 0xC0258111L, true, true, true),
        "fk" to Rendered(40, 29975, 0xFC33D43CL, 0xEE6A068AL, 0x507BC937L, true, true, true),
        "am" to Rendered(60, 7083, 0x7AF0D3B9L, 0x70F4B9D2L, 0x6D4953D2L, true, true, true),
        "mx" to Rendered(40, 30132, 0x35904F82L, 0x1F91FAD7L, 0x022C10D7L, true, true, true),
        "rc" to Rendered(30, 21880, 0x8A9F4193L, 0xC2557834L, 0x924F0CF8L, true, true, true),
        "nm" to Rendered(40, 26663, 0x2D16D134L, 0x6984B0E9L, 0x7F2AA32BL, true, true, true),
        "is" to Rendered(30, 20107, 0x44B0C181L, 0xE1EC9B59L, 0xE1EC9B59L, true, true, true),
        "ie" to Rendered(40, 26260, 0x66B46025L, 0x6EB3B5E1L, 0x6EB3B5E1L, true, true, true),
        "ix" to Rendered(30, 21897, 0x6C014923L, 0x539B640FL, 0x539B640FL, true, true, true),
        "vd" to Rendered(40, 33068, 0x715D4965L, 0x4BA256B1L, 0x4BA256B1L, true, true, true),
        "pm" to Rendered(40, 29697, 0xD359AE86L, 0xC2557834L, 0xCEE64238L, true, true, true),
        "fm" to Rendered(30, 22456, 0x3B3479EBL, 0xD8A49E08L, 0xAAD38DCEL, true, true, true),
        "fd" to Rendered(20, 14911, 0xDFE338AFL, 0xF38DAD3CL, 0xDE79D0B9L, true, true, true),
        "fx" to Rendered(20, 14639, 0x4D4A8444L, 0xC2557834L, 0xAAAA1F0CL, true, true, true),
        "dk" to Rendered(40, 5399, 0xD2011B6EL, 0x84E6AFC6L, 0x65FD44DFL, true, true, true),
        "dx" to Rendered(40, 32600, 0x48B2C495L, 0xC2557834L, 0xA731820BL, true, true, true),
        "mc" to Rendered(30, 24534, 0x6FE90A9DL, 0x1C2E2828L, 0x167D50EDL, true, true, true),
        "vc" to Rendered(40, 32831, 0x8E13D962L, 0x1C2E2828L, 0x46942F7FL, true, true, true),
        "vt" to Rendered(60, 42583, 0x02452791L, 0x5EF0FA93L, 0x96CE60A3L, true, true, true),
        "ro" to Rendered(80, 58661, 0xB79CD0E7L, 0xB2AE3B40L, 0x953086BAL, true, true, true),
        "te" to Rendered(40, 15028, 0x50E7B0D6L, 0xF8F1A920L, 0x52C9A537L, true, true, true),
        "tr" to Rendered(2, 818, 0x88567744L, 0x66BDA2F4L, 0x721DCDC0L, false, false, false),
        "tq" to Rendered(3, 1080, 0x463AF68BL, 0x2DB2F845L, 0xFC937E8CL, false, false, false),
        "tx" to Rendered(3, 1523, 0x4423709FL, 0xE77C853AL, 0x358F1573L, false, false, false),
        "sb" to Rendered(60, 20171, 0xE310732FL, 0x122B8E43L, 0x6D35548DL, true, true, true),
        "sm" to Rendered(60, 21411, 0x3A4EAD0BL, 0x122B8E43L, 0x1E98B44FL, true, true, true),
        "sx" to Rendered(60, 21793, 0x0327F3CEL, 0x85568D06L, 0x064E0FC1L, true, true, true),
        "hc" to Rendered(60, 20454, 0xD7FF8324L, 0xBBCAEFB7L, 0xB779D5BBL, true, true, true),
        "hy" to Rendered(60, 49101, 0x49326E02L, 0xEFE1CCE9L, 0x856B7576L, true, true, true),
        "hqr" to Rendered(40, 4556, 0xD1052A7CL, 0xBCB457B8L, 0x45387F63L, true, true, true),
        "h3r" to Rendered(2, 876, 0x277A24CDL, 0x898A96B3L, 0x3CE9C9ACL, false, false, false),
        "csr" to Rendered(40, 29805, 0xE9850648L, 0x4A98C540L, 0x868C9131L, true, true, true),
        "srr" to Rendered(60, 43902, 0xAABFBD14L, 0x77A2FE6AL, 0xE5D277A3L, true, true, true),
        "hlr" to Rendered(40, 29324, 0x6B92895EL, 0x4CD86D97L, 0xD51CB961L, true, true, true),
        "kp" to Rendered(30, 25309, 0x5FBDABEBL, 0x9466D39AL, 0x98D5E996L, true, true, true),
        "ks" to Rendered(20, 14915, 0x016B1178L, 0x9466D39AL, 0x98D5E996L, true, true, true),
        "ka" to Rendered(20, 16612, 0x8D166F4DL, 0x9466D39AL, 0x98D5E996L, true, true, true),
        "pd" to Rendered(30, 20071, 0x36B3A383L, 0x7F58E925L, 0x7F47A123L, true, true, true),
        "gs" to Rendered(30, 19756, 0x32A606A1L, 0x709AC902L, 0x6AC3B0A6L, true, true, true),
        "gp" to Rendered(40, 27053, 0x6D52BEE7L, 0x36D5B9B5L, 0xA69D1BABL, true, true, true),
        "gm" to Rendered(40, 26417, 0x244133A4L, 0xC4D4627AL, 0x89CCF351L, true, true, true),
        "gl" to Rendered(30, 21548, 0xBA8B3BB3L, 0x22C2FFEAL, 0xDF18AE32L, true, true, true),
        "gx" to Rendered(30, 21880, 0x6341D010L, 0x22C2FFEAL, 0xDF18AE32L, true, true, true),
        "dr" to Rendered(30, 22259, 0xB25E9FA7L, 0x591C38F5L, 0xA7832518L, true, true, true),
        "de" to Rendered(30, 22254, 0x6611018BL, 0x591C38F5L, 0xA7832518L, true, true, true),
        "da" to Rendered(30, 22402, 0x88D5F53AL, 0x9E6078E6L, 0x2E7E2903L, true, true, true),
        "kl" to Rendered(30, 22673, 0x02792DA6L, 0xF0607759L, 0x2E7DA77FL, true, true, true),
        "bd" to Rendered(30, 19969, 0xB4EF75A1L, 0x57530BAAL, 0x34147456L, true, true, true),
        "bu" to Rendered(30, 19983, 0x0CBD5A7FL, 0x38EC1FFDL, 0xFE406146L, true, true, true),
        "bn" to Rendered(4, 2749, 0x52D760CAL, 0xC64295EBL, 0xC64295EBL, true, true, true),
        "bnr" to Rendered(30, 21803, 0x683D0244L, 0x6A09D22CL, 0x7929D990L, true, true, true),
        "sf" to Rendered(30, 21823, 0x90FEA565L, 0x7040B450L, 0xE1BF0E90L, true, true, true),
        "cm" to Rendered(20, 15032, 0xE681BCEFL, 0x7040B450L, 0xE1BF0E90L, true, true, true),
        "ap" to Rendered(20, 15098, 0x758BB874L, 0x7040B450L, 0xE1BF0E90L, true, true, true),
        "wr" to Rendered(30, 21954, 0x43BF7473L, 0x297418EFL, 0x9E733E56L, true, true, true),
        "dl" to Rendered(3, 2173, 0xD9422CF7L, 0x17262DA5L, 0x73F26CFEL, false, false, false),
        "rg" to Rendered(3, 1729, 0x29545C8AL, 0xBA6FB236L, 0xDEBBF36DL, false, false, false),
        "rk" to Rendered(3, 8086, 0xE69E3D03L, 0x15E20547L, 0x7136441CL, false, false, false),
        "lv" to Rendered(30, 24519, 0x40B1B0B5L, 0x3B560566L, 0xE95B7310L, true, true, true),
        "lw" to Rendered(30, 24582, 0xD9C7A610L, 0x32879F4AL, 0x2254681EL, true, true, true),
        "lx" to Rendered(30, 24449, 0xB4D426FEL, 0x3B323637L, 0xE93F4041L, true, true, true),
        "ly" to Rendered(30, 24519, 0x3C9BE70CL, 0x32E3AC1BL, 0x22305B4FL, true, true, true),
        "go" to Rendered(30, 493, 0xECF84B89L, 0xFC293EB1L, 0xFC293EB1L, false, false, false),
        "gk" to Rendered(102, 5759, 0xAC6B5358L, 0x24E7468DL, 0xFC293EB1L, false, false, false),
        "ld" to Rendered(7, 2357, 0xA8B898F7L, 0x4DB8C28DL, 0xA8CB7450L, false, false, false),
        "sw" to Rendered(40, 27193, 0xFB28003FL, 0x791BF0B4L, 0x9B7E11BCL, true, true, true),
    )

    private fun regions(m: Machine): List<Triple<Int, Int, Int>> {
        val es = m.g(0x0BD6)
        val tbl = m.g(0x0BD2)
        val out = mutableListOf(Triple(es, tbl, 6 * m.g(0x0BDA)))
        var bx = 0
        for (i in 0 until 64) {
            val bp = m.rw(es, tbl + bx)
            val (aofs, aseg) = m.far(es, bp - 0x14)
            out += Triple(es, bp - 0x34, 0x34)
            out += Triple(es, bp, m.rw(aseg, aofs + 0x14))
            bx = m.rw(es, tbl + bx + 4)
            if (bx == 0) break
        }
        return out
    }

    private fun crc(m: ByteArray, from: Int, size: Int) = CRC32().apply { update(m, from, size) }.value

    @Test
    fun `pairs with the renderer match the rebuild and the live game`() =
        check(expected, pointer = false, live = Triple(68, 68, 0))

    @Test
    fun `pairs with the renderer and the pointer match the rebuild and the live game`() =
        check(expectedPresent, pointer = true, live = Triple(68, 68, 61))

    private fun check(expected: Map<String, Rendered>, pointer: Boolean, live: Triple<Int, Int, Int>) = runTest {
        val files = GameFiles.fromResourceRepository(TestResources())
        var regionsLive = 0
        var pageLive = 0
        var screenLive = 0
        for (s in VmPairs.scenarios()) {
            val want = requireNotNull(expected[s.a]) { s.a }
            val (a, b) = VmPairs.load(s)
            val machine = Machine(a.memory)
            val vm = if (pointer) {
                Scheduler.withStatements(machine, files.fresh())
            } else {
                Scheduler(machine, files.fresh()).also { vm ->
                    val stmts = ScriptStmts(vm).also { it.install() }
                    Collide(vm, stmts).install()
                    FileIo(vm).install()
                    Renderer(vm).install()
                }
            }
            vm.interrupts = true
            vm.trace = { op -> if (op in PALETTE_OPS || vm.stmts[op] == null) throw Stop() }
            try {
                repeat(s.frames) { vm.frame() }
            } catch (e: Stop) {
                // a palette statement: 4.4
            }
            val r = CRC32()
            for ((seg, ofs, size) in regions(machine)) r.update(machine.m, (seg shl 4) + ofs, size)
            val hex = { v: Long -> v.toString(16) }
            assertEquals(
                "frames=${want.frames} statements=${want.statements} regions=${hex(want.regions)} " +
                    "page=${hex(want.page)} screen=${hex(want.screen)}",
                "frames=${vm.frames} statements=${vm.statements} regions=${hex(r.value)} " +
                    "page=${hex(crc(machine.m, 0xE0000, 64000))} screen=${hex(crc(machine.m, 0xA0000, 64000))}",
                "pair ${s.a} -> ${s.b}",
            )
            if (want.regionsB) {
                regionsLive++
                val diffs = regions(machine).sumOf { (seg, ofs, size) ->
                    val p = (seg shl 4) + ofs
                    (0 until size).count { machine.m[p + it] != b.memory[p + it] }
                }
                assertEquals(0, diffs, "pair ${s.a}: region bytes differing from the live game")
            }
            if (want.pageB) {
                pageLive++
                val diffs = (0 until 64000).count { machine.m[0xE0000 + it] != b.memory[0xE0000 + it] }
                assertEquals(0, diffs, "pair ${s.a}: page pixels differing from the live game")
            }
            if (want.screenB) {
                screenLive++
                val diffs = (0 until 64000).count { machine.m[0xA0000 + it] != b.memory[0xA0000 + it] }
                assertEquals(0, diffs, "pair ${s.a}: screen pixels differing from the live game")
            }
        }
        assertEquals(live, Triple(regionsLive, pageLive, screenLive))
    }

    private companion object {
        val PALETTE_OPS = setOf(0x68, 0x69, 0xBC, 0xBE, 0xD7, 0xE6)
    }
}
