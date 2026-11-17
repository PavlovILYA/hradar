package dev.radar.sources

import dev.radar.research.*
import java.nio.file.Files
import kotlin.test.*

class OwnerBookmarkTest {
    @Test fun clearingCachePreservesLocalBookmarksWithoutExternalSharing() {
        val path = Files.createTempDirectory("owner-bookmark").resolve("db")
        Repositories(path).use { repo ->
            repo.saveOwnerBookmark(ResearchMessage(1, 23, 1, "synthetic private", "QA", null, sourceKey = "synthetic"))
            repo.clearSearchCache()
            assertEquals("synthetic private", repo.ownerBookmarks(25, Long.MAX_VALUE).items.single().message.text)
            assertTrue(repo.savedResults(25, Long.MAX_VALUE).items.isEmpty())
            assertTrue(repo.mcpSources().isEmpty())
        }
        Repositories(path).use { repo -> assertEquals(1, repo.ownerBookmarks(25, Long.MAX_VALUE).items.size) }
    }
}
