package dev.radar.telegram

import kotlinx.serialization.Serializable
import org.drinkless.tdlib.TdApi

@Serializable data class ApiCredentials(val apiId: Int, val apiHash: String) {
    fun validate() { require(apiId > 0 && apiHash.matches(Regex("[a-fA-F0-9]{32}"))) { "Проверьте API ID и API hash из my.telegram.org" } }
    override fun toString() = "ApiCredentials(redacted)"
}
@Serializable data class LoginInput(val state: String, val value: String) {
    override fun toString() = "LoginInput(redacted)"
}
@Serializable data class OnboardingStatus(val state: String, val csrfToken: String = "", val link: String? = null, val notice: String? = null)

class OnboardingRejected(val rateLimited: Boolean): IllegalArgumentException(
    if (rateLimited) "Слишком много попыток. Подождите перед повтором." else "Telegram отклонил ввод. Проверьте данные и повторите попытку."
)

/** A deliberately finite login API. No generic Telegram executor is exposed. */
class Onboarding(private val send: suspend (TdApi.Function<*>) -> TdApi.Object) {
    @Volatile private var current = OnboardingStatus("Credentials")
    fun status() = current
    fun initializing() { current = OnboardingStatus("Initializing") }
    fun update(state: TdApi.AuthorizationState) {
        current = OnboardingStatus(state.javaClass.simpleName.removePrefix("AuthorizationState"),
            link = (state as? TdApi.AuthorizationStateWaitOtherDeviceConfirmation)?.link?.takeIf { it.startsWith("tg://") },
            notice = when (state) {
                is TdApi.AuthorizationStateWaitRegistration -> "Регистрация нового аккаунта не поддерживается. Используйте существующий аккаунт Telegram."
                is TdApi.AuthorizationStateWaitPremiumPurchase -> "Telegram требует действие в официальном приложении. Завершите его там."
                else -> null
            })
    }
    @Synchronized fun parametersFailed() { current = OnboardingStatus("ParametersFailed", notice = "Не удалось настроить TDLib. Проверьте API credentials и перезапустите приложение.") }
    suspend fun submit(input: LoginInput) {
        require(input.state == current.state) { "Состояние входа изменилось. Обновите форму." }
        require(input.value.length in 1..512) { "Введите значение (не более 512 символов)." }
        val query = when (input.state) {
            "WaitPhoneNumber" -> { require(input.value.matches(Regex("\\+[0-9]{5,20}"))) { "Введите номер в международном формате +…" }; TdApi.SetAuthenticationPhoneNumber(input.value, null) }
            "WaitCode" -> TdApi.CheckAuthenticationCode(input.value)
            "WaitPassword" -> TdApi.CheckAuthenticationPassword(input.value)
            "WaitEmailAddress" -> TdApi.SetAuthenticationEmailAddress(input.value)
            "WaitEmailCode" -> TdApi.CheckAuthenticationEmailCode(TdApi.EmailAddressAuthenticationCode(input.value))
            else -> throw IllegalArgumentException("Это состояние не принимает ввод.")
        }
        val result = send(query)
        if (result is TdApi.Error) {
            // TDLib error text may echo user input. Never log/return it.
            throw OnboardingRejected(result.code == 429 || result.message.startsWith("FLOOD_WAIT"))
        }
    }
}
