package com.unifiedledger.ui

import com.unifiedledger.application.backup.BACKUP_FIXED_HEADER_LENGTH
import com.unifiedledger.application.backup.BACKUP_IV_LENGTH
import com.unifiedledger.application.backup.BACKUP_MAX_CONTAINER_BYTES
import com.unifiedledger.application.backup.BACKUP_SALT_LENGTH
import com.unifiedledger.application.backup.BACKUP_STREAM_CHUNK_BYTES
import com.unifiedledger.application.backup.BACKUP_TAG_LENGTH
import com.unifiedledger.application.backup.BackupCryptoPrimitives
import com.unifiedledger.application.backup.BackupGcmDecryptor
import com.unifiedledger.application.backup.BackupGcmEncryptor
import com.unifiedledger.application.backup.BackupPreflightRejection
import com.unifiedledger.application.backup.BackupSha256Digest
import com.unifiedledger.application.backup.backupContainerHeader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * P7-06 06.C (D-179; spec `2026-09-25-p7-06-restore-preflight-design.md` sections 3, 6 and 7): the
 * shared restore-preflight WIRING, not just the pure functions. These tests drive the use case
 * through the injected source/isolation/file-system/crypto ports and assert the load-bearing
 * branches end to end:
 *
 * - the operation lease is held for the whole preflight and released in `finally` — including when
 *   `newToken()` throws before the work begins (P2-2);
 * - the container bound is enforced before reading (reported size) and while counting (fallback);
 * - the header/payload `user_version` MISMATCH is a class-2 rejection (D-179's frozen ruling);
 * - an unsupported older version is rejected before any migration copy is made;
 * - a supported older version migrates the ISOLATED copy and leaves the snapshot untouched;
 * - a failed migration / validation is a typed rejection;
 * - a hash-read failure on the migrated copy is a typed read failure, not a domain verdict (N5);
 * - a tag failure is the uniform `P706_CONTAINER_AUTHENTICATION_FAILED` and its unauthenticated
 *   staging plaintext is deleted (P2-4);
 * - the class-3 identity check rejects an empty payload and any foreign id in a secondary owner,
 *   and never fabricates a target match (P1-1);
 * - success binds the captured generation, the target ledger and the authenticated digest into the
 *   token, and KEEPS the staging artifacts;
 * - every rejection path deletes its staging artifacts and touches no generation/pointer path
 *   ([aRejectionNeverTouchesTheGenerationSetOrTheActivePointer]).
 *
 * The fake crypto is a reversible XOR so a container assembled by the real write-side header helper
 * round-trips through the read path; the digest fake returns a digest derived from the plaintext
 * bytes so the `payload_sha256` check can be made to pass or fail deterministically. The XOR stand-in
 * is NOT a real cipher: the real JCE tag verification is pinned separately in
 * `BackupContainerCryptoJvmTest`.
 */
class RestorePreflightUseCaseTest {
    private val hostDirectory = "/host"
    private val stagingDirectory = "/host/backup-staging"
    private val containerFile = "$stagingDirectory/restore-container-tok"
    private val snapshotFile = "$stagingDirectory/restore-snapshot-tok"
    private val migratedFile = "$stagingDirectory/restore-migrated-tok"

    private val payload = ByteArray(2048) { (it * 5 % 251).toByte() }
    private val payloadSha256 = fakeDigestOf(payload)

    /** The container the fake source serves: real header + XOR "ciphertext" + 16-byte tag. */
    private fun container(
        schemaVersion: Long = 31,
        plaintextLength: Long = payload.size.toLong(),
    ): ByteArray {
        val header = backupContainerHeader(schemaVersion, plaintextLength, payloadSha256)
        val ciphertext = ByteArray(payload.size) { payload[it].toInt().xor(0x5A).toByte() }
        return header + ByteArray(BACKUP_SALT_LENGTH) + ByteArray(BACKUP_IV_LENGTH) + ciphertext + ByteArray(BACKUP_TAG_LENGTH)
    }

    /** A reversible XOR decryptor so a container round-trips; `doFinal` verifies the tag length. */
    private class FakeCrypto : BackupCryptoPrimitives {
        override fun randomBytes(count: Int): ByteArray = ByteArray(count)

        override fun deriveKey(
            password: CharArray,
            salt: ByteArray,
            iterations: Int,
            keyLengthBits: Int,
        ): ByteArray = ByteArray(keyLengthBits / 8)

        override fun sha256Digest(): BackupSha256Digest =
            object : BackupSha256Digest {
                var buffer = ByteArray(0)

                override fun update(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ) {
                    buffer += bytes.copyOfRange(offset, offset + length)
                }

                override fun digest(): ByteArray = fakeDigestOf(buffer)
            }

        override fun gcmEncryptor(
            key: ByteArray,
            iv: ByteArray,
            aad: ByteArray,
        ): BackupGcmEncryptor =
            object : BackupGcmEncryptor {
                override fun update(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ): ByteArray = ByteArray(0)

                override fun doFinal(): ByteArray = ByteArray(0)
            }

        override fun gcmDecryptor(
            key: ByteArray,
            iv: ByteArray,
            aad: ByteArray,
        ): BackupGcmDecryptor =
            object : BackupGcmDecryptor {
                // The real GCM decryptor consumes the trailing 16 tag bytes and emits ONLY plaintext,
                // so the fake must hold back a rolling 16-byte tail and discard it at `doFinal`.
                private var tail = ByteArray(0)

                override fun update(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ): ByteArray {
                    val combined = tail + bytes.copyOfRange(offset, offset + length)
                    val plaintextLength = (combined.size - BACKUP_TAG_LENGTH).coerceAtLeast(0)
                    tail = combined.copyOfRange(plaintextLength, combined.size)
                    val out = ByteArray(plaintextLength)
                    for (index in 0 until plaintextLength) {
                        out[index] = combined[index].toInt().xor(0x5A).toByte()
                    }
                    return out
                }

                override fun doFinal(): ByteArray {
                    // The held-back tail is the authentication tag; a real decryptor verifies it here.
                    tail = ByteArray(0)
                    return ByteArray(0)
                }
            }
    }

    private class FakeSource(
        private val bytes: ByteArray,
        private val reportedSize: Long?,
        private val launchFailed: Boolean = false,
        private val cancelled: Boolean = false,
    ) : BackupSourcePort {
        var openCalls = 0
        var readCalls = 0
        var maxReadBuffer = 0

        override fun openSource(): BackupSourceOpenResult {
            openCalls += 1
            if (launchFailed) return BackupSourceOpenResult.LaunchFailed
            if (cancelled) return BackupSourceOpenResult.Cancelled
            var position = 0
            val size = reportedSize
            return BackupSourceOpenResult.Opened(
                object : BackupSourceReader {
                    override fun read(buffer: ByteArray): Int {
                        readCalls += 1
                        maxReadBuffer = maxOf(maxReadBuffer, buffer.size)
                        if (position >= bytes.size) return -1
                        val count = minOf(buffer.size, bytes.size - position)
                        bytes.copyInto(buffer, 0, position, position + count)
                        position += count
                        return count
                    }

                    override val reportedSize: Long? get() = size

                    override fun close() {}
                },
            )
        }
    }

    /** Records the isolated-database calls so the class-2/migration gate can be asserted. */
    private class FakeIsolatedDatabase(
        var userVersion: Long = 31,
        var identities: List<String> = listOf("ledger-local-test"),
        var migration: RestoreMigrationOutcome = RestoreMigrationOutcome.Migrated(1, 31),
        var validation: RestoreValidationFacts = okFacts(),
        var userVersionThrows: Boolean = false,
    ) : RestoreIsolatedDatabasePort {
        val migrateCalls = mutableListOf<Long>()
        var validateCalls = 0
        var userVersionReads = 0
        var identityReads = 0

        override fun readAuthoritativeUserVersion(snapshotPath: String): Long {
            userVersionReads += 1
            if (userVersionThrows) throw IllegalStateException("injected version read failure")
            return userVersion
        }

        override fun readObservedLedgerIdentities(snapshotPath: String): List<String> {
            identityReads += 1
            return identities
        }

        override fun migrateStrictly(
            snapshotPath: String,
            fromVersion: Long,
            supportedVersions: Set<Long>,
        ): RestoreMigrationOutcome {
            migrateCalls += fromVersion
            return migration
        }

        override fun validate(snapshotPath: String): RestoreValidationFacts {
            validateCalls += 1
            return validation
        }

        companion object {
            fun okFacts(): RestoreValidationFacts =
                RestoreValidationFacts(
                    integrityOk = true,
                    foreignKeyOk = true,
                    domainOk = true,
                    ledgerIdentityCount = 1,
                    formalTableCount = 8,
                    postingImbalanceCount = 0,
                )
        }
    }

    private fun useCase(
        fileSystem: LedgerFileSystemFake,
        owner: LedgerRuntimeOwner<Any>,
        source: BackupSourcePort,
        isolated: RestoreIsolatedDatabasePort,
        crypto: BackupCryptoPrimitives = FakeCrypto(),
    ): RestorePreflightUseCase =
        RestorePreflightUseCase(
            owner = owner,
            fileSystem = fileSystem,
            layout = ledgerStorageLayout(fileSystem, hostDirectory),
            source = source,
            isolatedDatabase = isolated,
            crypto = crypto,
            newToken = { "tok" },
        )

    private fun readyOwner(): LedgerRuntimeOwner<Any> {
        val owner = LedgerRuntimeOwner<Any>(openGeneration = { Any() }, closeGraph = {}, facadeOf = { throw AssertionError("no facade") })
        owner.startup()
        return owner
    }

    @Test
    fun aFullyValidCurrentSchemaContainerIsPreviewReadyAndBindsTheToken() {
        val fileSystem = LedgerFileSystemFake()
        val owner = readyOwner()
        val isolated = FakeIsolatedDatabase(userVersion = 31, identities = listOf("ledger-local-test"))
        val source = FakeSource(container(), reportedSize = null)
        val result = useCase(fileSystem, owner, source, isolated).preflight(request())

        val ready = assertIs<RestorePreflightResult.PreviewReady>(result)
        assertEquals(31L, ready.summary.sourceSchemaVersion)
        assertEquals(31L, ready.summary.migratedSchemaVersion)
        assertEquals("ledger-local-test", ready.summary.targetLedgerId)
        assertEquals(0, isolated.migrateCalls.size)
        assertEquals(1, isolated.validateCalls)
        // The token binds the captured generation and the target ledger, never a path.
        assertEquals(owner.activeGeneration, ready.token.generation)
        assertEquals("ledger-local-test", ready.token.targetLedgerId)
        assertEquals("tok", ready.token.handle)
        // Success KEEPS the authenticated plaintext snapshot for a later confirmation, and drops the
        // up-to-2 GiB staging container copy (P3-12): confirmation binds the plaintext digest and
        // must never re-read the external container.
        assertTrue(fileSystem.hasFile(snapshotFile))
        assertTrue(!fileSystem.hasFile(containerFile))
        // The lease is released.
        assertEquals(0, owner.inFlightLeaseCount)
    }

    @Test
    fun theHeaderPayloadUserVersionMismatchIsAClass2Rejection() {
        val fileSystem = LedgerFileSystemFake()
        val owner = readyOwner()
        // Header says 30, payload says 31: D-179's frozen MISMATCH rejection.
        val isolated = FakeIsolatedDatabase(userVersion = 31)
        val source = FakeSource(container(schemaVersion = 30), reportedSize = null)
        val result = useCase(fileSystem, owner, source, isolated).preflight(request())

        val rejected = assertIs<RestorePreflightResult.Rejected>(result)
        assertEquals(BackupPreflightRejection.P706_SCHEMA_VERSION_UNSUPPORTED, rejected.code)
        // Nothing ran beyond the authoritative read.
        assertEquals(0, isolated.migrateCalls.size)
        assertEquals(0, isolated.validateCalls)
    }

    @Test
    fun aZeroUserVersionIsAClass2Rejection() {
        val fileSystem = LedgerFileSystemFake()
        val owner = readyOwner()
        val isolated = FakeIsolatedDatabase(userVersion = 0)
        val source = FakeSource(container(schemaVersion = 0), reportedSize = null)
        val result = useCase(fileSystem, owner, source, isolated).preflight(request())

        assertEquals(BackupPreflightRejection.P706_SCHEMA_VERSION_UNSUPPORTED, assertIs<RestorePreflightResult.Rejected>(result).code)
    }

    @Test
    fun aFutureUserVersionIsAClass2Rejection() {
        val fileSystem = LedgerFileSystemFake()
        val owner = readyOwner()
        val isolated = FakeIsolatedDatabase(userVersion = 32)
        val source = FakeSource(container(schemaVersion = 32), reportedSize = null)
        val result = useCase(fileSystem, owner, source, isolated).preflight(request())

        assertEquals(BackupPreflightRejection.P706_SCHEMA_VERSION_UNSUPPORTED, assertIs<RestorePreflightResult.Rejected>(result).code)
    }

    @Test
    fun anUnsupportedOlderVersionIsRejectedBeforeAnyMigrationCopy() {
        val fileSystem = LedgerFileSystemFake()
        val owner = readyOwner()
        val isolated = FakeIsolatedDatabase(userVersion = 20)
        val source = FakeSource(container(schemaVersion = 20), reportedSize = null)
        val result = useCase(fileSystem, owner, source, isolated).preflight(request(supported = setOf(1L, 2L)))

        assertEquals(BackupPreflightRejection.P706_SCHEMA_VERSION_UNSUPPORTED, assertIs<RestorePreflightResult.Rejected>(result).code)
        assertEquals(0, isolated.migrateCalls.size)
        assertTrue(!fileSystem.hasFile(migratedFile))
    }

    @Test
    fun aSupportedOlderVersionMigratesTheIsolatedCopyAndLeavesTheSnapshotUntouched() {
        val fileSystem = LedgerFileSystemFake()
        val owner = readyOwner()
        val isolated = FakeIsolatedDatabase(userVersion = 5, migration = RestoreMigrationOutcome.Migrated(5, 31))
        val source = FakeSource(container(schemaVersion = 5), reportedSize = null)
        val result = useCase(fileSystem, owner, source, isolated).preflight(request(supported = setOf(5L)))

        val ready = assertIs<RestorePreflightResult.PreviewReady>(result)
        assertEquals(listOf(5L), isolated.migrateCalls)
        assertEquals(5L, ready.summary.sourceSchemaVersion)
        assertEquals(31L, ready.summary.migratedSchemaVersion)
        // The migration ran on a COPY; the decrypted snapshot is preserved.
        assertTrue(fileSystem.hasFile(migratedFile))
        assertTrue(fileSystem.hasFile(snapshotFile))
        // The token binds the migrated artifact digest too.
        assertTrue(ready.token.migratedArtifactSha256 != null)
    }

    @Test
    fun aFailedMigrationIsATypedRejectionAndDeletesTheStaging() {
        val fileSystem = LedgerFileSystemFake()
        val owner = readyOwner()
        val isolated = FakeIsolatedDatabase(userVersion = 5, migration = RestoreMigrationOutcome.Failed)
        val source = FakeSource(container(schemaVersion = 5), reportedSize = null)
        val result = useCase(fileSystem, owner, source, isolated).preflight(request(supported = setOf(5L)))

        assertEquals(BackupPreflightRejection.P706_MIGRATION_FAILED, assertIs<RestorePreflightResult.Rejected>(result).code)
        // Failure leaves no staging artifact behind.
        assertTrue(!fileSystem.hasFile(containerFile))
        assertTrue(!fileSystem.hasFile(snapshotFile))
        assertTrue(!fileSystem.hasFile(migratedFile))
        assertEquals(0, owner.inFlightLeaseCount)
    }

    @Test
    fun aFailedValidationIsADomainRejection() {
        val fileSystem = LedgerFileSystemFake()
        val owner = readyOwner()
        val isolated =
            FakeIsolatedDatabase(
                userVersion = 31,
                validation = RestoreValidationFacts(false, true, true, 1, 8, 0),
            )
        val source = FakeSource(container(), reportedSize = null)
        val result = useCase(fileSystem, owner, source, isolated).preflight(request())

        assertEquals(BackupPreflightRejection.P706_DOMAIN_VALIDATION_FAILED, assertIs<RestorePreflightResult.Rejected>(result).code)
    }

    @Test
    fun aCrossLedgerPayloadIsAClass3Rejection() {
        val fileSystem = LedgerFileSystemFake()
        val owner = readyOwner()
        val isolated = FakeIsolatedDatabase(userVersion = 31, identities = listOf("ledger-local-test", "other-ledger"))
        val source = FakeSource(container(), reportedSize = null)
        val result = useCase(fileSystem, owner, source, isolated).preflight(request())

        assertEquals(BackupPreflightRejection.P706_LEDGER_IDENTITY_UNSUPPORTED, assertIs<RestorePreflightResult.Rejected>(result).code)
    }

    @Test
    fun aReportedSizeAboveTheBoundIsRejectedBeforeReadingAnyByte() {
        val fileSystem = LedgerFileSystemFake()
        val owner = readyOwner()
        val isolated = FakeIsolatedDatabase()
        val source = FakeSource(container(), reportedSize = BACKUP_MAX_CONTAINER_BYTES + 1L)
        val result = useCase(fileSystem, owner, source, isolated).preflight(request())

        assertEquals(BackupPreflightRejection.P706_CONTAINER_TOO_LARGE, assertIs<RestorePreflightResult.Rejected>(result).code)
        // The exact "do not read" of container-format spec section 4.8.
        assertEquals(0, source.readCalls)
    }

    @Test
    fun theCountedFallbackRejectsAnUnderReportingProviderAndStopsReading() {
        // P2-5 fix: drive the COUNTED fallback with a small bound and a provider that reports a size
        // within the bound but streams past it. Ablating the counting check at the `total` comparison
        // in `copySourceBounded` would let this reach PreviewReady and this test would go red.
        val fileSystem = LedgerFileSystemFake()
        val owner = readyOwner()
        val isolated = FakeIsolatedDatabase()
        val bytes = container()
        val bound = (bytes.size / 2).toLong()
        // reportedSize (within the bound) is a LIE: the stream carries the whole container.
        val source = FakeSource(bytes, reportedSize = 1L)
        val result = useCase(fileSystem, owner, source, isolated).preflight(request(containerSizeBound = bound))

        assertEquals(BackupPreflightRejection.P706_CONTAINER_TOO_LARGE, assertIs<RestorePreflightResult.Rejected>(result).code)
        // The bound check fires immediately when the count crosses it, so the loop stops well before
        // it would have consumed the whole stream. Each read is at most the 64 KiB chunk, so at most
        // two reads are needed to exceed `bound` on this small payload.
        assertTrue(source.readCalls <= 2, "the counted bound must stop reading promptly, readCalls=${source.readCalls}")
        // The plaintext staging was never created (rejection happened during step 1).
        assertTrue(!fileSystem.hasFile(snapshotFile))
        assertEquals(0, isolated.validateCalls)
    }

    @Test
    fun aProviderThatReportsNoSizeIsCountedAgainstTheDefaultBoundAndProceedsWhenHonest() {
        // The reported-null path must still count (it never short-circuits on a size it lacks), and
        // an honest small stream under the frozen 2 GiB bound proceeds.
        val fileSystem = LedgerFileSystemFake()
        val owner = readyOwner()
        val isolated = FakeIsolatedDatabase()
        val source = FakeSource(container(), reportedSize = null)
        val result = useCase(fileSystem, owner, source, isolated).preflight(request())

        assertIs<RestorePreflightResult.PreviewReady>(result)
        assertTrue(source.readCalls > 0)
        // The chunk size is the frozen 64 KiB regardless of the payload size.
        assertEquals(BACKUP_STREAM_CHUNK_BYTES, source.maxReadBuffer)
    }

    @Test
    fun aTruncatedContainerIsRejectedWithTheProposedTruncatedCode() {
        val fileSystem = LedgerFileSystemFake()
        val owner = readyOwner()
        val isolated = FakeIsolatedDatabase()
        val source = FakeSource(ByteArray(BACKUP_FIXED_HEADER_LENGTH - 1), reportedSize = null)
        val result = useCase(fileSystem, owner, source, isolated).preflight(request())

        assertEquals(BackupPreflightRejection.P706_CONTAINER_TRUNCATED, assertIs<RestorePreflightResult.Rejected>(result).code)
    }

    @Test
    fun aSelfInconsistentContainerSizeIsRejectedBeforeDecryption() {
        val fileSystem = LedgerFileSystemFake()
        val owner = readyOwner()
        val isolated = FakeIsolatedDatabase()
        // A valid header that describes a larger plaintext than the container carries.
        val source = FakeSource(container(plaintextLength = payload.size.toLong() + 1), reportedSize = null)
        val result = useCase(fileSystem, owner, source, isolated).preflight(request())

        assertEquals(BackupPreflightRejection.P706_CONTAINER_SIZE_MISMATCH, assertIs<RestorePreflightResult.Rejected>(result).code)
        // P3-5 fix: assert the property directly (no plaintext staging exists) instead of the
        // step-10 `validateCalls` counter, which is not what step 2/3 controls.
        assertTrue(!fileSystem.hasFile(snapshotFile))
        assertTrue(!fileSystem.hasFile(migratedFile))
        assertEquals(0, isolated.userVersionReads)
    }

    @Test
    fun anUnknownUsableSpaceSkipsThePrecheckButAKnownShortfallRejects() {
        val fileSystem = LedgerFileSystemFake()
        val owner = readyOwner()
        val isolated = FakeIsolatedDatabase()
        fileSystem.usableSpaceBytes = 1L
        val source = FakeSource(container(), reportedSize = null)
        val result = useCase(fileSystem, owner, source, isolated).preflight(request())

        assertEquals(BackupPreflightRejection.P706_INSUFFICIENT_SPACE, assertIs<RestorePreflightResult.Rejected>(result).code)
        // P3-5 fix: the property is "no decryption happened", asserted by the absence of the
        // plaintext staging file, not by the step-10 validation counter.
        assertTrue(!fileSystem.hasFile(snapshotFile))
        assertEquals(0, isolated.userVersionReads)
    }

    @Test
    fun aFatalErrorFromTheDecryptorPropagatesInsteadOfBecomingATypedRejection() {
        // P2-1 option (a): a fatal `Error` from `doFinal` (e.g. OutOfMemoryError) must NOT be
        // converted into P706_CONTAINER_AUTHENTICATION_FAILED. The blanket catches rethrow `Error`
        // first, so it escapes the use case. Ablating that `catch (failure: Error) { throw failure }`
        // at the doFinal wrapper would turn this into Rejected(...) and this test would go red.
        val fileSystem = LedgerFileSystemFake()
        val owner = readyOwner()
        val isolated = FakeIsolatedDatabase()
        val source = FakeSource(container(), reportedSize = null)
        // `assertFailsWith<OutOfMemoryError>` is itself the assertion: a converted typed rejection
        // would fail here.
        assertFailsWith<OutOfMemoryError> {
            useCase(fileSystem, owner, source, isolated, crypto = ErrorThrowingCrypto()).preflight(request())
        }
        // The lease is still released by the outer finally even on the fatal path.
        assertEquals(0, owner.inFlightLeaseCount)
    }

    @Test
    fun aPayloadSha256MismatchAfterAuthenticationIsAnIntegrityRejection() {
        val fileSystem = LedgerFileSystemFake()
        val owner = readyOwner()
        val isolated = FakeIsolatedDatabase()
        // The header digest does not match the decrypted plaintext (the fake crypto's digest is the
        // plaintext itself, so a header digest of the wrong bytes forces the mismatch).
        val badHeader = backupContainerHeader(31, payload.size.toLong(), ByteArray(32) { 0x7F })
        val ciphertext = ByteArray(payload.size) { payload[it].toInt().xor(0x5A).toByte() }
        val bytes = badHeader + ByteArray(BACKUP_SALT_LENGTH) + ByteArray(BACKUP_IV_LENGTH) + ciphertext + ByteArray(BACKUP_TAG_LENGTH)
        val source = FakeSource(bytes, reportedSize = null)
        val result = useCase(fileSystem, owner, source, isolated).preflight(request())

        assertEquals(BackupPreflightRejection.P706_PAYLOAD_INTEGRITY_FAILED, assertIs<RestorePreflightResult.Rejected>(result).code)
    }

    @Test
    fun aCancelledSourceIsReportedAsCancelledAndCleansUp() {
        val fileSystem = LedgerFileSystemFake()
        val owner = readyOwner()
        val isolated = FakeIsolatedDatabase()
        val source = FakeSource(container(), reportedSize = null, cancelled = true)
        val result = useCase(fileSystem, owner, source, isolated).preflight(request())

        assertEquals(RestorePreflightResult.Cancelled, result)
        assertTrue(!fileSystem.hasFile(containerFile))
        assertEquals(0, owner.inFlightLeaseCount)
    }

    @Test
    fun aSourceLaunchFailureIsATypedReadRejectionNotACancel() {
        // P2-8: a picker that fails to launch must not masquerade as a user cancel.
        val fileSystem = LedgerFileSystemFake()
        val owner = readyOwner()
        val isolated = FakeIsolatedDatabase()
        val source = FakeSource(container(), reportedSize = null, launchFailed = true)
        val result = useCase(fileSystem, owner, source, isolated).preflight(request())

        assertEquals(BackupPreflightRejection.P706_SOURCE_READ_FAILED, assertIs<RestorePreflightResult.Rejected>(result).code)
    }

    @Test
    fun aCancellationSignalFiringDuringCopyIsCancelled() {
        val fileSystem = LedgerFileSystemFake()
        val owner = readyOwner()
        val isolated = FakeIsolatedDatabase()
        val source = FakeSource(container(), reportedSize = null)
        val result =
            useCase(fileSystem, owner, source, isolated).preflight(request(), BackupCancellationSignal { true })

        assertEquals(RestorePreflightResult.Cancelled, result)
        assertTrue(!fileSystem.hasFile(containerFile))
        assertEquals(0, owner.inFlightLeaseCount)
    }

    @Test
    fun aNotReadyOwnerDoesNoWorkAndReturnsRuntimeNotReady() {
        val fileSystem = LedgerFileSystemFake()
        val owner = LedgerRuntimeOwner<Any>(openGeneration = { Any() }, closeGraph = {}, facadeOf = { throw AssertionError() })
        val isolated = FakeIsolatedDatabase()
        val source = FakeSource(container(), reportedSize = null)
        val result = useCase(fileSystem, owner, source, isolated).preflight(request())

        assertEquals(RestorePreflightResult.RuntimeNotReady, result)
        assertEquals(0, source.openCalls)
        assertEquals(0, fileSystem.operationsSnapshot().size)
    }

    @Test
    fun thePreflightHoldsALeaseForItsWholeDuration() {
        val fileSystem = LedgerFileSystemFake()
        val owner = readyOwner()
        // The isolated port observes the in-flight lease count at the moment it is called: it must be
        // positive, proving the lease covers the whole preflight (spec section 7.3).
        var inFlightDuringValidate = -1
        val isolated =
            object : RestoreIsolatedDatabasePort {
                override fun readAuthoritativeUserVersion(snapshotPath: String): Long = 31

                override fun readObservedLedgerIdentities(snapshotPath: String): List<String> = listOf("ledger-local-test")

                override fun migrateStrictly(
                    snapshotPath: String,
                    fromVersion: Long,
                    supportedVersions: Set<Long>,
                ): RestoreMigrationOutcome = RestoreMigrationOutcome.Failed

                override fun validate(snapshotPath: String): RestoreValidationFacts {
                    inFlightDuringValidate = owner.inFlightLeaseCount
                    return FakeIsolatedDatabase.okFacts()
                }
            }
        val source = FakeSource(container(), reportedSize = null)
        useCase(fileSystem, owner, source, isolated).preflight(request())

        assertEquals(1, inFlightDuringValidate)
        assertEquals(0, owner.inFlightLeaseCount)
    }

    // --------------------------------------------------- P1-1 identity (class 3), never fabricated

    @Test
    fun anEmptyPayloadWithNoObservedIdentityIsAClass3Rejection() {
        // P1-1: a payload that carries NO observable ledger identity must be rejected, never treated
        // as an implicit target match. Ablating the `identities.isEmpty()` guard would let this reach
        // PreviewReady and this test would go red.
        val fileSystem = LedgerFileSystemFake()
        val owner = readyOwner()
        val isolated = FakeIsolatedDatabase(userVersion = 31, identities = emptyList())
        val source = FakeSource(container(), reportedSize = null)
        val result = useCase(fileSystem, owner, source, isolated).preflight(request())

        assertEquals(BackupPreflightRejection.P706_LEDGER_IDENTITY_UNSUPPORTED, assertIs<RestorePreflightResult.Rejected>(result).code)
        // Nothing beyond the authoritative version read and the identity read ran.
        assertEquals(0, isolated.migrateCalls.size)
        assertEquals(0, isolated.validateCalls)
    }

    @Test
    fun aForeignIdObservedInASecondaryOwnerIsAClass3Rejection() {
        // P1-1: the identity set spans the whole owner set, so a payload whose formal rows carry the
        // target id but whose catalog/import/rg owners carry another id is rejected. Ablating the
        // multi-owner observation (reading only `ledger_transaction`) would make this pass.
        val fileSystem = LedgerFileSystemFake()
        val owner = readyOwner()
        val isolated =
            FakeIsolatedDatabase(
                userVersion = 31,
                identities = listOf("ledger-local-test", "other-ledger"),
            )
        val source = FakeSource(container(), reportedSize = null)
        val result = useCase(fileSystem, owner, source, isolated).preflight(request())

        assertEquals(BackupPreflightRejection.P706_LEDGER_IDENTITY_UNSUPPORTED, assertIs<RestorePreflightResult.Rejected>(result).code)
    }

    @Test
    fun aSingleForeignIdentityIsAClass3RejectionAndIsNotRelabelledAsTheTarget() {
        // P1-1: exactly one observed id, but it is not the target — reject, and never report it as
        // the target in a summary.
        val fileSystem = LedgerFileSystemFake()
        val owner = readyOwner()
        val isolated = FakeIsolatedDatabase(userVersion = 31, identities = listOf("foreign-ledger"))
        val source = FakeSource(container(), reportedSize = null)
        val result = useCase(fileSystem, owner, source, isolated).preflight(request())

        assertEquals(BackupPreflightRejection.P706_LEDGER_IDENTITY_UNSUPPORTED, assertIs<RestorePreflightResult.Rejected>(result).code)
    }

    // --------------------------------------------------- P2-4 uniform authentication failure

    @Test
    fun aTagFailureIsTheUniformAuthenticationRejectionAndDeletesTheStagingPlaintext() {
        // P2-4: the frozen A03-code branch and its cleanup. The fake decryptor's `doFinal` throws,
        // standing in for the platform AEAD failure. Ablating the cleanup would leave the staging
        // snapshot behind; ablating the catch mapping would surface an untyped exception.
        val fileSystem = LedgerFileSystemFake()
        val owner = readyOwner()
        val isolated = FakeIsolatedDatabase()
        val bytes = container()
        val source = FakeSource(bytes, reportedSize = null)
        val result =
            useCase(fileSystem, owner, source, isolated, crypto = TagFailingCrypto())
                .preflight(request())

        assertEquals(BackupPreflightRejection.P706_CONTAINER_AUTHENTICATION_FAILED, assertIs<RestorePreflightResult.Rejected>(result).code)
        // The plaintext streamed to staging before tag verification is deleted on failure.
        assertTrue(!fileSystem.hasFile(snapshotFile), "the unauthenticated staging plaintext must be deleted")
        assertTrue(!fileSystem.hasFile(containerFile))
        assertEquals(0, owner.inFlightLeaseCount)
    }

    @Test
    fun cancellationMidDecryptionIsCancelledAndCleansUp() {
        // P2-4: the cancel path inside `decryptToStaging` (checked between 64 KiB chunks) is reachable
        // and cleans up. The signal's first evaluation (the copy loop) returns false; its second (the
        // decryption loop) returns true.
        val fileSystem = LedgerFileSystemFake()
        val owner = readyOwner()
        val isolated = FakeIsolatedDatabase()
        val source = FakeSource(container(), reportedSize = null)
        var evaluations = 0
        val result =
            useCase(fileSystem, owner, source, isolated)
                .preflight(
                    request(),
                    BackupCancellationSignal {
                        evaluations += 1
                        evaluations > 1
                    },
                )

        assertEquals(RestorePreflightResult.Cancelled, result)
        assertTrue(!fileSystem.hasFile(snapshotFile))
        assertTrue(!fileSystem.hasFile(containerFile))
        assertEquals(0, owner.inFlightLeaseCount)
    }

    // --------------------------------------------------- P2-2 lease release on early throw

    @Test
    fun aTokenSourceThatThrowsStillReleasesTheLease() {
        // P2-2: `newToken()` runs inside the try, so a throw there must not leak the lease. Ablating
        // the move (leaving `newToken()`/`layout.restore*File` outside the try) would leave the lease
        // count at 1 and block reopen/quiesce forever.
        val fileSystem = LedgerFileSystemFake()
        val owner = readyOwner()
        val isolated = FakeIsolatedDatabase()
        val source = FakeSource(container(), reportedSize = null)
        val useCase =
            RestorePreflightUseCase(
                owner = owner,
                fileSystem = fileSystem,
                layout = ledgerStorageLayout(fileSystem, hostDirectory),
                source = source,
                isolatedDatabase = isolated,
                crypto = FakeCrypto(),
                newToken = { throw IllegalStateException("token source failed") },
            )

        assertFailsWith<IllegalStateException> { useCase.preflight(request()) }
        assertEquals(0, owner.inFlightLeaseCount, "the lease must be released even when newToken throws")
    }

    // --------------------------------------------------- P2-3 typed port failures

    @Test
    fun aThrowingVersionReadIsATypedReadRejectionNotAVersionClaim() {
        val fileSystem = LedgerFileSystemFake()
        val owner = readyOwner()
        val isolated = FakeIsolatedDatabase(userVersionThrows = true)
        val source = FakeSource(container(), reportedSize = null)
        val result = useCase(fileSystem, owner, source, isolated).preflight(request())

        assertEquals(BackupPreflightRejection.P706_SOURCE_READ_FAILED, assertIs<RestorePreflightResult.Rejected>(result).code)
    }

    @Test
    fun aHashReadFailureOnTheMigratedCopyIsATypedReadRejectionNotADomainVerdict() {
        // N5: reading the migrated copy in step 11 to bind its digest is a READ, so a failure there
        // is P706_SOURCE_READ_FAILED, not a domain verdict. The fake's single `failOn` seam throws on
        // exactly one operation; `openRead` of the migrated file is reachable ONLY in step 11 (steps
        // 6 and 9 read the snapshot, and step 9's copy reads it through `readBytes`), so the induced
        // failure is deterministic. Ablation proof: reverting the step-11 mapping to
        // P706_DOMAIN_VALIDATION_FAILED turns this test red.
        val fileSystem = LedgerFileSystemFake()
        val owner = readyOwner()
        val isolated = FakeIsolatedDatabase(userVersion = 5, migration = RestoreMigrationOutcome.Migrated(5, 31))
        fileSystem.failOn = "openRead:$migratedFile"
        val source = FakeSource(container(schemaVersion = 5), reportedSize = null)
        val result = useCase(fileSystem, owner, source, isolated).preflight(request(supported = setOf(5L)))

        assertEquals(BackupPreflightRejection.P706_SOURCE_READ_FAILED, assertIs<RestorePreflightResult.Rejected>(result).code)
        // The rejection still cleans up: the migration copy is deleted and the lease is released.
        assertTrue(!fileSystem.hasFile(migratedFile))
        assertEquals(0, owner.inFlightLeaseCount)
    }

    @Test
    fun aRejectionNeverTouchesTheGenerationSetOrTheActivePointer() {
        // P3-6 fix: the KDoc claims no rejection path touches the current library or the active
        // pointer; this asserts it concretely by recording every file operation the preflight makes
        // and requiring that none targets a generation file or the pointer.
        val fileSystem = LedgerFileSystemFake()
        val owner = readyOwner()
        val isolated = FakeIsolatedDatabase(identities = emptyList())
        val source = FakeSource(container(), reportedSize = null)
        val result = useCase(fileSystem, owner, source, isolated).preflight(request())

        assertIs<RestorePreflightResult.Rejected>(result)
        val pointer = ledgerStorageLayout(fileSystem, hostDirectory).activePointerFile
        val touchedLedgerState =
            fileSystem.operationsSnapshot().any { operation ->
                operation.contains("active-generation") ||
                    operation.contains("ledger-generations") ||
                    operation.contains(pointer)
            }
        assertTrue(!touchedLedgerState, "a rejection must not touch the pointer or a generation: ${fileSystem.operationsSnapshot()}")
    }

    private fun request(
        supported: Set<Long> = setOf(1L),
        currentSchemaVersion: Long = 31L,
        containerSizeBound: Long = BACKUP_MAX_CONTAINER_BYTES,
    ): RestorePreflightRequest =
        RestorePreflightRequest(
            password = "password123",
            targetLedgerId = "ledger-local-test",
            supportedSourceVersions = supported,
            currentSchemaVersion = currentSchemaVersion,
            containerSizeBound = containerSizeBound,
        )
}

/**
 * A deterministic 32-byte stand-in digest (not a real SHA-256): the fake crypto needs the digest of
 * the DECRYPTED plaintext to equal the header field for the success case, and to differ for the
 * mismatch case. Byte-for-byte reproducibility is all these wiring tests need.
 */
private fun fakeDigestOf(bytes: ByteArray): ByteArray {
    val out = ByteArray(32)
    for (index in bytes.indices) {
        out[index % 32] = (out[index % 32].toInt() xor bytes[index].toInt()).toByte()
    }
    // Mix in the length so two different-length inputs cannot collide trivially.
    out[31] = (out[31].toInt() xor bytes.size).toByte()
    return out
}

/**
 * P2-4: a crypto fake whose decryptor's `doFinal` throws, standing in for the platform AEAD tag
 * failure. It is otherwise the same reversible XOR stand-in as the class-level fake crypto.
 */
private class TagFailingCrypto : BackupCryptoPrimitives {
    override fun randomBytes(count: Int): ByteArray = ByteArray(count)

    override fun deriveKey(
        password: CharArray,
        salt: ByteArray,
        iterations: Int,
        keyLengthBits: Int,
    ): ByteArray = ByteArray(keyLengthBits / 8)

    override fun sha256Digest(): BackupSha256Digest =
        object : BackupSha256Digest {
            private var buffer = ByteArray(0)

            override fun update(
                bytes: ByteArray,
                offset: Int,
                length: Int,
            ) {
                buffer += bytes.copyOfRange(offset, offset + length)
            }

            override fun digest(): ByteArray = fakeDigestOf(buffer)
        }

    override fun gcmEncryptor(
        key: ByteArray,
        iv: ByteArray,
        aad: ByteArray,
    ): BackupGcmEncryptor =
        object : BackupGcmEncryptor {
            override fun update(
                bytes: ByteArray,
                offset: Int,
                length: Int,
            ): ByteArray = ByteArray(0)

            override fun doFinal(): ByteArray = ByteArray(0)
        }

    override fun gcmDecryptor(
        key: ByteArray,
        iv: ByteArray,
        aad: ByteArray,
    ): BackupGcmDecryptor =
        object : BackupGcmDecryptor {
            override fun update(
                bytes: ByteArray,
                offset: Int,
                length: Int,
            ): ByteArray {
                val out = ByteArray(length)
                for (index in 0 until length) {
                    out[index] = bytes[offset + index].toInt().xor(0x5A).toByte()
                }
                return out
            }

            override fun doFinal(): ByteArray = throw IllegalStateException("injected AEAD tag failure")
        }
}

/**
 * P2-1 option (a): a crypto fake whose decryptor's `doFinal` throws a fatal `Error` (an OOM stand-in),
 * proving the use case does not convert `Error` into a typed rejection.
 */
private class ErrorThrowingCrypto : BackupCryptoPrimitives {
    override fun randomBytes(count: Int): ByteArray = ByteArray(count)

    override fun deriveKey(
        password: CharArray,
        salt: ByteArray,
        iterations: Int,
        keyLengthBits: Int,
    ): ByteArray = ByteArray(keyLengthBits / 8)

    override fun sha256Digest(): BackupSha256Digest =
        object : BackupSha256Digest {
            private var buffer = ByteArray(0)

            override fun update(
                bytes: ByteArray,
                offset: Int,
                length: Int,
            ) {
                buffer += bytes.copyOfRange(offset, offset + length)
            }

            override fun digest(): ByteArray = fakeDigestOf(buffer)
        }

    override fun gcmEncryptor(
        key: ByteArray,
        iv: ByteArray,
        aad: ByteArray,
    ): BackupGcmEncryptor =
        object : BackupGcmEncryptor {
            override fun update(
                bytes: ByteArray,
                offset: Int,
                length: Int,
            ): ByteArray = ByteArray(0)

            override fun doFinal(): ByteArray = ByteArray(0)
        }

    override fun gcmDecryptor(
        key: ByteArray,
        iv: ByteArray,
        aad: ByteArray,
    ): BackupGcmDecryptor =
        object : BackupGcmDecryptor {
            override fun update(
                bytes: ByteArray,
                offset: Int,
                length: Int,
            ): ByteArray {
                val out = ByteArray(length)
                for (index in 0 until length) {
                    out[index] = bytes[offset + index].toInt().xor(0x5A).toByte()
                }
                return out
            }

            override fun doFinal(): ByteArray = throw OutOfMemoryError("injected fatal error")
        }
}
