package com.unifiedledger.ui

import com.unifiedledger.application.backup.BACKUP_DISK_HEADROOM_BYTES
import com.unifiedledger.application.backup.BACKUP_MAX_PLAINTEXT_BYTES
import com.unifiedledger.application.backup.BACKUP_STREAM_CHUNK_BYTES
import com.unifiedledger.application.backup.BackupCryptoPrimitives
import com.unifiedledger.application.backup.BackupGcmEncryptor
import com.unifiedledger.application.backup.BackupSha256Digest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P7-06 06.B (D-177; spec `2026-09-24-p7-06-backup-export-design.md` sections 3, 5 and 6): the
 * shared export use case's lease requirement, disk precheck, both write-side plaintext gates, the
 * snapshot-target rule, the bounded two-phase write, cancellation and staging cleanup. Everything
 * runs through the injected fake filesystem, fake snapshot port, fake target port and fake crypto,
 * so no real database or filesystem is touched.
 */
class BackupExportUseCaseTest {
    private val hostDirectory = "/host"
    private val generationDirectory = "/host/ledger-generations/gen-1"
    private val activeMainFile = "$generationDirectory/ledger.db"
    private val stagingDirectory = "/host/backup-staging"
    private val snapshotFile = "$stagingDirectory/snapshot-token"
    private val containerFile = "$stagingDirectory/container-token"
    private val targetPath = "/user/picked.ulbk"

    /** An XOR "cipher" plus a real streaming SHA-256 substitute is unnecessary; this fake is
     * deterministic and records the pass order. */
    private class FakeCrypto : BackupCryptoPrimitives {
        var salt = ByteArray(16) { it.toByte() }
        var iv = ByteArray(12) { (it + 1).toByte() }
        var key = ByteArray(32) { 0x11 }
        var events = mutableListOf<String>()
        var hashOf = ByteArray(0)

        override fun randomBytes(count: Int): ByteArray = if (count == 16) salt.copyOf() else iv.copyOf()

        override fun deriveKey(
            password: CharArray,
            salt: ByteArray,
            iterations: Int,
            keyLengthBits: Int,
        ): ByteArray {
            events += "deriveKey"
            return key.copyOf()
        }

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

                override fun digest(): ByteArray {
                    events += "hashDigest"
                    hashOf = buffer.copyOf()
                    return ByteArray(32) { 0x22 }
                }
            }

        override fun gcmEncryptor(
            key: ByteArray,
            iv: ByteArray,
            aad: ByteArray,
        ): BackupGcmEncryptor {
            events += "gcmInit"
            return object : BackupGcmEncryptor {
                override fun update(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ): ByteArray {
                    events += "encryptUpdate"
                    return bytes.copyOfRange(offset, offset + length)
                }

                override fun doFinal(): ByteArray {
                    events += "encryptFinal"
                    return ByteArray(16) { 0x33 }
                }
            }
        }
    }

    private class FakeSnapshot(
        private val fileSystem: LedgerFileSystemFake,
        private val snapshotBytes: ByteArray,
    ) : BackupSnapshotPort {
        var snapshotCalls = 0
        var verifyCalls = 0
        var snapshotThrows = false
        var integrityOk = true
        var schemaVersion = 31L
        var existingTargetDetected = false

        override fun snapshot(target: String) {
            snapshotCalls += 1
            if (snapshotThrows) throw IllegalStateException("injected VACUUM INTO failure")
            if (fileSystem.exists(target)) {
                // Both engines refuse to overwrite; the use case must have deleted any stale target.
                existingTargetDetected = true
                throw IllegalStateException("output file already exists")
            }
            fileSystem.putFile(target, snapshotBytes)
        }

        override fun verify(snapshotPath: String): BackupSnapshotVerification {
            verifyCalls += 1
            return BackupSnapshotVerification(integrityOk, schemaVersion)
        }
    }

    private class FakeTarget(
        private val fileSystem: LedgerFileSystemFake,
    ) : BackupTargetPort {
        var cancelled = false
        var openCalls = 0
        var failOnWrite = false
        val delivered = ArrayList<Byte>()

        override fun openTarget(): BackupTargetWriter? {
            openCalls += 1
            if (cancelled) return null
            return object : BackupTargetWriter {
                override fun write(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ) {
                    if (failOnWrite) throw IllegalStateException("injected target write failure")
                    for (index in offset until offset + length) delivered += bytes[index]
                }

                override fun flushAndSync() {}

                override fun commit() {
                    fileSystem.recordDeliveredTarget("/user/picked.ulbk", delivered.toByteArray())
                }

                override fun close() {
                    // The fake target abandons nothing: an uncommitted delivery never registers.
                }
            }
        }
    }

    private class Harness {
        val fileSystem = LedgerFileSystemFake()
        val crypto = FakeCrypto()
        var openCount = 0
        lateinit var owner: LedgerRuntimeOwner<Any>

        fun unreadyOwner(): LedgerRuntimeOwner<Any> {
            owner =
                LedgerRuntimeOwner(
                    openGeneration = {
                        openCount += 1
                        Any()
                    },
                    closeGraph = {},
                    facadeOf = { minimalP503LedgerFacade() },
                )
            return owner
        }

        fun readyOwner(): LedgerRuntimeOwner<Any> = unreadyOwner().also { it.startup() }
    }

    private fun harness(): Harness = Harness()

    private fun layoutOf(fileSystem: LedgerFileSystemFake): LedgerStorageLayout = ledgerStorageLayout(fileSystem, hostDirectory)

    private fun useCase(
        harness: Harness,
        snapshotBytes: ByteArray,
        snapshotPort: FakeSnapshot? = null,
        targetPort: FakeTarget? = null,
    ): Triple<BackupExportUseCase, FakeSnapshot, FakeTarget> {
        val layout = layoutOf(harness.fileSystem)
        val snapshot = snapshotPort ?: FakeSnapshot(harness.fileSystem, snapshotBytes)
        val target = targetPort ?: FakeTarget(harness.fileSystem)
        val useCase =
            BackupExportUseCase(
                owner = harness.owner,
                fileSystem = harness.fileSystem,
                layout = layout,
                snapshotProvider = { snapshot },
                target = target,
                crypto = harness.crypto,
                newToken = { "token" },
            )
        return Triple(useCase, snapshot, target)
    }

    private fun readyHarness(snapshotBytes: ByteArray = ByteArray(100) { 0x7A }): Harness {
        val harness = harness()
        harness.owner = harness.readyOwner()
        harness.fileSystem.putFile(activeMainFile, ByteArray(2048))
        harness.fileSystem.usableSpaceBytes = Long.MAX_VALUE
        return harness
    }

    // ------------------------------------------------------------------ lease requirement

    @Test
    fun anUnreadyRuntimeReturnsTypedNotReadyAndDoesNoWork() {
        val harness = harness()
        harness.owner = harness.unreadyOwner()
        harness.fileSystem.putFile(activeMainFile, ByteArray(2048))
        val (useCase, snapshot, target) = useCase(harness, ByteArray(50))

        val result = useCase.export(BackupExportRequest("password123", activeMainFile))

        assertEquals(BackupExportResult.Failed(BackupExportFailure.RUNTIME_NOT_READY), result)
        assertEquals(0, snapshot.snapshotCalls)
        assertEquals(0, target.openCalls)
        assertEquals(0, harness.fileSystem.openReadCount)
        assertFalse(harness.fileSystem.hasFile(snapshotFile))
    }

    @Test
    fun theLeaseIsReleasedAfterASuccessfulExport() {
        val harness = readyHarness()
        val (useCase, _, _) = useCase(harness, ByteArray(100))

        useCase.export(BackupExportRequest("password123", activeMainFile))

        assertEquals(0, harness.owner.inFlightLeaseCount)
    }

    @Test
    fun theLeaseIsReleasedAfterAFailedExport() {
        val harness = readyHarness()
        val snapshotPort = FakeSnapshot(harness.fileSystem, ByteArray(100)).apply { snapshotThrows = true }
        val (useCase, _, _) = useCase(harness, ByteArray(100), snapshotPort = snapshotPort)

        useCase.export(BackupExportRequest("password123", activeMainFile))

        assertEquals(0, harness.owner.inFlightLeaseCount)
    }

    @Test
    fun theLeaseIsHeldAcrossTheSnapshotAndVerificationSteps() {
        // Spec section 3.1: the operation lease is held for the WHOLE export, so a
        // close/reopen cannot switch the controlled connection mid-export. The snapshot and
        // verification steps are the first two database touches; both must run under the lease.
        val harness = readyHarness()
        val leaseCounts = mutableListOf<Int>()
        val recordingSnapshot =
            object : BackupSnapshotPort {
                override fun snapshot(target: String) {
                    leaseCounts += harness.owner.inFlightLeaseCount
                    harness.fileSystem.putFile(target, ByteArray(100))
                }

                override fun verify(snapshotPath: String): BackupSnapshotVerification {
                    leaseCounts += harness.owner.inFlightLeaseCount
                    return BackupSnapshotVerification(integrityOk = true, schemaVersion = 31)
                }
            }
        val useCase =
            BackupExportUseCase(
                owner = harness.owner,
                fileSystem = harness.fileSystem,
                layout = layoutOf(harness.fileSystem),
                snapshotProvider = { recordingSnapshot },
                target = FakeTarget(harness.fileSystem),
                crypto = harness.crypto,
                newToken = { "token" },
            )

        val result = useCase.export(BackupExportRequest("password123", activeMainFile))

        assertIs<BackupExportResult.Succeeded>(result)
        assertEquals(listOf(1, 1), leaseCounts, "the snapshot and verification must run under the lease")
        assertEquals(0, harness.owner.inFlightLeaseCount)
    }

    @Test
    fun anEmptyOrShortPasswordIsRejectedBeforeAnyWork() {
        val harness = readyHarness()
        val (useCase, snapshot, target) = useCase(harness, ByteArray(100))

        val result = useCase.export(BackupExportRequest("short", activeMainFile))

        assertEquals(BackupExportResult.Failed(BackupExportFailure.PASSWORD_TOO_SHORT), result)
        assertEquals(0, snapshot.snapshotCalls)
        assertEquals(0, target.openCalls)
        // The lease is never even taken for a policy rejection.
        assertEquals(0, harness.owner.inFlightLeaseCount)
    }

    // ------------------------------------------------------------------ disk precheck

    @Test
    fun insufficientSpaceIsRejectedBeforeTheSnapshotAndBeforeAnyUserTargetFile() {
        val harness = readyHarness()
        val (useCase, snapshot, target) = useCase(harness, ByteArray(100))
        val plaintext = 2048L
        val container = plaintext + 103
        // One byte short of the formula.
        harness.fileSystem.usableSpaceBytes = container + plaintext + BACKUP_DISK_HEADROOM_BYTES - 1

        val result = useCase.export(BackupExportRequest("password123", activeMainFile))

        assertEquals(BackupExportResult.Failed(BackupExportFailure.INSUFFICIENT_SPACE), result)
        assertEquals(0, snapshot.snapshotCalls)
        assertEquals(0, target.openCalls)
        assertFalse(harness.fileSystem.hasFile(snapshotFile))
        assertFalse(harness.fileSystem.deliveredTargets.containsKey(targetPath))
    }

    @Test
    fun exactlyEnoughSpacePassesThePrecheck() {
        val harness = readyHarness()
        val (useCase, snapshot, _) = useCase(harness, ByteArray(100))
        val plaintext = 2048L
        val container = plaintext + 103
        harness.fileSystem.usableSpaceBytes = container + plaintext + BACKUP_DISK_HEADROOM_BYTES

        val result = useCase.export(BackupExportRequest("password123", activeMainFile))

        assertIs<BackupExportResult.Succeeded>(result)
        assertEquals(1, snapshot.snapshotCalls)
    }

    @Test
    fun anUnknownAvailableSpaceSkipsThePrecheckAndReliesOnWriteFailure() {
        val harness = readyHarness()
        val (useCase, snapshot, _) = useCase(harness, ByteArray(100))
        harness.fileSystem.usableSpaceBytes = null

        val result = useCase.export(BackupExportRequest("password123", activeMainFile))

        assertIs<BackupExportResult.Succeeded>(result)
        assertEquals(1, snapshot.snapshotCalls)
    }

    // ------------------------------------------------------------------ write-side plaintext gates

    @Test
    fun thePreSnapshotGateRejectsAnOversizedActiveMainFileBeforeAnySnapshotOrTarget() {
        val harness = readyHarness()
        harness.fileSystem.lengthOverrides[activeMainFile] = BACKUP_MAX_PLAINTEXT_BYTES + 1
        val (useCase, snapshot, target) = useCase(harness, ByteArray(100))

        val result = useCase.export(BackupExportRequest("password123", activeMainFile))

        assertEquals(BackupExportResult.Failed(BackupExportFailure.PLAINTEXT_TOO_LARGE), result)
        assertEquals(0, snapshot.snapshotCalls)
        assertEquals(0, target.openCalls)
        assertFalse(harness.fileSystem.hasFile(snapshotFile))
    }

    @Test
    fun thePostSnapshotGateRejectsAnOversizedSnapshotAndNeverCreatesTheUserTarget() {
        val harness = readyHarness()
        // The source is within bounds but the snapshot is not: the snapshot size is not guaranteed
        // to equal the source size (spec section 5), so the post-gate must catch it.
        val snapshotPort = FakeSnapshot(harness.fileSystem, ByteArray(10))
        harness.fileSystem.lengthOverrides[snapshotFile] = BACKUP_MAX_PLAINTEXT_BYTES + 1
        val (useCase, snapshot, target) = useCase(harness, ByteArray(10), snapshotPort = snapshotPort)

        val result = useCase.export(BackupExportRequest("password123", activeMainFile))

        assertEquals(BackupExportResult.Failed(BackupExportFailure.PLAINTEXT_TOO_LARGE), result)
        assertEquals(1, snapshot.snapshotCalls)
        assertEquals(0, target.openCalls)
        assertFalse(harness.fileSystem.deliveredTargets.containsKey(targetPath))
        // The oversized snapshot is cleaned from staging.
        assertFalse(harness.fileSystem.hasFile(snapshotFile))
    }

    // ------------------------------------------------------------------ snapshot target rule

    @Test
    fun theSnapshotTargetDoesNotExistWhenVacuumIntoRuns() {
        val harness = readyHarness()
        // Pre-seed a stale snapshot at the token path: the use case must delete it first.
        harness.fileSystem.putFile(snapshotFile, ByteArray(5))
        val (useCase, snapshot, _) = useCase(harness, ByteArray(100))

        useCase.export(BackupExportRequest("password123", activeMainFile))

        assertEquals(1, snapshot.snapshotCalls)
        assertFalse(snapshot.existingTargetDetected, "the stale snapshot must be removed before VACUUM INTO")
    }

    @Test
    fun aSnapshotFailureIsReportedAndLeavesNoStagingArtifact() {
        val harness = readyHarness()
        val snapshotPort = FakeSnapshot(harness.fileSystem, ByteArray(100)).apply { snapshotThrows = true }
        val (useCase, snapshot, target) = useCase(harness, ByteArray(100), snapshotPort = snapshotPort)

        val result = useCase.export(BackupExportRequest("password123", activeMainFile))

        assertEquals(BackupExportResult.Failed(BackupExportFailure.SNAPSHOT_FAILED), result)
        assertEquals(1, snapshot.snapshotCalls)
        assertEquals(0, target.openCalls)
        assertFalse(harness.fileSystem.hasFile(snapshotFile))
    }

    @Test
    fun aFailedIntegrityCheckIsReportedAndNeverCreatesTheUserTarget() {
        val harness = readyHarness()
        val snapshotPort = FakeSnapshot(harness.fileSystem, ByteArray(100)).apply { integrityOk = false }
        val (useCase, snapshot, target) = useCase(harness, ByteArray(100), snapshotPort = snapshotPort)

        val result = useCase.export(BackupExportRequest("password123", activeMainFile))

        assertEquals(BackupExportResult.Failed(BackupExportFailure.SNAPSHOT_INTEGRITY_FAILED), result)
        assertEquals(1, snapshot.verifyCalls)
        assertEquals(0, target.openCalls)
        assertFalse(harness.fileSystem.hasFile(snapshotFile))
    }

    // ------------------------------------------------------------------ two-pass streaming / bounded IO

    @Test
    fun theSnapshotIsReadTwiceInBoundedChunksAndNeverWholeFile() {
        val harness = readyHarness()
        // Larger than one 64 KiB chunk so the loop is exercised.
        val payload = ByteArray(BACKUP_STREAM_CHUNK_BYTES * 2 + 100) { (it % 251).toByte() }
        val (useCase, _, _) = useCase(harness, payload)

        val result = useCase.export(BackupExportRequest("password123", activeMainFile))

        assertIs<BackupExportResult.Succeeded>(result)
        // Two snapshot passes (hash + encrypt) plus one delivery read of the staged container.
        assertEquals(3, harness.fileSystem.openReadCount)
        // The snapshot itself is opened exactly twice (pass 1 + pass 2), never once.
        assertEquals(2, harness.fileSystem.operations.count { it == "openRead:$snapshotFile" })
        assertTrue(harness.fileSystem.maxReadBufferSize <= BACKUP_STREAM_CHUNK_BYTES)
        // The whole-file primitive must never be used on the snapshot or the container.
        assertEquals(0, harness.fileSystem.readBytesCallCount)
        // The hash pass must complete before encryption starts.
        val hashIndex = harness.crypto.events.indexOf("hashDigest")
        val gcmIndex = harness.crypto.events.indexOf("gcmInit")
        assertTrue(hashIndex in 0 until gcmIndex, "the digest must complete before GCM init: ${harness.crypto.events}")
    }

    @Test
    fun theContainerIsDeliveredToTheUserTargetByteForByte() {
        val harness = readyHarness()
        val payload = ByteArray(300) { (it % 200).toByte() }
        val (useCase, _, target) = useCase(harness, payload)

        val result = useCase.export(BackupExportRequest("password123", activeMainFile))

        val succeeded = assertIs<BackupExportResult.Succeeded>(result)
        val delivered = harness.fileSystem.deliveredTargets[targetPath]
        assertNotNull(delivered)
        // header(59) + salt(16) + iv(12) + ciphertext(=payload) + tag(16)
        assertEquals(59 + 16 + 12 + payload.size + 16, delivered.size)
        assertEquals(delivered.size.toLong(), succeeded.containerBytes)
        // The fake cipher is an identity on the payload, so the plaintext appears at offset 87.
        assertContentEquals(payload, delivered.copyOfRange(87, 87 + payload.size))
    }

    // ------------------------------------------------------------------ cancellation & cleanup

    @Test
    fun cancellingDuringTheHashPassReturnsCancelledAndLeavesNoSuccessArtifact() {
        val harness = readyHarness()
        val payload = ByteArray(BACKUP_STREAM_CHUNK_BYTES * 3) { (it % 251).toByte() }
        val (useCase, _, target) = useCase(harness, payload)
        var checks = 0

        val result =
            useCase.export(
                BackupExportRequest("password123", activeMainFile),
                cancellation = { checks++ >= 1 },
            )

        assertEquals(BackupExportResult.Cancelled, result)
        assertEquals(0, target.openCalls)
        assertFalse(harness.fileSystem.hasFile(snapshotFile))
        assertFalse(harness.fileSystem.hasFile(containerFile))
        assertFalse(harness.fileSystem.deliveredTargets.containsKey(targetPath))
    }

    @Test
    fun cancellingDuringDeliveryReturnsCancelledAndCommitsNothing() {
        val harness = readyHarness()
        val payload = ByteArray(BACKUP_STREAM_CHUNK_BYTES * 3) { (it % 251).toByte() }
        val (useCase, _, target) = useCase(harness, payload)
        // Allow the hash and encrypt passes (2 opens * 3 chunks + margin) then cancel in delivery.
        var checks = 0

        val result =
            useCase.export(
                BackupExportRequest("password123", activeMainFile),
                cancellation = { checks++ >= 8 },
            )

        assertEquals(BackupExportResult.Cancelled, result)
        assertEquals(1, target.openCalls)
        assertFalse(harness.fileSystem.deliveredTargets.containsKey(targetPath))
        // Staging is cleaned on cancel.
        assertFalse(harness.fileSystem.hasFile(snapshotFile))
        assertFalse(harness.fileSystem.hasFile(containerFile))
    }

    @Test
    fun aTargetWriteFailureIsReportedAndIsNeverSuccess() {
        val harness = readyHarness()
        val targetPort = FakeTarget(harness.fileSystem).apply { failOnWrite = true }
        val (useCase, _, target) = useCase(harness, ByteArray(200), targetPort = targetPort)

        val result = useCase.export(BackupExportRequest("password123", activeMainFile))

        assertEquals(BackupExportResult.Failed(BackupExportFailure.TARGET_WRITE_FAILED), result)
        assertEquals(1, target.openCalls)
        assertFalse(harness.fileSystem.deliveredTargets.containsKey(targetPath))
        assertFalse(harness.fileSystem.hasFile(snapshotFile))
        assertFalse(harness.fileSystem.hasFile(containerFile))
    }

    @Test
    fun aContainerWriteFailureIsReportedAndLeavesNoStagingContainer() {
        val harness = readyHarness()
        val (useCase, _, target) = useCase(harness, ByteArray(200))
        harness.fileSystem.failOnOpenWrite = true

        val result = useCase.export(BackupExportRequest("password123", activeMainFile))

        assertEquals(BackupExportResult.Failed(BackupExportFailure.CONTAINER_WRITE_FAILED), result)
        assertEquals(0, target.openCalls)
        assertFalse(harness.fileSystem.hasFile(containerFile))
        assertFalse(harness.fileSystem.hasFile(snapshotFile))
    }

    @Test
    fun aDismissedTargetPickerIsReportedAsCancelledAndCleansStaging() {
        val harness = readyHarness()
        val targetPort = FakeTarget(harness.fileSystem).apply { cancelled = true }
        val (useCase, _, target) = useCase(harness, ByteArray(200), targetPort = targetPort)

        val result = useCase.export(BackupExportRequest("password123", activeMainFile))

        assertEquals(BackupExportResult.Cancelled, result)
        assertEquals(1, target.openCalls)
        assertFalse(harness.fileSystem.hasFile(snapshotFile))
        assertFalse(harness.fileSystem.hasFile(containerFile))
    }

    @Test
    fun successIsReportedOnlyAfterTheAuthenticatedTailAndTheCleanDeliveryClose() {
        val harness = readyHarness()
        val (useCase, _, _) = useCase(harness, ByteArray(200))

        val result = useCase.export(BackupExportRequest("password123", activeMainFile))

        assertIs<BackupExportResult.Succeeded>(result)
        // The final tail write happened before delivery, and staging is cleaned afterwards.
        assertTrue(harness.crypto.events.contains("encryptFinal"))
        assertFalse(harness.fileSystem.hasFile(snapshotFile))
        assertFalse(harness.fileSystem.hasFile(containerFile))
        assertTrue(harness.fileSystem.deliveredTargets.containsKey(targetPath))
    }

    @Test
    fun theSchemaVersionHintFromTheSnapshotLandsInTheHeader() {
        val harness = readyHarness()
        val snapshotPort = FakeSnapshot(harness.fileSystem, ByteArray(64)).apply { schemaVersion = 31 }
        val (useCase, _, _) = useCase(harness, ByteArray(64), snapshotPort = snapshotPort)

        useCase.export(BackupExportRequest("password123", activeMainFile))

        val delivered = harness.fileSystem.deliveredTargets.getValue(targetPath)
        // db_schema_version is the u32 at offset 15.
        assertEquals(31, ((delivered[15].toInt() and 0xFF) shl 24) or ((delivered[16].toInt() and 0xFF) shl 16) or ((delivered[17].toInt() and 0xFF) shl 8) or (delivered[18].toInt() and 0xFF))
    }

    @Test
    fun aNullTargetWriterIsDistinctFromAFailure() {
        val harness = readyHarness()
        val (useCase, _, _) = useCase(harness, ByteArray(200))
        // Not cancelled, but the port returns null (user dismissed without a cancel flag).
        val nullTarget =
            object : BackupTargetPort {
                override fun openTarget(): BackupTargetWriter? = null
            }
        val useCase2 =
            BackupExportUseCase(
                owner = harness.owner,
                fileSystem = harness.fileSystem,
                layout = layoutOf(harness.fileSystem),
                snapshotProvider = { FakeSnapshot(harness.fileSystem, ByteArray(200)) },
                target = nullTarget,
                crypto = harness.crypto,
                newToken = { "token" },
            )

        val result = useCase2.export(BackupExportRequest("password123", activeMainFile))

        assertEquals(BackupExportResult.Cancelled, result)
        assertNull(harness.fileSystem.deliveredTargets[targetPath])
    }

    @Test
    fun aTwoCodePointEmojiCountsAsOneAndPassesTheEightCodePointGate() {
        // "1234567" + one emoji = 8 code points (the emoji is a surrogate pair, 2 chars).
        val password = "1234567\uD83D\uDD10"
        val harness = readyHarness()
        val (useCase, _, _) = useCase(harness, ByteArray(100))

        val result = useCase.export(BackupExportRequest(password, activeMainFile))

        assertIs<BackupExportResult.Succeeded>(result)
    }
}
