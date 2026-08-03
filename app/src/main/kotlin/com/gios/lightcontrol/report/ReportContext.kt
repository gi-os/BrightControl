package com.gios.lightcontrol.report

/**
 * The only file in `report/` that differs between apps.
 *
 * Everything else in this package is byte-identical everywhere it is installed apart from the
 * package line, which is the point — the reporting feature was stuck inside LightCamera for
 * months because it was woven through that app's own UI. Keeping the per-app strings here means
 * porting it is a copy plus three edits, and means a fix to the queue or the gesture can be
 * applied everywhere the same way.
 */
object ReportApp {
    /** How the app calls itself in an issue title and in "X could not …". */
    const val NAME = "LightControl"

    /** The label the triage skill routes on. One per app, matching the light-reports convention. */
    const val LABEL = "control"
}

/**
 * Where the app was when it went wrong.
 *
 * A single field rather than anything passed down: the crash handler runs on a dying thread that
 * has no view of the composition, and a report is worth far more with "standings" on it than
 * without. Written from the navigation, read from anywhere, so it is deliberately volatile.
 */
object ReportContext {
    @Volatile
    var screen: String = "buttons"
}
