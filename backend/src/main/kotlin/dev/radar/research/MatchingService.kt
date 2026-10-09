package dev.radar.research

import java.text.Normalizer
import java.util.Locale

class MatchingService {
    fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace('ё', 'е').replace(Regex("\\s+"), " ").trim()

    fun check(template: SearchTemplate, text: String?): MatchExplanation {
        val normalized = normalize(text.orEmpty())
        fun contains(term: String): Boolean = Regex("(?<![\\p{L}\\p{N}_])${Regex.escape(normalize(term))}(?![\\p{L}\\p{N}_])").containsMatchIn(normalized)
        val required = template.required.filter(::contains)
        val any = template.any.filter(::contains)
        val excluded = template.excluded.filter(::contains)
        val missing = template.required.filterNot { it in required }
        return MatchExplanation(normalized.isNotEmpty() && missing.isEmpty() && (template.any.isEmpty() || any.isNotEmpty()) && excluded.isEmpty(), required, any, excluded, missing)
    }

    fun validate(template: SearchTemplate): SearchTemplate {
        require(template.name.trim().length in 1..100) { "Название шаблона: от 1 до 100 символов" }
        require(template.telegramQuery.trim().length in 1..256) { "Укажите отдельный запрос Telegram (до 256 символов)" }
        require(template.sourceMode in setOf("all", "selected", "global")) { "Неизвестный режим источников" }
        require(template.sourceMode != "global" || !template.subscribed) { "Глобальные шаблоны запускаются только вручную" }
        require(template.sources.size <= 10) { "Не более 10 источников в шаблоне" }
        require(template.sourceMode != "selected" || template.sources.isNotEmpty()) { "Выберите источники" }
        val lists = listOf(template.required, template.any, template.excluded)
        require(lists.all { words -> words.size <= 20 && words.all { it.trim().length in 1..100 } }) { "Не более 20 слов или фраз в каждом условии, до 100 символов" }
        require(template.required.isNotEmpty() || template.any.isNotEmpty()) { "Укажите обязательные слова или хотя бы одно слово" }
        return template.copy(name = template.name.trim(), telegramQuery = template.telegramQuery.trim(), required = template.required.map(String::trim).distinct(), any = template.any.map(String::trim).distinct(), excluded = template.excluded.map(String::trim).distinct(), sources = template.sources.distinct())
    }
}
