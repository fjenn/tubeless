package app.tubeless.engine

/**
 * Live streams the SABR way (the JVM twin in common/jvm keeps the last minute and serves it as a live
 * HLS list). Not on the iPhone yet: its live videos play from the usual clients' own HLS lists, and the
 * page here never says it can do live ([SabrHost.live] is false), so the shared engine never asks.
 */
internal object SabrLive {
    /** the live list of video [id] on this device; none here */
    fun master(id: String): String? = null
    fun hostGone() {}
}
