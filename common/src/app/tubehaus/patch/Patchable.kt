package app.tubehaus.patch

/**
 * Left over from TubePatch (the iPad's patch.js runtime, removed: the hot-fix lever is now the
 * remote clients.json plus fast releases). It does nothing anywhere; kept so the files that still
 * carry it need no change. Safe to drop together with its uses.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class Patchable
