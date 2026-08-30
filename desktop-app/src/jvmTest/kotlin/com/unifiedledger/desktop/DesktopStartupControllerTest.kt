package com.unifiedledger.desktop

import com.unifiedledger.ui.P503StartupState
import java.nio.file.Files
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Desktop composition-root fail-closed evidence (spec sections 8/13.2 gate 7). A driver
 * open failure reaches StartupError with no business graph exposed; retry rebuilds from the
 * driver/graph start and reaches Ready; a repeated failure stays fail-closed. Exit is the
 * window close action wired through the shared error screen (the reducer covers the Exit
 * event transition).
 */
class DesktopStartupControllerTest {
    @Test
    fun injectedOpenFailureGoesToErrorThenRetryReachesReady() {
        val directory = Files.createTempDirectory("p5-03-startup-")
        val url = "jdbc:sqlite:${directory.resolve("ledger.db").absolutePathString()}"
        var shouldFail = true
        val controller =
            DesktopStartupController(
                openDatabase = {
                    if (shouldFail) {
                        throw IllegalStateException("injected driver open failure")
                    }
                    openDesktopLedger(url)
                },
            )

        controller.start()
        assertEquals(P503StartupState.StartupError, controller.state)
        assertNull(controller.facade)

        shouldFail = false
        controller.start()
        assertEquals(P503StartupState.Ready, controller.state)
        assertNotNull(controller.facade)
    }

    @Test
    fun repeatedInjectedFailureStaysFailClosedWithNoGraphExposed() {
        val controller =
            DesktopStartupController(
                openDatabase = {
                    throw IllegalStateException("injected driver open failure")
                },
            )

        controller.start()
        controller.start()

        assertEquals(P503StartupState.StartupError, controller.state)
        assertNull(controller.facade)
    }
}
