package com.molagpt.app.core.network

import com.molagpt.app.core.model.WebSearchProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchProviderTest {
    @Test
    fun fromIdMapsKnownProviders() {
        assertEquals(WebSearchProvider.DUCKDUCKGO, WebSearchProvider.fromId("duckduckgo"))
        assertEquals(WebSearchProvider.TAVILY, WebSearchProvider.fromId("tavily"))
        assertEquals(WebSearchProvider.EXA, WebSearchProvider.fromId("exa"))
    }

    @Test
    fun fromIdFallsBackToDuckDuckGoForUnknownOrNull() {
        assertEquals(WebSearchProvider.DUCKDUCKGO, WebSearchProvider.fromId(null))
        assertEquals(WebSearchProvider.DUCKDUCKGO, WebSearchProvider.fromId("bing-unknown"))
    }

    @Test
    fun keyRequirementMatchesProvider() {
        assertFalse(WebSearchProvider.DUCKDUCKGO.needsKey)
        assertTrue(WebSearchProvider.TAVILY.needsKey)
        assertTrue(WebSearchProvider.EXA.needsKey)
    }
}
