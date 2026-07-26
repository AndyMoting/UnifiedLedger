package com.unifiedledger.application

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val MANUAL_LOCATOR = "$.manual_path"
private const val MANUAL_REQUEST = "request-rg05-manual"
private const val IMPORT_LOCATOR = "$.import_path"
private const val IMPORT_SOURCE = "source-bank-debit-rg05"
private const val CONFIRM_REQUEST = "request-rg05-confirm-candidate"
private const val CANDIDATE = "candidate-rg05-imported"
private const val CANDIDATE_STATUS_LOCATOR = "$.import_path.ordered_operations[*].expected.candidate_status"

class Rg05IdentityTest {
    private val manualRoot = rg05RootId(MANUAL_LOCATOR, MANUAL_REQUEST)
    private val importRoot = rg05RootId(IMPORT_LOCATOR, IMPORT_SOURCE)

    @Test
    fun sameInputAlwaysProducesTheSameId() {
        repeat(3) {
            assertEquals(rg05UuidV5("RG-05"), rg05UuidV5("RG-05"))
            assertEquals(importRoot, rg05RootId(IMPORT_LOCATOR, IMPORT_SOURCE))
            assertEquals(
                rg05MigrationId(importRoot, "confirmation", CANDIDATE_STATUS_LOCATOR, CONFIRM_REQUEST),
                rg05MigrationId(importRoot, "confirmation", CANDIDATE_STATUS_LOCATOR, CONFIRM_REQUEST),
            )
        }
    }

    @Test
    fun generatedIdsAreVersionFiveRfc4122Uuids() {
        contractIdentities().values.forEach { id ->
            assertTrue(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$").matches(id), "canonical lowercase form: $id")
            assertEquals('5', id[14], "UUID version nibble must be 5: $id")
            assertTrue(id[19] in setOf('8', '9', 'a', 'b'), "RFC 4122 variant bits must be 10xx: $id")
        }
    }

    @Test
    fun distinctInputsProduceDistinctIds() {
        assertNotEquals(rg05UuidV5("RG-05"), rg05UuidV5("RG-06"))
        assertNotEquals(importRoot, rg05RootId(IMPORT_LOCATOR, "source-bank-debit-other"))
        assertNotEquals(importRoot, manualRoot)
        val confirmation = rg05MigrationId(importRoot, "confirmation", CANDIDATE_STATUS_LOCATOR, CONFIRM_REQUEST)
        assertNotEquals(confirmation, rg05MigrationId(importRoot, "candidate_status", CANDIDATE_STATUS_LOCATOR, CONFIRM_REQUEST))
        assertNotEquals(confirmation, rg05MigrationId(importRoot, "confirmation", "$.import_path.confirm", CONFIRM_REQUEST))
        assertNotEquals(confirmation, rg05MigrationId(importRoot, "confirmation", CANDIDATE_STATUS_LOCATOR, "request-other"))
        assertNotEquals(confirmation, rg05MigrationId(manualRoot, "confirmation", CANDIDATE_STATUS_LOCATOR, CONFIRM_REQUEST))

        val all = contractIdentities().values.toList()
        assertEquals(all.size, all.toSet().size, "every contract identity must be distinct: $all")
    }

    /**
     * These are the identities frozen in `docs/migrations/golden-v2/rg-05-expected.json`, produced
     * by `tools/python/golden_cases/v2.py`. Matching them proves this generator agrees with the
     * contract on namespace, name layout, entity kind and the locator/discriminator values declared
     * below, so any drift in `rg05Sha1`/`rg05UuidV5` breaks this test.
     *
     * It does NOT prove that the decoder passes these locators: the values below are declared here,
     * not read from `decodeRg05RawJson`. A locator edited in `Rg05RawJsonDecoder` is caught only by
     * `Rg05RawJsonEndToEndTest.decodedIdentitiesAreDeterministicAndMatchTheFrozenContract`, which
     * lives in `ledger-data` because the generator is `internal` to this module.
     */
    @Test
    fun generatedIdentitiesMatchTheFrozenGoldenContractValues() {
        assertEquals(
            mapOf(
                "manual root" to "57792c2e-70a9-53fd-8253-a8a56aafb7b3",
                "manual confirmation" to "94539e72-e936-531a-a9a5-a5f5fb352ed7",
                "manual posting reconciliation" to "a2dbab61-27d1-5b76-950b-88c8b2f51f7c",
                "import root" to "bbf56c46-cfd1-5034-9db8-efe40a8f144e",
                "candidate pending status" to "6efb9958-d8ac-5dc1-a42e-932a94b8c27b",
                "candidate confirmed status" to "de5933f3-ab27-567c-802e-4e4e2a636dff",
                "import confirmation" to "f13b31d2-f853-5f03-b6a7-e801ab481d9c",
                "import posting reconciliation" to "e8eaaca9-8236-54b5-980a-0f060655bb90",
            ),
            contractIdentities(),
        )
        // Independent RFC 4122 anchor on the raw generator, outside any RG-05 locator.
        assertEquals("6aa8c5ff-b64d-5ced-b70a-f26ab5dfa636", rg05UuidV5("RG-05"))
    }

    private fun contractIdentities() = mapOf(
        "manual root" to manualRoot,
        "manual confirmation" to rg05MigrationId(manualRoot, "confirmation", "$.manual_path.confirmation", MANUAL_REQUEST),
        "manual posting reconciliation" to rg05MigrationId(manualRoot, "posting_reconciliation", "$.manual_path.expected.reconciliation", "posting-asset-rg05-manual"),
        "import root" to importRoot,
        "candidate pending status" to rg05MigrationId(importRoot, "candidate_status", "$.import_path.ordered_operations[*].expected.candidate.status", CANDIDATE),
        "candidate confirmed status" to rg05MigrationId(importRoot, "candidate_status", CANDIDATE_STATUS_LOCATOR, CONFIRM_REQUEST),
        "import confirmation" to rg05MigrationId(importRoot, "confirmation", CANDIDATE_STATUS_LOCATOR, CONFIRM_REQUEST),
        "import posting reconciliation" to rg05MigrationId(importRoot, "posting_reconciliation", "$.import_path.ordered_operations[*].expected.reconciliation", "posting-asset-rg05-imported"),
    )
}
