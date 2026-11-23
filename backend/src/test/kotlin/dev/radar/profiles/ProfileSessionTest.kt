package dev.radar.profiles

import dev.radar.desktop.RuntimePaths
import dev.radar.research.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

class ProfileSessionTest {
    @Test fun identityGatesWorkersAndWrongReloginNeverOpensAnotherWorkspace() = runBlocking {
        val home=Files.createTempDirectory("profile-flow-").toRealPath()
        try {
            val store=ProfileStore(home);store.prepare();val a=store.active();val b=store.create()
            var starts=0;var stops=0;var who=ProfileIdentity(10,"Synthetic A")
            fun session(id:String) = ProfileSession(store,id,{true},{who},{"saved"},{starts++},{stops++})
            session(a.id).use { current ->
                assertEquals("AUTHORIZING",current.phase);assertEquals(0,starts)
                current.verify();assertEquals("READY",current.phase);assertEquals(1,starts)
                assertEquals(10L,current.identity!!.userId)
                current.prepareChange(b.id);assertEquals("SWITCHING",current.phase)
                assertFails { current.prepareChange(b.id) }
            }
            store.select(b.id);who=ProfileIdentity(20,"Synthetic B")
            session(b.id).use { current ->current.verify();assertEquals("READY",current.phase) }
            store.select(a.id);who=ProfileIdentity(20,"Wrong user")
            session(a.id).use { current ->current.authenticationAttempt();current.verify();assertEquals("MISMATCH",current.phase) }
            assertEquals(10L,store.active().identity!!.userId)
            who=ProfileIdentity(10,"Synthetic A")
            session(a.id).use { current ->current.verify();assertEquals("READY",current.phase) }
            assertEquals(3,starts)
        } finally { home.toFile().deleteRecursively() }
    }
    @Test fun activeOperationsRejectChangesBeforeCreatingAProfile() = runBlocking {
        val home=Files.createTempDirectory("profile-busy-").toRealPath()
        try {
            val store=ProfileStore(home);store.prepare();val initial=store.list().size
            ProfileSession(store,store.active().id,{true},{ProfileIdentity(1,"Synthetic")},{"environment"},{},{},operationBusy={true}).use { flow ->
                assertFails { flow.prepareNew(store::create) };assertEquals(initial,store.list().size)
                assertEquals("AUTHORIZING",flow.phase)
            }
        } finally {home.toFile().deleteRecursively()}
    }
    @Test fun separateDatabasesKeepFtsProjectsTemplatesAndBotGrantsApart() {
        val home=Files.createTempDirectory("profile-isolation-").toRealPath()
        try {
            val store=ProfileStore(home);store.prepare();val a=store.active();val b=store.create()
            val ap=RuntimePaths(home,a.id);val bp=RuntimePaths(home,b.id)
            Files.writeString(ap.documents.resolve("synthetic-sentinel"),"Synthetic A")
            Repositories(ap.database).use { repo ->
                repo.saveTemplate(SearchTemplate(name="A only",required=listOf("Java"),telegramQuery="Java"))
                repo.configureBot(123,setOf(10));repo.saveBotAccess(123,10,"owner","Synthetic A",null)
            }
            Repositories(bp.database).use { repo ->assertTrue(repo.templates().isEmpty());assertTrue(repo.botAccessUsers(123).isEmpty());assertEquals(0,repo.documents(10,0).total) }
            assertFalse(Files.exists(bp.documents.resolve("synthetic-sentinel")))
            store.select(b.id);store.select(a.id)
            Repositories(ap.database).use {assertEquals("A only",it.templates().single().name);assertEquals(10L,it.botAccessUsers(123).single().id)}
        } finally {home.toFile().deleteRecursively()}
    }
    @Test fun persistenceFailureAndGetMeTimeoutDoNotStrandSwitchingOrVerifying() = runBlocking {
        val home=Files.createTempDirectory("profile-errors-").toRealPath()
        try {
            val store=ProfileStore(home);store.prepare();var fail=true
            ProfileSession(store,store.active().id,{true},{if(fail) kotlinx.coroutines.withTimeout(1){kotlinx.coroutines.delay(100);ProfileIdentity(1,"Synthetic")} else ProfileIdentity(1,"Synthetic")},{"saved"},{},{}).use { flow ->
                flow.verify();assertEquals("ERROR",flow.phase)
                fail=false;flow.verify();assertEquals("READY",flow.phase)
                assertFailsWith<java.io.IOException> { flow.prepareNew { throw java.io.IOException("synthetic full disk") } }
                assertEquals("READY",flow.phase);assertEquals(1,store.list().size)
                flow.prepareChange(flow.profileId);assertEquals("SWITCHING",flow.phase)
            }
        } finally {home.toFile().deleteRecursively()}
    }
    @Test fun identityFloodWaitBlocksFurtherNativeChecks() = runBlocking {
        val home=Files.createTempDirectory("profile-flood-").toRealPath()
        try {
            val store=ProfileStore(home);store.prepare();var calls=0
            ProfileSession(store,store.active().id,{true},{calls++;throw dev.radar.telegram.TelegramRequestException(429,"FLOOD_WAIT_60")},{"saved"},{},{}).use { flow ->
                flow.verify();assertEquals("ERROR",flow.phase)
                assertFails {flow.verify()};assertEquals(1,calls)
            }
        } finally {home.toFile().deleteRecursively()}
    }

    @Test fun ownerConfirmationDoesNotExposeWorkspaceWhenWorkerStartupFails() = runBlocking {
        val home=Files.createTempDirectory("profile-confirm-failure-").toRealPath()
        try {
            val old=RuntimePaths(home);Files.createDirectories(old.session);Files.writeString(old.session.resolve("td.binlog"),"synthetic")
            val store=ProfileStore(home);store.prepare();var stopped=false
            ProfileSession(store,store.active().id,{true},{ProfileIdentity(1,"Synthetic")},{"saved"},{throw java.io.IOException("synthetic worker failure")},{stopped=true}).use { flow ->
                flow.verify();assertEquals("CONFIRM_OWNER",flow.phase)
                assertFails {flow.confirm(1)};assertEquals("ERROR",flow.phase);assertTrue(stopped)
            }
        } finally {home.toFile().deleteRecursively()}
    }

}
