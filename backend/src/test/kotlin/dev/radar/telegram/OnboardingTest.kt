package dev.radar.telegram

import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.drinkless.tdlib.TdApi
import kotlin.test.*

class OnboardingTest {
    private fun code(type: TdApi.AuthenticationCodeType? = TdApi.AuthenticationCodeTypeSms(5),
                     next: TdApi.AuthenticationCodeType? = TdApi.AuthenticationCodeTypeCall(5), timeout: Int = 0) =
        TdApi.AuthorizationStateWaitCode(TdApi.AuthenticationCodeInfo("+1234567890", type, next, timeout))

    @Test fun `delivery is explicit masked and never exposes code fragments or receipts`() {
        val auth = Onboarding { TdApi.Ok() }
        val methods = listOf(
            TdApi.AuthenticationCodeTypeTelegramMessage() to "telegram",
            TdApi.AuthenticationCodeTypeSms() to "sms",
            TdApi.AuthenticationCodeTypeSmsWord("sensitive") to "sms_word",
            TdApi.AuthenticationCodeTypeSmsPhrase("sensitive") to "sms_phrase",
            TdApi.AuthenticationCodeTypeCall() to "call",
            TdApi.AuthenticationCodeTypeFlashCall("sensitive") to "flash_call",
            TdApi.AuthenticationCodeTypeMissedCall("sensitive", 3) to "missed_call",
            TdApi.AuthenticationCodeTypeFragment("https://fragment.com/sensitive", 5) to "fragment",
            TdApi.AuthenticationCodeTypeFirebaseAndroid() to "official_android",
            TdApi.AuthenticationCodeTypeFirebaseIos() to "official_ios",
        )
        methods.forEach { (type, expected) ->
            auth.update(code(type))
            assertEquals(expected, auth.status().delivery?.method)
            assertEquals("••••90", auth.status().delivery?.destination)
            val json = Json.encodeToString(auth.status())
            assertFalse(json.contains("sensitive")); assertFalse(json.contains("1234567890"))
        }
        auth.update(code(null, null))
        assertEquals("unknown", auth.status().delivery?.method)
        assertFalse(auth.status().resendSupported)
    }

    @Test fun `server resend deadline decreases and email uses the supported resend function`() = runBlocking {
        var time = 1_000L
        val queries = mutableListOf<TdApi.Function<*>>()
        val auth = Onboarding(now = { time }) { queries += it; TdApi.Ok() }
        auth.update(code(timeout = 10))
        assertEquals(10, auth.status().resendAfterSeconds)
        assertFailsWith<OnboardingRejected> { auth.resend(LoginInput("WaitCode", "")) }
        time += 10_000
        auth.resend(LoginInput("WaitCode", ""))
        assertIs<TdApi.ResendAuthenticationCode>(queries.single())
        assertTrue(auth.status().resendAfterSeconds >= 30)
        auth.update(TdApi.AuthorizationStateWaitEmailCode(false, false,
            TdApi.EmailAddressAuthenticationCodeInfo("a***@example.invalid", 6), null))
        assertEquals("email", auth.status().delivery?.method)
        assertFalse(auth.status().delivery!!.destination!!.contains("a***"))
        time += 30_000
        auth.resend(LoginInput("WaitEmailCode", ""))
        assertIs<TdApi.ResendAuthenticationCode>(queries.last())
    }

    @Test fun `phone replacement keeps actual state until TDLib sends a transition`() = runBlocking {
        val queries = mutableListOf<TdApi.Function<*>>()
        val auth = Onboarding { queries += it; TdApi.Ok() }
        for (state in listOf(code(), TdApi.AuthorizationStateWaitPassword(), TdApi.AuthorizationStateWaitEmailCode())) {
            auth.update(state)
            val current = auth.status()
            assertTrue(current.canChangePhone)
            auth.changePhone(LoginInput(current.state, "+19876543210", current.revision))
            assertEquals(current.state, auth.status().state)
            assertEquals("+19876543210", (queries.last() as TdApi.SetAuthenticationPhoneNumber).phoneNumber)
        }
        auth.update(TdApi.AuthorizationStateReady())
        assertFalse(auth.status().canChangePhone)
        assertFailsWith<OnboardingRejected> { auth.changePhone(LoginInput("Ready", "+19876543210")) }
        auth.update(TdApi.AuthorizationStateWaitPhoneNumber())
        assertFailsWith<OnboardingRejected> { auth.submit(LoginInput("WaitPhoneNumber", "bad")) }
    }

    @Test fun `known errors and flood interval are safe and enforce a shared cooldown`() = runBlocking {
        var time = 0L
        var error = TdApi.Error(400, "PHONE_CODE_INVALID")
        var calls = 0
        val auth = Onboarding(now = { time }) { calls++; error }
        auth.update(code())
        for ((raw, kind) in listOf("PHONE_CODE_INVALID" to "invalid_code", "PHONE_CODE_EXPIRED" to "expired_code",
            "EMAIL_CODE_INVALID" to "invalid_code", "EMAIL_CODE_EXPIRED" to "expired_code",
            "PASSWORD_HASH_INVALID" to "invalid_password", "SEND_CODE_UNAVAILABLE" to "resend_unavailable",
            "SESSION_PASSWORD_NEEDED" to "confirmation_required", "unknown-secret-value" to "rejected")) {
            error = TdApi.Error(400, raw)
            val ex = assertFailsWith<OnboardingRejected> { auth.submit(LoginInput("WaitCode", "synthetic")) }
            assertEquals(kind, ex.kind); assertFalse(ex.toString().contains(raw))
        }
        error = TdApi.Error(429, "FLOOD_WAIT_42")
        val ex = assertFailsWith<OnboardingRejected> { auth.submit(LoginInput("WaitCode", "synthetic")) }
        assertEquals(42, ex.retryAfterSeconds)
        val before = calls
        assertFailsWith<OnboardingRejected> { auth.changePhone(LoginInput("WaitCode", "+1234567890")) }
        assertEquals(before, calls)
        time += 42_000
        error = TdApi.Error(406, "FLOOD_WAIT_999999_private")
        assertEquals("rejected", assertFailsWith<OnboardingRejected> { auth.submit(LoginInput("WaitCode", "x")) }.kind)
        assertEquals(0, auth.status().retryAfterSeconds)
        error = TdApi.Error(429, "Too Many Requests: retry after 8")
        assertEquals(8, assertFailsWith<OnboardingRejected> { auth.submit(LoginInput("WaitCode", "x")) }.retryAfterSeconds)
        time += 8_000
        error = TdApi.Error(500, "private server diagnostics")
        assertEquals("network", assertFailsWith<OnboardingRejected> { auth.submit(LoginInput("WaitCode", "x")) }.kind)
    }

    @Test fun `concurrent and stale operations are rejected and network failures sanitized`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val auth = Onboarding { entered.complete(Unit); finish.await(); TdApi.Ok() }
        auth.update(code())
        val old = auth.status()
        val first = async { auth.submit(LoginInput(old.state, "12345", old.revision)) }
        entered.await()
        assertTrue(auth.status().busy)
        assertEquals("busy", assertFailsWith<OnboardingRejected> { auth.resend(LoginInput(old.state, "")) }.kind)
        finish.complete(Unit); first.await()
        auth.update(code())
        assertEquals("state_changed", assertFailsWith<OnboardingRejected> { auth.submit(LoginInput(old.state, "1", old.revision)) }.kind)
        val offline = Onboarding { throw IllegalStateException("secret network details") }
        offline.update(code())
        val failure = assertFailsWith<OnboardingRejected> { offline.submit(LoginInput("WaitCode", "1")) }
        assertEquals("network", failure.kind); assertFalse(failure.toString().contains("secret"))
        offline.update(TdApi.AuthorizationStateWaitOtherDeviceConfirmation())
        assertEquals("unsupported_state", assertFailsWith<OnboardingRejected> { offline.submit(LoginInput(offline.status().state, "x")) }.kind)
    }
}
