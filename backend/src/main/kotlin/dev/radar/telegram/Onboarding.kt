package dev.radar.telegram

import kotlinx.serialization.Serializable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import org.drinkless.tdlib.TdApi

@Serializable data class ApiCredentials(val apiId: Int, val apiHash: String) {
    fun validate() { require(apiId > 0 && apiHash.matches(Regex("[a-fA-F0-9]{32}"))) { "Проверьте API ID и API hash из my.telegram.org" } }
    override fun toString() = "ApiCredentials(redacted)"
}
@Serializable data class LoginInput(val state: String, val value: String = "", val revision: Long? = null) {
    override fun toString() = "LoginInput(redacted)"
}
@Serializable data class CodeDelivery(val method: String, val destination: String? = null, val length: Int? = null)
@Serializable data class OnboardingStatus(val state: String, val csrfToken: String = "", val link: String? = null,
    val notice: String? = null, val revision: Long = 0, val delivery: CodeDelivery? = null,
    val nextDelivery: CodeDelivery? = null, val resendSupported: Boolean = false,
    val resendAfterSeconds: Int = 0, val retryAfterSeconds: Int = 0,
    val canChangePhone: Boolean = false, val busy: Boolean = false) {
    override fun toString() = "OnboardingStatus(state=$state)"
}
@Serializable data class OnboardingFailure(val error: String, val kind: String, val retryAfterSeconds: Int)

class OnboardingRejected(val kind: String, val retryAfterSeconds: Int = 0): IllegalArgumentException(when (kind) {
    "invalid_code" -> "Неверный код. Проверьте его и попробуйте ещё раз."
    "expired_code" -> "Срок действия кода истёк. Запросите новый код или измените номер."
    "invalid_password" -> "Неверный пароль двухэтапной проверки. Попробуйте ещё раз."
    "invalid_phone" -> "Проверьте номер телефона: нужен международный формат с +."
    "invalid_email" -> "Telegram не принял email. Проверьте адрес."
    "rate_limited" -> "Слишком много попыток. Дождитесь окончания паузы перед повтором."
    "wait" -> "Повторная отправка пока недоступна. Дождитесь окончания таймера."
    "resend_unavailable" -> "Telegram пока не разрешает повторную отправку. Можно проверить код или изменить номер."
    "network" -> "Не удалось связаться с Telegram. Проверьте соединение и повторите текущий этап."
    "confirmation_required" -> "Telegram требует дополнительного подтверждения. Дождитесь обновления этапа входа."
    "state_changed" -> "Этап входа изменился. Используйте обновлённую форму."
    "busy" -> "Предыдущий запрос ещё выполняется. Подождите."
    "unsupported_state" -> "Это действие недоступно на текущем этапе. Следуйте инструкции Telegram на экране."
    else -> "Telegram отклонил запрос. Проверьте данные и повторите текущий этап."
}) {
    val rateLimited get() = kind == "rate_limited"
}

/** A deliberately finite login API. No generic Telegram executor is exposed. */
class Onboarding(private val now: () -> Long = System::currentTimeMillis,
                 private val send: suspend (TdApi.Function<*>) -> TdApi.Object) {
    @Volatile private var current = OnboardingStatus("Credentials")
    private val operation = Mutex()
    private var resendAt = 0L
    private var localResendAt = 0L
    private var retryAt = 0L
    @Volatile private var busy = false
    private val phoneStates = setOf("WaitPremiumPurchase", "WaitEmailAddress", "WaitEmailCode", "WaitCode", "WaitRegistration", "WaitPassword")
    @Synchronized fun status() = current.copy(resendAfterSeconds = remaining(maxOf(resendAt, localResendAt, retryAt)),
        retryAfterSeconds = remaining(retryAt), busy = busy)
    private fun remaining(deadline: Long) = ((deadline - now()).coerceAtLeast(0) / 1000.0).let { kotlin.math.ceil(it).toInt() }
    @Synchronized fun initializing() { current = OnboardingStatus("Initializing", revision = current.revision + 1) }
    @Synchronized fun update(state: TdApi.AuthorizationState) {
        val name = state.javaClass.simpleName.removePrefix("AuthorizationState")
        val code = (state as? TdApi.AuthorizationStateWaitCode)?.codeInfo
        val email = (state as? TdApi.AuthorizationStateWaitEmailCode)?.codeInfo
        resendAt = now() + (code?.timeout?.coerceAtLeast(0)?.toLong() ?: 0) * 1000
        current = OnboardingStatus(name, revision = current.revision + 1,
            delivery = when (state) {
                is TdApi.AuthorizationStateWaitCode -> delivery(code?.type).copy(destination = maskPhone(code?.phoneNumber))
                is TdApi.AuthorizationStateWaitEmailCode -> CodeDelivery("email", maskEmail(email?.emailAddressPattern), email?.length?.takeIf { it in 1..512 })
                else -> null
            },
            nextDelivery = code?.nextType?.let(::delivery),
            resendSupported = (name == "WaitCode" && code?.nextType != null) || name == "WaitEmailCode",
            canChangePhone = name in phoneStates,
            link = (state as? TdApi.AuthorizationStateWaitOtherDeviceConfirmation)?.link?.takeIf { it.startsWith("tg://") },
            notice = when (state) {
                is TdApi.AuthorizationStateWaitRegistration -> "Регистрация нового аккаунта не поддерживается. Используйте существующий аккаунт Telegram."
                is TdApi.AuthorizationStateWaitPremiumPurchase -> "Telegram требует действие в официальном приложении. Завершите его там."
                is TdApi.AuthorizationStateWaitEmailCode -> "Telegram не сообщает таймер повторной отправки email-кода. Между запросами hradar выдерживает паузу 30 секунд."
                else -> if (name !in setOf("WaitPhoneNumber", "WaitCode", "WaitPassword", "WaitEmailAddress", "WaitOtherDeviceConfirmation", "WaitTdlibParameters", "Ready", "Closing", "Closed", "LoggingOut"))
                    "Этот этап пока не поддерживается. Проверьте официальное приложение Telegram; локальные данные сохранены." else null
            })
    }
    @Synchronized fun parametersFailed() { current = OnboardingStatus("ParametersFailed", notice = "Не удалось настроить TDLib. Проверьте API credentials и перезапустите приложение.") }
    suspend fun submit(input: LoginInput) = perform(input) {
        require(input.value.length in 1..512) { "Введите значение (не более 512 символов)." }
        when (input.state) {
            "WaitPhoneNumber" -> phone(input.value)
            "WaitCode" -> TdApi.CheckAuthenticationCode(input.value)
            "WaitPassword" -> TdApi.CheckAuthenticationPassword(input.value)
            "WaitEmailAddress" -> TdApi.SetAuthenticationEmailAddress(input.value)
            "WaitEmailCode" -> TdApi.CheckAuthenticationEmailCode(TdApi.EmailAddressAuthenticationCode(input.value))
            else -> throw OnboardingRejected("unsupported_state")
        }
    }
    suspend fun changePhone(input: LoginInput) = perform(input) {
        if (!status().canChangePhone) throw OnboardingRejected("unsupported_state")
        phone(input.value)
    }
    suspend fun resend(input: LoginInput) = perform(input) {
        val state = status()
        if (!state.resendSupported) throw OnboardingRejected("resend_unavailable")
        if (state.resendAfterSeconds > 0) throw OnboardingRejected("wait", state.resendAfterSeconds)
        synchronized(this) { localResendAt = now() + 30_000 }
        TdApi.ResendAuthenticationCode(null)
    }
    private fun phone(value: String): TdApi.SetAuthenticationPhoneNumber {
        if (!value.matches(Regex("\\+[0-9]{5,20}"))) throw OnboardingRejected("invalid_phone")
        return TdApi.SetAuthenticationPhoneNumber(value, null)
    }
    private suspend fun perform(input: LoginInput, query: () -> TdApi.Function<*>) {
        if (!operation.tryLock()) throw OnboardingRejected("busy")
        busy = true
        try {
            val snapshot = status()
            if (input.state != snapshot.state || (input.revision != null && input.revision != snapshot.revision)) throw OnboardingRejected("state_changed")
            if (snapshot.retryAfterSeconds > 0) throw OnboardingRejected("rate_limited", snapshot.retryAfterSeconds)
            val request = query()
            val result = try { send(request) }
            catch (_: TimeoutCancellationException) { throw OnboardingRejected("network") }
            catch (ex: CancellationException) { throw ex }
            catch (_: Exception) { throw OnboardingRejected("network") }
            if (result is TdApi.Error) {
                val failure = safeError(result)
                if (failure.rateLimited) synchronized(this) { retryAt = now() + failure.retryAfterSeconds.toLong() * 1000 }
                throw failure
            }
        } finally { busy = false; operation.unlock() }
    }
    private fun safeError(error: TdApi.Error): OnboardingRejected {
        // Error 406 must not be interpreted. All other raw messages stay within this allowlist.
        if (error.code == 406) return OnboardingRejected("rejected")
        val raw = error.message.orEmpty()
        val interval = Regex("(?:FLOOD(?:_PREMIUM)?_WAIT_|Too Many Requests: retry after )([0-9]{1,9})")
            .matchEntire(raw)?.groupValues?.get(1)?.toIntOrNull()?.coerceAtLeast(1)
        if (error.code == 429 || interval != null || raw in setOf("FLOOD_WAIT", "PHONE_NUMBER_FLOOD", "PHONE_PASSWORD_FLOOD", "PHONE_CODE_FLOOD"))
            return OnboardingRejected("rate_limited", interval ?: 60)
        return OnboardingRejected(when (raw) {
            "PHONE_CODE_INVALID", "PHONE_CODE_EMPTY", "EMAIL_CODE_INVALID", "CODE_INVALID", "EMAIL_VERIFY_CODE_INVALID" -> "invalid_code"
            "PHONE_CODE_EXPIRED", "EMAIL_CODE_EXPIRED", "CODE_EXPIRED", "EMAIL_VERIFY_EXPIRED" -> "expired_code"
            "PASSWORD_HASH_INVALID" -> "invalid_password"
            "PHONE_NUMBER_INVALID" -> "invalid_phone"
            "EMAIL_INVALID", "EMAIL_NOT_ALLOWED" -> "invalid_email"
            "SEND_CODE_UNAVAILABLE", "PHONE_CODE_HASH_EMPTY", "Authentication code can't be resent" -> "resend_unavailable"
            "SESSION_PASSWORD_NEEDED", "EMAIL_VERIFICATION_NEEDED" -> "confirmation_required"
            "AUTH_RESTART" -> "state_changed"
            else -> if (error.code >= 500) "network" else "rejected"
        })
    }
    private fun maskPhone(value: String?) = value?.takeIf { it.matches(Regex("\\+?[0-9]{5,20}")) }?.let { "••••${it.takeLast(2)}" }
    private fun maskEmail(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val domain = value.substringAfterLast('@', "").takeIf { it.matches(Regex("[A-Za-z0-9.-]{1,100}")) }
        return if (domain != null) "•••@$domain" else "•••"
    }
    private fun delivery(type: TdApi.AuthenticationCodeType?): CodeDelivery {
        val (method, length) = when (type) {
            is TdApi.AuthenticationCodeTypeTelegramMessage -> "telegram" to type.length
            is TdApi.AuthenticationCodeTypeSms -> "sms" to type.length
            is TdApi.AuthenticationCodeTypeSmsWord -> "sms_word" to null
            is TdApi.AuthenticationCodeTypeSmsPhrase -> "sms_phrase" to null
            is TdApi.AuthenticationCodeTypeCall -> "call" to type.length
            is TdApi.AuthenticationCodeTypeFlashCall -> "flash_call" to null
            is TdApi.AuthenticationCodeTypeMissedCall -> "missed_call" to type.length
            is TdApi.AuthenticationCodeTypeFragment -> "fragment" to type.length
            is TdApi.AuthenticationCodeTypeFirebaseAndroid -> "official_android" to type.length
            is TdApi.AuthenticationCodeTypeFirebaseIos -> "official_ios" to type.length
            else -> "unknown" to null
        }
        return CodeDelivery(method, length = length?.takeIf { it in 1..512 })
    }
}
