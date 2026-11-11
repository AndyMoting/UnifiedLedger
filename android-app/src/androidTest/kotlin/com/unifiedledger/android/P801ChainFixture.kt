package com.unifiedledger.android

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.unifiedledger.application.CATALOG_MANAGED_CURRENCY
import com.unifiedledger.application.CatalogAdmissionTransferTransactionFactory
import com.unifiedledger.application.CatalogCommandResult
import com.unifiedledger.application.CommitOnceInvocationTrackerTransfer
import com.unifiedledger.application.ConfirmedManualTransferResult
import com.unifiedledger.application.ConfirmedTransferTransactionFactory
import com.unifiedledger.application.DEFAULT_MANAGEABLE_ACCOUNT_ID
import com.unifiedledger.application.ExecuteConfirmedManualTransfer
import com.unifiedledger.application.ExecuteImportIntake
import com.unifiedledger.application.ExplicitManualSave
import com.unifiedledger.application.ExplicitlyConfirmedManualTransfer
import com.unifiedledger.application.IMPORT_FUNDING_RULE_LEGACY_SETTLED
import com.unifiedledger.application.ImportCompleteness
import com.unifiedledger.application.ImportContentFingerprint
import com.unifiedledger.application.ImportFundingState
import com.unifiedledger.application.ImportIntakeRequest
import com.unifiedledger.application.ImportIntakeResult
import com.unifiedledger.application.ImportRecordKind
import com.unifiedledger.application.ImportRequestId
import com.unifiedledger.application.ImportRequestIdentity
import com.unifiedledger.application.ImportSourceFacts
import com.unifiedledger.application.ManualTransferTransactionFactory
import com.unifiedledger.application.P408MatchCandidate
import com.unifiedledger.application.P801AccountPostingFactsResult
import com.unifiedledger.application.P801AccountReconciliationPageResult
import com.unifiedledger.application.P801AccountReconciliationQueueRow
import com.unifiedledger.application.P801AccountReconciliationStatusTotal
import com.unifiedledger.application.P801AccountReconciliationSummaryResult
import com.unifiedledger.application.P801BindEvidenceRequest
import com.unifiedledger.application.P801BindEvidenceResult
import com.unifiedledger.application.P801CandidateProposalResult
import com.unifiedledger.application.P801ConfirmLinkInput
import com.unifiedledger.application.P801ConfirmLinkResult
import com.unifiedledger.application.P801ReconciledLegAuditResult
import com.unifiedledger.application.P801ReconciliationCandidateEvidence
import com.unifiedledger.application.UuidV7ConfirmedManualTransferIdSource
import com.unifiedledger.application.UuidV7Generator
import com.unifiedledger.application.UuidV7ImportIntakeIdSource
import com.unifiedledger.application.UuidV7ManualTransferRequestIdSource
import com.unifiedledger.data.AndroidLedgerDatabaseHandle
import com.unifiedledger.data.createAndroidLedgerDatabase
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.DomainViolation
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.PostingId
import com.unifiedledger.ui.ImportFilePickResultChannel
import java.io.File
import java.security.SecureRandom
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * P8-01a V-10 device-proof fixture (spec sections 4 V-10 / 6): the reconciliation chain seeded
 * through the PRODUCTION composition root only.
 *
 * WHY THIS FILE CARRIES NO TEST ANNOTATION: `tools/python/android_scale/inventory.py` enumerates
 * exactly one class per file (the FIRST `class` keyword) and skips every file without a test
 * annotation, so the four device cases live in their own file and this helper never enters
 * `tools/ci/android-instrumented-inventory.txt`.
 *
 * WHAT IS REAL HERE. [open] is the production entry [openAndroidStableStorageLedger] over the real
 * Android SQLite driver; the second asset account is a real catalog command; the transfer is the
 * real claim-first commit chain (`CommitOnceInvocationTrackerTransfer` + the product factory); the
 * import evidence is the real intake (source + evidence + candidate in ONE transaction, which is
 * what `ConfirmReconciliationLink` requires); and bind / propose / confirm are the graph's own
 * production use cases. No row is inserted with SQL and the handle's driver is never reached: every
 * write goes through the same ports the product uses.
 *
 * TEST INDEPENDENCE. The production entry resolves a FIXED relative database name, so a shared
 * device ledger would let one case see another's rows. Each fixture therefore owns a redirected
 * root (the `ContextWrapper.getDatabasePath` shape the P706-A06 harness introduced) under the app
 * cache dir: its own fresh ledger at the production path over that root, and [close] deletes the
 * whole root. [p801RedirectedContext] asserts the redirection is in effect, so the suite can never
 * touch the real production `databases/` tree.
 */
internal object P801ChainFacts {
    /** The product's fixed single-ledger identity (`App.kt` `PRODUCT_LEDGER_ID`). */
    val ledgerId: LedgerId = LedgerId(PRODUCT_LEDGER_ID)

    /** The product's managed currency (`CATALOG_MANAGED_CURRENCY`), also the seeded accounts' own. */
    val currency = CATALOG_MANAGED_CURRENCY

    /**
     * The one frozen instant the transfer and its evidence share. `Instant.toString()` is what the
     * transfer commit port persists as `transaction_version.occurred_at`
     * (`SqlDelightConfirmedManualTransferCommitPort`), so the evidence's source text below is
     * byte-identical to the posting's: the frozen matcher requires the same temporal SHAPE and a
     * natural-day distance within `P408Matcher.DEFAULT_WINDOW_DAYS` (2), and this is distance 0.
     */
    val occurredAt: Instant = Instant.parse("2026-03-05T00:00:00Z")

    /** The exact persisted time text both sides carry (`Instant.parse(...).toString()`). */
    const val OCCURRED_AT_TEXT: String = "2026-03-05T00:00:00Z"

    /** The transfer's destination credit, in exact minor units (100.00 CNY). */
    const val TRANSFER_MINOR: Long = 10_000L

    /** The seeded account's name; `createManagedAccount` normalizes it and it must be unique. */
    const val ACCOUNT_NAME: String = "对账链资产账户"

    /** The transfer note, also the queue row's label. */
    const val NOTE: String = "对账链合成转账"

    /** The import pick-session handle the intake stores; the audit read reports it back. */
    const val INPUT_REF: String = "p801-v10-chain-input"

    /** The direction token of the DESTINATION leg (a positive posting is "in"). */
    const val DIRECTION_IN: String = "in"

    /** The frozen settled token a VALID_COMPLETE intake row requires. */
    const val STATUS_TOKEN: String = "settled"

    /** The cached-redirect root name prefix, so no other suite's root can collide. */
    const val ROOT_PREFIX: String = "p801-v10-chain-"
}

/** One seeded chain: the opened graph, its captured handle and the ids the four cases assert on. */
internal class P801ChainFixture(
    testName: String,
) : AutoCloseable {
    private val target: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root: File = File(target.cacheDir, P801ChainFacts.ROOT_PREFIX + testName)
    private val context: Context = p801RedirectedContext(root, target)

    lateinit var graph: CloseableLedgerGraph
        private set

    lateinit var handle: AndroidLedgerDatabaseHandle
        private set

    /**
     * Opens the production graph. [openDriver] is the documented injectable seam on
     * [openAndroidStableStorageLedger]: the production default is called unchanged, and the handle
     * it returns is captured here (the driver itself stays private to that handle).
     */
    fun open() {
        graph =
            openAndroidStableStorageLedger(
                context = context,
                importFilePickPort = silentImportPort(),
                importPickChannel = ImportFilePickResultChannel(),
                openDriver = { name -> createAndroidLedgerDatabase(context, name).also { handle = it } },
            )
    }

    /** Closes the graph (which closes the driver) and removes this case's whole ledger root. */
    override fun close() {
        if (::graph.isInitialized) graph.close()
        root.deleteRecursively()
    }

    // ------------------------------------------------------------------ seeding (production ports)

    /**
     * The second owned real ASSET account. The default catalog seed holds exactly ONE owned asset
     * account (`defaultCatalogSeed`), so a second one is required before a transfer can have two
     * real-account legs. `expectedCatalogVersion = 1` is the version the bootstrap leaves behind.
     */
    fun createSecondAccount(): AccountId {
        val commands =
            checkNotNull(graph.catalogCommands) { "the production graph must wire the catalog commands" }
        val receipt =
            when (
                val result =
                    commands.createAccount(
                        ledgerId = P801ChainFacts.ledgerId,
                        name = P801ChainFacts.ACCOUNT_NAME,
                        kind = AccountKind.ASSET,
                        expectedCatalogVersion = 1L,
                    )
            ) {
                is CatalogCommandResult.Accepted -> result.receipt
                else -> throw AssertionError("the second asset account must be created, got $result")
            }
        return checkNotNull(receipt.createdManageableAccountId) {
            "an accepted CreateAccount must name the manageable account it created"
        }
    }

    /**
     * One confirmed `ACCOUNT_TRANSFER` with two legs, built exactly as `App.kt` builds the product
     * chain: the invocation tracker over the handle's commit port, the real id sources, and the
     * catalog-admission wrapper whose delegate reloads the current catalog per write. Fee is zero,
     * so the domain's pure-principal two-leg factory runs.
     */
    fun createConfirmedTransfer(destinationAccountId: AccountId) {
        val store = handle.catalogStore
        val delegate =
            ConfirmedTransferTransactionFactory { request, ids ->
                val catalog =
                    store.loadCurrent(request.ledgerId)
                        ?: return@ConfirmedTransferTransactionFactory DomainResult.Failure(DomainViolation.InvalidCatalog)
                ManualTransferTransactionFactory(catalog).create(request, ids)
            }
        val generator = UuidV7Generator(::p801SecureRandomBytes)
        val execute =
            ExecuteConfirmedManualTransfer(
                CommitOnceInvocationTrackerTransfer(handle.transferCommitPort),
                UuidV7ConfirmedManualTransferIdSource(generator),
                CatalogAdmissionTransferTransactionFactory(admissionReader = store, delegate = delegate),
            )
        val result =
            execute.execute(
                ExplicitlyConfirmedManualTransfer(
                    ledgerId = P801ChainFacts.ledgerId,
                    requestId = UuidV7ManualTransferRequestIdSource(generator).next(),
                    sourceAccountId = AccountId(DEFAULT_MANAGEABLE_ACCOUNT_ID),
                    destinationAccountId = destinationAccountId,
                    destinationCredit = Money.ofMinor(P801ChainFacts.TRANSFER_MINOR, P801ChainFacts.currency),
                    fee = Money.ofMinor(0L, P801ChainFacts.currency),
                    feeCategoryId = null,
                    occurredAt = P801ChainFacts.occurredAt,
                    note = P801ChainFacts.NOTE,
                    tagIds = emptySet(),
                    merchantId = null,
                    createdAt = P801ChainFacts.occurredAt,
                    confirmation = ExplicitManualSave,
                ),
            )
        check(result is ConfirmedManualTransferResult.Created) { "the seeded transfer must be created, got $result" }
    }

    /**
     * One fully resolved import evidence row (its own request, source, evidence and candidate) via
     * the production intake, which writes all of them in one transaction. The facts are the frozen
     * five-field funding identity of the transfer's DESTINATION leg: `in`, the same minor units, the
     * same currency and precision, and a source time whose text shape and natural day match.
     */
    fun createImportEvidence(): String {
        val execute =
            ExecuteImportIntake(
                commitPort = handle.importSpineStore,
                idSource = UuidV7ImportIntakeIdSource(UuidV7Generator(::p801SecureRandomBytes)),
                fingerprint = ImportContentFingerprint(),
            )
        val result =
            execute.execute(
                ImportIntakeRequest(
                    identity =
                        ImportRequestIdentity(
                            P801ChainFacts.ledgerId,
                            ImportRequestId("p801-v10-chain-intake-request"),
                        ),
                    inputRef = P801ChainFacts.INPUT_REF,
                    recordOrdinal = 0,
                    recordKind = ImportRecordKind.ORDINARY_FLOW_SOURCE,
                    facts =
                        ImportSourceFacts(
                            amountMinor = P801ChainFacts.TRANSFER_MINOR,
                            currencyCode = P801ChainFacts.currency.code,
                            currencyPrecision = P801ChainFacts.currency.precision,
                            occurredAt = P801ChainFacts.OCCURRED_AT_TEXT,
                            directionToken = P801ChainFacts.DIRECTION_IN,
                            statusToken = P801ChainFacts.STATUS_TOKEN,
                            fundingState = ImportFundingState.SETTLED,
                            fundingRuleId = IMPORT_FUNDING_RULE_LEGACY_SETTLED,
                            fundingRuleVersion = 1,
                        ),
                    completeness = ImportCompleteness.VALID_COMPLETE,
                    candidateGeneratedAt = P801ChainFacts.OCCURRED_AT_TEXT,
                ),
            )
        val receipt =
            when (result) {
                is ImportIntakeResult.Accepted -> result.receipt
                else -> throw AssertionError("the seeded intake must be accepted, got $result")
            }
        return checkNotNull(receipt.evidenceId) { "an accepted intake must name its evidence" }.value
    }

    // ------------------------------------------------------------------ the production chain reads

    /** The account queue page rows straight from the graph's own reconciliation read face. */
    fun queueRows(accountId: AccountId): List<P801AccountReconciliationQueueRow> =
        when (val result = readPort().loadAccountReconciliationPage(P801ChainFacts.ledgerId, accountId, after = null)) {
            is P801AccountReconciliationPageResult.Success -> result.page.rows
            is P801AccountReconciliationPageResult.Failed ->
                throw AssertionError("the queue read must answer, got ${result.failure}")
        }

    /** The account summary totals, the same read face (V-1's second half). */
    fun summaryTotals(accountId: AccountId): List<P801AccountReconciliationStatusTotal> =
        when (val result = readPort().loadAccountReconciliationSummary(P801ChainFacts.ledgerId, accountId)) {
            is P801AccountReconciliationSummaryResult.Success -> result.totals
            is P801AccountReconciliationSummaryResult.Failed ->
                throw AssertionError("the summary read must answer, got ${result.failure}")
        }

    /** The graph's production bind use case (the ONE explicit "this evidence belongs here" write). */
    fun bind(
        evidenceId: String,
        accountId: AccountId,
    ): P801BindEvidenceResult =
        checkNotNull(graph.bindReconciliationEvidence) { "the production graph must wire the bind use case" }
            .bind(
                P801BindEvidenceRequest(
                    ledgerId = P801ChainFacts.ledgerId,
                    evidenceId = evidenceId,
                    targetAccountId = accountId,
                    materializedAt = P801ChainFacts.occurredAt,
                ),
            )

    /** The graph's production candidate proposal over the frozen matcher. */
    fun propose(leg: P801AccountReconciliationQueueRow): P801CandidateProposalResult =
        checkNotNull(graph.proposeReconciliationCandidates) { "the production graph must wire the proposal use case" }
            .proposeForAccountLeg(P801ChainFacts.ledgerId, leg)

    /** The graph's production confirm use case over the existing reconciliation commit port. */
    fun confirm(
        evidence: P801ReconciliationCandidateEvidence,
        candidate: P408MatchCandidate,
    ): P801ConfirmLinkResult =
        checkNotNull(graph.confirmReconciliationLink) { "the production graph must wire the confirm use case" }
            .confirm(P801ConfirmLinkInput(P801ChainFacts.ledgerId, evidence, candidate))

    /** The audit read-back of one leg (spec section 2.6). */
    fun audit(postingId: PostingId): P801ReconciledLegAuditResult = readPort().loadReconciledLegAudit(P801ChainFacts.ledgerId, postingId)

    // ------------------------------------------------------------------ the "no accounting moved" face

    /**
     * The formal row counts a reconciliation write must never move: transactions, versions, posting
     * sets and postings, plus the effective-transaction count the report aggregates are built on
     * (`effectiveTransactionCount` is the Analysis/monthly report's own count read).
     */
    fun formalCounts(): List<Long> {
        val queries = handle.database.ledgerQueries
        return listOf(
            queries.countTransactions().executeAsOne(),
            queries.countVersions().executeAsOne(),
            queries.countPostingSets().executeAsOne(),
            queries.countPostings().executeAsOne(),
            queries.effectiveTransactionCount(P801ChainFacts.ledgerId.value).executeAsOne(),
        )
    }

    /** Every current balance sum of the ledger, as one comparable string (the balance face). */
    fun balanceFingerprint(): String =
        handle.database.ledgerQueries
            .currentAccountBalanceSums(P801ChainFacts.ledgerId.value)
            .executeAsList()
            .joinToString("|") { row ->
                "${row.account_id}:${row.currency_code}:${row.currency_precision}:${row.total_minor}"
            }

    /**
     * The exact posting set of [accountIds] as one comparable string: every `ACCOUNT_TRANSFER` leg
     * fact the reconciliation read reports (id, transaction, amount, currency, precision, direction,
     * account, persisted time text, current/eligible flags). A row COUNT cannot see an amended value,
     * so the zero-accounting-effect assertions compare this string before and after.
     */
    fun postingFingerprint(vararg accountIds: AccountId): String =
        accountIds.joinToString("||") { accountId ->
            val windowFrom = P801ChainFacts.occurredAt - 1.days
            val windowTo = P801ChainFacts.occurredAt + 1.days
            when (val result = readPort().loadAccountPostingFacts(P801ChainFacts.ledgerId, accountId, windowFrom, windowTo)) {
                is P801AccountPostingFactsResult.Success ->
                    result.facts.joinToString("|") { fact ->
                        "${fact.postingId}:${fact.transactionId}:${fact.amountMinor}:${fact.currencyCode}:" +
                            "${fact.currencyPrecision}:${fact.direction}:${fact.accountId}:" +
                            "${fact.occurredAt.rawText}:${fact.current}:${fact.eligibleRealAccount}"
                    }
                is P801AccountPostingFactsResult.Failed ->
                    throw AssertionError("the posting-facts read must answer, got ${result.failure}")
            }
        }

    private fun readPort() = checkNotNull(graph.accountReconciliationRead) { "the production graph must wire the reconciliation read face" }

    /** The silent SAF port the open sequence requires; no case in this suite launches a picker. */
    private fun silentImportPort(): AndroidImportFilePickPort<Uri> =
        AndroidImportFilePickPort<Uri>(
            launchOpenDocument = {},
            resolveMetadata = { PickedSafFileMetadata(displayName = "", sizeBytes = null) },
            openInputStream = { null },
            onResult = {},
        )
}

/**
 * The redirected context: RELATIVE database names resolve under [root], ABSOLUTE names pass through
 * unchanged (the generation driver opens an absolute main file), and the guard proves the redirection
 * is in effect so this suite can never resolve the real production ledger.
 */
internal fun p801RedirectedContext(
    root: File,
    target: Context,
): Context {
    val wrapper =
        object : ContextWrapper(target) {
            override fun getDatabasePath(name: String): File = if (File(name).isAbsolute) File(name) else File(root, name)
        }
    // "ledger.db" is the production entry's fixed relative name (App.kt LEGACY_ANDROID_DATABASE_NAME,
    // private there): probing that exact name is what proves the production path is redirected.
    val effective = wrapper.getDatabasePath("ledger.db").absolutePath
    val production = target.getDatabasePath("ledger.db").absolutePath
    if (effective == production) throw AssertionError("the suite must not resolve the production database path")
    if (!effective.startsWith(root.absolutePath)) {
        throw AssertionError("the effective database path must be inside this case's root: $effective")
    }
    return wrapper
}

private val p801SecureRandom = SecureRandom()

/** The platform secure-random provider the UUIDv7 id sources need (the composition-root shape). */
private fun p801SecureRandomBytes(count: Int): ByteArray = ByteArray(count).also(p801SecureRandom::nextBytes)
