package com.unifiedledger.application

/**
 * P7-04.A format capability matrix (D-146 Q08-3, spec section 3.1.1 / 4.2.1).
 *
 * The matrix is an immutable code declaration: each entry carries a format identifier, a
 * display name, the MIME filter set handed to the platform file picker, and the per-platform
 * availability. The UI must only offer formats the matrix declares available. A format's
 * Android availability may only flip from [ImportFormatAvailability.PENDING_DEVICE_VERIFICATION]
 * to [ImportFormatAvailability.AVAILABLE] once the device runtime-evidence gate has passed —
 * the CCB XLS unit did so after A-04.1's instrumented verification (HSSF equivalence on
 * Android). Runtime discrimination (the GB18030 charset probe, container pre-checks) never
 * changes this declaration; it is layered on top by the intake orchestration (spec section 4.2).
 */
enum class ImportPlatformKind {
    ANDROID,
    DESKTOP,
}

/**
 * Per-platform availability. [PENDING_DEVICE_VERIFICATION] is an honest "not yet" state:
 * picking such a format on that platform must surface the typed
 * "该格式在 Android 待运行验证" result, never a silent failure or a silent success
 * (R-Q08-3).
 */
enum class ImportFormatAvailability {
    AVAILABLE,
    PENDING_DEVICE_VERIFICATION,
}

/** Opaque format identifier; never derived from a file name, path, or content. */
data class ImportFormatId(
    val value: String,
) {
    override fun toString(): String = value
}

data class ImportFormatDescriptor(
    val identifier: ImportFormatId,
    val displayName: String,
    val mimeFilters: List<String>,
    val availability: Map<ImportPlatformKind, ImportFormatAvailability>,
) {
    fun availabilityOn(platform: ImportPlatformKind): ImportFormatAvailability =
        availability[platform]
            ?: throw IllegalStateException(
                "format ${identifier.value} has no declared availability for platform $platform",
            )
}

object ImportFormatCapabilities {
    /** WeChat bill XLSX (D-099 frozen format contract; production read via BoundedXlsxReader). */
    val WECHAT_XLSX: ImportFormatDescriptor =
        ImportFormatDescriptor(
            identifier = ImportFormatId("wechat-xlsx"),
            displayName = "微信账单（XLSX）",
            mimeFilters = listOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            availability =
                mapOf(
                    ImportPlatformKind.ANDROID to ImportFormatAvailability.AVAILABLE,
                    ImportPlatformKind.DESKTOP to ImportFormatAvailability.AVAILABLE,
                ),
        )

    /** Alipay bill CSV (strict UTF-8 first, GB18030 fallback; runtime charset probe at intake). */
    val ALIPAY_CSV: ImportFormatDescriptor =
        ImportFormatDescriptor(
            identifier = ImportFormatId("alipay-csv"),
            displayName = "支付宝账单（CSV）",
            mimeFilters = listOf("text/csv", "application/csv", "text/comma-separated-values"),
            availability =
                mapOf(
                    ImportPlatformKind.ANDROID to ImportFormatAvailability.AVAILABLE,
                    ImportPlatformKind.DESKTOP to ImportFormatAvailability.AVAILABLE,
                ),
        )

    /** CMB online-banking CSV (strict UTF-8, no extra probe). */
    val CMB_CSV: ImportFormatDescriptor =
        ImportFormatDescriptor(
            identifier = ImportFormatId("cmb-csv"),
            displayName = "招商银行账单（CSV）",
            mimeFilters = listOf("text/csv", "application/csv", "text/comma-separated-values"),
            availability =
                mapOf(
                    ImportPlatformKind.ANDROID to ImportFormatAvailability.AVAILABLE,
                    ImportPlatformKind.DESKTOP to ImportFormatAvailability.AVAILABLE,
                ),
        )

    /**
     * CCB online-banking XLS. Both platforms read it through HSSF (`poi` stays in jvmMain);
     * the Android unit flipped to available after A-04.1's instrumented verification proved
     * HSSF executable on Android with JVM-oracle-equivalent results (D-146 matrix, Q08-3).
     */
    val CCB_XLS: ImportFormatDescriptor =
        ImportFormatDescriptor(
            identifier = ImportFormatId("ccb-xls"),
            displayName = "建设银行账单（XLS）",
            mimeFilters = listOf("application/vnd.ms-excel"),
            availability =
                mapOf(
                    ImportPlatformKind.ANDROID to ImportFormatAvailability.AVAILABLE,
                    ImportPlatformKind.DESKTOP to ImportFormatAvailability.AVAILABLE,
                ),
        )

    /** The frozen four-format matrix (D-146; UI filters its import entries on this list). */
    val ALL: List<ImportFormatDescriptor> = listOf(WECHAT_XLSX, ALIPAY_CSV, CMB_CSV, CCB_XLS)

    /**
     * Fail-loud descriptor lookup. An unknown identifier is an orchestration/programming
     * error (the UI may only launch picks for matrix formats), so it throws instead of
     * returning a typed "unavailable" that would silently swallow the defect.
     */
    fun byIdentifier(identifier: ImportFormatId): ImportFormatDescriptor =
        ALL.firstOrNull { it.identifier == identifier }
            ?: throw IllegalArgumentException("unknown import format identifier: ${identifier.value}")
}
