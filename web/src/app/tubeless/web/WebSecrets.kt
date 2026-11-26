package app.tubeless.web

import app.tubeless.engine.SecretStore

/** The website's secrets (TvSignIn's refresh token): this origin's localStorage, nothing leaves the browser */
object WebSecrets : SecretStore {
    override fun read(name: String): String? = jsRead("tubeless.secret.$name")
    override fun write(name: String, value: String?) = jsWrite("tubeless.secret.$name", value)
}

private fun jsRead(k: String): String? = js("(function(){ try { return localStorage.getItem(k) } catch (e) { return null } })()")
private fun jsWrite(k: String, v: String?): Unit = js("{ try { if (v == null) localStorage.removeItem(k); else localStorage.setItem(k, v) } catch (e) {} }")
