package app.tubehaus.desktop

import app.tubehaus.engine.AccountError
import app.tubehaus.engine.Accounts
import app.tubehaus.engine.Ck
import app.tubehaus.engine.SecretStore
import app.tubehaus.engine.SignInLauncher
import app.tubehaus.engine.json
import app.tubehaus.engine.parse
import app.tubehaus.engine.at
import app.tubehaus.engine.str
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.Base64

// Desktop sign-in (stream S3): Google's page in tools/signin-helper (a small wry web view, its own process),
// the accounts in the system's secret store (macOS Keychain, Windows DPAPI, Linux libsecret).

/**
 * Runs tools/signin-helper: `--profile` gives every Google sign-in its own web view store, so each saved
 * account can be refreshed (`--refresh`, hidden) and forgotten (`--forget`) on its own.
 */
class HelperSignIn(private val dataDir: File) : SignInLauncher {
    private val exe: File? by lazy { find() }
    override val available: Boolean get() = exe != null

    private fun find(): File? {
        val name = if (System.getProperty("os.name").startsWith("Windows")) "signin-helper.exe" else "signin-helper"
        env("TUBELESS_SIGNIN_HELPER")?.let { File(it) }?.takeIf { it.canExecute() }?.let { return it }
        // packaged: next to the app's other resources (S9 bundles it, see BUILD-STATUS S3)
        System.getProperty("compose.application.resources.dir")?.let { File(it, name) }?.takeIf { it.canExecute() }?.let { return it }
        // run from Gradle / the repo: tools/signin-helper/target/release
        var d: File? = File("").absoluteFile
        while (d != null) {
            val f = File(d, "tools/signin-helper/target/release/$name")
            if (f.canExecute()) return f
            d = d.parentFile
        }
        return null
    }

    private suspend fun run(vararg args: String): String? = withContext(Dispatchers.IO) {
        val exe = exe ?: throw AccountError("The sign-in window is missing from this build. Import a cookies.txt instead.")
        val pb = ProcessBuilder(listOf(exe.path) + args + listOf("--data-dir", File(dataDir, "webview").path))
        // its page loads and cookie names (never values) go to the sign-in log
        pb.environment()["KESTREL_SIGNIN_DEBUG"] = "1"
        val p = pb.start()
        p.outputStream.close()
        Thread { runCatching { p.errorStream.bufferedReader().forEachLine { Accounts.log("helper: $it") } } }.apply { isDaemon = true }.start()
        try {
            runInterruptible { p.inputStream.bufferedReader().readLines() }.lastOrNull { it.startsWith("{") }
        } finally {
            if (p.isAlive) p.destroy()
        }
    }

    private fun cookies(line: String?): List<Ck>? {
        val d = parse(line ?: return null)
        if (d.at("ok").str != "true") {
            return when (val e = d.at("error").str) {
                "cancelled", "signed-out", null -> null
                "timeout" -> throw AccountError("Sign-in took too long. Try again.")
                else -> throw AccountError(e)
            }
        }
        return json.decodeFromString(ListSerializer(Ck.serializer()), d.at("cookies").toString())
    }

    override suspend fun signIn(profile: String, add: Boolean): List<Ck>? =
        cookies(if (add) run("--add", "--profile", profile) else run("--profile", profile))

    override suspend fun refresh(profile: String): List<Ck>? = runCatching { cookies(run("--refresh", "--profile", profile)) }.getOrNull()

    override suspend fun forget(profile: String) { runCatching { run("--forget", "--profile", profile) } }
}

/** the secret store of this desktop; falls back to a file only the user can read */
fun desktopSecrets(dataDir: File): SecretStore {
    val os = System.getProperty("os.name")
    val file = FileSecrets(File(dataDir, "secrets"))
    return when {
        // a test run with its own home (-Duser.home=…) or TUBELESS_SECRETS=file never reads or writes the
        // Keychain, so it can't pick up (or sign out) the sign-in of the app the user really runs
        env("TUBELESS_SECRETS") == "file" || System.getProperty("user.home") != (System.getenv("HOME") ?: System.getProperty("user.home")) -> file
        os.startsWith("Mac") && File("/usr/bin/security").canExecute() -> MacKeychain().also { migrateSecrets(it, MacKeychain(LEGACY_SERVICE)) }
        os.startsWith("Windows") -> runCatching { DpapiSecrets(File(dataDir, "secrets")) }.getOrElse { file }
        else -> if (LibSecret.works()) LibSecret(file).also { migrateSecrets(it, LibSecret(NoSecrets, LEGACY_SERVICE)) } else file
    }
}

private const val SERVICE = "dev.tubeless.app"

/** the Keychain / libsecret service before the rename to Tubeless (migration only) */
private const val LEGACY_SERVICE = "dev.kestrel.app"

/** first start after the rename: the accounts item moves from the old service to the new one */
private fun migrateSecrets(now: SecretStore, legacy: SecretStore) = runCatching {
    if (now.read("accounts") != null) return@runCatching
    val old = legacy.read("accounts") ?: return@runCatching
    now.write("accounts", old)
    if (now.read("accounts") == old) legacy.write("accounts", null)
}

private object NoSecrets : SecretStore {
    override fun read(name: String): String? = null
    override fun write(name: String, value: String?) {}
}

private fun exec(cmd: List<String>, input: String? = null): Pair<Int, String> {
    val p = ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    p.outputStream.use { o -> if (input != null) o.write(input.toByteArray()) }
    val out = p.inputStream.bufferedReader().readText()
    return p.waitFor() to out
}

/**
 * macOS Keychain through /usr/bin/security; the secret goes in on stdin (`-i`), never on a command line.
 * `security -i` cuts every input line at 4 KB, and a sign-in (its cookies) is bigger: the secret is gzipped
 * and spread over items of 3,000 characters ("accounts", "accounts.1", …), the first one saying how many.
 * A value an older build saved (one item, cut off) still reads back as far as it goes, so the sign-in code
 * can find its web view profile in it and mint the session afresh.
 */
class MacKeychain(private val service: String = SERVICE) : SecretStore {
    private val chunk = 3000

    private fun raw(name: String): String? {
        val (code, out) = exec(listOf("/usr/bin/security", "find-generic-password", "-s", service, "-a", name, "-w"))
        return if (code == 0) out.trim() else null
    }

    override fun read(name: String): String? {
        val first = raw(name) ?: return null
        val m = Regex("^kc2:(\\d+):").find(first)
        if (m == null) {
            // the old one-item format; a cut-off one decodes as far as whole 4-character groups go
            val b64 = first.take(first.length / 4 * 4)
            return runCatching { String(Base64.getDecoder().decode(b64)) }.getOrNull()
        }
        val n = m.groupValues[1].toInt()
        val sb = StringBuilder(first.substring(m.value.length))
        for (i in 1 until n) sb.append(raw("$name.$i") ?: return null)
        return runCatching { java.util.zip.GZIPInputStream(Base64.getDecoder().decode(sb.toString()).inputStream()).readBytes().toString(Charsets.UTF_8) }.getOrNull()
    }

    override fun write(name: String, value: String?) {
        if (value == null) {
            exec(listOf("/usr/bin/security", "delete-generic-password", "-s", service, "-a", name))
            dropParts(name, 1)
            return
        }
        val bytes = java.io.ByteArrayOutputStream().also { o -> java.util.zip.GZIPOutputStream(o).use { it.write(value.toByteArray()) } }.toByteArray()
        val parts = Base64.getEncoder().encodeToString(bytes).chunked(chunk)
        val lines = parts.mapIndexed { i, p ->
            val acct = if (i == 0) name else "$name.$i"
            val v = if (i == 0) "kc2:${parts.size}:$p" else p
            "add-generic-password -U -s $service -a $acct -l Tubeless -w $v\n"
        }.joinToString("")
        val (code, _) = exec(listOf("/usr/bin/security", "-i"), lines)
        if (code != 0) throw IllegalStateException("Keychain write failed ($code)")
        dropParts(name, parts.size)
        // read back: a save that doesn't come back whole is a failed save
        if (read(name) != value) throw IllegalStateException("Keychain read-back differs")
    }

    /** removes "name.from", "name.from+1", … left from a longer value */
    private fun dropParts(name: String, from: Int) {
        var i = from
        while (i < 200 && exec(listOf("/usr/bin/security", "delete-generic-password", "-s", service, "-a", "$name.$i")).first == 0) i++
    }
}

/** Linux: libsecret's secret-tool (GNOME Keyring, KWallet's Secret Service); the secret on stdin */
class LibSecret(private val fallback: SecretStore, private val service: String = SERVICE) : SecretStore {
    companion object {
        fun works(): Boolean = runCatching { exec(listOf("secret-tool", "--version")).first == 0 }.getOrDefault(false) ||
            runCatching { exec(listOf("which", "secret-tool")).first == 0 }.getOrDefault(false)
    }

    override fun read(name: String): String? {
        val (code, out) = runCatching { exec(listOf("secret-tool", "lookup", "service", service, "account", name)) }.getOrDefault(1 to "")
        return if (code == 0 && out.isNotEmpty()) out else fallback.read(name)
    }

    override fun write(name: String, value: String?) {
        val ok = runCatching {
            if (value == null) exec(listOf("secret-tool", "clear", "service", service, "account", name)).first == 0
            else exec(listOf("secret-tool", "store", "--label=Tubeless", "service", service, "account", name), value).first == 0
        }.getOrDefault(false)
        // no keyring running (a bare window manager): the private file instead
        if (!ok || value == null) fallback.write(name, if (ok) null else value)
    }
}

/** a file in the app's data folder, readable by this user only */
class FileSecrets(private val dir: File) : SecretStore {
    override fun read(name: String): String? = File(dir, name).takeIf { it.isFile }?.readText()
    override fun write(name: String, value: String?) {
        val f = File(dir, name)
        if (value == null) { f.delete(); return }
        dir.mkdirs()
        f.writeText("")
        f.setReadable(false, false); f.setReadable(true, true)
        f.setWritable(false, false); f.setWritable(true, true)
        f.writeText(value)
    }
}

/** Windows: the secret encrypted for this Windows user (DPAPI, CryptProtectData) in the data folder */
class DpapiSecrets(private val dir: File) : SecretStore {
    @Suppress("unused")
    @Structure.FieldOrder("cbData", "pbData")
    class Blob : Structure {
        @JvmField var cbData: Int = 0
        @JvmField var pbData: Pointer? = null
        constructor() : super()
        constructor(bytes: ByteArray) : super() {
            val m = com.sun.jna.Memory(maxOf(1, bytes.size).toLong())
            m.write(0, bytes, 0, bytes.size)
            cbData = bytes.size; pbData = m
        }
        fun bytes(): ByteArray = pbData?.getByteArray(0, cbData) ?: ByteArray(0)
    }

    interface Crypt32 : Library {
        fun CryptProtectData(dataIn: Blob, desc: String?, entropy: Blob?, reserved: Pointer?, prompt: Pointer?, flags: Int, dataOut: Blob): Boolean
        fun CryptUnprotectData(dataIn: Blob, desc: Pointer?, entropy: Blob?, reserved: Pointer?, prompt: Pointer?, flags: Int, dataOut: Blob): Boolean
    }

    interface Kernel32 : Library { fun LocalFree(p: Pointer?): Pointer? }

    private val crypt = Native.load("Crypt32", Crypt32::class.java)
    private val kernel = Native.load("Kernel32", Kernel32::class.java)
    private val entropy = "tubeless-accounts".toByteArray()
    /** what files written before the rename to Tubeless were sealed with (migration only) */
    private val legacyEntropy = "kestrel-accounts".toByteArray()

    override fun read(name: String): String? {
        val f = File(dir, "$name.dpapi").takeIf { it.isFile } ?: return null
        unprotect(f.readBytes(), entropy)?.let { return it }
        // a file from before the rename: read it with the old entropy and seal it again with the new one
        return unprotect(f.readBytes(), legacyEntropy)?.also { v -> runCatching { write(name, v) } }
    }

    private fun unprotect(data: ByteArray, e: ByteArray): String? {
        val out = Blob()
        if (!crypt.CryptUnprotectData(Blob(data), null, Blob(e), null, null, 1, out)) return null
        return try { String(out.bytes()) } finally { kernel.LocalFree(out.pbData) }
    }

    override fun write(name: String, value: String?) {
        val f = File(dir, "$name.dpapi")
        if (value == null) { f.delete(); return }
        val out = Blob()
        if (!crypt.CryptProtectData(Blob(value.toByteArray()), "Tubeless", Blob(entropy), null, null, 1, out)) throw IllegalStateException("DPAPI failed")
        try { dir.mkdirs(); f.writeBytes(out.bytes()) } finally { kernel.LocalFree(out.pbData) }
    }
}
