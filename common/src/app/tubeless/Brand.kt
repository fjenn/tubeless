package app.tubeless

/**
 * The app's name and home, in one place.
 *
 * [REPO] is the public GitHub repository: the update check, "What's new" and the remote
 * clients.json come from there. Set it to null and the app makes no request anywhere but YouTube
 * (and the opt-in services it already used).
 */
object Brand {
    const val NAME = "Tubeless"
    /** application / bundle id on every platform */
    const val ID = "dev.tubeless.app"
    /** "owner/name" on GitHub, or null: every look at our own repository is off */
    val REPO: String? = "fjenn/tubeless"
    /** the repository's web page, when there is one */
    val PAGE: String? get() = REPO?.let { "https://github.com/$it" }
    /** the upstream app this one is built on (credited in Settings → About) */
    const val UPSTREAM = "TubeHaus"
}
