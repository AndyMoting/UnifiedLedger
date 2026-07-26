package com.unifiedledger.application

import kotlin.test.Test
import kotlin.test.assertEquals

private const val IMPORT_LOCATOR = "$.import_lifecycle"
private const val IMPORT_SOURCE = "source-record-rg04-complete"
private const val CONFIRM_REQUEST = "request-rg04-confirm-candidate"
private const val CANDIDATE_STATUS_LOCATOR = "$.import_lifecycle.ordered_operations[*].expected.candidate_status"
private const val RECONCILIATION_LOCATOR = "$.import_lifecycle.ordered_operations[*].expected.reconciliation"
private const val PENDING_STATUS_LOCATOR = "$.import_lifecycle.ordered_operations[*].expected.candidate.status"
private const val CANDIDATE = "candidate-purchase-rg04"
private const val MISSING_LEG_LOCATOR = "$.missing_funding_leg"
private const val MISSING_LEG_SOURCE = "source-record-rg04-missing-leg"
private const val MISSING_LEG_STATUS_LOCATOR = "$.missing_funding_leg.expected.candidate.status"
private const val MISSING_LEG_CANDIDATE = "candidate-purchase-rg04-missing-leg"

class Rg04IdentityTest {
    private val importRoot = rg04RootId(IMPORT_LOCATOR, IMPORT_SOURCE)
    private val missingLegRoot = rg04RootId(MISSING_LEG_LOCATOR, MISSING_LEG_SOURCE)

    /**
     * These are the identities frozen in `docs/migrations/golden-v2/rg-04-expected.json`, which is
     * already `approved`. They were produced by `tools/python/golden_cases/v2.py`, so matching them
     * proves this generator agrees with the contract on namespace, name layout, entity kind and the
     * locator/discriminator values declared below.
     *
     * The point of anchoring output rather than implementation is that the generator is shared
     * across scenarios: this test must keep passing across changes to it, which is what makes such
     * changes provably behaviour-preserving.
     *
     * It does NOT prove that the decoder passes these locators: the values below are declared here,
     * not read from `decodeRg04RawJson`. A locator edited in `Rg04RawJsonDecoder` would rename an
     * entity in an already approved contract, and only an end-to-end comparison catches that.
     */
    @Test
    fun generatedIdentitiesMatchTheFrozenGoldenContractValues() {
        assertEquals(
            mapOf(
                "import root" to "fe316e6a-5f79-5fc7-a517-b587e2238e2b",
                "import confirmation" to "8c2dd505-2f11-5527-a78b-01fe71e474b3",
                "candidate confirmed status" to "5f6fad69-6379-527c-ac6e-325a639345c4",
                "asset posting reconciliation" to "3d1d9844-a204-5eb5-a6c0-252ce8a1df25",
                "liability posting reconciliation" to "24730466-70b5-57c4-8709-147007590f95",
                "candidate pending status" to "b27b91c0-9243-5713-8fe6-d198cd81a4dd",
                "missing funding leg root" to "cd0d9d9f-90b0-54ce-9ccc-3e6552817409",
                "missing funding leg candidate status" to "b0168a14-730a-5ba5-a31b-5842da3e3fba",
            ),
            contractIdentities(),
        )
    }

    @Test
    fun generatedIdsAreVersionFiveRfc4122Uuids() {
        contractIdentities().values.forEach { id ->
            assertEquals(36, id.length, "canonical form: $id")
            assertEquals('5', id[14], "UUID version nibble must be 5: $id")
            kotlin.test.assertTrue(id[19] in setOf('8', '9', 'a', 'b'), "RFC 4122 variant bits must be 10xx: $id")
        }
    }

    @Test
    fun distinctInputsProduceDistinctIds() {
        val all = contractIdentities().values.toList()
        assertEquals(all.size, all.toSet().size, "every contract identity must be distinct: $all")
        kotlin.test.assertNotEquals(importRoot, rg04RootId(IMPORT_LOCATOR, "source-record-rg04-other"))
        kotlin.test.assertNotEquals(
            rg04MigrationId(importRoot, "confirmation", CANDIDATE_STATUS_LOCATOR, CONFIRM_REQUEST),
            rg04MigrationId(importRoot, "candidate_status", CANDIDATE_STATUS_LOCATOR, CONFIRM_REQUEST),
        )
    }

    private fun contractIdentities() = mapOf(
        "import root" to importRoot,
        "import confirmation" to rg04MigrationId(importRoot, "confirmation", CANDIDATE_STATUS_LOCATOR, CONFIRM_REQUEST),
        "candidate confirmed status" to rg04MigrationId(importRoot, "candidate_status", CANDIDATE_STATUS_LOCATOR, CONFIRM_REQUEST),
        "asset posting reconciliation" to rg04MigrationId(importRoot, "posting_reconciliation", RECONCILIATION_LOCATOR, "posting-asset-rg04-imported"),
        "liability posting reconciliation" to rg04MigrationId(importRoot, "posting_reconciliation", RECONCILIATION_LOCATOR, "posting-liability-rg04-imported"),
        "candidate pending status" to rg04MigrationId(importRoot, "candidate_status", PENDING_STATUS_LOCATOR, CANDIDATE),
        "missing funding leg root" to missingLegRoot,
        "missing funding leg candidate status" to rg04MigrationId(missingLegRoot, "candidate_status", MISSING_LEG_STATUS_LOCATOR, MISSING_LEG_CANDIDATE),
    )
}
