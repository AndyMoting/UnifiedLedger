package com.unifiedledger.ui

/** D-203 desktop actual: startup timing trace is an Android diagnostic surface; a no-op here. */
internal actual object StartupTrace {
    actual fun emit(event: String) {
        // Intentionally empty (D-203): no desktop startup trace surface.
    }

    /** D-269 item 7: the desktop actual drops the trace, matching [emit]'s no-op surface. */
    actual fun emitFailure(
        event: String,
        failure: Throwable,
    ) {
        // Intentionally empty (D-203): no desktop trace surface.
    }
}
