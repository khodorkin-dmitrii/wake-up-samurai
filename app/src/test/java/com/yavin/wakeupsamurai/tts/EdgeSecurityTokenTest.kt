package com.yavin.wakeupsamurai.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class EdgeSecurityTokenTest {
    @Test
    fun `matches edge tts v2 upstream token vector`() {
        assertEquals(
            "492D7694A183A45E-56E821F95D4579C5B7C7E33DC7303FF379EC36D510213555BBBB9EB17EF15876",
            EdgeSecurityToken.generate(nowSeconds = 1_791_888_000L),
        )
    }

    @Test
    fun `matches pinned upstream at five minute boundaries`() {
        // Values generated independently by rany2/edge-tts 7.3.0,
        // commit 6d3fff402a77dd4d43cccf074d042252770e41c2.
        val expected = mapOf(
            1_791_887_999L to "DAAEAD2FD5438630-897933E9C951AADAA0A792FFD23FBB12339BBBBA71EA7BF84FFA0C777C3C9550",
            1_791_888_000L to "492D7694A183A45E-56E821F95D4579C5B7C7E33DC7303FF379EC36D510213555BBBB9EB17EF15876",
            1_791_888_299L to "492D7694A183A45E-56E821F95D4579C5B7C7E33DC7303FF379EC36D510213555BBBB9EB17EF15876",
            1_791_888_300L to "6D26B5F5B70269AD-280E82E1BBE24E4E8866E3502EB6277B9436F61ABDC1C224518A038BBD7BBDAD",
            1_791_888_600L to "89BE994E396B2546-D62B97817C26F3B6C4530572377BC408EE7E83F934B2332B705F38CCA82D6DBE",
        )
        expected.forEach { (timestamp, token) -> assertEquals(token, EdgeSecurityToken.generate(timestamp)) }
    }

    @Test
    fun `token format and version prefix stay paired`() {
        val token = EdgeSecurityToken.generate(1_791_888_000L)
        assertEquals("2-", EdgeSecurityToken.SEC_MS_GEC_VERSION.take(2))
        assertEquals(81, token.length)
        assertEquals('-', token[16])
    }
}
