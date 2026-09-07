package com.streamdek.tv.nativeapp.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamDekTvAppPolicyTest {
    private val topLevelRoutes = setOf("home", "search", "live", "library", "profile")

    @Test
    fun `back on Home exits without popping the navigation root`() {
        assertEquals(
            AppBackAction.Exit,
            appBackAction("home", homeRoute = "home", topLevelRoutes = topLevelRoutes),
        )
    }

    @Test
    fun `back from another top level destination returns Home`() {
        assertEquals(
            AppBackAction.ReturnHome,
            appBackAction("library", homeRoute = "home", topLevelRoutes = topLevelRoutes),
        )
    }

    @Test
    fun `back from detail pops only the nested destination`() {
        assertEquals(
            AppBackAction.PopNested,
            appBackAction("detail/{type}/{id}", homeRoute = "home", topLevelRoutes = topLevelRoutes),
        )
    }

    @Test
    fun `an empty host is restored to Home`() {
        assertEquals(
            AppBackAction.RestoreHome,
            appBackAction(null, homeRoute = "home", topLevelRoutes = topLevelRoutes),
        )
    }
}
