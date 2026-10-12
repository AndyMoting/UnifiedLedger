package com.unifiedledger.android

import android.app.Application
import android.content.Context
import com.unifiedledger.ui.ExternalNavigationChannel

/**
 * P7-09 09.0 (spec section 6.3; D-225 Q19 P1): the Android [Application] subclass that owns the
 * process-scoped [LedgerProcessHolder].
 *
 * Before this batch the process had NO `Application` subclass and no `applicationContext`-scoped
 * runtime holder (spec section 1.4: the grep for an `Application` subtype/`applicationContext`
 * holder in `android-app/src/main` returned zero hits), so the only runtime owner was the
 * composition-scoped one. This class is the process-wide root that makes the spec section 6.3
 * requirement satisfiable: non-composition entries (a future widget receiver / shortcut cold
 * start) reach the SAME single owner through [ledgerProcessHolderFor] instead of opening a graph
 * of their own or calling the bootstrap/migrate builder.
 *
 * The holder is built lazily on first access, so merely starting the process (for example the
 * system instantiating the Application) does not open the ledger; the first real entry — the
 * Activity's composition or a non-composition entry — does.
 */
class UnifiedLedgerApplication : Application() {
    private val holder: LedgerProcessHolder by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        LedgerProcessHolder(applicationContext, AndroidSafLaunchSlot(), ExternalNavigationChannel())
    }

    /** The process's single runtime owner holder (one owner/active graph for the whole process). */
    internal fun ledgerProcessHolder(): LedgerProcessHolder = holder
}

/**
 * P7-09 09.0 (spec section 6.3): resolves the process-scoped holder from ANY context (an Activity
 * or a receiver/service context all reach the same [Application]). Returns null when the process
 * runs without [UnifiedLedgerApplication] (a plain unit-test context), so callers can fall back
 * explicitly rather than assume a holder exists.
 */
internal fun ledgerProcessHolderFor(context: Context): LedgerProcessHolder? = (context.applicationContext as? UnifiedLedgerApplication)?.ledgerProcessHolder()
