package app.tubehaus.ui

import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * A saved video's two files (YouTube's DASH: picture and sound apart) put into one file that any
 * player opens, without re-encoding and without ffmpeg: fragmented MP4 + M4A into one MP4, WebM +
 * WebM into one WebM (Matroska when it carries extras). The pieces are copied as they are and laid
 * out in time order. [Extras] go in too: subtitle tracks (MP4 timed text `tx3g`, Matroska
 * `D_WEBVTT/SUBTITLES`), chapters (MP4 `chpl`, Matroska Chapters), the thumbnail as cover art (MP4
 * `covr`, a Matroska attachment) and the title.
 */
object Mux {
    /** a subtitle track: its language ("en", "pt-BR"), its name, and the WebVTT file */
    class Sub(val lang: String, val name: String, val file: File)
    class Extras(
        val title: String = "",
        val artist: String = "",
        val captions: List<Sub> = emptyList(),
        /** (title, start in ms) */
        val chapters: List<Pair<String, Long>> = emptyList(),
        /** a JPEG */
        val cover: File? = null,
    ) {
        fun isEmpty() = captions.isEmpty() && chapters.isEmpty() && cover == null
    }

    fun join(video: File, audio: File, out: File, extras: Extras = Extras()) {
        val webm = video.name.endsWith(".webm")
        val tmp = File(out.path + ".part")
        try {
            tmp.outputStream().buffered(1 shl 20).use { o -> if (webm) webm(video, audio, o, extras) else mp4(video, audio, o, extras) }
            if (out.exists()) out.delete()
            if (!tmp.renameTo(out)) error("rename")
        } finally { tmp.delete() }
    }

    private fun RandomAccessFile.bytes(at: Long, n: Int): ByteArray { seek(at); return ByteArray(n).also { readFully(it) } }

    private fun copy(f: RandomAccessFile, at: Long, len: Long, o: OutputStream) {
        f.seek(at)
        val buf = ByteArray(1 shl 16)
        var left = len
        while (left > 0) {
            val n = f.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
            if (n < 0) error("short file")
            o.write(buf, 0, n); left -= n
        }
    }

    private fun u32(b: ByteArray, i: Int) = ((b[i].toLong() and 0xff) shl 24) or ((b[i + 1].toLong() and 0xff) shl 16) or
        ((b[i + 2].toLong() and 0xff) shl 8) or (b[i + 3].toLong() and 0xff)
    private fun put32(b: ByteArray, i: Int, v: Long) { for (k in 0..3) b[i + k] = (v shr (24 - 8 * k)).toByte() }
    private fun u64(b: ByteArray, i: Int) = (u32(b, i) shl 32) or u32(b, i + 4)

    // ------------------------------------------------------------------ MP4

    private class Box(val type: String, val at: Long, val size: Long, val head: Int)

    private fun boxes(f: RandomAccessFile, from: Long, to: Long): List<Box> {
        val l = ArrayList<Box>()
        var p = from
        while (p + 8 <= to) {
            val h = f.bytes(p, 8)
            var size = u32(h, 0)
            val type = String(h, 4, 4, Charsets.ISO_8859_1)
            var head = 8
            if (size == 1L) { size = u64(f.bytes(p + 8, 8), 0); head = 16 } else if (size == 0L) size = to - p
            if (size < head) break
            l += Box(type, p, size, head); p += size
        }
        return l
    }

    /** children of a box held in memory: (type, start, end) */
    private fun kids(b: ByteArray, from: Int, to: Int): List<Triple<String, Int, Int>> {
        val l = ArrayList<Triple<String, Int, Int>>()
        var p = from
        while (p + 8 <= to) {
            val size = u32(b, p).toInt()
            if (size < 8 || p + size > to) break
            l += Triple(String(b, p + 4, 4, Charsets.ISO_8859_1), p, p + size); p += size
        }
        return l
    }

    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val n = 8 + parts.sumOf { it.size }
        val b = ByteArray(n); put32(b, 0, n.toLong())
        type.toByteArray(Charsets.ISO_8859_1).copyInto(b, 4)
        var p = 8
        for (x in parts) { x.copyInto(b, p); p += x.size }
        return b
    }

    private class Track(val f: RandomAccessFile, val trak: ByteArray, val trex: ByteArray, val scale: Long, val frags: List<Pair<Box, Box>>)

    private fun track(f: RandomAccessFile, id: Int): Pair<Track, ByteArray> {
        val top = boxes(f, 0, f.length())
        val moovBox = top.first { it.type == "moov" }
        val moov = f.bytes(moovBox.at, moovBox.size.toInt())
        val mk = kids(moov, 8, moov.size)
        val trakR = mk.first { it.first == "trak" }
        val trak = moov.copyOfRange(trakR.second, trakR.third)
        val mvhdR = mk.first { it.first == "mvhd" }
        val mvhd = moov.copyOfRange(mvhdR.second, mvhdR.third)
        val mvexR = mk.first { it.first == "mvex" }
        val trexR = kids(moov, mvexR.second + 8, mvexR.third).first { it.first == "trex" }
        val trex = moov.copyOfRange(trexR.second, trexR.third)
        // tkhd and trex take the new track number
        for ((t, s, _) in kids(trak, 8, trak.size)) if (t == "tkhd") put32(trak, s + 12 + if (trak[s + 8].toInt() == 1) 16 else 8, id.toLong())
        put32(trex, 12, id.toLong())
        var scale = 1000L
        for ((t, s, e) in kids(trak, 8, trak.size)) if (t == "mdia") for ((t2, s2, _) in kids(trak, s + 8, e)) if (t2 == "mdhd")
            scale = u32(trak, s2 + 12 + if (trak[s2 + 8].toInt() == 1) 16 else 8)
        val frags = ArrayList<Pair<Box, Box>>()
        var moof: Box? = null
        for (b in top) when (b.type) {
            "moof" -> moof = b
            "mdat" -> moof?.let { frags += it to b; moof = null }
        }
        return Track(f, trak, trex, scale, frags) to mvhd
    }

    private fun mp4(video: File, audio: File, o: OutputStream, x: Extras) {
        RandomAccessFile(video, "r").use { vf -> RandomAccessFile(audio, "r").use { af ->
            val (v, mvhd) = track(vf, 1)
            val (a, _) = track(af, 2)
            val subs = x.captions.mapNotNull { s -> runCatching { cues(s.file.readText()) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { s to it } }
            put32(mvhd, mvhd.size - 4, 3L + subs.size) // next_track_ID
            val ftyp = boxes(vf, 0, vf.length()).first { it.type == "ftyp" }
            o.write(vf.bytes(ftyp.at, ftyp.size.toInt()))
            val subTraks = subs.mapIndexed { i, (s, _) -> subTrak(3 + i, s) }
            val subTrex = subs.indices.map { i -> full("trex", 0, u32b(3L + i), u32b(1), u32b(0), u32b(0), u32b(0)) }
            o.write(box("moov", mvhd, v.trak, a.trak, *subTraks.toTypedArray(), box("mvex", v.trex, a.trex, *subTrex.toTypedArray()), udta(x)))
            // the subtitles first: one fragment a track, all its cues, from the start
            var seq0 = 1L
            subs.forEachIndexed { i, (_, cs) -> o.write(subFragment(3 + i, seq0++, cs)) }
            // each fragment with its start in seconds, both tracks in time order
            class Frag(val t: Track, val moof: Box, val mdat: Box, val time: Double)
            fun times(t: Track) = t.frags.map { (moof, mdat) ->
                val m = t.f.bytes(moof.at, moof.size.toInt())
                var time = 0L
                for ((k, s, e) in kids(m, 8, m.size)) if (k == "traf") for ((k2, s2, _) in kids(m, s + 8, e)) if (k2 == "tfdt")
                    time = if (m[s2 + 8].toInt() == 1) u64(m, s2 + 12) else u32(m, s2 + 12)
                Frag(t, moof, mdat, time.toDouble() / t.scale)
            }
            var seq = seq0
            for (fr in (times(v) + times(a)).sortedBy { it.time }) {
                val m = fr.t.f.bytes(fr.moof.at, fr.moof.size.toInt())
                val id = if (fr.t === v) 1L else 2L
                for ((k, s, e) in kids(m, 8, m.size)) {
                    if (k == "mfhd") put32(m, s + 12, seq++)
                    if (k == "traf") for ((k2, s2, _) in kids(m, s + 8, e)) if (k2 == "tfhd") {
                        // offsets from the file's start would point into the old file
                        if (m[s2 + 11].toInt() and 1 != 0) error("base-data-offset")
                        put32(m, s2 + 12, id)
                    }
                }
                o.write(m)
                copy(fr.t.f, fr.mdat.at, fr.mdat.size, o)
            }
        } }
    }

    // ------------------------------------------------------------------ MP4 extras

    /** a cue: from, to (ms), its text */
    class Cue(val from: Long, val to: Long, val text: String)

    /** the cues of a WebVTT file (as Vtt.clean leaves it) */
    fun cues(vtt: String): List<Cue> {
        val time = Regex("""((?:\d+:)?\d{1,2}:\d{2}[.,]\d{3})\s*-->\s*((?:\d+:)?\d{1,2}:\d{2}[.,]\d{3})""")
        fun ms(t: String): Long {
            val p = t.replace(',', '.').split(':')
            val sec = p.last().toDouble()
            val min = p.getOrNull(p.size - 2)?.toLong() ?: 0
            val hr = if (p.size == 3) p[0].toLong() else 0
            return ((hr * 3600 + min * 60) * 1000) + (sec * 1000).toLong()
        }
        val out = ArrayList<Cue>()
        val lines = vtt.replace("\r\n", "\n").split('\n')
        var i = 0
        while (i < lines.size) {
            val m = time.find(lines[i])
            if (m == null) { i++; continue }
            val text = StringBuilder()
            i++
            while (i < lines.size && lines[i].isNotBlank()) { if (text.isNotEmpty()) text.append('\n'); text.append(lines[i]); i++ }
            val a = ms(m.groupValues[1]); val b = ms(m.groupValues[2])
            if (b > a && text.isNotBlank()) out += Cue(a, b, text.toString())
        }
        return out.sortedBy { it.from }
    }

    private fun u32b(v: Long) = ByteArray(4).also { put32(it, 0, v) }
    private fun u16b(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
    private fun u64b(v: Long) = u32b(v ushr 32) + u32b(v and 0xffffffffL)
    /** a full box: version, flags, then the parts */
    private fun full(type: String, flags: Int, vararg parts: ByteArray, version: Int = 0) =
        box(type, byteArrayOf(version.toByte(), (flags shr 16).toByte(), (flags shr 8).toByte(), flags.toByte()), *parts)

    /** ISO 639-2/T, as MP4 and Matroska name languages */
    private val LANG3 = mapOf(
        "en" to "eng", "de" to "deu", "es" to "spa", "fr" to "fra", "it" to "ita", "pt" to "por", "ru" to "rus", "ja" to "jpn",
        "ko" to "kor", "zh" to "zho", "ar" to "ara", "hi" to "hin", "nl" to "nld", "pl" to "pol", "tr" to "tur", "sv" to "swe",
        "no" to "nor", "nb" to "nob", "da" to "dan", "fi" to "fin", "cs" to "ces", "el" to "ell", "he" to "heb", "iw" to "heb",
        "id" to "ind", "th" to "tha", "vi" to "vie", "uk" to "ukr", "ro" to "ron", "hu" to "hun", "fa" to "fas", "bn" to "ben",
        "ta" to "tam", "te" to "tel", "ms" to "msa", "fil" to "fil", "ca" to "cat", "hr" to "hrv", "sr" to "srp", "sk" to "slk",
        "bg" to "bul", "lt" to "lit", "lv" to "lav", "et" to "est", "sl" to "slv", "ur" to "urd", "sw" to "swa",
    )
    fun lang3(tag: String): String = LANG3[tag.substringBefore('-').lowercase()] ?: "und"

    private fun mp4Lang(tag: String): Int {
        val l = lang3(tag)
        return ((l[0].code - 0x60) shl 10) or ((l[1].code - 0x60) shl 5) or (l[2].code - 0x60)
    }

    private val MATRIX = longArrayOf(0x10000, 0, 0, 0, 0x10000, 0, 0, 0, 0x40000000).fold(ByteArray(0)) { b, v -> b + u32b(v) }

    /** a 3GPP timed-text sample entry ("tx3g"), as ffmpeg writes it: plain white text, bottom centre */
    private val TX3G = "00000000000000010000000001ff000000ff00000000000000000000000000010010ffffffff00000012667461620001000105417269616c"
        .chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /**
     * a subtitle track as MP4 text ("tx3g", which QuickTime, iOS, ffmpeg/mpv and VLC all read); its
     * samples come in [subFragment]
     */
    private fun subTrak(id: Int, s: Sub): ByteArray {
        val tkhd = full("tkhd", 3, u32b(0), u32b(0), u32b(id.toLong()), u32b(0), u32b(0), ByteArray(8), u16b(0), u16b(0), u16b(0), u16b(0), MATRIX, u32b(0), u32b(0))
        val mdhd = full("mdhd", 0, u32b(0), u32b(0), u32b(1000), u32b(0), u16b(mp4Lang(s.lang)), u16b(0))
        val hdlr = full("hdlr", 0, u32b(0), "sbtl".toByteArray(), ByteArray(12), (s.name.ifEmpty { s.lang } + "\u0000").toByteArray(Charsets.UTF_8))
        val dinf = box("dinf", full("dref", 0, u32b(1), full("url ", 1)))
        val stbl = box("stbl", full("stsd", 0, u32b(1), box("tx3g", TX3G)), full("stts", 0, u32b(0)), full("stsc", 0, u32b(0)),
            full("stsz", 0, u32b(0), u32b(0)), full("stco", 0, u32b(0)))
        return box("trak", tkhd, box("mdia", mdhd, hdlr, box("minf", full("nmhd", 0), dinf, stbl)))
    }

    /** every cue of a track in one fragment: a sample a stretch of time (overlapping cues joined), gaps empty */
    private fun subFragment(id: Int, seq: Long, cues: List<Cue>): ByteArray {
        val marks = (cues.flatMap { listOf(it.from, it.to) } + 0L).distinct().sorted()
        val samples = ArrayList<Pair<Long, ByteArray>>()
        for (k in 0 until marks.size - 1) {
            val a = marks[k]; val b = marks[k + 1]
            val text = cues.filter { it.from <= a && it.to >= b }.joinToString("\n") { it.text }.toByteArray(Charsets.UTF_8)
                .let { if (it.size > 65535) it.copyOf(65535) else it }
            samples += (b - a) to (u16b(text.size) + text)
        }
        val n = samples.size
        fun moof(offset: Int) = box("moof", full("mfhd", 0, u32b(seq)),
            box("traf", full("tfhd", 0x020000, u32b(id.toLong())), full("tfdt", 0, u64b(0), version = 1),
                full("trun", 0x000301, u32b(n.toLong()), u32b(offset.toLong()), *samples.map { (d, b) -> u32b(d) + u32b(b.size.toLong()) }.toTypedArray())))
        val size = moof(0).size
        val mdat = box("mdat", *samples.map { it.second }.toTypedArray())
        return moof(size + 8) + mdat
    }

    /** the file's title, artist, chapters (Nero `chpl`, read by ffmpeg, mpv, VLC) and cover art */
    private fun udta(x: Extras): ByteArray {
        val parts = ArrayList<ByteArray>()
        if (x.chapters.isNotEmpty()) {
            val list = x.chapters.take(255)
            parts += full("chpl", 0, u32b(0), byteArrayOf(list.size.toByte()), *list.map { (t, ms) ->
                val name = t.toByteArray(Charsets.UTF_8).let { if (it.size > 255) it.copyOf(255) else it }
                u64b(ms * 10_000) + byteArrayOf(name.size.toByte()) + name
            }.toTypedArray(), version = 1)
        }
        val items = ArrayList<ByteArray>()
        fun text(type: String, v: String) { if (v.isNotEmpty()) items += box(type, box("data", u32b(1), u32b(0), v.toByteArray(Charsets.UTF_8))) }
        text("\u00A9nam", x.title)
        text("\u00A9ART", x.artist)
        x.cover?.let { items += box("covr", box("data", u32b(13), u32b(0), it.readBytes())) }
        if (items.isNotEmpty()) parts += full("meta", 0, full("hdlr", 0, u32b(0), "mdir".toByteArray(), "appl".toByteArray(), ByteArray(9)),
            box("ilst", *items.toTypedArray()))
        return if (parts.isEmpty()) ByteArray(0) else box("udta", *parts.toTypedArray())
    }

    // ------------------------------------------------------------------ WebM

    private const val SEGMENT = 0x18538067L
    private const val INFO = 0x1549A966L
    private const val TRACKS = 0x1654AE6BL
    private const val ENTRY = 0xAEL
    private const val NUMBER = 0xD7L
    private const val UID = 0x73C5L
    private const val CLUSTER = 0x1F43B675L
    private const val TIMESTAMP = 0xE7L
    private const val SIMPLE = 0xA3L
    private const val GROUP = 0xA0L
    private const val BLOCK = 0xA1L

    /** an element: id, where its data starts, its data length (-1 unknown), where it ends */
    private class El(val id: Long, val at: Long, val data: Long, val len: Long) { val end get() = data + len }

    private fun vint(f: RandomAccessFile, p: Long, keepMarker: Boolean): Pair<Long, Int> {
        f.seek(p)
        val first = f.read()
        var n = 1
        while (n <= 8 && first and (0x80 shr (n - 1)) == 0) n++
        var v = if (keepMarker) first.toLong() else (first and (0xff shr n)).toLong()
        var ones = (first and (0xff shr n)) == (0xff shr n)
        for (i in 1 until n) { val b = f.read(); v = (v shl 8) or b.toLong(); if (b != 0xff) ones = false }
        return (if (!keepMarker && ones) -1L else v) to n
    }

    private fun el(f: RandomAccessFile, p: Long): El {
        val (id, a) = vint(f, p, true)
        val (len, b) = vint(f, p + a, false)
        return El(id, p, p + a + b, len)
    }

    private fun children(f: RandomAccessFile, from: Long, to: Long): List<El> {
        val l = ArrayList<El>()
        var p = from
        while (p < to) {
            val e = el(f, p)
            if (e.len < 0) { l += El(e.id, e.at, e.data, to - e.data); break }
            l += e; p = e.end
        }
        return l
    }

    private fun idBytes(id: Long): ByteArray {
        val n = when { id > 0xFFFFFF -> 4; id > 0xFFFF -> 3; id > 0xFF -> 2; else -> 1 }
        return ByteArray(n) { (id shr (8 * (n - 1 - it))).toByte() }
    }
    private fun sizeBytes(len: Long): ByteArray {
        val b = ByteArray(8); b[0] = 0x01
        for (i in 1..7) b[i] = (len shr (8 * (7 - i))).toByte()
        return b
    }
    private fun ebml(id: Long, data: ByteArray) = idBytes(id) + sizeBytes(data.size.toLong()) + data

    /** a TrackEntry with its number and UID changed */
    private fun entry(f: RandomAccessFile, e: El, number: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (c in children(f, e.data, e.end)) when (c.id) {
            NUMBER -> out.write(ebml(NUMBER, byteArrayOf(number.toByte())))
            UID -> out.write(ebml(UID, byteArrayOf(number.toByte())))
            else -> out.write(f.bytes(c.at, (c.end - c.at).toInt()))
        }
        return ebml(ENTRY, out.toByteArray())
    }

    private class Clu(val f: RandomAccessFile, val e: El, val time: Long, val number: Int)

    private fun uint(v: Long): ByteArray {
        var n = 1
        while (n < 8 && (v ushr (8 * n)) != 0L) n++
        return ByteArray(n) { (v shr (8 * (n - 1 - it))).toByte() }
    }
    private fun ebmlUint(id: Long, v: Long) = ebml(id, uint(v))
    private fun ebmlStr(id: Long, v: String) = ebml(id, v.toByteArray(Charsets.UTF_8))

    /** a Matroska file's head (with extras the file is Matroska, not WebM) */
    private fun matroskaHead() = ebml(0x1A45DFA3L, ebmlUint(0x4286, 1) + ebmlUint(0x42F7, 1) + ebmlUint(0x42F2, 4) + ebmlUint(0x42F3, 8) +
        ebmlStr(0x4282, "matroska") + ebmlUint(0x4287, 4) + ebmlUint(0x4285, 2))

    private fun subEntry(number: Int, s: Sub) = ebml(ENTRY, ebmlUint(NUMBER, number.toLong()) + ebmlUint(UID, number.toLong()) +
        ebmlUint(0x83, 0x11) + ebmlUint(0x88, 0) + ebmlUint(0x9C, 0) + ebmlStr(0x86, "D_WEBVTT/SUBTITLES") +
        ebmlStr(0x22B59C, lang3(s.lang)) + ebmlStr(0x22B59D, s.lang) + ebmlStr(0x536E, s.name.ifEmpty { s.lang }))

    /** Chapters: one edition, an atom a chapter (start, end = the next one's start) */
    private fun chaptersEl(list: List<Pair<String, Long>>, scaleNs: Long): ByteArray {
        val atoms = list.mapIndexed { i, (t, ms) ->
            ebml(0xB6, ebmlUint(0x73C4, i + 1L) + ebmlUint(0x91, ms * 1_000_000) +
                (list.getOrNull(i + 1)?.let { ebmlUint(0x92, it.second * 1_000_000) } ?: ByteArray(0)) +
                ebml(0x80, ebmlStr(0x85, t) + ebmlStr(0x437C, "eng")))
        }
        return ebml(0x1043A770, ebml(0x45B9, ebmlUint(0x45BC, 1) + atoms.fold(ByteArray(0)) { a, b -> a + b }))
    }

    private fun coverEl(f: File) = ebml(0x1941A469, ebml(0x61A7, ebmlStr(0x466E, "cover.jpg") + ebmlStr(0x4660, "image/jpeg") +
        ebml(0x465C, f.readBytes()) + ebmlUint(0x46AE, 1)))

    /** a cue as its own cluster: at its start, one block with its duration */
    private fun cueCluster(number: Int, c: Cue, scaleNs: Long): Pair<Long, ByteArray> {
        val t = c.from * 1_000_000 / scaleNs
        val dur = ((c.to - c.from) * 1_000_000 / scaleNs).coerceAtLeast(1)
        // WebM's WebVTT block: the cue's id line, its settings line (both empty here), then its text
        val block = byteArrayOf((0x80 or number).toByte(), 0, 0, 0) + ("\n\n" + c.text).toByteArray(Charsets.UTF_8)
        return t to ebml(CLUSTER, ebmlUint(TIMESTAMP, t) + ebml(GROUP, ebml(BLOCK, block) + ebmlUint(0x9B, dur)))
    }

    private fun webm(video: File, audio: File, o: OutputStream, x: Extras) {
        RandomAccessFile(video, "r").use { vf -> RandomAccessFile(audio, "r").use { af ->
            fun parts(f: RandomAccessFile, number: Int): Triple<El, List<El>, ByteArray> {
                val top = children(f, 0, f.length())
                val seg = top.first { it.id == SEGMENT }
                val kids = children(f, seg.data, seg.end)
                val tracks = kids.first { it.id == TRACKS }
                val entryEl = children(f, tracks.data, tracks.end).first { it.id == ENTRY }
                return Triple(top.first(), kids, entry(f, entryEl, number))
            }
            val (head, vk, ve) = parts(vf, 1)
            val (_, ak, ae) = parts(af, 2)
            if (x.isEmpty()) o.write(vf.bytes(head.at, (head.end - head.at).toInt())) else o.write(matroskaHead())
            o.write(idBytes(SEGMENT)); o.write(byteArrayOf(0x01, -1, -1, -1, -1, -1, -1, -1)) // size unknown
            val info = vk.firstOrNull { it.id == INFO }
            // the time unit of the clusters (1 ms unless the file says otherwise)
            val scaleNs = info?.let { i -> children(vf, i.data, i.end).firstOrNull { it.id == 0x2AD7B1L } }?.let { ts ->
                var v = 0L; vf.seek(ts.data); repeat(ts.len.toInt()) { v = (v shl 8) or vf.read().toLong() }; v
            }?.takeIf { it > 0 } ?: 1_000_000L
            info?.let { o.write(vf.bytes(it.at, (it.end - it.at).toInt())) }
            val subs = x.captions.mapNotNull { s -> runCatching { cues(s.file.readText()) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { s to it } }
            o.write(ebml(TRACKS, ve + ae + subs.mapIndexed { i, (s, _) -> subEntry(3 + i, s) }.fold(ByteArray(0)) { a, b -> a + b }))
            if (x.chapters.isNotEmpty()) o.write(chaptersEl(x.chapters, scaleNs))
            x.cover?.let { o.write(coverEl(it)) }
            val cueClusters = subs.flatMapIndexed { i, (_, cs) -> cs.map { cueCluster(3 + i, it, scaleNs) } }.sortedBy { it.first }
            var nextCue = 0
            fun clusters(f: RandomAccessFile, k: List<El>, n: Int) = k.filter { it.id == CLUSTER }.map { c ->
                val t = children(f, c.data, c.end).firstOrNull { it.id == TIMESTAMP }?.let { ts ->
                    var v = 0L; f.seek(ts.data); repeat(ts.len.toInt()) { v = (v shl 8) or f.read().toLong() }; v
                } ?: 0L
                Clu(f, c, t, n)
            }
            for (c in (clusters(vf, vk, 1) + clusters(af, ak, 2)).sortedBy { it.time }) {
                while (nextCue < cueClusters.size && cueClusters[nextCue].first <= c.time) o.write(cueClusters[nextCue++].second)
                val start = c.e.data
                val b = c.f.bytes(start, c.e.len.toInt())
                // every block's track number: one byte (0x81, 0x82), so it changes in place
                fun mark(from: Long, to: Long) {
                    for (x in children(c.f, from, to)) when (x.id) {
                        SIMPLE, BLOCK -> b[(x.data - start).toInt()] = (0x80 or c.number).toByte()
                        GROUP -> mark(x.data, x.end)
                    }
                }
                mark(start, start + c.e.len)
                o.write(ebml(CLUSTER, b))
            }
            while (nextCue < cueClusters.size) o.write(cueClusters[nextCue++].second)
        } }
    }
}
