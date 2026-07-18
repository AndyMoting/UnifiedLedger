package com.unifiedledger.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class LedgerCatalogTest {
    private val fixture = Rg01Fixture()

    @Test
    fun rejectsDuplicateAccountIds() {
        val result = LedgerCatalog.create(
            accounts = fixture.accounts + fixture.accounts.first(),
            categories = fixture.categories,
        )

        assertEquals(DomainViolation.InvalidCatalog, failure(result))
    }

    @Test
    fun rejectsDuplicateCategoryIds() {
        val result = LedgerCatalog.create(
            accounts = fixture.accounts,
            categories = fixture.categories + fixture.categories.first(),
        )

        assertEquals(DomainViolation.InvalidCatalog, failure(result))
    }
}
