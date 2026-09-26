package com.unifiedledger.android;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * P7-06 06.C (D-179) test-only ContentProvider: serves a FIXED synthetic payload from
 * {@code openFile} as a real {@link ParcelFileDescriptor} (a pipe prefilled with those bytes, write
 * end closed), so {@link AndroidRestoreSourcePortInstrumentedTest} can drive the restore source
 * port through the real ContentResolver/SAF-shaped file-access path on device. It holds no product
 * data, exists only inside the androidTest APK, and is registered for this test APK in
 * {@code src/androidTest/AndroidManifest.xml}.
 *
 * <p>Pure Java on purpose: the manifest registers this provider for the test package, so when the
 * instrumentation target's ContentResolver resolves it, the system starts the test package's own
 * process and loads this class from the test APK alone — a classloading path where the Kotlin
 * runtime is NOT visible (a Kotlin fixture crashed there with {@code NoClassDefFoundError:
 * kotlin.jvm.internal.Intrinsics}). The instrumented test classes themselves are unaffected: they
 * run in the instrumentation process whose classpath includes the app APK (which bundles the
 * Kotlin runtime). This class is a fixture, NOT code under test; the test asserts the delivered
 * bytes against its own Kotlin copy of the payload, so a divergent payload fails the run.
 */
public final class FixedPayloadRestoreSourceProvider extends ContentProvider {

    /**
     * The synthetic bytes served from {@code openFile} (fixed, anonymous, no product data). Must
     * stay byte-identical to {@code RESTORE_SOURCE_FIXTURE_PAYLOAD} in
     * {@link AndroidRestoreSourcePortInstrumentedTest}, which the test asserts against.
     */
    private static final byte[] FIXTURE_PAYLOAD =
            "ulbk-restore-source-fixture-0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

    /**
     * The backup-container MIME the production port filters on, mirrored as a literal so this
     * fixture keeps ZERO Kotlin references (the production constant is a Kotlin const).
     */
    private static final String BACKUP_CONTAINER_MIME = "application/octet-stream";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(
            Uri uri,
            String mode) {
        final ParcelFileDescriptor[] pipe;
        try {
            pipe = ParcelFileDescriptor.createPipe();
        } catch (IOException failure) {
            throw new IllegalStateException("the fixture pipe must be creatable", failure);
        }
        try (OutputStream writeEnd = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
            writeEnd.write(FIXTURE_PAYLOAD);
        } catch (IOException failure) {
            throw new IllegalStateException("the fixture payload must be writable", failure);
        }
        return pipe[0];
    }

    @Override
    public Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return BACKUP_CONTAINER_MIME;
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
}
