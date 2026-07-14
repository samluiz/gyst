package com.samluiz.gyst.domain.service

import com.samluiz.gyst.domain.model.RawTransactionExtraction

data class FinancialNotificationText(
    val sourcePackage: String,
    val title: String,
    val text: String,
    val expandedText: String? = null,
    val category: String? = null,
    val channelId: String? = null,
)

enum class NotificationIgnoreReason {
    EMPTY,
    SENSITIVE,
    PERSONAL_MESSAGE,
    PROMOTIONAL,
    NO_FINANCIAL_SIGNAL,
}

sealed interface FinancialNotificationRuleResult {
    data class Candidate(
        val extraction: RawTransactionExtraction,
        val normalizedTextForOptionalAi: String,
    ) : FinancialNotificationRuleResult

    data class Ignored(val reason: NotificationIgnoreReason) : FinancialNotificationRuleResult
}

/**
 * Locale-aware, deterministic filtering and extraction performed before any provider request.
 * It intentionally returns a conservative draft: uncertain transfers/refunds remain reviewable
 * instead of being silently coerced into an expense.
 */
class FinancialNotificationRuleEngine {
    fun evaluate(input: FinancialNotificationText): FinancialNotificationRuleResult {
        val combined = normalize(listOf(input.title, input.text, input.expandedText.orEmpty()).joinToString(" "))
        if (combined.isBlank()) return FinancialNotificationRuleResult.Ignored(NotificationIgnoreReason.EMPTY)
        if (isSensitive(combined)) return FinancialNotificationRuleResult.Ignored(NotificationIgnoreReason.SENSITIVE)
        if (isPersonalMessage(input.category, combined)) {
            return FinancialNotificationRuleResult.Ignored(NotificationIgnoreReason.PERSONAL_MESSAGE)
        }
        val amount = amountPattern.find(combined)?.value
        val financialAction = financialActionPattern.containsMatchIn(combined)
        if (promotionPattern.containsMatchIn(combined) && !completedActionPattern.containsMatchIn(combined)) {
            return FinancialNotificationRuleResult.Ignored(NotificationIgnoreReason.PROMOTIONAL)
        }
        if (amount == null || !financialAction) {
            return FinancialNotificationRuleResult.Ignored(NotificationIgnoreReason.NO_FINANCIAL_SIGNAL)
        }

        val transactionType = detectType(combined)
        val description =
            merchantPattern.find(combined)?.groupValues?.getOrNull(1)?.cleanMerchant()
                ?: input.title.cleanMerchant().takeIf(String::isNotBlank)
                ?: "Transaction"
        val confidence =
            when {
                description.isNotBlank() && completedActionPattern.containsMatchIn(combined) -> 0.88
                description.isNotBlank() -> 0.78
                else -> 0.65
            }
        val minimalText = combined.take(MAX_PROVIDER_TEXT_LENGTH)
        return FinancialNotificationRuleResult.Candidate(
            extraction =
                RawTransactionExtraction(
                    description = description,
                    amount = amount,
                    transactionType = transactionType,
                    confidence = confidence,
                    sourcePage = input.sourcePackage,
                    supportingText = minimalText.take(MAX_SUPPORTING_TEXT_LENGTH),
                ),
            normalizedTextForOptionalAi = minimalText,
        )
    }

    private fun detectType(text: String): String =
        when {
            refundPattern.containsMatchIn(text) -> "refund"
            incomePattern.containsMatchIn(text) -> "income"
            transferPattern.containsMatchIn(text) -> "transfer"
            expensePattern.containsMatchIn(text) -> "expense"
            else -> "unknown"
        }

    private fun isSensitive(text: String): Boolean = sensitiveTerms.containsMatchIn(text) && shortCodePattern.containsMatchIn(text)

    private fun isPersonalMessage(
        category: String?,
        text: String,
    ): Boolean =
        category.equals("msg", ignoreCase = true) ||
            category.equals("message", ignoreCase = true) ||
            messagingPattern.containsMatchIn(text)

    private fun normalize(value: String): String =
        value.replace(controlCharacters, " ").replace(whitespace, " ").trim().take(MAX_INPUT_LENGTH)

    private fun String.cleanMerchant(): String =
        trim()
            .trimEnd('.', ',', ';', ':')
            .replace(whitespace, " ")
            .take(MAX_DESCRIPTION_LENGTH)

    private companion object {
        const val MAX_INPUT_LENGTH = 1_400
        const val MAX_PROVIDER_TEXT_LENGTH = 700
        const val MAX_SUPPORTING_TEXT_LENGTH = 240
        const val MAX_DESCRIPTION_LENGTH = 120

        val whitespace = Regex("\\s+")
        val controlCharacters = Regex("[\\p{Cc}&&[^\\n\\t]]")
        val amountPattern =
            Regex(
                "(?:R\\$|BRL|US\\$|USD|EUR|€|\\$)\\s*-?\\s*" +
                    "(?:\\d{1,3}(?:[. ,]\\d{3})+|\\d+)(?:[,.]\\d{1,2})?",
                RegexOption.IGNORE_CASE,
            )
        val sensitiveTerms =
            Regex(
                "(?:otp|one[ -]?time|verification code|security code|passcode|token|" +
                    "c[oó]digo (?:de )?(?:verifica[cç][aã]o|seguran[cç]a|acesso)|senha tempor[aá]ria|n[aã]o compartilhe)",
                RegexOption.IGNORE_CASE,
            )
        val shortCodePattern = Regex("(?<!\\d)\\d{4,8}(?!\\d)")
        val messagingPattern =
            Regex("(?:nova mensagem|new message|respondeu|sent you|mensagem de)", RegexOption.IGNORE_CASE)
        val promotionPattern =
            Regex(
                "(?:oferta|promo[cç][aã]o|cupom|desconto|aproveite|compre agora|offer|sale|coupon|shop now)",
                RegexOption.IGNORE_CASE,
            )
        val financialActionPattern =
            Regex(
                "(?:compra|pagamento|d[eé]bito|cr[eé]dito|pix|transfer[eê]ncia|saque|dep[oó]sito|" +
                    "estorno|reembolso|purchase|payment|charged|debit|credit|transfer|withdrawal|deposit|refund)",
                RegexOption.IGNORE_CASE,
            )
        val completedActionPattern =
            Regex(
                "(?:aprovad[ao]|realizad[ao]|efetuad[ao]|recebid[ao]|enviad[ao]|conclu[ií]d[ao]|" +
                    "confirmad[ao]|paid|approved|completed|received|sent|charged|refunded)",
                RegexOption.IGNORE_CASE,
            )
        val refundPattern = Regex("(?:estorno|reembolso|refund|refunded)", RegexOption.IGNORE_CASE)
        val incomePattern =
            Regex(
                "(?:pix recebido|transfer[eê]ncia recebida|dep[oó]sito|cr[eé]dito recebido|payment received|deposit)",
                RegexOption.IGNORE_CASE,
            )
        val transferPattern =
            Regex("(?:pix enviado|transfer[eê]ncia|transfer sent|wire transfer)", RegexOption.IGNORE_CASE)
        val expensePattern =
            Regex("(?:compra|pagamento|d[eé]bito|purchase|payment|charged|paid)", RegexOption.IGNORE_CASE)
        val merchantPattern =
            Regex(
                "(?:\\bem\\s+|\\bno\\s+|\\bna\\s+|\\bat\\s+|\\bfrom\\s+)([^.;]{2,120})",
                RegexOption.IGNORE_CASE,
            )
    }
}
