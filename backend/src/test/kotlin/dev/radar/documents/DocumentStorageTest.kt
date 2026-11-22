package dev.radar.documents

import dev.radar.research.*
import java.nio.file.Files
import kotlin.test.*
import org.junit.jupiter.api.Test

class DocumentStorageTest {
    @Test fun `separate publications share hash but retain snapshots and literal safe search`() {
        val dir = Files.createTempDirectory("document-test")
        Repositories(dir.resolve("test.db")).use { repo ->
            fun store(id: String, message: Long) = repo.saveDocument(StoredDocument(
                DocumentRecord(id,ResearchMessage(message,1,0,null,"Synthetic",null,sourceKey="test"),"resume.txt","text/plain",7,"txt",state="READY",hasFile=true,hasText=true),1,"remote$id","a".repeat(64),"Kotlin <script>alert(1)</script>"))
            store("first",1); store("second",2)
            assertEquals(2,repo.documents(10,0).total)
            assertEquals(7L,repo.documentBytes())
            assertEquals(2,repo.searchDocuments("Kotlin",10,0).total)
            assertEquals(1,repo.searchDocuments("Kotlin",1,1).items.size)
            assertEquals(0,repo.searchDocuments("\" OR *",10,0).total)
            assertEquals(2,repo.documentIds().size)
        }
        Repositories(dir.resolve("test.db")).use { assertEquals(2,it.documents(10,0).total) }
        dir.toFile().deleteRecursively()
    }
    @Test fun `local search never silently discards later query words`() {
        val dir=Files.createTempDirectory("document-long-query-")
        try { Repositories(dir.resolve("db")).use { repo ->
            val terms=(1..20).map { "skill$it" }
            repo.saveDocument(StoredDocument(DocumentRecord("one",ResearchMessage(1,1,0,null,"Synthetic",null,sourceKey="test"),"resume.txt","text/plain",1,"txt",state="READY",hasFile=true,hasText=true),1,"remote","b".repeat(64),terms.joinToString(" ")))
            assertEquals(1,repo.searchDocuments(terms.joinToString(" "),10,0).total)
            assertEquals(0,repo.searchDocuments((terms+"absent").joinToString(" "),10,0).total)
        } } finally { dir.toFile().deleteRecursively() }
    }

}
