package com.unifiedledger.data

/** D-203 desktop actual: startup timing trace is an Android diagnostic surface; a no-op here. */
internal actual object StartupTrace {
    actual fun emit(event: String) {
        // Intentionally empty (D-203): no desktop startup trace surface.
    }
}
