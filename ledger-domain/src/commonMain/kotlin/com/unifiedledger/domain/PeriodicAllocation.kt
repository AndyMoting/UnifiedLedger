package com.unifiedledger.domain

import kotlin.time.Instant

/**
 * D-085 RG-11 `cadence` of `periodic_allocation_schedule.payload`. The frozen contract
 * (`golden/rules/rg-11.json`) only uses `monthly`.
 */
enum class PeriodicAllocationCadence {
    MONTHLY,
}

/**
 * D-085 RG-11 `anchor` of `periodic_allocation_schedule.payload`. `month_end` anchors every
 * installment on the local-calendar last day of its month; `day_of_month` anchors on the given
 * day, restricted to 1..28 so the day exists in every month (design doc, line 28).
 */
sealed interface PeriodicAllocationAnchor {
    data object MonthEnd : PeriodicAllocationAnchor

    data class DayOfMonth(
        val day: Int,
    ) : PeriodicAllocationAnchor
}

/** Derived `allocation_status` of a schedule: `active` or `recognized` (design doc, line 44). */
enum class PeriodicAllocationScheduleStatus {
    ACTIVE,
    RECOGNIZED,
}

/** Derived `allocation_status` of an installment: `pending`, `recognized` or `superseded`. */
enum class PeriodicAllocationInstallmentStatus {
    PENDING,
    RECOGNIZED,
    SUPERSEDED,
}

/**
 * D-085 RG-11 `periodic_allocation_schedule` payload owner: `payment_transaction_id`,
 * `prepaid_account_id`, `category_id`, `total_amount`/`currency`, `cadence`, `start_at` and
 * `anchor`. `allocation_status` is derived and never written into the payload.
 */
data class PeriodicAllocationSchedule(
    val id: String,
    val paymentTransactionId: TransactionId,
    val prepaidAccountId: AccountId,
    val categoryId: CategoryId,
    val totalAmountMinor: Long,
    val currency: CurrencyUnit,
    val cadence: PeriodicAllocationCadence,
    val startAt: Instant,
    val anchor: PeriodicAllocationAnchor,
)

/**
 * D-085 RG-11 `periodic_allocation_revision` payload owner: `schedule_id`, `revision_number`,
 * `recognized_through`, `remaining_amount`/`currency` and exact `installment_ids`. Revisions
 * are append-only: an effective revision is never overwritten and the boundary always comes
 * from the directly previous revision.
 */
data class PeriodicAllocationRevision(
    val id: String,
    val scheduleId: String,
    val revisionNumber: Int,
    val recognizedThrough: String?,
    val remainingAmountMinor: Long,
    val currency: CurrencyUnit,
    val installmentIds: List<String>,
)

/**
 * D-085 RG-11 `periodic_allocation_installment` payload owner: immutable and owns only
 * `schedule_id`, `revision_id`, `sequence`, `scheduled_at`, `amount`/`currency`; recognition
 * results are never written back as payload state (design doc, lines 10-11).
 */
data class PeriodicAllocationInstallment(
    val id: String,
    val scheduleId: String,
    val revisionId: String,
    val sequence: Int,
    val scheduledAt: Instant,
    val amountMinor: Long,
    val currency: CurrencyUnit,
)

/**
 * Constructs a periodic allocation schedule. Invariants (D-085 RG-11, frozen rg-11.json):
 * - identities are non-blank;
 * - total is a positive exact amount in minor units;
 * - currency is one of the supported contract currencies (CNY, precision 2);
 * - cadence is `monthly` (the only frozen value);
 * - a `day_of_month` anchor is inside 1..28 (`invalid_anchor`, design doc line 28).
 */
fun createPeriodicAllocationSchedule(
    id: String,
    paymentTransactionId: TransactionId,
    prepaidAccountId: AccountId,
    categoryId: CategoryId,
    totalAmountMinor: Long,
    currency: CurrencyUnit,
    startAt: Instant,
    anchor: PeriodicAllocationAnchor,
    cadence: PeriodicAllocationCadence = PeriodicAllocationCadence.MONTHLY,
): DomainResult<PeriodicAllocationSchedule> {
    if (
        id.isBlank() ||
        paymentTransactionId.value.isBlank() ||
        prepaidAccountId.value.isBlank() ||
        categoryId.value.isBlank()
    ) {
        return DomainResult.Failure(PeriodicAllocationViolation.IdentityRequired())
    }
    if (totalAmountMinor <= 0L) {
        return DomainResult.Failure(PeriodicAllocationViolation.MustBePositive())
    }
    if (!isSupportedPeriodicAllocationCurrency(currency)) {
        return DomainResult.Failure(PeriodicAllocationViolation.UnsupportedCurrency())
    }
    val validatedAnchor =
        when (val candidate = anchor) {
            PeriodicAllocationAnchor.MonthEnd -> candidate
            is PeriodicAllocationAnchor.DayOfMonth -> {
                if (candidate.day < 1 || candidate.day > 28) {
                    return DomainResult.Failure(PeriodicAllocationViolation.InvalidAnchor())
                }
                candidate
            }
        }
    return DomainResult.Success(
        PeriodicAllocationSchedule(
            id = id,
            paymentTransactionId = paymentTransactionId,
            prepaidAccountId = prepaidAccountId,
            categoryId = categoryId,
            totalAmountMinor = totalAmountMinor,
            currency = currency,
            cadence = cadence,
            startAt = startAt,
            anchor = validatedAnchor,
        ),
    )
}

/**
 * Splits [totalMinor] into [count] installments in exact minor units: the first `count - 1`
 * parts take the integer quotient and the full remainder goes to the last part
 * (100.00 -> 33.33/33.33/33.34, 66.67 -> 22.22/22.22/22.23; design doc lines 28-30).
 * Every installment is at least one minor unit: a split whose integer quotient is zero
 * (`totalMinor < count`) fails with `must_be_positive` (fail-closed hardening; the frozen
 * contract only splits totals that keep every installment positive).
 */
fun splitAmountIntoInstallments(
    totalMinor: Long,
    count: Int,
): DomainResult<List<Long>> {
    if (count < 1) {
        return DomainResult.Failure(
            PeriodicAllocationViolation.InvalidInstallmentCount(),
        )
    }
    if (totalMinor <= 0L) {
        return DomainResult.Failure(PeriodicAllocationViolation.MustBePositive())
    }
    val quotient = totalMinor / count
    if (quotient == 0L) {
        return DomainResult.Failure(PeriodicAllocationViolation.MustBePositive())
    }
    val last = totalMinor - quotient * (count - 1)
    return DomainResult.Success(List(count - 1) { quotient } + last)
}

/**
 * Generates [count] consecutive anchor dates starting at [startAt] for [anchor] in the fixed
 * local calendar of [utcOffsetSeconds] (the frozen fixtures use `+08:00`). `start_at` itself
 * must hit the anchor; every later date takes one local-calendar month step with no day drift,
 * no gap, no reordering and nothing before the start (design doc, lines 28-30).
 */
fun generateInstallmentScheduledDates(
    startAt: Instant,
    anchor: PeriodicAllocationAnchor,
    count: Int,
    utcOffsetSeconds: Int,
): DomainResult<List<Instant>> {
    if (count < 1) {
        return DomainResult.Failure(
            PeriodicAllocationViolation.InvalidInstallmentCount(PeriodicAllocationField.INSTALLMENT_COUNT),
        )
    }
    val (startYear, startMonth, startDay) = localDateOf(startAt, utcOffsetSeconds)
    if (!hitsAnchor(startYear, startMonth, startDay, anchor)) {
        return DomainResult.Failure(PeriodicAllocationViolation.InvalidAnchor())
    }
    return DomainResult.Success(
        List(count) { index ->
            val zeroBasedMonth = startMonth - 1 + index
            val year = startYear + zeroBasedMonth.floorDiv(12)
            val month = zeroBasedMonth.mod(12) + 1
            val day = anchorDay(year, month, anchor)
            instantOfLocalDate(year, month, day, utcOffsetSeconds)
        },
    )
}

/**
 * The anchor date of the month following [from] in the local calendar of [utcOffsetSeconds];
 * `revise_periodic_allocation` starts the new revision's installments right after
 * `recognized_through` (contract `revision-installment-04` follows 2026-01-15 with 2026-02-15).
 */
fun nextAnchorDate(
    from: Instant,
    anchor: PeriodicAllocationAnchor,
    utcOffsetSeconds: Int,
): Instant {
    val (year, month, _) = localDateOf(from, utcOffsetSeconds)
    val zeroBasedNext = month
    val nextYear = year + zeroBasedNext.floorDiv(12)
    val nextMonth = zeroBasedNext.mod(12) + 1
    return instantOfLocalDate(nextYear, nextMonth, anchorDay(nextYear, nextMonth, anchor), utcOffsetSeconds)
}

/**
 * Builds the immutable installments of the initial revision (revision 1) of [schedule]:
 * exact closed amounts, anchor dates starting at `start_at` and consecutive sequences.
 */
fun createInitialInstallments(
    schedule: PeriodicAllocationSchedule,
    revisionId: String,
    installmentIds: List<String>,
    utcOffsetSeconds: Int,
): DomainResult<List<PeriodicAllocationInstallment>> {
    if (installmentIds.isEmpty()) {
        return DomainResult.Failure(
            PeriodicAllocationViolation.InvalidInstallmentCount(PeriodicAllocationField.INSTALLMENT_COUNT),
        )
    }
    if (installmentIds.toSet().size != installmentIds.size) {
        return DomainResult.Failure(PeriodicAllocationViolation.IdentityRequired())
    }
    val amounts =
        when (val split = splitAmountIntoInstallments(schedule.totalAmountMinor, installmentIds.size)) {
            is DomainResult.Success -> split.value
            is DomainResult.Failure -> return DomainResult.Failure(split.violation)
        }
    val dates =
        when (
            val generated =
                generateInstallmentScheduledDates(
                    startAt = schedule.startAt,
                    anchor = schedule.anchor,
                    count = installmentIds.size,
                    utcOffsetSeconds = utcOffsetSeconds,
                )
        ) {
            is DomainResult.Success -> generated.value
            is DomainResult.Failure -> return DomainResult.Failure(generated.violation)
        }
    return buildInstallments(schedule.id, revisionId, installmentIds, amounts, dates, schedule.currency)
}

/**
 * Builds the immutable installments of a later revision of [schedule], re-allocating the
 * remaining amount over [installmentIds] with the tail remainder in the last installment and
 * dates anchored right after [recognizedThrough] (design doc, lines 28-30).
 */
fun createRevisedInstallments(
    schedule: PeriodicAllocationSchedule,
    previousRevision: PeriodicAllocationRevision,
    allInstallments: List<PeriodicAllocationInstallment>,
    recognizedThrough: String,
    remainingAmountMinor: Long,
    installmentIds: List<String>,
    newRevisionId: String,
    utcOffsetSeconds: Int,
): DomainResult<List<PeriodicAllocationInstallment>> {
    if (previousRevision.scheduleId != schedule.id) {
        return DomainResult.Failure(PeriodicAllocationViolation.RevisionMustBeAppendOnly())
    }
    if (installmentIds.isEmpty()) {
        return DomainResult.Failure(
            PeriodicAllocationViolation.InvalidInstallmentCount(),
        )
    }
    if (installmentIds.toSet().size != installmentIds.size) {
        return DomainResult.Failure(PeriodicAllocationViolation.IdentityRequired())
    }
    val throughInstallment =
        allInstallments.firstOrNull { it.id == recognizedThrough }
            ?: return DomainResult.Failure(PeriodicAllocationViolation.UnknownInstallment())
    if (throughInstallment.scheduleId != schedule.id) {
        return DomainResult.Failure(PeriodicAllocationViolation.UnknownInstallment())
    }
    val amounts =
        when (val split = splitAmountIntoInstallments(remainingAmountMinor, installmentIds.size)) {
            is DomainResult.Success -> split.value
            is DomainResult.Failure -> return DomainResult.Failure(split.violation)
        }
    val start = nextAnchorDate(throughInstallment.scheduledAt, schedule.anchor, utcOffsetSeconds)
    val dates =
        when (
            val generated =
                generateInstallmentScheduledDates(
                    startAt = start,
                    anchor = schedule.anchor,
                    count = installmentIds.size,
                    utcOffsetSeconds = utcOffsetSeconds,
                )
        ) {
            is DomainResult.Success -> generated.value
            is DomainResult.Failure -> return DomainResult.Failure(generated.violation)
        }
    return buildInstallments(schedule.id, newRevisionId, installmentIds, amounts, dates, schedule.currency)
}

/**
 * Constructs an append-only revision of [schedule]. [previousRevision] is the directly previous
 * revision (`null` for revision 1). Invariants (D-085 RG-11, frozen rg-11.json):
 * - currency matches the schedule and remaining amount is positive;
 * - `remaining_amount` equals schedule total minus all previously recognized amounts
 *   (`RemainingAmountMismatch`);
 * - revision numbers are consecutive (`RevisionMustBeAppendOnly`);
 * - revision 1 has `recognized_through == null`; later revisions must point into the directly
 *   previous revision and equal the latest continuous recognized prefix of that revision
 *   (`invalid_revision_boundary`: gaps, hidden later recognitions or cross-revision boundaries
 *   are rejected; design doc lines 30-31);
 * - installment ids are non-empty, unique and never reused from a previous revision.
 */
fun createPeriodicAllocationRevision(
    id: String,
    schedule: PeriodicAllocationSchedule,
    previousRevision: PeriodicAllocationRevision?,
    recognizedThrough: String?,
    remainingAmountMinor: Long,
    currency: CurrencyUnit,
    installmentIds: List<String>,
    recognizedInstallmentIds: Set<String>,
    recognizedAmountMinor: Long,
): DomainResult<PeriodicAllocationRevision> {
    if (id.isBlank()) {
        return DomainResult.Failure(PeriodicAllocationViolation.IdentityRequired())
    }
    if (currency != schedule.currency) {
        return DomainResult.Failure(PeriodicAllocationViolation.CurrencyMismatch())
    }
    if (remainingAmountMinor <= 0L) {
        return DomainResult.Failure(PeriodicAllocationViolation.MustBePositive())
    }
    if (installmentIds.isEmpty()) {
        return DomainResult.Failure(PeriodicAllocationViolation.InvalidInstallmentCount())
    }
    if (installmentIds.toSet().size != installmentIds.size) {
        return DomainResult.Failure(PeriodicAllocationViolation.IdentityRequired())
    }
    if (recognizedAmountMinor < 0L) {
        return DomainResult.Failure(PeriodicAllocationViolation.RemainingAmountMismatch())
    }
    val expectedRemaining = schedule.totalAmountMinor - recognizedAmountMinor
    if (remainingAmountMinor != expectedRemaining) {
        return DomainResult.Failure(PeriodicAllocationViolation.RemainingAmountMismatch())
    }
    if (previousRevision == null) {
        if (recognizedThrough != null) {
            return DomainResult.Failure(PeriodicAllocationViolation.InvalidRevisionBoundary())
        }
        return DomainResult.Success(
            PeriodicAllocationRevision(
                id = id,
                scheduleId = schedule.id,
                revisionNumber = 1,
                recognizedThrough = null,
                remainingAmountMinor = remainingAmountMinor,
                currency = currency,
                installmentIds = installmentIds,
            ),
        )
    }
    if (previousRevision.scheduleId != schedule.id) {
        return DomainResult.Failure(PeriodicAllocationViolation.RevisionMustBeAppendOnly())
    }
    if (recognizedThrough == null) {
        return DomainResult.Failure(PeriodicAllocationViolation.InvalidRevisionBoundary())
    }
    if (recognizedThrough !in previousRevision.installmentIds) {
        return DomainResult.Failure(PeriodicAllocationViolation.InvalidRevisionBoundary())
    }
    val recognizedPrefix = previousRevision.installmentIds.takeWhile { it in recognizedInstallmentIds }
    if (recognizedPrefix.isEmpty() || recognizedThrough != recognizedPrefix.last()) {
        return DomainResult.Failure(PeriodicAllocationViolation.InvalidRevisionBoundary())
    }
    if (installmentIds.any { it in previousRevision.installmentIds }) {
        return DomainResult.Failure(PeriodicAllocationViolation.RevisionMustBeAppendOnly())
    }
    return DomainResult.Success(
        PeriodicAllocationRevision(
            id = id,
            scheduleId = schedule.id,
            revisionNumber = previousRevision.revisionNumber + 1,
            recognizedThrough = recognizedThrough,
            remainingAmountMinor = remainingAmountMinor,
            currency = currency,
            installmentIds = installmentIds,
        ),
    )
}

/**
 * Validates a recognition request against the current effective state before any formal effect:
 * only a `pending` installment of the latest revision can be recognized, the requested amount
 * must equal the exact installment amount, the currency must match, and the amount must not
 * exceed the remaining prepaid balance (design doc lines 22, 36-38).
 */
fun validateInstallmentRecognition(
    schedule: PeriodicAllocationSchedule,
    latestRevision: PeriodicAllocationRevision,
    installments: List<PeriodicAllocationInstallment>,
    recognizedInstallmentIds: Set<String>,
    requestedInstallmentId: String,
    requestedAmountMinor: Long,
    requestedCurrency: CurrencyUnit,
): DomainResult<Unit> {
    val installment =
        installments.firstOrNull { it.id == requestedInstallmentId }
            ?: return DomainResult.Failure(PeriodicAllocationViolation.UnknownInstallment())
    if (installment.scheduleId != schedule.id) {
        return DomainResult.Failure(PeriodicAllocationViolation.UnknownInstallment())
    }
    if (requestedCurrency != schedule.currency) {
        return DomainResult.Failure(PeriodicAllocationViolation.CurrencyMismatch())
    }
    if (requestedAmountMinor != installment.amountMinor) {
        return DomainResult.Failure(PeriodicAllocationViolation.AmountMustMatchInstallment())
    }
    if (installment.id in recognizedInstallmentIds || installment.revisionId != latestRevision.id) {
        return DomainResult.Failure(PeriodicAllocationViolation.InstallmentNotPending())
    }
    val recognizedSum =
        recognizedAmountMinorOrNull(installments, recognizedInstallmentIds)
            ?: return DomainResult.Failure(DomainViolation.ArithmeticOverflow)
    val remaining = schedule.totalAmountMinor - recognizedSum
    if (requestedAmountMinor > remaining) {
        return DomainResult.Failure(PeriodicAllocationViolation.ExceedsRemainingPrepaid())
    }
    return DomainResult.Success(Unit)
}

/**
 * Derived `allocation_status` of a schedule: `recognized` once every installment of the
 * current effective set is recognized, `active` otherwise (design doc, line 44).
 */
fun deriveScheduleAllocationStatus(
    currentInstallments: List<PeriodicAllocationInstallment>,
    recognizedInstallmentIds: Set<String>,
): PeriodicAllocationScheduleStatus =
    if (currentInstallments.isNotEmpty() && currentInstallments.all { it.id in recognizedInstallmentIds }) {
        PeriodicAllocationScheduleStatus.RECOGNIZED
    } else {
        PeriodicAllocationScheduleStatus.ACTIVE
    }

/**
 * Derived `allocation_status` of an installment: `recognized` for confirmed installments,
 * `pending` for unconfirmed installments of the latest revision, `superseded` for unconfirmed
 * installments of an earlier revision. Existing recognition links always keep their history.
 */
fun deriveInstallmentAllocationStatus(
    installment: PeriodicAllocationInstallment,
    latestRevision: PeriodicAllocationRevision,
    recognizedInstallmentIds: Set<String>,
): PeriodicAllocationInstallmentStatus =
    when {
        installment.id in recognizedInstallmentIds -> PeriodicAllocationInstallmentStatus.RECOGNIZED
        installment.revisionId == latestRevision.id -> PeriodicAllocationInstallmentStatus.PENDING
        else -> PeriodicAllocationInstallmentStatus.SUPERSEDED
    }

/**
 * Parses an exact decimal string (e.g. `"100.00"`) into minor units with [precision] decimal
 * places. A non-string-like value or a wrong number of fractional digits fails with
 * `exact_decimal_string_required`; a parsed zero or negative amount fails with
 * `must_be_positive` (frozen operation-reject-malformed/zero/negative-amount).
 */
fun parseExactDecimalMinorUnits(
    text: String,
    precision: Int,
): DomainResult<Long> {
    val scale =
        scaleForPrecision(precision)
            ?: return DomainResult.Failure(PeriodicAllocationViolation.ExactDecimalStringRequired())
    val match =
        Regex("^([0-9]+)\\.([0-9]{$precision})$").matchEntire(text)
            ?: return DomainResult.Failure(PeriodicAllocationViolation.ExactDecimalStringRequired())
    val whole =
        match.groupValues[1].toLongOrNull()
            ?: return DomainResult.Failure(PeriodicAllocationViolation.ExactDecimalStringRequired())
    val fraction =
        match.groupValues[2].toLongOrNull()
            ?: return DomainResult.Failure(PeriodicAllocationViolation.ExactDecimalStringRequired())
    val maxWhole = (Long.MAX_VALUE - (scale - 1L)) / scale
    if (whole > maxWhole) {
        return DomainResult.Failure(PeriodicAllocationViolation.ExactDecimalStringRequired())
    }
    val minor = whole * scale + fraction
    if (minor <= 0L) {
        return DomainResult.Failure(PeriodicAllocationViolation.MustBePositive())
    }
    return DomainResult.Success(minor)
}

private fun isSupportedPeriodicAllocationCurrency(currency: CurrencyUnit): Boolean = currency == CurrencyUnit(code = "CNY", precision = 2)

private fun buildInstallments(
    scheduleId: String,
    revisionId: String,
    installmentIds: List<String>,
    amounts: List<Long>,
    dates: List<Instant>,
    currency: CurrencyUnit,
): DomainResult<List<PeriodicAllocationInstallment>> =
    DomainResult.Success(
        installmentIds.mapIndexed { index, id ->
            PeriodicAllocationInstallment(
                id = id,
                scheduleId = scheduleId,
                revisionId = revisionId,
                sequence = index + 1,
                scheduledAt = dates[index],
                amountMinor = amounts[index],
                currency = currency,
            )
        },
    )

private fun recognizedAmountMinorOrNull(
    installments: List<PeriodicAllocationInstallment>,
    recognizedInstallmentIds: Set<String>,
): Long? {
    var sum = 0L
    for (installment in installments) {
        if (installment.id in recognizedInstallmentIds) {
            sum = checkedAdd(sum, installment.amountMinor) ?: return null
        }
    }
    return sum
}

private fun scaleForPrecision(precision: Int): Long? {
    if (precision < 0 || precision > 18) return null
    var scale = 1L
    repeat(precision) { scale *= 10L }
    return scale
}

// Fixed-offset local calendar arithmetic (civil-from-days / days-from-civil, Howard Hinnant's
// public-domain algorithm). The domain owns the local calendar of the case timezone so anchor
// semantics stay exact without platform or datetime dependencies.

private const val SECONDS_PER_DAY = 86_400L

private fun localDateOf(
    instant: Instant,
    utcOffsetSeconds: Int,
): Triple<Int, Int, Int> {
    val localSeconds = instant.epochSeconds + utcOffsetSeconds
    return civilFromDays(localSeconds.floorDiv(SECONDS_PER_DAY))
}

private fun instantOfLocalDate(
    year: Int,
    month: Int,
    day: Int,
    utcOffsetSeconds: Int,
): Instant {
    val days = daysFromCivil(year, month, day)
    return Instant.fromEpochSeconds(days * SECONDS_PER_DAY - utcOffsetSeconds)
}

private fun hitsAnchor(
    year: Int,
    month: Int,
    day: Int,
    anchor: PeriodicAllocationAnchor,
): Boolean = day == anchorDay(year, month, anchor)

private fun anchorDay(
    year: Int,
    month: Int,
    anchor: PeriodicAllocationAnchor,
): Int =
    when (anchor) {
        PeriodicAllocationAnchor.MonthEnd -> lastDayOfMonth(year, month)
        is PeriodicAllocationAnchor.DayOfMonth -> anchor.day
    }

private fun lastDayOfMonth(
    year: Int,
    month: Int,
): Int {
    val (nextYear, nextMonth) = if (month == 12) year + 1 to 1 else year to month + 1
    return civilFromDays(daysFromCivil(nextYear, nextMonth, 1) - 1L).third
}

private fun daysFromCivil(
    year: Int,
    month: Int,
    day: Int,
): Long {
    val adjustedYear = if (month <= 2) year - 1 else year
    val era = (if (adjustedYear >= 0) adjustedYear else adjustedYear - 399) / 400
    val yearOfEra = adjustedYear - era * 400
    val dayOfYear = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
    val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    return era * 146097L + dayOfEra - 719468L
}

private fun civilFromDays(days: Long): Triple<Int, Int, Int> {
    val z = days + 719468L
    val era = (if (z >= 0) z else z - 146096L) / 146097L
    val dayOfEra = z - era * 146097L
    val yearOfEra = (dayOfEra - dayOfEra / 1460L + dayOfEra / 36524L - dayOfEra / 146096L) / 365L
    val year = yearOfEra + era * 400L
    val dayOfYear = dayOfEra - (365L * yearOfEra + yearOfEra / 4L - yearOfEra / 100L)
    val monthPrime = (5L * dayOfYear + 2L) / 153L
    val day = (dayOfYear - (153L * monthPrime + 2L) / 5L + 1L).toInt()
    val month = (if (monthPrime < 10L) monthPrime + 3L else monthPrime - 9L).toInt()
    val outYear = (if (month <= 2) year + 1L else year).toInt()
    return Triple(outYear, month, day)
}
