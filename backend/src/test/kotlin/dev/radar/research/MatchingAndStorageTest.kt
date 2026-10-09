package dev.radar.research

import java.nio.file.Files
import kotlin.test.*

class MatchingAndStorageTest {
    private val matcher = MatchingService()
    private fun template() = SearchTemplate(name = "Senior Java", required = listOf("Java", "Spring"), any = listOf("Kafka", "PostgreSQL"), excluded = listOf("Junior", "стажёр"), telegramQuery = "Java")
    @Test fun matchesAndExplainsConditions() {
        val match = matcher.check(template(), "Senior JAVA developer: Spring and Kafka")
        assertTrue(match.matches)
        assertEquals(listOf("Java", "Spring"), match.foundRequired)
        assertEquals(listOf("Kafka"), match.foundAny)
        assertFalse(matcher.check(template(), "Java Spring Kafka Junior").matches)
        assertFalse(matcher.check(template(), "Java Spring Kafka стажер").matches)
        assertFalse(matcher.check(template(), "Java Spring").matches)
        assertEquals(listOf("Spring"), matcher.check(template(), "Java Kafka").missingRequired)
        assertFalse(matcher.check(template(), null).matches)
    }
    @Test fun normalizesPhrasesAndRespectsWordBoundaries() {
        val t = SearchTemplate(name = "Phrase", required = listOf("senior java", "C++"), telegramQuery = "java")
        assertTrue(matcher.check(t, "SENIOR   JAVA · C++ developer").matches)
        assertFalse(matcher.check(t, "senior JavaScript C++").matches)
        assertTrue(matcher.check(t, "ＳＥＮＩＯＲ java C++").matches)
    }
    @Test fun validatesTemplateAndRejectsGlobalSubscriptions() {
        assertFailsWith<IllegalArgumentException> { matcher.validate(template().copy(sourceMode = "global", subscribed = true)) }
        assertFailsWith<IllegalArgumentException> { matcher.validate(template().copy(required = emptyList(), any = emptyList())) }
        assertFailsWith<IllegalArgumentException> { matcher.validate(template().copy(sourceMode = "selected")) }
    }
    @Test fun persistsUpdatesSourcesAndDeletesTemplatesAcrossReopen() {
        val path = Files.createTempDirectory("hradar-storage-test").resolve("research.sqlite")
        val id: String
        Repositories(path).use { repo ->
            repo.saveChannel(MonitoredChannel(-1001L, "test_channel", "Test"))
            val saved = repo.saveTemplate(template().copy(sourceMode = "selected", sources = listOf(-1001L)))
            id = saved.id
            repo.saveTemplate(saved.copy(name = "Edited", active = false))
        }
        Repositories(path).use { repo ->
            assertEquals("Edited", repo.template(id)?.name)
            assertFalse(repo.template(id)!!.active)
            assertEquals(listOf(-1001L), repo.template(id)!!.sources)
            repo.deleteChannel(-1001L)
            assertEquals(emptyList(), repo.template(id)!!.sources)
            assertTrue(repo.deleteTemplate(id))
            assertNull(repo.template(id))
        }
    }
    @Test fun deduplicatesNotificationsAndPreservesReadState() {
        val path = Files.createTempDirectory("hradar-notification-test").resolve("research.sqlite")
        var id = ""
        Repositories(path).use { repo ->
            val t = repo.saveTemplate(template())
            val message = ResearchMessage(1L, -1001L, 1700000000, "Synthetic Java Spring Kafka", "Test", "test_channel")
            assertTrue(repo.recordMatch(t, message))
            assertFalse(repo.recordMatch(t, message))
            assertEquals(1, repo.unreadCount())
            id = repo.notifications().single().id
            repo.markRead(id)
            assertEquals(0, repo.unreadCount())
        }
        Repositories(path).use { repo ->
            assertEquals(id, repo.notifications().single().id)
            assertTrue(repo.notifications().single().read)
            assertEquals(0, repo.unreadCount())
        }
    }
}
