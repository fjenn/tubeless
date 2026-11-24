package app.tubehaus.golden

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.lang.reflect.Modifier
import kotlin.system.exitProcess

/**
 * Golden-JSON parser tests. For every tests/golden/fixtures/<parser>/<case>.json (a captured or
 * hand-trimmed YouTube response), runs PARSERS[<parser>] on it, turns the result into JSON and
 * compares it with <case>.golden.json next to it.
 *
 *   args: <fixtures dir> <dir for actual output> [--update] [filter]
 *
 * --update writes the .golden.json files instead of comparing (review the diff before committing).
 * A mismatch writes <actual dir>/<parser>/<case>.json so `diff` shows what changed. Exit 1 on any
 * failure, a fixture without a .golden.json, or a folder that names no parser.
 */
private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

fun main(args: Array<String>) {
    val root = File(args.getOrNull(0) ?: "tests/golden/fixtures")
    val actualDir = File(args.getOrNull(1) ?: "build/golden-actual")
    val update = "--update" in args
    val filter = args.drop(2).firstOrNull { !it.startsWith("--") }
    var pass = 0; var fail = 0; var written = 0
    val dirs = root.listFiles { f -> f.isDirectory }?.sortedBy { it.name } ?: emptyList()
    for (dir in dirs) {
        val parser = PARSERS[dir.name]
        if (parser == null) {
            println("FAIL ${dir.name}/  no parser of that name in Parsers.kt (known: ${PARSERS.keys.joinToString()})"); fail++; continue
        }
        val cases = dir.listFiles { f -> f.name.endsWith(".json") && !f.name.endsWith(".golden.json") }!!.sortedBy { it.name }
        for (input in cases) {
            val case = "${dir.name}/${input.name.removeSuffix(".json")}"
            if (filter != null && filter !in case) continue
            val golden = File(dir, input.name.removeSuffix(".json") + ".golden.json")
            val actual = try {
                pretty.encodeToString(JsonElement.serializer(), toJson(parser(Json.parseToJsonElement(input.readText()))))
            } catch (e: Throwable) {
                println("FAIL $case  threw $e"); fail++; continue
            }
            when {
                update -> { if (!golden.exists() || golden.readText() != actual + "\n") { golden.writeText(actual + "\n"); written++; println("wrote $case.golden.json") }; pass++ }
                !golden.exists() -> { println("FAIL $case  no ${golden.name} yet: run goldenCheck with -Pupdate, review it, commit it"); fail++ }
                golden.readText().trimEnd() == actual.trimEnd() -> { println("ok   $case"); pass++ }
                else -> {
                    val out = File(actualDir, "$case.json").apply { parentFile.mkdirs(); writeText(actual + "\n") }
                    println("FAIL $case  differs from ${golden.name}:")
                    firstDiff(golden.readText(), actual).forEach { println("       $it") }
                    println("       full output: diff ${golden.path} ${out.path}")
                    fail++
                }
            }
        }
    }
    println(if (update) "\n$pass case(s), $written golden file(s) written" else "\n$pass passed, $fail failed")
    if (pass + fail == 0) { println("no fixtures found under ${root.absolutePath}"); exitProcess(1) }
    if (fail > 0) exitProcess(1)
}

private fun firstDiff(want: String, got: String): List<String> {
    val a = want.trimEnd().lines(); val b = got.trimEnd().lines()
    val i = (0 until maxOf(a.size, b.size)).firstOrNull { a.getOrNull(it) != b.getOrNull(it) } ?: return listOf("(whitespace only)")
    return listOf("line ${i + 1}:", "- ${a.getOrNull(i) ?: "<end>"}", "+ ${b.getOrNull(i) ?: "<end>"}")
}

/** Any parser result as JSON: data classes field by field (in declaration order), with "@type" for the class. */
fun toJson(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is String -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is Char -> JsonPrimitive(v.toString())
    is Enum<*> -> JsonPrimitive(v.name)
    is ClosedRange<*> -> JsonPrimitive("${v.start}..${v.endInclusive}")
    is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to toJson(x) })
    is Iterable<*> -> JsonArray(v.map(::toJson))
    is Array<*> -> JsonArray(v.map(::toJson))
    is IntArray -> JsonArray(v.map { JsonPrimitive(it) })
    is LongArray -> JsonArray(v.map { JsonPrimitive(it) })
    else -> {
        val fields = generateSequence<Class<*>>(v.javaClass) { it.superclass }.takeWhile { it != Any::class.java }
            .toList().asReversed().flatMap { it.declaredFields.toList() }
            .filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic && '$' !in it.name }
        if (fields.isEmpty()) JsonPrimitive(v.javaClass.simpleName)   // a Kotlin `object`
        else JsonObject(linkedMapOf<String, JsonElement>("@type" to JsonPrimitive(v.javaClass.simpleName)).apply {
            for (f in fields) { f.isAccessible = true; put(f.name, toJson(f.get(v))) }
        })
    }
}
