package com.unifiedledger.application.import.selfentry

import com.unifiedledger.application.IMPORT_FUNDING_RULE_LEGACY_SETTLED
import com.unifiedledger.application.ImportCompleteness
import com.unifiedledger.application.ImportFundingState
import com.unifiedledger.application.ImportRecordKind
import com.unifiedledger.application.ImportSourceFacts
import com.unifiedledger.application.import.wechat.BoundedXlsxReader
import java.io.ByteArrayInputStream
import java.io.IOException
import java.math.BigDecimal
import java.time.DateTimeException
import java.time.LocalDateTime
import java.util.zip.ZipInputStream

/**
 * P8-04 i2 self-entry XLSX parser (spec `docs/specs/2026-11-14-p8-04-self-entry-import-design.md`,
 * section 4 i2 + section 3.1 D-16 schedule; D-9/D-5/D-10/D-13/D-18 are the settled row semantics).
 *
 * Deterministic pure function over an in-memory byte stream: no file/network I/O, no Clock,
 * no randomness, no path dependencies. Input = xlsx bytes + opaque synthetic input ref; output =
 * per-row Accepted(facts + match inputs)/Rejected(diagnostic) results plus the batch outcome.
 * The container face is the frozen `WechatBillParser` shape over the same [BoundedXlsxReader]
 * minimal reader (D-12: zero new dependencies; `poi-ooxml` stays jvmTest-only).
 *
 * HEADER CONTRACT (D-14/D-16): row 1's 9 cells are byte-compared in column order against
 * [SelfEntryTemplateGenerator.HEADERS] — the generator's public constants are the single source
 * (this file never hand-copies a header string). Any drift (missing column, extra column,
 * reordered column, rewritten text, wrong cell kind) is a whole-batch rejection with the frozen
 * `STRUCTURE_MISMATCH` header shape — the exact `AlipayCsvParser`/`WechatBillParser` header-reject
 * form the orchestrator maps to `ParserRejected` (`AlipayCsvParser.kt:43`, `WechatBillParser.kt:92-101`).
 * A contract change takes the D-14 path (new format id), never a tolerated drift.
 *
 * DATA ROWS (D-16 schedule; sheet row N >= 2 = record ordinal N-2, the 3 example rows of a fresh
 * template included — the user deletes or edits them):
 * - 类型 (col A): `支出|收入|转账` byte match; missing or unknown -> row-level reject
 *   `REQUIRED_FACT_MISSING` (fieldRole=type; the recordKind cannot stand, a required source fact).
 * - 日期时间 (col B): STRING/inlineStr cells only, text `yyyy-MM-dd HH:mm:ss`. A NUMERIC (or any
 *   other) cell kind is a row-level reject `SPINE_SELF_ENTRY_DATETIME_NOT_TEXTUAL` — D-16.2 rules
 *   out the 1900-serial conversion on purpose (no float, no date-system ambiguity; the wrong-kind
 *   mistake must be visible, never silently converted). A wrong-shape or calendar-invalid text is
 *   the frozen `FIELD_TIME_INVALID` reject. Parsing mirrors `AlipayCsvParser.parseTime`
 *   (`AlipayCsvParser.kt:577-586`): strict shape regex + `LocalDateTime.parse` calendar validity,
 *   `T`-joined ISO local text + the fixed Asia/Shanghai civil offset `+08:00`
 *   (`AlipaySourceTokens.UTC_OFFSET` precedent; no timezone is ever read from the file).
 * - 金额 (col C): STRING or NUMERIC both legal — the reader's cached `<v>` rawValue text is taken
 *   verbatim and parsed as [BigDecimal] text (AGENTS: money is exact; no float/double anywhere in
 *   this file). SIGN CONVENTION: the frozen bill parsers carry UNSIGNED magnitudes with direction
 *   from the type/direction column (`AlipayCsvParser.AMOUNT_SHAPE` = `\d+\.\d{2}` at
 *   `AlipayCsvParser.kt:626`; `WechatBillParser.AMOUNT_DECIMAL` = `\d+(\.\d+)?` at
 *   `WechatBillParser.kt:345`), and the template's own example rows (generator lines 104-109) show
 *   positive amounts on 支出/收入/转账 alike — so the round-trip-valid reading is: the 金额 cell is
 *   an unsigned magnitude, its fractional part beyond the currency precision is the
 *   `SELF_ENTRY_AMOUNT_PRECISION` reject, and the only sign form a magnitude can take is an
 *   explicit contradiction: a `-`-prefixed magnitude on a directed row contradicts its 类型
 *   (支出 claims income) — the D-9 row reject `SELF_ENTRY_DIRECTION_CONFLICT` (fieldRole=amount);
 *   on an undirected 转账 row there is no direction to contradict, so the negative magnitude fails
 *   the magnitude domain as the frozen `FIELD_AMOUNT_INVALID` (the `WechatBillParser` "negative
 *   values are out of the frozen domain" face). Either way the value is rejected — never a silent
 *   re-sign. Overflow beyond `Long` minor units is the frozen `FIELD_AMOUNT_INVALID` reject.
 * - 币种 (col D): blank -> the ledger-default CNY inference, made visible by a
 *   `REQUIRED_FACT_UNRESOLVED` incomplete diagnostic (fieldRole=currency) so the candidate detail
 *   can carry the D-5 explanation; non-blank `CNY` -> normal; non-blank non-CNY -> D-5's row-level
 *   `VALID_INCOMPLETE` outcome: the row is emitted as a candidate (never rejected, never silently
 *   re-denominated) with the raw currency text carried in the facts so the explanation can name it.
 *   The incomplete outcome rides the EXISTING spine mechanism: `ImportCompleteness.VALID_INCOMPLETE`
 *   facts intake normally and the candidate surfaces through the review explanation slot
 *   (`P503ImportReview.kt:713/:814` form) — the `ImportIntakeRecordSummary` disposition family has
 *   no per-row incomplete member and needs none.
 * - 一级/二级分类 + 账户1/账户2 (cols E-H): text passthrough as MATCH INPUTS for the i3 name
 *   matching — they are deliberately NOT parsed into ids and NOT placed in `ImportSourceFacts`
 *   (D-016 three-way attribution: source facts carry only the parsed facts). CARRIER DECISION:
 *   they travel on [SelfEntryMatchInputs] of this parser's row result. The fingerprint
 *   (`ImportContentFingerprint.digest(recordKind, facts, paymentProfile)`) has no match-input
 *   members by design, so replay equivalence stays fact-only and deterministic; the match inputs
 *   must therefore stay OUT of the facts (putting them in the facts' free slots would both change
 *   the digest domain and fake a parsed fact). Like the note, they are session-carried data: they
 *   die at the orchestrator boundary today and i3 extends the carry path for the review surface.
 *   转账 semantics (D-4/D-18): blank 账户2 -> `TRANSFER_FLOW_SOURCE_MISSING_LEG` +
 *   `fundingState = NO_FUNDS`; filled 账户2 -> `TRANSFER_FLOW_SOURCE` + `SETTLED`; blank 账户1 on a
 *   转账 row adds a `REQUIRED_FACT_UNRESOLVED` incomplete diagnostic (fieldRole=account_primary,
 *   D-4's "缺资金腿" explanation) without moving the kind or funding state. 账户1 is the template's
 *   frozen source leg (generator KDoc line 74), so a transfer row's direction token derives
 *   structurally as `out` (money leaves 账户1) — the `AlipaySourceTokens.YUEBAO_SUBTYPE_DIRECTION_MAP`
 *   FROM-leg precedent (`AlipaySourceTokens.kt:98-107`), not a guess.
 * - 备注 (col I): carried on [SelfEntryRowResult.Accepted.noteText] ONLY —
 *   `ImportIntakeRecordSummary` (`ImportFileIntake.kt:140-147`) has no display slot, so the note
 *   rides the parser's row record that i3's presentation reads; the orchestrator's dispatch drops
 *   it today and it is never persisted (D-7: `import_source_record` has no note column).
 *
 * FUNDING FACTS PIN (D-18, review F-3): every accepted row carries explicit funding facts —
 * `fundingState` as pinned above (`SETTLED` full transfer / ordinary, `NO_FUNDS` missing-账户2
 * transfer — deliberately diverging from WeChat, whose bill-side missing-leg rows keep `SETTLED`,
 * because a self-declared blank 账户2 means the funding leg itself is unnameable) — with
 * `statusToken = "settled"` and the frozen legacy relay (`IMPORT_FUNDING_RULE_LEGACY_SETTLED`,
 * version 1): self-entry has no approved funding-state provider contract, so both parsers'
 * D-105 relay form is mirrored; the store's duplicate-family branch reads the state, not the rule
 * id. No field is ever left to a silent default (`ImportSpine.kt:128-131`).
 *
 * BATCH SHAPE (D-10, both granularities frozen): empty bytes / over-size / OLE2 / missing parts /
 * reader failure -> typed batch rejection (the `WechatBillParser` container face); header drift ->
 * batch rejection; L0/L2 row-count bounds here are reader-safety margins only — the L2 accepted-
 * record bound is the orchestrator's job (`AlipaySourceTokens.MAX_DATA_ROWS` = 20_000 stays above
 * L2's 10_000 so V-7's 10_001 case reaches `BatchExceedsLimit`); row-level: the three mandatory
 * facts (类型/日期时间/金额) missing or unparseable reject the row, empty rows skip without
 * renumbering (`AlipayCsvParser.kt:51` precedent). A header-only file returns zero records and
 * folds to the orchestrator's empty-Accepted addendum (`ImportFileIntake.kt:209-227`).
 *
 * ROUND-TRIP INVARIANT (V-1, i2 half): this parser reads [SelfEntryTemplateGenerator] output back
 * through the same reader face and reuses its header constants; the generator must not rename a
 * header unilaterally (D-14 versioning path) and this parser must not hand-copy one.
 */
object SelfEntryXlsxParser {
    fun parse(
        inputRef: String,
        bytes: ByteArray,
    ): SelfEntryBatchResult {
        if (bytes.size > SelfEntrySourceTokens.MAX_INPUT_BYTES) {
            return rejected(SelfEntryDiagnostics.unsafeOrOverLimit(inputRef))
        }
        containerViolation(inputRef, bytes)?.let { return rejected(it) }
        return try {
            parseWorkbook(inputRef, BoundedXlsxReader.read(bytes))
        } catch (failure: Exception) {
            classifiedOpenFailure(inputRef, failure)
        }
    }

    // Container-level checks run before the reader opens the package — the frozen
    // `WechatBillParser.containerViolation` face (empty, OLE2, non-zip, vbaProject,
    // missing parts); the bounded reader's inflate-ratio guard stays untouched.
    private fun containerViolation(
        inputRef: String,
        bytes: ByteArray,
    ): SelfEntryDiagnostic? {
        if (bytes.isEmpty()) return SelfEntryDiagnostics.decodeFailed(inputRef)
        if (isOle2Container(bytes)) return SelfEntryDiagnostics.unsupportedInput(inputRef)
        if (!looksLikeZipContainer(bytes)) return SelfEntryDiagnostics.decodeFailed(inputRef)
        var hasContentTypes = false
        var hasWorkbookXml = false
        var hasVbaProject = false
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    when (entry.name) {
                        "[Content_Types].xml" -> hasContentTypes = true
                        "xl/workbook.xml" -> hasWorkbookXml = true
                        "xl/vbaProject.bin" -> hasVbaProject = true
                    }
                    zip.closeEntry()
                }
            }
        } catch (failure: IOException) {
            return SelfEntryDiagnostics.decodeFailed(inputRef)
        }
        if (hasVbaProject) return SelfEntryDiagnostics.unsafeOrOverLimitContainer(inputRef)
        if (!hasContentTypes || !hasWorkbookXml) return SelfEntryDiagnostics.unsupportedInput(inputRef)
        return null
    }

    private fun parseWorkbook(
        inputRef: String,
        workbook: BoundedXlsxReader.Workbook,
    ): SelfEntryBatchResult {
        if (workbook.numberOfSheets == 0) {
            return rejected(SelfEntryDiagnostics.structureMismatchHeader(inputRef))
        }
        val sheet = workbook.firstSheet
        val headerRow =
            sheet.getRow(SelfEntrySourceTokens.HEADER_ROW_INDEX)
                ?: return rejected(SelfEntryDiagnostics.structureMismatchHeader(inputRef))
        if (headerRow.lastCellNum != SelfEntryTemplateGenerator.HEADERS.size) {
            return rejected(SelfEntryDiagnostics.structureMismatchHeader(inputRef))
        }
        // D-14: byte-exact 9-string match in column order against the generator's constants.
        // Any drift — missing, extra, reordered, rewritten text, or a non-text cell kind —
        // is a whole-batch rejection, never a tolerated shape.
        SelfEntryTemplateGenerator.HEADERS.forEachIndexed { index, expected ->
            val cell =
                headerRow.getCell(index)
                    ?: return rejected(SelfEntryDiagnostics.structureMismatchHeader(inputRef))
            if (cell.type != BoundedXlsxReader.CellType.STRING) {
                return rejected(SelfEntryDiagnostics.structureMismatchHeader(inputRef))
            }
            if (cell.string != expected) {
                return rejected(SelfEntryDiagnostics.structureMismatchHeader(inputRef))
            }
        }
        if (sheet.lastRowNum >= SelfEntrySourceTokens.FIRST_DATA_ROW_INDEX &&
            sheet.lastRowNum - SelfEntrySourceTokens.FIRST_DATA_ROW_INDEX + 1 > SelfEntrySourceTokens.MAX_DATA_ROWS
        ) {
            return rejected(SelfEntryDiagnostics.unsafeOrOverLimit(inputRef))
        }
        val rows = mutableListOf<SelfEntryRowResult>()
        if (sheet.lastRowNum >= SelfEntrySourceTokens.FIRST_DATA_ROW_INDEX) {
            for (rowIndex in SelfEntrySourceTokens.FIRST_DATA_ROW_INDEX..sheet.lastRowNum) {
                val ordinal = rowIndex - SelfEntrySourceTokens.FIRST_DATA_ROW_INDEX
                val row = sheet.getRow(rowIndex) ?: continue // fully empty row: no record, no renumbering
                if (row.lastCellNum <= 0) continue // all-empty row: no record, no renumbering
                if (row.lastCellNum > SelfEntryTemplateGenerator.HEADERS.size) {
                    rows +=
                        SelfEntryRowResult.Rejected(
                            ordinal,
                            listOf(SelfEntryDiagnostics.structureMismatchRecord(inputRef, ordinal)),
                        )
                    continue
                }
                rows += parseDataRow(inputRef, ordinal, row)
            }
        }
        val diagnostics = rows.flatMap { it.diagnostics }
        val outcome = if (diagnostics.isEmpty()) SelfEntryBatchOutcome.COMPLETE else SelfEntryBatchOutcome.PARTIAL
        return SelfEntryBatchResult(outcome, rows, null)
    }

    // D-16 judgment order, deterministic per row: (1) 类型 byte match, (2) 日期时间 cell kind
    // then shape, (3) 金额 missing/shape/precision/conflict, (4) 币种 D-5 branch, (5) transfer
    // leg composition (kind + D-18 funding pin + D-4 account diagnostics) or ordinary direction.
    // Every step short-circuits; rejected rows carry exactly one diagnostic (zero write).
    private fun parseDataRow(
        inputRef: String,
        ordinal: Int,
        row: BoundedXlsxReader.Row,
    ): SelfEntryRowResult {
        val typeToken = textOf(row.getCell(SelfEntrySourceTokens.TYPE_COLUMN))
        // D-16.1: the three-value domain 支出|收入|转账 is accepted here; ONLY a missing or
        // unknown token rejects (the recordKind cannot stand). 转账 must flow through to the
        // transfer branch below — an earlier draft's else-arm rejected it here, which broke
        // the D-17 example row round trip.
        if (typeToken != SelfEntrySourceTokens.TYPE_EXPENSE &&
            typeToken != SelfEntrySourceTokens.TYPE_INCOME &&
            typeToken != SelfEntrySourceTokens.TYPE_TRANSFER
        ) {
            return SelfEntryRowResult.Rejected(
                ordinal,
                listOf(SelfEntryDiagnostics.requiredFactMissing(inputRef, ordinal, SelfEntrySourceTokens.FIELD_ROLE_TYPE)),
            )
        }
        val directionOfOrdinary =
            if (typeToken == SelfEntrySourceTokens.TYPE_EXPENSE) {
                SelfEntrySourceTokens.DIRECTION_OUT
            } else {
                SelfEntrySourceTokens.DIRECTION_IN
            }
        val occurredAtCell = row.getCell(SelfEntrySourceTokens.OCCURRED_AT_COLUMN)
        if (occurredAtCell == null || cellText(occurredAtCell).isBlank()) {
            // D-10: a missing mandatory datetime is the 缺格 face.
            return SelfEntryRowResult.Rejected(
                ordinal,
                listOf(SelfEntryDiagnostics.requiredFactMissing(inputRef, ordinal, SelfEntrySourceTokens.FIELD_ROLE_OCCURRED_AT)),
            )
        }
        if (occurredAtCell.type != BoundedXlsxReader.CellType.STRING) {
            // D-16.2: only STRING/inlineStr cells carry the datetime text; a NUMERIC (serial)
            // cell is rejected, never converted through the 1900 epoch.
            return SelfEntryRowResult.Rejected(
                ordinal,
                listOf(SelfEntryDiagnostics.datetimeNotTextual(inputRef, ordinal)),
            )
        }
        val occurredAt =
            parseOccurredAt(cellText(occurredAtCell))
                ?: return SelfEntryRowResult.Rejected(
                    ordinal,
                    listOf(SelfEntryDiagnostics.fieldTimeInvalid(inputRef, ordinal)),
                )
        val amountCell = row.getCell(SelfEntrySourceTokens.AMOUNT_COLUMN)
        val parsedAmount =
            parseAmount(amountCell)
                ?: return SelfEntryRowResult.Rejected(
                    ordinal,
                    listOf(
                        // D-10: a missing mandatory amount (缺格) and an unparseable one are the
                        // same row-level rejection granularity with their distinct registry codes.
                        if (amountCell == null || cellText(amountCell).isBlank()) {
                            SelfEntryDiagnostics.requiredFactMissing(inputRef, ordinal, SelfEntrySourceTokens.FIELD_ROLE_AMOUNT)
                        } else {
                            SelfEntryDiagnostics.fieldAmountInvalid(inputRef, ordinal)
                        },
                    ),
                )
        // D-16.3: the precision gate runs on the cell text's own fraction BEFORE any minor-unit
        // conversion — a 3dp text must surface the precision code, never an overflow face.
        if (parsedAmount.fractionalDigits > SelfEntrySourceTokens.CURRENCY_PRECISION) {
            return SelfEntryRowResult.Rejected(
                ordinal,
                listOf(SelfEntryDiagnostics.amountPrecision(inputRef, ordinal)),
            )
        }
        // Exact minor units at the ledger-default precision; the only possible failure now is
        // Long overflow (the value is already shape-valid and fraction-bounded).
        val amount =
            try {
                parsedAmount.amount.movePointRight(SelfEntrySourceTokens.CURRENCY_PRECISION).longValueExact()
            } catch (failure: ArithmeticException) {
                return SelfEntryRowResult.Rejected(
                    ordinal,
                    listOf(SelfEntryDiagnostics.fieldAmountInvalid(inputRef, ordinal)),
                )
            }

        val diagnostics = mutableListOf<SelfEntryDiagnostic>()
        // D-5 currency: blank infers the ledger default (visible as an unresolved-fact
        // diagnostic for the candidate explanation); non-blank non-CNY rides the incomplete
        // candidate path with the raw text carried for the 币种 X 暂不支持 explanation.
        val currencyCode =
            when (val currencyText = textOf(row.getCell(SelfEntrySourceTokens.CURRENCY_COLUMN))) {
                SelfEntrySourceTokens.CURRENCY_CNY -> SelfEntrySourceTokens.CURRENCY_CNY
                "" -> {
                    diagnostics +=
                        SelfEntryDiagnostics.requiredFactUnresolved(
                            inputRef,
                            ordinal,
                            SelfEntrySourceTokens.FIELD_ROLE_CURRENCY,
                        )
                    SelfEntrySourceTokens.CURRENCY_CNY
                }
                else -> {
                    diagnostics +=
                        SelfEntryDiagnostics.requiredFactUnresolved(
                            inputRef,
                            ordinal,
                            SelfEntrySourceTokens.FIELD_ROLE_CURRENCY,
                        )
                    currencyText
                }
            }

        // D-9 sign gate: the magnitude stays untouched — a contradiction rejects the row,
        // never re-signs it. Magnitudes are unsigned in the frozen bill-parser convention
        // and in the template's own example rows, so the ONLY sign forms a magnitude can
        // carry beyond the convention are negative ones, and EVERY directed row rejects
        // them (支出 claims an income; 收入 claims an expense — a silently absorbed sign
        // would be exactly the re-direction D-9 forbids). A negative magnitude on an
        // undirected 转账 row has no direction to contradict and fails the magnitude
        // domain instead (the WechatBillParser "negative values are out of the frozen
        // domain" face). `amount` is minor units (Long): the sign test is `< 0L`.
        if (amount < 0L) {
            if (typeToken == SelfEntrySourceTokens.TYPE_EXPENSE || typeToken == SelfEntrySourceTokens.TYPE_INCOME) {
                return SelfEntryRowResult.Rejected(
                    ordinal,
                    listOf(SelfEntryDiagnostics.directionConflict(inputRef, ordinal)),
                )
            }
            if (typeToken == SelfEntrySourceTokens.TYPE_TRANSFER) {
                return SelfEntryRowResult.Rejected(
                    ordinal,
                    listOf(SelfEntryDiagnostics.fieldAmountInvalid(inputRef, ordinal)),
                )
            }
        }

        val recordKind: ImportRecordKind
        val directionToken: String
        val fundingState: ImportFundingState
        if (typeToken == SelfEntrySourceTokens.TYPE_TRANSFER) {
            // D-4/D-18: the transfer's legs decide the kind and the pinned funding state.
            val accountSecondary = textOf(row.getCell(SelfEntrySourceTokens.ACCOUNT_SECONDARY_COLUMN))
            if (accountSecondary.isEmpty()) {
                recordKind = ImportRecordKind.TRANSFER_FLOW_SOURCE_MISSING_LEG
                fundingState = ImportFundingState.NO_FUNDS
            } else {
                recordKind = ImportRecordKind.TRANSFER_FLOW_SOURCE
                fundingState = ImportFundingState.SETTLED
            }
            // 账户1 is the template's frozen source leg, so the direction derives structurally.
            directionToken = SelfEntrySourceTokens.DIRECTION_OUT
            if (textOf(row.getCell(SelfEntrySourceTokens.ACCOUNT_PRIMARY_COLUMN)).isEmpty()) {
                diagnostics +=
                    SelfEntryDiagnostics.requiredFactUnresolved(
                        inputRef,
                        ordinal,
                        SelfEntrySourceTokens.FIELD_ROLE_ACCOUNT_PRIMARY,
                    )
            }
        } else {
            recordKind = ImportRecordKind.ORDINARY_FLOW_SOURCE
            directionToken = directionOfOrdinary
            fundingState = ImportFundingState.SETTLED
        }

        val completeness = if (diagnostics.isEmpty()) ImportCompleteness.VALID_COMPLETE else ImportCompleteness.VALID_INCOMPLETE
        val facts =
            ImportSourceFacts(
                amountMinor = amount,
                currencyCode = currencyCode,
                currencyPrecision = SelfEntrySourceTokens.CURRENCY_PRECISION,
                occurredAt = occurredAt,
                directionToken = directionToken,
                statusToken = SelfEntrySourceTokens.STATUS_SETTLED,
                fundingState = fundingState,
                fundingRuleId = IMPORT_FUNDING_RULE_LEGACY_SETTLED,
                fundingRuleVersion = 1,
            )
        val matchInputs =
            SelfEntryMatchInputs(
                categoryPrimary = textOf(row.getCell(SelfEntrySourceTokens.CATEGORY_PRIMARY_COLUMN)),
                categorySecondary = textOf(row.getCell(SelfEntrySourceTokens.CATEGORY_SECONDARY_COLUMN)),
                accountPrimary = textOf(row.getCell(SelfEntrySourceTokens.ACCOUNT_PRIMARY_COLUMN)),
                accountSecondary = textOf(row.getCell(SelfEntrySourceTokens.ACCOUNT_SECONDARY_COLUMN)),
            )
        return SelfEntryRowResult.Accepted(
            recordOrdinal = ordinal,
            recordKind = recordKind,
            facts = facts,
            completeness = completeness,
            diagnostics = diagnostics,
            matchInputs = matchInputs,
            noteText = textOf(row.getCell(SelfEntrySourceTokens.NOTE_COLUMN)),
        )
    }

    private data class ParsedAmount(
        val amount: BigDecimal,
        val fractionalDigits: Int,
        val negative: Boolean,
    )

    // Amount: the cached `<v>` raw text of a STRING or NUMERIC cell, verbatim — never a
    // float. Shape = optional '-' + digits + optional fraction (the magnitude convention
    // documented in the class KDoc); precision = the fraction's digit count, read off the
    // exact text BEFORE any conversion (the caller gates it against the currency precision).
    // The BigDecimal itself is returned unperturbed; the caller converts to exact minor
    // units only after the precision gate.
    private fun parseAmount(cell: BoundedXlsxReader.Cell?): ParsedAmount? {
        val raw =
            when {
                cell == null -> ""
                cell.type == BoundedXlsxReader.CellType.STRING -> cell.string ?: ""
                else -> cell.rawValue ?: ""
            }
        if (!AMOUNT_TEXT_SHAPE.matches(raw)) return null
        val amount =
            try {
                BigDecimal(raw)
            } catch (failure: NumberFormatException) {
                return null
            }
        val fractionalDigits = if (raw.contains('.')) raw.length - raw.indexOf('.') - 1 else 0
        return ParsedAmount(amount, fractionalDigits, amount.signum() < 0)
    }

    // Time: frozen shape yyyy-MM-dd HH:mm:ss, strict calendar validation, the fixed
    // Asia/Shanghai civil offset (+08:00), second granularity, deterministic byte-equal
    // output text (the AlipayCsvParser.parseTime precedent). No Clock, no timezone read
    // from any region of the file.
    private fun parseOccurredAt(text: String): String? {
        if (!OCCURRED_AT_SHAPE.matches(text)) return null
        val isoLocal = text.replace(' ', 'T')
        return try {
            LocalDateTime.parse(isoLocal)
            isoLocal + SelfEntrySourceTokens.ASIA_SHANGHAI_OFFSET
        } catch (failure: DateTimeException) {
            null
        }
    }

    // STRING cells read via the shared string table (or the inline/formula-cached string);
    // every other cell kind via its raw cached value text. Absent cells stay absent and
    // read as empty text — the D-16 "blank" semantics of the six optional columns.
    private fun textOf(cell: BoundedXlsxReader.Cell?): String = cellText(cell)

    private fun cellText(cell: BoundedXlsxReader.Cell?): String =
        when {
            cell == null -> ""
            cell.type == BoundedXlsxReader.CellType.STRING -> cell.string ?: ""
            else -> cell.rawValue ?: ""
        }

    // Reader open failures: the bounded reader's inflate-ratio guard triggers with the
    // frozen `Zip bomb detected!` IOException marker (POI ZipSecureFile default mirror),
    // mapped to INPUT_UNSAFE_OR_OVER_LIMIT with container scope; every other container or
    // structure failure maps to INPUT_DECODE_FAILED (the WechatBillParser.classifiedOpenFailure
    // face; the cause chain is walked so wrapper exceptions do not change the class).
    private fun classifiedOpenFailure(
        inputRef: String,
        failure: Exception,
    ): SelfEntryBatchResult {
        var current: Throwable? = failure
        while (current != null) {
            if (current is IOException) {
                val message = current.message ?: ""
                if (message.contains("zip bomb", ignoreCase = true)) {
                    return rejected(SelfEntryDiagnostics.unsafeOrOverLimitContainer(inputRef))
                }
            }
            current = current.cause
        }
        return rejected(SelfEntryDiagnostics.decodeFailed(inputRef))
    }

    private fun rejected(diagnostic: SelfEntryDiagnostic): SelfEntryBatchResult = SelfEntryBatchResult(SelfEntryBatchOutcome.REJECTED, emptyList(), diagnostic)

    private fun isOle2Container(bytes: ByteArray): Boolean =
        bytes.size >= 8 &&
            bytes[0] == 0xD0.toByte() &&
            bytes[1] == 0xCF.toByte() &&
            bytes[2] == 0x11.toByte() &&
            bytes[3] == 0xE0.toByte() &&
            bytes[4] == 0xA1.toByte() &&
            bytes[5] == 0xB1.toByte() &&
            bytes[6] == 0x1A.toByte() &&
            bytes[7] == 0xE1.toByte()

    private fun looksLikeZipContainer(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 'P'.code.toByte() &&
            bytes[1] == 'K'.code.toByte() &&
            (
                (bytes[2] == 3.toByte() && bytes[3] == 4.toByte()) ||
                    (bytes[2] == 5.toByte() && bytes[3] == 6.toByte()) ||
                    (bytes[2] == 7.toByte() && bytes[3] == 8.toByte())
            )

    private val OCCURRED_AT_SHAPE = Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")
    private val AMOUNT_TEXT_SHAPE = Regex("-?\\d+(\\.\\d+)?")
}

/**
 * P8-04 i2 parse result shapes — the frozen family shape of `AlipayParserTypes`/`WechatParserTypes`.
 *
 * Diagnostics carry the D-097 safe location only: {input_ref, record_ordinal, field_role}; raw
 * cell text, whole rows and file names never appear. The two spec-named codes
 * (`SELF_ENTRY_DIRECTION_CONFLICT`, `SELF_ENTRY_AMOUNT_PRECISION`) and the parser-owned
 * `SPINE_SELF_ENTRY_*` face codes live beside the frozen D-097:1459 registry codes.
 */
enum class SelfEntryBatchOutcome { COMPLETE, PARTIAL, REJECTED }

data class SelfEntryDiagnostic(
    val code: String,
    val severity: String,
    val scope: String,
    val inputRef: String,
    val recordOrdinal: Int?,
    val fieldRole: String?,
)

/**
 * The i3 match inputs of one parsed row (D-16.5-8; the carrier decision is documented on the
 * parser KDoc): verbatim cell texts, never parsed ids, never part of the content fingerprint.
 * The orchestrator drops them at its boundary today; i3 extends the carry path to the review.
 */
data class SelfEntryMatchInputs(
    val categoryPrimary: String,
    val categorySecondary: String,
    val accountPrimary: String,
    val accountSecondary: String,
)

sealed interface SelfEntryRowResult {
    val recordOrdinal: Int
    val diagnostics: List<SelfEntryDiagnostic>

    data class Accepted(
        override val recordOrdinal: Int,
        val recordKind: ImportRecordKind,
        val facts: ImportSourceFacts,
        val completeness: ImportCompleteness,
        override val diagnostics: List<SelfEntryDiagnostic>,
        /** D-16.5-8: the i3 name-match inputs, verbatim texts (see the parser KDoc carrier decision). */
        val matchInputs: SelfEntryMatchInputs,
        /** D-7/D-16.9: display-only; never persisted, dropped at the orchestrator boundary today. */
        val noteText: String,
    ) : SelfEntryRowResult

    /** Rejected rows carry exactly one diagnostic and produce no record (zero write). */
    data class Rejected(
        override val recordOrdinal: Int,
        override val diagnostics: List<SelfEntryDiagnostic>,
    ) : SelfEntryRowResult
}

data class SelfEntryBatchResult(
    val outcome: SelfEntryBatchOutcome,
    val rows: List<SelfEntryRowResult>,
    /** Non-null iff outcome == REJECTED (container/input/structure fatal). */
    val diagnostic: SelfEntryDiagnostic?,
)

object SelfEntryDiagnostics {
    fun unsupportedInput(inputRef: String): SelfEntryDiagnostic = SelfEntryDiagnostic("INPUT_UNSUPPORTED", "fatal", "input", inputRef, null, null)

    fun unsafeOrOverLimit(inputRef: String): SelfEntryDiagnostic = SelfEntryDiagnostic("INPUT_UNSAFE_OR_OVER_LIMIT", "fatal", "input", inputRef, null, null)

    fun unsafeOrOverLimitContainer(inputRef: String): SelfEntryDiagnostic = SelfEntryDiagnostic("INPUT_UNSAFE_OR_OVER_LIMIT", "fatal", "container", inputRef, null, null)

    fun decodeFailed(inputRef: String): SelfEntryDiagnostic = SelfEntryDiagnostic("INPUT_DECODE_FAILED", "fatal", "input", inputRef, null, null)

    fun structureMismatchHeader(inputRef: String): SelfEntryDiagnostic = SelfEntryDiagnostic("STRUCTURE_MISMATCH", "fatal", "structure", inputRef, null, null)

    fun structureMismatchRecord(
        inputRef: String,
        ordinal: Int,
    ): SelfEntryDiagnostic = SelfEntryDiagnostic("STRUCTURE_MISMATCH", "fatal", "record", inputRef, ordinal, null)

    /** D-16.1: the 类型 cell is blank or unknown — the recordKind cannot stand. */
    fun requiredFactMissing(
        inputRef: String,
        ordinal: Int,
        fieldRole: String,
    ): SelfEntryDiagnostic = SelfEntryDiagnostic("REQUIRED_FACT_MISSING", "incomplete", "field", inputRef, ordinal, fieldRole)

    /** The D-5/D-4 incomplete faces (currency inference / missing 资金腿): the row stays a candidate. */
    fun requiredFactUnresolved(
        inputRef: String,
        ordinal: Int,
        fieldRole: String,
    ): SelfEntryDiagnostic = SelfEntryDiagnostic("REQUIRED_FACT_UNRESOLVED", "incomplete", "field", inputRef, ordinal, fieldRole)

    fun fieldAmountInvalid(
        inputRef: String,
        ordinal: Int,
    ): SelfEntryDiagnostic = SelfEntryDiagnostic("FIELD_AMOUNT_INVALID", "record_error", "field", inputRef, ordinal, SelfEntrySourceTokens.FIELD_ROLE_AMOUNT)

    fun fieldTimeInvalid(
        inputRef: String,
        ordinal: Int,
    ): SelfEntryDiagnostic = SelfEntryDiagnostic("FIELD_TIME_INVALID", "record_error", "field", inputRef, ordinal, SelfEntrySourceTokens.FIELD_ROLE_OCCURRED_AT)

    /** D-16.2: a NUMERIC (serial) datetime cell — rejected, never 1900-epoch converted. */
    fun datetimeNotTextual(
        inputRef: String,
        ordinal: Int,
    ): SelfEntryDiagnostic = SelfEntryDiagnostic("SPINE_SELF_ENTRY_DATETIME_NOT_TEXTUAL", "record_error", "field", inputRef, ordinal, SelfEntrySourceTokens.FIELD_ROLE_OCCURRED_AT)

    /** D-9: 类型↔金额符号 contradiction (spec-named code; fieldRole=amount per the ruling). */
    fun directionConflict(
        inputRef: String,
        ordinal: Int,
    ): SelfEntryDiagnostic = SelfEntryDiagnostic("SELF_ENTRY_DIRECTION_CONFLICT", "record_error", "field", inputRef, ordinal, SelfEntrySourceTokens.FIELD_ROLE_AMOUNT)

    /** D-16.3: fractional digits beyond the ledger-default currency precision (spec-named code). */
    fun amountPrecision(
        inputRef: String,
        ordinal: Int,
    ): SelfEntryDiagnostic = SelfEntryDiagnostic("SELF_ENTRY_AMOUNT_PRECISION", "record_error", "field", inputRef, ordinal, SelfEntrySourceTokens.FIELD_ROLE_AMOUNT)
}

/**
 * P8-04 i2 frozen token tables (spec section 3.1 D-16 schedule + D-3/D-5/D-18 pins).
 *
 * The header strings are NOT duplicated here: the 9 header constants live in
 * [SelfEntryTemplateGenerator] (D-16's single constant source) and the parser references them
 * there. Every token below is a frozen contract constant of the self-entry format itself.
 */
object SelfEntrySourceTokens {
    /** The header row is sheet row 1 (0-based index 0); data starts at sheet row 2. */
    const val HEADER_ROW_INDEX: Int = 0
    const val FIRST_DATA_ROW_INDEX: Int = 1

    // D-16.1: the three type tokens, byte-matched; the direction token values are the
    // existing ImportSourceFacts domain ("in"/"out", WechatSourceTokens.DIRECTION_TOKEN_MAP values).
    const val TYPE_EXPENSE: String = "支出"
    const val TYPE_INCOME: String = "收入"
    const val TYPE_TRANSFER: String = "转账"
    const val DIRECTION_IN: String = "in"
    const val DIRECTION_OUT: String = "out"

    /** D-18: every accepted row's status token is the frozen settled value. */
    const val STATUS_SETTLED: String = "settled"

    /** Frozen evidence constants; the offset is the fixed Asia/Shanghai civil offset (D-138 precedent). */
    const val CURRENCY_CNY: String = "CNY"
    const val ASIA_SHANGHAI_OFFSET: String = "+08:00"

    /** The ledger-default currency precision (D-16.3); a constant, never derived per row. */
    const val CURRENCY_PRECISION: Int = 2

    /** Frozen bounded-input constants (the parser-family evidence bound; V-7 keeps L2 at the orchestrator). */
    const val MAX_INPUT_BYTES: Int = 10 * 1024 * 1024
    const val MAX_DATA_ROWS: Int = 20_000

    // 0-based column indices of the frozen 9-column contract (A..I).
    const val TYPE_COLUMN: Int = 0
    const val OCCURRED_AT_COLUMN: Int = 1
    const val AMOUNT_COLUMN: Int = 2
    const val CURRENCY_COLUMN: Int = 3
    const val CATEGORY_PRIMARY_COLUMN: Int = 4
    const val CATEGORY_SECONDARY_COLUMN: Int = 5
    const val ACCOUNT_PRIMARY_COLUMN: Int = 6
    const val ACCOUNT_SECONDARY_COLUMN: Int = 7
    const val NOTE_COLUMN: Int = 8

    // Frozen diagnostic field roles (the D-097 safe-location shape, self-entry's own roles).
    const val FIELD_ROLE_TYPE: String = "type"
    const val FIELD_ROLE_OCCURRED_AT: String = "occurred_at"
    const val FIELD_ROLE_AMOUNT: String = "amount"
    const val FIELD_ROLE_CURRENCY: String = "currency"
    const val FIELD_ROLE_ACCOUNT_PRIMARY: String = "account_primary"
}
