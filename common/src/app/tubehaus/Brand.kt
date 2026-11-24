package app.tubehaus

/**
 * The app's name and home, in one place. "Tubeless" is the product name; the Kotlin packages
 * stay app.tubehaus so upstream TubeHaus fixes still cherry-pick cleanly.
 *
 * [REPO] is null until release work (stream S9) points it at our own GitHub repository. While it
 * is null the app makes no request anywhere but YouTube (and the opt-in services it already
 * used): no remote clients.json, no update or "What's new" check, no Windows/iOS self-update.
 */
object Brand {
    const val NAME = "Tubeless"
    /** application / bundle id on every platform */
    const val ID = "dev.tubeless.app"
    /** "owner/name" on GitHub, or null: every look at our own repository is off */
    val REPO: String? = null
    /** the repository's web page, when there is one */
    val PAGE: String? get() = REPO?.let { "https://github.com/$it" }
    /** the upstream app this one is built on (credited in Settings → About) */
    const val UPSTREAM = "TubeHaus"
}
