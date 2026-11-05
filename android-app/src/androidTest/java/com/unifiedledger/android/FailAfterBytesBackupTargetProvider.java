package com.unifiedledger.android;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * P706-A10 second half (D-239 item 4, "SAF 中途写失败") test-only ContentProvider: the target stream
 * of a backup export ACCEPTS bytes and then stops accepting them, so the write fails MID-STREAM
 * rather than at open.
 *
 * <p>Why a provider instead of a real folder: the product writes the container through
 * {@code ContentResolver.openOutputStream}, i.e. the SAF-shaped platform path. The only way to make
 * that path fail after N bytes — with the failure coming from the transport, not from the app — is
 * for the document's provider to stop draining. The rig drives the REAL
 * {@code ContentResolver.openOutputStream} against this provider, so what is exercised is the real
 * platform path; nothing in the product is stubbed.
 *
 * <p>Mechanism: {@link #openFile} returns the WRITE end of a pipe and starts a drainer on the read
 * end. The drainer discards exactly {@link #FAIL_AFTER_BYTES} bytes and then closes the read end
 * (try-with-resources). Every later write by the product raises {@code EPIPE}, an
 * {@link IOException} raised by the transport — the mid-write failure this vector is about.
 *
 * <p>Pure Java on purpose: the manifest registers this provider for the TEST package, so the system
 * starts that package's own process and loads this class from the test APK alone — a classloading
 * path where the Kotlin runtime is not visible (the sibling restore fixture documents the
 * {@code NoClassDefFoundError: kotlin.jvm.internal.Intrinsics} it hit there).
 *
 * <p>No product data is held, and the provider never ships in the product build: it lives only in
 * {@code src/androidTest}.
 */
public final class FailAfterBytesBackupTargetProvider extends ContentProvider {

    /**
     * The byte count the drainer accepts before it closes the read end. Must stay identical to
     * {@code FAIL_AFTER_BYTES} in {@link BackupExportSafMidWriteInstrumentedTest}, which asserts the
     * recorded count against it.
     */
    public static final long FAIL_AFTER_BYTES = 1024L * 1024L;

    /** The {@link #call} method the test uses to read the drained count (cross-process proof). */
    public static final String CALL_DRAINED_BYTES = "drainedBytes";

    /** Drained bytes for the most recent stream; {@code -1} until the first {@link #openFile}. */
    private static final AtomicLong DRAINED = new AtomicLong(-1L);

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
            throw new IllegalStateException("the failing-target pipe must be creatable", failure);
        }
        DRAINED.set(0L);
        final ParcelFileDescriptor readEnd = pipe[0];
        final Thread drainer =
                new Thread(
                        () -> {
                            long seen = 0L;
                            try (ParcelFileDescriptor.AutoCloseInputStream input =
                                    new ParcelFileDescriptor.AutoCloseInputStream(readEnd)) {
                                final byte[] buffer = new byte[16 * 1024];
                                while (seen < FAIL_AFTER_BYTES) {
                                    final int want = (int) Math.min((long) buffer.length, FAIL_AFTER_BYTES - seen);
                                    final int read = input.read(buffer, 0, want);
                                    if (read < 0) {
                                        break;
                                    }
                                    seen += read;
                                    DRAINED.set(seen);
                                }
                            } catch (IOException ignored) {
                                // The writer went away first; the recorded count is what the test reads.
                            }
                            // Leaving the try-with-resources closed the read end: every later write
                            // by the product fails with EPIPE, i.e. AFTER bytes were accepted.
                        },
                        "ul-a10-fail-after-bytes");
        drainer.setDaemon(true);
        drainer.start();
        return pipe[1];
    }

    @Override
    public Bundle call(
            String method,
            String arg,
            Bundle extras) {
        if (CALL_DRAINED_BYTES.equals(method)) {
            final Bundle out = new Bundle();
            out.putLong(CALL_DRAINED_BYTES, DRAINED.get());
            return out;
        }
        return super.call(method, arg, extras);
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
        return null;
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
