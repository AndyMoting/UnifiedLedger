package com.unifiedledger.android;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.CursorWrapper;
import android.database.MatrixCursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * P7-06 06.D (D-182) test-only ContentProvider: the {@link OpenableColumns#SIZE} shapes
 * {@code queryDocumentSize} must distinguish, served from {@code query} as a real cursor.
 *
 * <p>The paths:
 *
 * <ul>
 *   <li>{@code /size} — a cursor carrying the requested SIZE column with a non-null value;
 *   <li>{@code /null} — the SIZE column present with a SQL NULL value;
 *   <li>{@code /no-size-column} — a cursor whose columns do NOT include SIZE (a provider that
 *       ignores the projection);
 *   <li>{@code /empty} — a SIZE column with ZERO rows;
 *   <li>{@code /throw} — {@code query} throws, standing in for an unresponsive/crashing provider.
 * </ul>
 *
 * <p>Pure Java on purpose, exactly like {@link FixedPayloadRestoreSourceProvider}: this class
 * doubles as (a) the manifest-registered provider the instrumentation target resolves through its
 * REAL {@code ContentResolver} (a separate process, so the delivered cursor is the only
 * observable) and (b) an in-process instance wrapped by
 * {@code ContentResolver.wrap(ContentProvider)} in the same suite, where the recorded projection
 * and the close count below ARE observable. It holds no product data and never ships in the
 * product build.
 */
public final class OpenableColumnsSizeFixtureProvider extends ContentProvider {

    /** The test provider's authority; declared in {@code src/androidTest/AndroidManifest.xml}. */
    public static final String AUTHORITY = "com.unifiedledger.android.p706d.documentsize";

    /** The non-null size {@code /size} reports; the test asserts this exact value. */
    public static final long REPORTED_SIZE = 4096L;

    private static final AtomicInteger CLOSE_COUNT = new AtomicInteger();
    private static final AtomicReference<String[]> LAST_PROJECTION = new AtomicReference<>();

    /** Per-test reset of the in-process observations. */
    public static void resetObservations() {
        CLOSE_COUNT.set(0);
        LAST_PROJECTION.set(null);
    }

    /** How many cursors this provider handed out have been closed by the caller. */
    public static int closeCount() {
        return CLOSE_COUNT.get();
    }

    /** The projection of the most recent query, or null when no query ran yet. */
    public static String[] lastProjection() {
        return LAST_PROJECTION.get();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder) {
        LAST_PROJECTION.set(projection);
        final String path = uri.getLastPathSegment();
        if ("throw".equals(path)) {
            throw new IllegalStateException("p706d fixture provider: deliberate query failure");
        }
        final String[] columns =
                "no-size-column".equals(path)
                        ? new String[] {"a_different_column"}
                        : new String[] {OpenableColumns.SIZE};
        final MatrixCursor cursor = new MatrixCursor(columns);
        if ("size".equals(path)) {
            cursor.addRow(new Object[] {REPORTED_SIZE});
        } else if ("null".equals(path)) {
            cursor.addRow(new Object[] {null});
        }
        // `/empty` and `/no-size-column` keep the cursor row-less / differently shaped.
        return new CloseRecordingCursor(cursor);
    }

    @Override
    public String getType(Uri uri) {
        return "application/octet-stream";
    }

    @Override
    public Uri insert(
            Uri uri,
            ContentValues values) {
        return null;
    }

    @Override
    public int delete(
            Uri uri,
            String selection,
            String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(
            Uri uri,
            ContentValues values,
            String selection,
            String[] selectionArgs) {
        return 0;
    }

    /**
     * Counts closes so the in-process suite can pin that {@code queryDocumentSize}'s {@code use}
     * block actually closes the cursor rather than leaking it (a leaked cursor keeps a provider
     * connection alive for the process lifetime).
     */
    private static final class CloseRecordingCursor extends CursorWrapper {
        private boolean closed;

        CloseRecordingCursor(Cursor cursor) {
            super(cursor);
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                CLOSE_COUNT.incrementAndGet();
            }
            super.close();
        }
    }
}
