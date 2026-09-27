package com.unifiedledger.android

import android.content.ContentResolver
import android.content.pm.ProviderInfo
import android.net.Uri
import android.provider.OpenableColumns
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P7-06 06.D (D-182; spec section 5.6, ruling F — the D-180 5(d) `sizeOf` wiring recheck): on-device
 * verification for [queryDocumentSize], the SINGLE `ContentResolver` size query behind
 * `AndroidBackupSourcePort`'s production `sizeOf`. Before this file the function had NO test at any
 * level: the 06.D F-10 tests covered only the port's INJECTED `sizeOf` closure
 * (`AndroidBackupSourcePortTest`), never the real query implementation.
 *
 * Every case runs through the REAL `DocumentSize` query against a real `ContentResolver`:
 *
 * - the fixture provider ([OpenableColumnsSizeFixtureProvider], registered for this androidTest APK
 *   in `src/androidTest/AndroidManifest.xml`, pure Java for the same cross-process classloading
 *   reason as `FixedPayloadRestoreSourceProvider`) reports a non-null SIZE for `/size`;
 * - `/null` returns a cursor whose SIZE column is SQL NULL — `queryDocumentSize` must report null so
 *   the preflight falls back to the counted stream (spec section 5.6);
 * - `/no-size-column` returns a cursor WITHOUT the SIZE column (a provider that ignores the
 *   projection) — the `sizeColumn < 0` branch, null;
 * - `/empty` returns a row-less SIZE cursor — the `!moveToFirst()` branch, null;
 * - `/throw` makes the provider throw — `runCatching` must absorb it and report null, never
 *   propagate (an unresponsive provider must not fail the restore before the count fallback);
 * - an authority no provider serves reports null without propagating (the `query` returns null, or
 *   the framework throws; both are absorbed).
 *
 * The last two cases pin `queryDocumentSize`'s `use`-block: a cursor it hands back must be CLOSED
 * (a leaked cursor keeps the provider connection alive). That is only observable in-process, so
 * those cases wrap the same fixture with `ContentResolver.wrap(provider)`, whose `query` dispatches
 * straight to the provider's own `ContentInterface.query` — still the REAL resolver code path, just
 * without the cross-process hop.
 *
 * Deliberately NO Robolectric: this file only runs as `connectedDebugAndroidTest`. The production
 * ledger is never touched — this suite performs no file I/O at all.
 */
@RunWith(AndroidJUnit4::class)
class AndroidRestoreDocumentSizeInstrumentedTest {
    @Test
    fun aProviderThatReportsASizeIsReadThroughTheRealContentResolver() {
        val size = queryDocumentSize(resolver(), fixtureUri("size"))

        // `-1` distinguishes "null came back" from the reported value in the failure message.
        assertEquals(OpenableColumnsSizeFixtureProvider.REPORTED_SIZE, size ?: -1L)
    }

    @Test
    fun aNullSizeValueReportsNullSoThePreflightFallsBackToTheCountedStream() {
        assertNull(queryDocumentSize(resolver(), fixtureUri("null")))
    }

    @Test
    fun aCursorWithoutTheSizeColumnReportsNull() {
        // The provider ignores the projection and answers with an unrelated column, so
        // getColumnIndex(SIZE) is negative: the missing-column branch, never a crash.
        assertNull(queryDocumentSize(resolver(), fixtureUri("no-size-column")))
    }

    @Test
    fun aRowLessCursorReportsNull() {
        assertNull(queryDocumentSize(resolver(), fixtureUri("empty")))
    }

    @Test
    fun aThrowingProviderReportsNullInsteadOfPropagating() {
        // A crashing/unresponsive provider must not fail the restore: the size is merely
        // unavailable and the preflight reads the counted stream instead.
        assertNull(queryDocumentSize(resolver(), fixtureUri("throw")))
    }

    @Test
    fun anAuthorityNoProviderServesReportsNullInsteadOfPropagating() {
        val unserved = Uri.parse("content://com.unifiedledger.android.p706d.no-such-authority/none")

        // The resolver either answers null or throws for an unserved authority; `queryDocumentSize`
        // must absorb both and report null, so a stale/unavailable provider degrades to the counted
        // stream instead of failing the restore.
        assertNull(queryDocumentSize(resolver(), unserved))
    }

    @Test
    fun theQueryAsksTheProviderForTheOpenableColumnsSizeProjection() {
        val provider = attachedFixtureProvider()
        OpenableColumnsSizeFixtureProvider.resetObservations()

        // A reported size, so the query is a complete success path and the projection is the
        // only thing observed.
        val reported = queryDocumentSize(ContentResolver.wrap(provider), fixtureUri("size"))
        assertEquals(OpenableColumnsSizeFixtureProvider.REPORTED_SIZE, reported ?: -1L)

        assertArrayEquals(
            "the size query must request exactly the OpenableColumns.SIZE column",
            arrayOf(OpenableColumns.SIZE),
            OpenableColumnsSizeFixtureProvider.lastProjection(),
        )
    }

    @Test
    fun theCursorHandedBackByTheQueryIsClosed() {
        val provider = attachedFixtureProvider()
        OpenableColumnsSizeFixtureProvider.resetObservations()

        queryDocumentSize(ContentResolver.wrap(provider), fixtureUri("size"))

        // LOAD-BEARING: dropping the `use` block (or replacing it with a plain call) leaks the
        // cursor and turns this assertion red.
        assertEquals("queryDocumentSize must close the cursor it obtained", 1, OpenableColumnsSizeFixtureProvider.closeCount())
    }

    /**
     * The production-shaped resolver: the instrumentation target's REAL `ContentResolver`, exactly
     * the instance `App.kt` passes as `context.contentResolver`.
     */
    private fun resolver(): ContentResolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver

    private fun fixtureUri(path: String): Uri = Uri.parse("content://${OpenableColumnsSizeFixtureProvider.AUTHORITY}/$path")

    /**
     * One fixture instance attached to a real `ProviderInfo` so the framework's own
     * `ContentProvider.query(uri, projection, queryArgs, signal)` dispatch (including its
     * `validateIncomingUri` context use) runs before the fixture's own `query`. `attachInfo` is the
     * only public attach entry, and the same-app check grants this in-process caller access without
     * declaring a read permission.
     */
    private fun attachedFixtureProvider(): OpenableColumnsSizeFixtureProvider {
        val info = ProviderInfo().apply { authority = OpenableColumnsSizeFixtureProvider.AUTHORITY }
        val provider = OpenableColumnsSizeFixtureProvider()
        provider.attachInfo(InstrumentationRegistry.getInstrumentation().targetContext, info)
        return provider
    }
}
