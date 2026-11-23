package dev.radar.documents

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.Writer
import java.net.URLClassLoader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory

/** Parsing happens in a disposable, memory/time limited JVM. Never include document data in diagnostics. */
data class ExtractionResult(val text: String, val status: String, val truncated: Boolean)

class DocumentExtractor(private val timeoutMillis: Long = 20_000) {
    init { require(timeoutMillis in 1..60_000) }

    fun extract(path: Path, format: String): ExtractionResult {
        var child: Process? = null
        var directory: Path? = null
        try {
            directory = Files.createTempDirectory("hradar-extract-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            val output = Files.createFile(directory.resolve("result"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            val builder = ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx128m", "-XX:MaxDirectMemorySize=32m", "-XX:+ExitOnOutOfMemoryError",
                "-Djava.io.tmpdir=$directory", "-Duser.home=$directory", "-cp", workerClasspath(),
                DocumentExtractionWorker::class.java.name, path.toAbsolutePath().toString(), format, output.toString(),
            ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
            // The worker needs no Telegram credentials, profile, shell, or inherited application settings.
            builder.environment().clear()
            child = builder.start()
            if (!child.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) return failure()
            if (child.exitValue() != 0 || Files.size(output) > MAX_TEXT * 4L + 64) return failure()
            DataInputStream(Files.newInputStream(output)).use { input ->
                val status = input.readUTF()
                val truncated = input.readBoolean()
                val size = input.readInt()
                if (status !in setOf("READY", "EMPTY", "NEEDS_OCR", "ERROR") || size !in 0..MAX_TEXT * 4) return failure()
                val bytes = input.readNBytes(size)
                if (bytes.size != size || input.read() != -1) return failure()
                val text = decode(bytes, Charsets.UTF_8)
                if (text.length > MAX_TEXT) return failure()
                return ExtractionResult(text, status, truncated)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return failure()
        } catch (_: Exception) {
            return failure()
        } finally {
            child?.let {
                if (it.isAlive) it.destroyForcibly()
                // Reap before removing private scratch files (also on caller interruption).
                var interrupted = Thread.interrupted()
                try { it.waitFor(5, TimeUnit.SECONDS) } catch (_: InterruptedException) { interrupted = true }
                if (interrupted) Thread.currentThread().interrupt()
            }
            directory?.let { dir ->
                runCatching { Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
            }
        }
    }

    private fun workerClasspath(): String {
        val entries = System.getProperty("java.class.path").split(java.io.File.pathSeparator).toMutableSet()
        var loader: ClassLoader? = javaClass.classLoader
        while (loader != null) {
            if (loader is URLClassLoader) loader.urLs.filter { it.protocol == "file" }.forEach { entries += Path.of(it.toURI()).toString() }
            loader = loader.parent
        }
        return entries.joinToString(java.io.File.pathSeparator)
    }
}

private const val MAX_TEXT = 200_000
private const val MAX_INPUT = 32L * 1024 * 1024
private const val MAX_XML = 4 * 1024 * 1024
private const val MAX_PAGES = 200
private fun failure() = ExtractionResult("", "ERROR", false)
private fun decode(bytes: ByteArray, charset: Charset): String = charset.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(bytes)).toString()

/** Only the worker and account-free parser tests call this directly. */
internal object DocumentParser {
    fun extract(path: Path, format: String): ExtractionResult = try {
        require(Files.isRegularFile(path, NOFOLLOW_LINKS) && Files.size(path) <= MAX_INPUT)
        when (format.lowercase().removePrefix(".")) {
            "pdf", "application/pdf" -> pdf(path)
            "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> docx(path)
            "txt", "text/plain" -> txt(path)
            else -> failure()
        }
    } catch (_: Exception) { failure() }

    private fun pdf(path: Path): ExtractionResult = Loader.loadPDF(path.toFile()).use { document ->
        if (document.isEncrypted || !document.currentAccessPermission.canExtractContent()) return failure()
        val writer = LimitedText()
        val stripper = PDFTextStripper().apply { endPage = MAX_PAGES }
        try { stripper.writeText(document, writer) } catch (_: TextLimit) { writer.truncated = true }
        writer.result(if (writer.value.isBlank()) "NEEDS_OCR" else "READY", document.numberOfPages > MAX_PAGES)
    }

    private fun docx(path: Path): ExtractionResult = ZipFile(path.toFile()).use { zip ->
        require(zip.size() <= 10_000)
        val entries = zip.entries().asSequence().filter { it.name == "word/document.xml" }.toList()
        require(entries.size == 1)
        val entry = entries.single()
        require(!entry.isDirectory && entry.size in 0..MAX_XML.toLong())
        val xml = zip.getInputStream(entry).use { it.readNBytes(MAX_XML + 1) }
        require(xml.size <= MAX_XML)
        val writer = LimitedText()
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }
        val reader = factory.newSAXParser().xmlReader.apply {
            setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }
        val handler = object : DefaultHandler() {
            var inText = false
            override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
                if (uri == WORD_NS) {
                    if (localName == "t") inText = true
                    if (localName == "tab") writer.write("\t")
                    if (localName == "br" || localName == "cr") writer.write("\n")
                }
            }
            override fun endElement(uri: String, localName: String, qName: String) {
                if (uri == WORD_NS) {
                    if (localName == "t") inText = false
                    if (localName == "p") writer.write("\n")
                }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) { if (inText) writer.write(ch, start, length) }
            override fun resolveEntity(publicId: String?, systemId: String?): InputSource = throw SAXException("External entities prohibited")
        }
        reader.contentHandler = handler
        reader.errorHandler = handler
        reader.entityResolver = handler
        try { reader.parse(InputSource(xml.inputStream())) } catch (_: TextLimit) { writer.truncated = true }
        writer.result()
    }

    private fun txt(path: Path): ExtractionResult {
        // Limit decoded allocations as well as the final indexed text.
        require(Files.size(path) <= 8L * 1024 * 1024)
        val bytes = Files.readAllBytes(path)
        val text = when {
            bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> decode(bytes.copyOfRange(2, bytes.size), Charsets.UTF_16LE)
            bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> decode(bytes.copyOfRange(2, bytes.size), Charsets.UTF_16BE)
            else -> try { decode(bytes, Charsets.UTF_8).removePrefix("\uFEFF") } catch (_: CharacterCodingException) {
                decode(bytes, Charset.forName("windows-1251")).also { value ->
                    require(value.any { it in '\u0400'..'\u04ff' })
                }
            }
        }
        require(text.none { (it.code < 32 && it !in "\n\r\t") || it in '\u007f'..'\u009f' || it == '\ufffd' })
        val writer = LimitedText()
        try { writer.write(text) } catch (_: TextLimit) { writer.truncated = true }
        return writer.result()
    }

    private const val WORD_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
}

private class TextLimit : IOException()
private class LimitedText : Writer() {
    val value = StringBuilder()
    var truncated = false
    override fun write(chars: CharArray, offset: Int, length: Int) {
        val remaining = MAX_TEXT - value.length
        value.append(chars, offset, minOf(length, remaining))
        if (length > remaining) throw TextLimit()
    }
    override fun flush() = Unit
    override fun close() = Unit
    fun result(emptyStatus: String = if (value.isBlank()) "EMPTY" else "READY", extraTruncated: Boolean = false): ExtractionResult {
        // Avoid producing an invalid UTF-16 tail when the cap splits a supplementary character.
        if (value.isNotEmpty() && value.last().isHighSurrogate()) value.setLength(value.length - 1)
        return ExtractionResult(value.toString(), emptyStatus, truncated || extraTruncated)
    }
}

/** Private binary IPC only; stdout/stderr are discarded by the parent. */
object DocumentExtractionWorker {
    @JvmStatic fun main(args: Array<String>) {
        if (args.size != 3) return
        val result = DocumentParser.extract(Path.of(args[0]), args[1])
        DataOutputStream(Files.newOutputStream(Path.of(args[2]))).use { output ->
            val text = result.text.toByteArray(Charsets.UTF_8)
            output.writeUTF(result.status)
            output.writeBoolean(result.truncated)
            output.writeInt(text.size)
            output.write(text)
        }
    }
}

/** Account-free packaged-runtime check, using only disposable synthetic input. */
fun documentExtractionSmoke() {
    val directory = Files.createTempDirectory("hradar-extraction-smoke-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
    try {
        val extractor = DocumentExtractor()
        val text = directory.resolve("sample.txt")
        Files.write(text, "Synthetic Программист".toByteArray(Charset.forName("windows-1251")))
        check(extractor.extract(text, "txt").let { it.status == "READY" && it.text == "Synthetic Программист" }) {
            "Document TXT runtime check failed"
        }
        val pdf = directory.resolve("sample.pdf")
        org.apache.pdfbox.pdmodel.PDDocument().use { document ->
            val page = org.apache.pdfbox.pdmodel.PDPage()
            document.addPage(page)
            org.apache.pdfbox.pdmodel.PDPageContentStream(document, page).use { stream ->
                stream.beginText()
                stream.setFont(org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12f)
                stream.newLineAtOffset(30f, 700f)
                stream.showText("Synthetic runtime resume")
                stream.endText()
            }
            document.save(pdf.toFile())
        }
        check(extractor.extract(pdf, "pdf").let { it.status == "READY" && it.text.contains("Synthetic runtime resume") }) {
            "Document PDF runtime check failed"
        }
        val docx = directory.resolve("sample.docx")
        java.util.zip.ZipOutputStream(Files.newOutputStream(docx)).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("word/document.xml"))
            zip.write("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:p><w:r><w:t>Synthetic DOCX runtime</w:t></w:r></w:p></w:document>""".toByteArray())
            zip.closeEntry()
        }
        check(extractor.extract(docx, "docx").let { it.status == "READY" && it.text.contains("Synthetic DOCX runtime") }) {
            "Document DOCX runtime check failed"
        }
    } finally {
        Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }
}
