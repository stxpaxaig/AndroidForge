package com.ghosttap.core.investigate

/**
 * In-memory ledger of investigation reports, newest last.
 * Callers read a snapshot via [all]; mutating the returned list
 * never affects the ledger.
 */
object InvestigationLedger {

    private val reports = mutableListOf<FindingsReport>()

    // Synchronized: add() runs from discovery triggers (BLE scan,
    // USB attach, NFC read, network discovery) while all() is read on
    // the UI thread. A plain unsynchronized list could throw
    // ConcurrentModificationException mid-iteration.
    fun add(report: FindingsReport) {
        synchronized(reports) { reports.add(report) }
    }

    /**
     * DISCOVER (on-demand refresh): file a report for a target, replacing
     * the earlier report for that same target instead of stacking a
     * duplicate. Two reports are "the same target" only when both carry
     * the same non-null [FindingsReport.targetId] — a stable id the
     * investigating trigger already knows (BLE address, USB device +
     * driver, network address, Wi-Fi network, NFC tag id, picture
     * content). A report with no targetId can never match, so it is
     * always appended, exactly like [add]. [add] itself is untouched.
     */
    fun upsert(report: FindingsReport) {
        synchronized(reports) {
            val id = report.targetId
            if (id != null) {
                val idx = reports.indexOfFirst { it.targetId == id }
                if (idx >= 0) {
                    reports[idx] = report
                    return
                }
            }
            reports.add(report)
        }
    }

    fun all(): List<FindingsReport> = synchronized(reports) { reports.toList() }

    fun clear() {
        synchronized(reports) { reports.clear() }
    }
}
