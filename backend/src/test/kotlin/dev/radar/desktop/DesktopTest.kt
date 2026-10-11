package dev.radar.desktop

import dev.radar.telegram.*
import org.drinkless.tdlib.TdApi
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

class DesktopTest {
    @Test fun `credentials permissions persistence and session exclusion`() {
        val home = Files.createTempDirectory("hradar-test")
        try {
            val paths = RuntimePaths(home); paths.prepare()
            val creds = ApiCredentials(12345, "a".repeat(32))
            paths.saveCredentials(creds)
            assertEquals(creds, RuntimePaths(home).loadCredentials())
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(paths.credentials))
            val lease = SessionLease.acquire(paths.session)!!
            assertNull(SessionLease.acquire(paths.session))
            lease.close()
            SessionLease.acquire(paths.session)!!.close()
            assertTrue(Files.exists(paths.credentials))
        } finally { home.toFile().deleteRecursively() }
    }
    @Test fun `finite login states fake TDLib and redacted failures`() = runBlocking {
        val requests = mutableListOf<TdApi.Function<*>>()
        var fail = false
        val auth = Onboarding { requests += it; if (fail) TdApi.Error(400,"secret-echo") else TdApi.Ok() }
        val cases = listOf(
            TdApi.AuthorizationStateWaitPhoneNumber() to "+1234567890",
            TdApi.AuthorizationStateWaitCode() to "12345",
            TdApi.AuthorizationStateWaitPassword() to "fake-password",
            TdApi.AuthorizationStateWaitEmailAddress() to "fake@example.invalid",
            TdApi.AuthorizationStateWaitEmailCode() to "12345",
        )
        for ((state, value) in cases) {
            auth.update(state)
            auth.submit(LoginInput(auth.status().state,value))
            assertFailsWith<IllegalArgumentException> { auth.submit(LoginInput("stale",value)) }
        }
        assertEquals(listOf("SetAuthenticationPhoneNumber","CheckAuthenticationCode","CheckAuthenticationPassword","SetAuthenticationEmailAddress","CheckAuthenticationEmailCode"), requests.map { it.javaClass.simpleName })
        fail = true
        val error = assertFailsWith<IllegalArgumentException> { auth.submit(LoginInput("WaitEmailCode","123")) }
        assertFalse(error.message!!.contains("secret-echo"))
        auth.update(TdApi.AuthorizationStateWaitOtherDeviceConfirmation("tg://login?token=fake"))
        assertEquals("tg://login?token=fake",auth.status().link)
        assertFailsWith<IllegalArgumentException> { auth.submit(LoginInput(auth.status().state,"no")) }
        auth.update(TdApi.AuthorizationStateReady()); assertEquals("Ready",auth.status().state)
        auth.update(TdApi.AuthorizationStateClosed()); assertEquals("Closed",auth.status().state)
    }
}
