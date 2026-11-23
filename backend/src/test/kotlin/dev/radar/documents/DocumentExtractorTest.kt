package dev.radar.documents

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import java.awt.image.BufferedImage
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocumentExtractorTest {
    @Test fun `runtime smoke uses child JVM for all supported formats`() {
        documentExtractionSmoke()
    }

    private fun <T> temporary(block: (Path) -> T): T {
        val directory = Files.createTempDirectory("hradar-parser-test-")
        return try { block(directory) } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    @Test fun `plain text encodings and empty text`() = temporary { dir ->
        val path = dir.resolve("input")
        val content = "Synthetic resume Программист\nKotlin"
        for (bytes in listOf(content.toByteArray(), byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + content.toByteArray(),
            byteArrayOf(0xff.toByte(), 0xfe.toByte()) + content.toByteArray(Charsets.UTF_16LE),
            byteArrayOf(0xfe.toByte(), 0xff.toByte()) + content.toByteArray(Charsets.UTF_16BE), content.toByteArray(Charset.forName("windows-1251")))) {
            Files.write(path, bytes)
            assertEquals(ExtractionResult(content, "READY", false), DocumentParser.extract(path, "txt"))
        }
        Files.writeString(path, "")
        assertEquals("EMPTY", DocumentParser.extract(path, "txt").status)
    }

    @Test fun `binary unsupported corrupt and malformed unicode fail without data`() = temporary { dir ->
        val path = dir.resolve("input")
        for (bytes in listOf(byteArrayOf(0, 1, 2, 3), byteArrayOf(0xff.toByte(), 0xfe.toByte(), 3))) {
            Files.write(path, bytes)
            assertEquals(ExtractionResult("", "ERROR", false), DocumentParser.extract(path, "txt"))
        }
        Files.writeString(path, "synthetic invalid container")
        for (format in listOf("pdf", "docx", "rtf")) {
            assertEquals(ExtractionResult("", "ERROR", false), DocumentParser.extract(path, format))
        }
        assertEquals("ERROR", DocumentParser.extract(dir, "txt").status)
        val link = dir.resolve("link")
        Files.createSymbolicLink(link, path)
        assertEquals("ERROR", DocumentParser.extract(link, "txt").status)
    }

    @Test fun `plain text output and input are bounded`() = temporary { dir ->
        val path = dir.resolve("input")
        Files.writeString(path, "a".repeat(200_001))
        val result = DocumentParser.extract(path, "txt")
        assertEquals(200_000, result.text.length)
        assertTrue(result.truncated)
        Files.newByteChannel(path, java.nio.file.StandardOpenOption.WRITE).use { channel ->
            channel.position(8L * 1024 * 1024)
            channel.write(java.nio.ByteBuffer.wrap(byteArrayOf(65)))
        }
        assertEquals("ERROR", DocumentParser.extract(path, "txt").status)
    }

    private fun docx(path: Path, xml: String, name: String = "word/document.xml") {
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(xml.toByteArray())
            zip.closeEntry()
        }
    }

    @Test fun `docx reads only text elements with paragraph boundaries`() = temporary { dir ->
        val path = dir.resolve("input.docx")
        docx(path, """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>Synthetic</w:t><w:tab/><w:t>Resume</w:t><w:br/><w:t>Kotlin</w:t></w:r></w:p></w:body></w:document>""")
        assertEquals(ExtractionResult("Synthetic\tResume\nKotlin\n", "READY", false), DocumentParser.extract(path, "docx"))
        docx(path, """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"/>""")
        assertEquals("EMPTY", DocumentParser.extract(path, "docx").status)
    }

    @Test fun `docx refuses entities traversal missing entry and expanded zip bomb`() = temporary { dir ->
        val path = dir.resolve("input.docx")
        docx(path, """<!DOCTYPE document [<!ENTITY xxe SYSTEM "file:///synthetic-secret">]><document>&xxe;</document>""")
        assertEquals("ERROR", DocumentParser.extract(path, "docx").status)
        docx(path, "<document/>", "../../word/document.xml")
        assertEquals("ERROR", DocumentParser.extract(path, "docx").status)
        assertFalse(Files.exists(dir.resolve("word")))
        docx(path, "x".repeat(4 * 1024 * 1024 + 1))
        assertEquals("ERROR", DocumentParser.extract(path, "docx").status)
    }

    @Test fun `docx output is bounded`() = temporary { dir ->
        val path = dir.resolve("input.docx")
        docx(path, """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:t>${"x".repeat(200_001)}</w:t></w:document>""")
        val result = DocumentParser.extract(path, "docx")
        assertEquals("READY", result.status)
        assertEquals(200_000, result.text.length)
        assertTrue(result.truncated)
    }

    @Test fun `pdf embedded text scan and page cap`() = temporary { dir ->
        val path = dir.resolve("input.pdf")
        PDDocument().use { document ->
            val page = PDPage()
            document.addPage(page)
            PDPageContentStream(document, page).use { stream ->
                stream.beginText()
                stream.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                stream.newLineAtOffset(30f, 700f)
                stream.showText("Synthetic Kotlin resume")
                stream.endText()
            }
            document.save(path.toFile())
        }
        val text = DocumentParser.extract(path, "pdf")
        assertEquals("READY", text.status)
        assertTrue(text.text.contains("Synthetic Kotlin resume"))
        assertFalse(text.truncated)
        PDDocument().use { document ->
            val page = PDPage()
            document.addPage(page)
            PDPageContentStream(document, page).use { stream ->
                stream.drawImage(LosslessFactory.createFromImage(document, BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)), 20f, 20f)
            }
            document.save(path.toFile())
        }
        assertEquals("NEEDS_OCR", DocumentParser.extract(path, "pdf").status)
        PDDocument().use { document ->
            repeat(201) { document.addPage(PDPage()) }
            document.save(path.toFile())
        }
        assertTrue(DocumentParser.extract(path, "pdf").truncated)
    }

    @Test fun `subprocess returns private IPC result and terminates within deadline`() = temporary { dir ->
        val path = dir.resolve("input.txt")
        Files.writeString(path, "Synthetic child process text")
        assertEquals(ExtractionResult("Synthetic child process text", "READY", false), DocumentExtractor().extract(path, "txt"))
        val before = System.nanoTime()
        assertEquals(ExtractionResult("", "ERROR", false), DocumentExtractor(timeoutMillis = 1).extract(path, "txt"))
        assertTrue((System.nanoTime() - before) / 1_000_000 < 6_000)
        assertEquals("ERROR", DocumentExtractor().extract(dir.resolve("absent"), "pdf").status)
    }
}
