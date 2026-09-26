package com.unifiedledger.application.backup

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * P7-06 06.C (D-179; spec `2026-09-25-p7-06-restore-preflight-design.md` section 4): the read-side
 * fixed-header parser. Every row of the frozen rejection table (container-format spec section 4.3.1)
 * and the pre-authentication size checks (section 4.8) is exercised by MUTATING exactly one field of
 * a valid header assembled by the write-side `backupContainerHeader`, so a parser that dropped or
 * reordered a check goes red on the corresponding case.
 *
 * The parser is the mirror of the writer, so the valid case is produced by the writer itself rather
 * than hand-assembled: a divergence between the two would fail the round-trip assertion.
 */
class BackupContainerReaderTest {
    private val payloadSha256 = ByteArray(32) { (it + 1).toByte() }
    private val plaintextLength = 4096L
    private val schemaVersion = 31L

    private fun validHeader(): ByteArray = backupContainerHeader(schemaVersion, plaintextLength, payloadSha256)

    @Test
    fun parsesTheWriteSideHeaderBackToTheSameFieldValues() {
        val parsed = assertIs<BackupHeaderParseResult.Parsed>(parseBackupContainerHeader(validHeader()))

        assertEquals(BACKUP_CONTAINER_FORMAT_VERSION, parsed.header.containerFormatVersion)
        assertEquals(BACKUP_KDF_ID, parsed.header.kdfId)
        assertEquals(BACKUP_AEAD_ID, parsed.header.aeadId)
        assertEquals(BACKUP_KDF_ITERATIONS.toLong(), parsed.header.kdfIterations)
        assertEquals(BACKUP_SALT_LENGTH, parsed.header.saltLength)
        assertEquals(BACKUP_IV_LENGTH, parsed.header.ivLength)
        assertEquals(BACKUP_TAG_LENGTH, parsed.header.tagLength)
        assertEquals(schemaVersion, parsed.header.dbSchemaVersion)
        assertEquals(plaintextLength, parsed.header.plaintextLength)
        assertContentEquals(payloadSha256, parsed.header.payloadSha256)
    }

    @Test
    fun fewerThanFiftyNineBytesIsTruncated() {
        val short = validHeader().copyOf(BACKUP_FIXED_HEADER_LENGTH - 1)
        val rejected = assertIs<BackupHeaderParseResult.Rejected>(parseBackupContainerHeader(short))
        assertEquals(BackupPreflightRejection.P706_CONTAINER_TRUNCATED, rejected.code)
    }

    @Test
    fun anEmptyArrayIsTruncated() {
        val rejected = assertIs<BackupHeaderParseResult.Rejected>(parseBackupContainerHeader(ByteArray(0)))
        assertEquals(BackupPreflightRejection.P706_CONTAINER_TRUNCATED, rejected.code)
    }

    @Test
    fun badMagicIsFormatUnsupported() {
        val header = validHeader()
        header[0] = 'X'.code.toByte()
        val rejected = assertIs<BackupHeaderParseResult.Rejected>(parseBackupContainerHeader(header))
        assertEquals(BackupPreflightRejection.P706_CONTAINER_FORMAT_UNSUPPORTED, rejected.code)
    }

    @Test
    fun aFutureContainerFormatVersionIsVersionUnsupported() {
        val header = validHeader()
        header[5] = 2 // container_format_version = 2 (big-endian u16)
        val rejected = assertIs<BackupHeaderParseResult.Rejected>(parseBackupContainerHeader(header))
        assertEquals(BackupPreflightRejection.P706_CONTAINER_VERSION_UNSUPPORTED, rejected.code)
    }

    @Test
    fun anUnsupportedKdfIdIsKdfUnsupported() {
        val header = validHeader()
        header[6] = 2
        val rejected = assertIs<BackupHeaderParseResult.Rejected>(parseBackupContainerHeader(header))
        assertEquals(BackupPreflightRejection.P706_KDF_UNSUPPORTED, rejected.code)
    }

    @Test
    fun anUnsupportedAeadIdIsAeadUnsupported() {
        val header = validHeader()
        header[7] = 2
        val rejected = assertIs<BackupHeaderParseResult.Rejected>(parseBackupContainerHeader(header))
        assertEquals(BackupPreflightRejection.P706_AEAD_UNSUPPORTED, rejected.code)
    }

    @Test
    fun aSaltLengthBelowSixteenIsKdfParametersUnsupported() {
        val header = validHeader()
        header[12] = 15
        val rejected = assertIs<BackupHeaderParseResult.Rejected>(parseBackupContainerHeader(header))
        assertEquals(BackupPreflightRejection.P706_KDF_PARAMETERS_UNSUPPORTED, rejected.code)
    }

    @Test
    fun aSaltLengthAboveThirtyTwoIsKdfParametersUnsupported() {
        val header = validHeader()
        header[12] = 33
        val rejected = assertIs<BackupHeaderParseResult.Rejected>(parseBackupContainerHeader(header))
        assertEquals(BackupPreflightRejection.P706_KDF_PARAMETERS_UNSUPPORTED, rejected.code)
    }

    @Test
    fun iterationsBelowTheLowerBoundAreKdfParametersUnsupported() {
        val header = validHeader()
        writeU32(header, 8, 599_999L)
        val rejected = assertIs<BackupHeaderParseResult.Rejected>(parseBackupContainerHeader(header))
        assertEquals(BackupPreflightRejection.P706_KDF_PARAMETERS_UNSUPPORTED, rejected.code)
    }

    @Test
    fun iterationsAboveTheUpperBoundAreKdfParametersUnsupported() {
        val header = validHeader()
        writeU32(header, 8, 10_000_001L)
        val rejected = assertIs<BackupHeaderParseResult.Rejected>(parseBackupContainerHeader(header))
        assertEquals(BackupPreflightRejection.P706_KDF_PARAMETERS_UNSUPPORTED, rejected.code)
    }

    @Test
    fun bothIterationBoundsAreInclusive() {
        val lower = validHeader().also { writeU32(it, 8, 600_000L) }
        val upper = validHeader().also { writeU32(it, 8, 10_000_000L) }
        assertIs<BackupHeaderParseResult.Parsed>(parseBackupContainerHeader(lower))
        assertIs<BackupHeaderParseResult.Parsed>(parseBackupContainerHeader(upper))
    }

    @Test
    fun aNonTwelveByteIvIsAeadParametersUnsupported() {
        val header = validHeader()
        header[13] = 8
        val rejected = assertIs<BackupHeaderParseResult.Rejected>(parseBackupContainerHeader(header))
        assertEquals(BackupPreflightRejection.P706_AEAD_PARAMETERS_UNSUPPORTED, rejected.code)
    }

    @Test
    fun aNonSixteenByteTagIsAeadParametersUnsupported() {
        val header = validHeader()
        header[14] = 12
        val rejected = assertIs<BackupHeaderParseResult.Rejected>(parseBackupContainerHeader(header))
        assertEquals(BackupPreflightRejection.P706_AEAD_PARAMETERS_UNSUPPORTED, rejected.code)
    }

    @Test
    fun aPlaintextLengthAboveOneGibIsPlaintextTooLarge() {
        val header = validHeader()
        writeU64(header, 19, BACKUP_MAX_PLAINTEXT_BYTES + 1L)
        val rejected = assertIs<BackupHeaderParseResult.Rejected>(parseBackupContainerHeader(header))
        assertEquals(BackupPreflightRejection.P706_PLAINTEXT_TOO_LARGE, rejected.code)
    }

    @Test
    fun aPlaintextLengthWithTheTopBitSetIsPlaintextTooLarge() {
        // u64 value 0x8000000000000000 = 2^63: negative as a Long, far above the 1 GiB bound.
        val header = validHeader()
        header[19] = 0x80.toByte()
        val rejected = assertIs<BackupHeaderParseResult.Rejected>(parseBackupContainerHeader(header))
        assertEquals(BackupPreflightRejection.P706_PLAINTEXT_TOO_LARGE, rejected.code)
    }

    @Test
    fun exactlyOneGibIsAccepted() {
        val header = validHeader()
        writeU64(header, 19, BACKUP_MAX_PLAINTEXT_BYTES)
        val parsed = assertIs<BackupHeaderParseResult.Parsed>(parseBackupContainerHeader(header))
        assertEquals(BACKUP_MAX_PLAINTEXT_BYTES, parsed.header.plaintextLength)
    }

    @Test
    fun theFrozenResourceCapsArePinnedToTheirLiteralValues() {
        // P3-4: pin the frozen container-format section 4.8 caps to their literal values, so changing
        // either constant (a frozen-value change that would require reopening container-format spec
        // section 4.8) goes red here.
        assertEquals(1L shl 30, BACKUP_MAX_PLAINTEXT_BYTES)
        assertEquals(2L shl 30, BACKUP_MAX_CONTAINER_BYTES)
        assertEquals(64L * 1024L * 1024L, BACKUP_DISK_HEADROOM_BYTES)
        assertEquals(64 * 1024, BACKUP_STREAM_CHUNK_BYTES)
        assertEquals(59, BACKUP_FIXED_HEADER_LENGTH)
        assertEquals(12, BACKUP_IV_LENGTH)
        assertEquals(16, BACKUP_TAG_LENGTH)
        assertEquals(16, BACKUP_SALT_LENGTH)
    }

    @Test
    fun theSizeBoundIsInclusiveAtTwoGib() {
        assertTrue(backupContainerWithinSizeBound(BACKUP_MAX_CONTAINER_BYTES))
        assertTrue(!backupContainerWithinSizeBound(BACKUP_MAX_CONTAINER_BYTES + 1L))
    }

    @Test
    fun theSelfConsistencyEquationMatchesTheHeaderDerivedSize() {
        val parsed = assertIs<BackupHeaderParseResult.Parsed>(parseBackupContainerHeader(validHeader()))
        val expected = backupContainerExpectedSize(parsed.header)
        // 59 header + 16 salt + 12 IV + 16 tag + 4096 plaintext = 4199.
        assertEquals(4199L, expected)
        assertTrue(backupContainerSelfConsistent(parsed.header, expected))
        assertTrue(!backupContainerSelfConsistent(parsed.header, expected + 1))
    }

    private fun writeU32(
        target: ByteArray,
        offset: Int,
        value: Long,
    ) {
        for (index in 0 until 4) {
            target[offset + index] = ((value ushr ((3 - index) * 8)) and 0xFF).toByte()
        }
    }

    private fun writeU64(
        target: ByteArray,
        offset: Int,
        value: Long,
    ) {
        for (index in 0 until 8) {
            target[offset + index] = ((value ushr ((7 - index) * 8)) and 0xFF).toByte()
        }
    }
}
