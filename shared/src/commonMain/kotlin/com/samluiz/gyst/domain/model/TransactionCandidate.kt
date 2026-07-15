package com.samluiz.gyst.domain.model

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlin.time.Instant

enum class CandidateSource {
    IMAGE,
    ANDROID_NOTIFICATION,
}

enum class CandidateTransactionType {
    EXPENSE,
    INCOME,
    TRANSFER,
    REFUND,
    UNKNOWN,
}

enum class CandidateStatus {
    RECEIVED,
    PENDING_ANALYSIS,
    ANALYZING,
    NEEDS_REVIEW,
    APPROVED,
    REJECTED,
    FAILED,
    CANCELLED,
    IGNORED,
}

/** Provider-independent wire result. It is never used as a database entity. */
@Serializable
data class RawTransactionExtraction(
    val description: String? = null,
    val amount: String? = null,
    val currency: String? = null,
    val transactionDate: String? = null,
    val transactionTime: String? = null,
    val transactionType: String? = null,
    val suggestedCategory: String? = null,
    val accountOrPaymentMethod: String? = null,
    val installmentIndex: Int? = null,
    val installmentTotal: Int? = null,
    val notes: String? = null,
    val confidence: Double? = null,
    val sourcePage: String? = null,
    val supportingText: String? = null,
    val warnings: List<String> = emptyList(),
)

data class TransactionCandidate(
    val id: String,
    val importSessionId: String?,
    val source: CandidateSource,
    val sourceReference: String?,
    val sourcePage: String?,
    val rowOrder: Long,
    val idempotencyKey: String,
    val fingerprint: String?,
    val description: String?,
    val amountCents: Long?,
    val currency: String?,
    val occurredDate: LocalDate?,
    val occurredTime: String?,
    val timeZoneId: String?,
    val transactionType: CandidateTransactionType,
    val suggestedCategoryId: String?,
    val suggestedCategoryLabel: String? = null,
    val accountOrPaymentMethod: String?,
    val installmentIndex: Int?,
    val installmentTotal: Int?,
    val note: String?,
    val confidence: Double?,
    val sourceImageHash: String?,
    val supportingText: String?,
    val warnings: List<String>,
    val lowConfidenceFields: Set<String>,
    val selected: Boolean,
    val status: CandidateStatus,
    val duplicateCandidateId: String?,
    val duplicateExpenseId: String?,
    val linkedExpenseId: String?,
    val providerId: String?,
    val modelId: String?,
    val retryCount: Long,
    val errorType: String?,
    val errorMessage: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class CandidateDraft(
    val source: CandidateSource,
    val sourceReference: String?,
    val sourcePage: String?,
    val description: String?,
    val amountCents: Long?,
    val currency: String?,
    val occurredDate: LocalDate?,
    val occurredTime: String?,
    val timeZoneId: String?,
    val transactionType: CandidateTransactionType,
    val suggestedCategoryName: String?,
    val accountOrPaymentMethod: String?,
    val installmentIndex: Int?,
    val installmentTotal: Int?,
    val note: String?,
    val confidence: Double?,
    val sourceImageHash: String?,
    val supportingText: String?,
    val warnings: List<String>,
    val lowConfidenceFields: Set<String>,
) {
    val fingerprint: String?
        get() =
            transactionFingerprint(
                date = occurredDate,
                amountCents = amountCents,
                currency = currency,
                description = description,
                account = accountOrPaymentMethod,
                type = transactionType,
            )
}

data class CandidateNormalizationContext(
    val source: CandidateSource,
    val sourceReference: String? = null,
    val sourceImageHash: String? = null,
    val defaultCurrency: String? = null,
    val localeTag: String,
    val timeZoneId: String? = null,
    val defaultDate: LocalDate? = null,
)

enum class CandidateIssueSeverity {
    WARNING,
    ERROR,
}

enum class CandidateIssueCode {
    EMPTY_DESCRIPTION,
    INVALID_AMOUNT,
    ZERO_AMOUNT,
    MISSING_CURRENCY,
    UNSUPPORTED_CURRENCY,
    INVALID_DATE,
    AMBIGUOUS_DATE,
    UNKNOWN_TYPE,
    UNSUPPORTED_TYPE,
    MISSING_CATEGORY,
    INVALID_PAYMENT_METHOD,
    INVALID_INSTALLMENT,
    LOW_CONFIDENCE,
    POSSIBLE_DUPLICATE,
    POSSIBLE_SUMMARY_ROW,
}

data class CandidateValidationIssue(
    val code: CandidateIssueCode,
    val severity: CandidateIssueSeverity,
    val field: String?,
)

data class CandidateNormalizationResult(
    val candidate: CandidateDraft,
    val issues: List<CandidateValidationIssue>,
)

fun normalizeExtraction(
    raw: RawTransactionExtraction,
    context: CandidateNormalizationContext,
): CandidateNormalizationResult {
    val parsedAmount = parseMoneyToCents(raw.amount)
    val currency = normalizeCurrency(raw.currency, raw.amount, context.defaultCurrency)
    val extractedDate = parseUnambiguousDate(raw.transactionDate, context.localeTag)
    val parsedDate = extractedDate ?: context.defaultDate
    val type =
        normalizeTransactionType(
            explicit = raw.transactionType,
            evidence = listOfNotNull(raw.description, raw.notes, raw.supportingText).joinToString(" "),
        )
    val description = deriveCandidateDescription(raw)
    val lowConfidence =
        buildSet {
            addAll(
                candidateLowConfidenceFields(
                    description = description,
                    amountCents = parsedAmount,
                    occurredDate = parsedDate,
                    currency = currency,
                    transactionType = type,
                    confidence = raw.confidence,
                ),
            )
            if (extractedDate == null && context.defaultDate != null) add("date")
        }
    val draft =
        CandidateDraft(
            source = context.source,
            sourceReference = context.sourceReference,
            sourcePage = raw.sourcePage,
            description = description,
            amountCents = parsedAmount?.let { kotlin.math.abs(it) },
            currency = currency,
            occurredDate = parsedDate,
            occurredTime = raw.transactionTime?.trim()?.takeIf(String::isNotEmpty),
            timeZoneId = context.timeZoneId,
            transactionType = type,
            suggestedCategoryName = raw.suggestedCategory?.trim()?.takeIf(String::isNotEmpty),
            accountOrPaymentMethod = raw.accountOrPaymentMethod?.trim()?.takeIf(String::isNotEmpty),
            installmentIndex = raw.installmentIndex,
            installmentTotal = raw.installmentTotal,
            note = raw.notes?.trim()?.takeIf(String::isNotEmpty),
            confidence = raw.confidence?.coerceIn(0.0, 1.0),
            sourceImageHash = context.sourceImageHash,
            supportingText = raw.supportingText?.trim()?.takeIf(String::isNotEmpty),
            warnings = raw.warnings.map(String::trim).filter(String::isNotEmpty).distinct(),
            lowConfidenceFields = lowConfidence,
        )
    return CandidateNormalizationResult(draft, validateCandidatePreview(draft))
}

/** Resolves a provider suggestion against the user's real category set, with local evidence as fallback. */
fun inferExpenseCategory(
    categories: List<Category>,
    suggestedName: String?,
    description: String?,
    supportingText: String?,
): Category? {
    if (categories.isEmpty()) return null
    val suggestion = normalizeTransactionText(suggestedName.orEmpty())
    val evidence = normalizeTransactionText(listOfNotNull(description, supportingText, suggestedName).joinToString(" "))

    categories.firstOrNull { normalizeTransactionText(it.name) == suggestion && suggestion.isNotEmpty() }?.let { return it }
    categories.firstOrNull {
        val name = normalizeTransactionText(it.name)
        name.isNotEmpty() &&
            (
                evidence.containsNormalizedPhrase(name) ||
                    suggestion.containsNormalizedPhrase(name) ||
                    (suggestion.isNotEmpty() && name.containsNormalizedPhrase(suggestion))
            )
    }?.let { return it }

    CATEGORY_INFERENCE_RULES.forEach { rule ->
        if (rule.evidenceTerms.any(evidence::containsNormalizedTerm)) {
            categories.firstOrNull { category ->
                val name = normalizeTransactionText(category.name)
                rule.categoryTerms.any(name::containsNormalizedTerm)
            }?.let { return it }
        }
    }
    return null
}

fun inferExpensePaymentMethod(vararg evidence: String?): PaymentMethod {
    val normalized = normalizeTransactionText(evidence.filterNotNull().joinToString(" "))
    return when {
        normalized.containsAny("pix", "instant payment") -> PaymentMethod.PIX
        normalized.containsAny("cash", "dinheiro", "especie") -> PaymentMethod.CASH
        normalized.containsAny("transfer", "transferencia", "ted", "doc") -> PaymentMethod.TRANSFER
        else -> PaymentMethod.DEBIT
    }
}

fun validateCandidatePreview(candidate: CandidateDraft): List<CandidateValidationIssue> =
    buildList {
        if (candidate.description.isNullOrBlank()) {
            add(CandidateValidationIssue(CandidateIssueCode.EMPTY_DESCRIPTION, CandidateIssueSeverity.ERROR, "description"))
        }
        when {
            candidate.amountCents == null -> {
                add(CandidateValidationIssue(CandidateIssueCode.INVALID_AMOUNT, CandidateIssueSeverity.ERROR, "amount"))
            }
            candidate.amountCents == 0L -> {
                add(CandidateValidationIssue(CandidateIssueCode.ZERO_AMOUNT, CandidateIssueSeverity.ERROR, "amount"))
            }
        }
        if (candidate.currency == null) {
            add(CandidateValidationIssue(CandidateIssueCode.MISSING_CURRENCY, CandidateIssueSeverity.WARNING, "currency"))
        }
        if (candidate.occurredDate == null) {
            add(CandidateValidationIssue(CandidateIssueCode.INVALID_DATE, CandidateIssueSeverity.ERROR, "date"))
        }
        if (candidate.transactionType == CandidateTransactionType.UNKNOWN) {
            add(CandidateValidationIssue(CandidateIssueCode.UNKNOWN_TYPE, CandidateIssueSeverity.WARNING, "transactionType"))
        }
        val installmentIsInvalid =
            (candidate.installmentIndex == null) != (candidate.installmentTotal == null) ||
                (candidate.installmentIndex != null && candidate.installmentIndex < 1) ||
                (candidate.installmentTotal != null && candidate.installmentTotal < 1) ||
                (
                    candidate.installmentIndex != null &&
                        candidate.installmentTotal != null &&
                        candidate.installmentIndex > candidate.installmentTotal
                )
        if (installmentIsInvalid) {
            add(CandidateValidationIssue(CandidateIssueCode.INVALID_INSTALLMENT, CandidateIssueSeverity.ERROR, "installment"))
        }
        if (candidate.confidence != null && candidate.confidence < LOW_CONFIDENCE_THRESHOLD) {
            add(CandidateValidationIssue(CandidateIssueCode.LOW_CONFIDENCE, CandidateIssueSeverity.WARNING, null))
        }
        if (candidate.description.orEmpty().lowercase().containsAny(*SUMMARY_ROW_TERMS)) {
            add(CandidateValidationIssue(CandidateIssueCode.POSSIBLE_SUMMARY_ROW, CandidateIssueSeverity.WARNING, null))
        }
    }

fun candidateLowConfidenceFields(
    description: String?,
    amountCents: Long?,
    occurredDate: LocalDate?,
    currency: String?,
    transactionType: CandidateTransactionType,
    confidence: Double?,
): Set<String> =
    buildSet {
        if (description.isNullOrBlank()) add("description")
        if (amountCents == null || amountCents == 0L) add("amount")
        if (occurredDate == null) add("date")
        if (currency.isNullOrBlank()) add("currency")
        if (transactionType == CandidateTransactionType.UNKNOWN) add("transactionType")
        if (confidence != null && confidence < LOW_CONFIDENCE_THRESHOLD) add("confidence")
    }

fun validateCandidateForExpenseApproval(candidate: TransactionCandidate): List<CandidateValidationIssue> =
    buildList {
        if (candidate.description.isNullOrBlank()) {
            add(CandidateValidationIssue(CandidateIssueCode.EMPTY_DESCRIPTION, CandidateIssueSeverity.ERROR, "description"))
        }
        if (candidate.amountCents == null) {
            add(CandidateValidationIssue(CandidateIssueCode.INVALID_AMOUNT, CandidateIssueSeverity.ERROR, "amount"))
        } else if (candidate.amountCents == 0L) {
            add(CandidateValidationIssue(CandidateIssueCode.ZERO_AMOUNT, CandidateIssueSeverity.ERROR, "amount"))
        }
        if (candidate.occurredDate == null) {
            add(CandidateValidationIssue(CandidateIssueCode.INVALID_DATE, CandidateIssueSeverity.ERROR, "date"))
        }
        if (candidate.currency?.uppercase() != SUPPORTED_LEDGER_CURRENCY) {
            add(CandidateValidationIssue(CandidateIssueCode.UNSUPPORTED_CURRENCY, CandidateIssueSeverity.ERROR, "currency"))
        }
        if (candidate.transactionType != CandidateTransactionType.EXPENSE) {
            add(CandidateValidationIssue(CandidateIssueCode.UNSUPPORTED_TYPE, CandidateIssueSeverity.ERROR, "transactionType"))
        }
        if (candidate.suggestedCategoryId.isNullOrBlank()) {
            add(CandidateValidationIssue(CandidateIssueCode.MISSING_CATEGORY, CandidateIssueSeverity.ERROR, "category"))
        }
        if (candidate.accountOrPaymentMethod?.uppercase() !in PaymentMethod.entries.map { it.name }) {
            add(
                CandidateValidationIssue(
                    CandidateIssueCode.INVALID_PAYMENT_METHOD,
                    CandidateIssueSeverity.ERROR,
                    "paymentMethod",
                ),
            )
        }
    }

fun transactionFingerprint(
    date: LocalDate?,
    amountCents: Long?,
    currency: String?,
    description: String?,
    account: String?,
    type: CandidateTransactionType,
): String? {
    if (date == null || amountCents == null || currency.isNullOrBlank() || description.isNullOrBlank()) return null
    val canonical =
        listOf(
            date.toString(),
            amountCents.toString(),
            currency.uppercase(),
            normalizeTransactionText(description),
            normalizeTransactionText(account.orEmpty()),
            type.name,
        ).joinToString("|")
    return sha256(canonical.encodeToByteArray())
}

fun normalizeTransactionText(value: String): String =
    value
        .lowercase()
        .replace('á', 'a')
        .replace('à', 'a')
        .replace('â', 'a')
        .replace('ã', 'a')
        .replace('ä', 'a')
        .replace('é', 'e')
        .replace('è', 'e')
        .replace('ê', 'e')
        .replace('ë', 'e')
        .replace('í', 'i')
        .replace('ì', 'i')
        .replace('î', 'i')
        .replace('ï', 'i')
        .replace('ó', 'o')
        .replace('ò', 'o')
        .replace('ô', 'o')
        .replace('õ', 'o')
        .replace('ö', 'o')
        .replace('ú', 'u')
        .replace('ù', 'u')
        .replace('û', 'u')
        .replace('ü', 'u')
        .replace('ç', 'c')
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

private fun normalizeCurrency(
    explicit: String?,
    amount: String?,
    fallback: String?,
): String? {
    val value = explicit?.trim()?.uppercase()
    return when {
        value in setOf("BRL", "R$", "REAL", "REAIS") -> "BRL"
        value in setOf("USD", "US$", "DOLLAR", "DÓLAR") -> "USD"
        value in setOf("EUR", "€", "EURO") -> "EUR"
        !value.isNullOrBlank() -> value
        amount?.contains("R$") == true -> "BRL"
        amount?.contains("€") == true -> "EUR"
        amount?.contains("US$") == true -> "USD"
        else -> fallback?.trim()?.uppercase()?.takeIf(String::isNotEmpty)
    }
}

private fun parseMoneyToCents(raw: String?): Long? {
    val original = raw?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val negative = original.startsWith('-') || (original.startsWith('(') && original.endsWith(')'))
    val digitsAndSeparators = original.replace(Regex("[^0-9,.]"), "")
    if (digitsAndSeparators.none(Char::isDigit)) return null

    val comma = digitsAndSeparators.lastIndexOf(',')
    val dot = digitsAndSeparators.lastIndexOf('.')
    val decimalIndex =
        when {
            comma >= 0 && dot >= 0 -> maxOf(comma, dot)
            comma >= 0 && digitsAndSeparators.length - comma - 1 in 1..2 -> comma
            dot >= 0 && digitsAndSeparators.length - dot - 1 in 1..2 -> dot
            else -> -1
        }
    val integralPart =
        if (decimalIndex >= 0) digitsAndSeparators.substring(0, decimalIndex) else digitsAndSeparators
    if (!hasValidGrouping(integralPart)) return null
    val wholeDigits =
        if (decimalIndex >= 0) {
            integralPart.filter(Char::isDigit)
        } else {
            digitsAndSeparators.filter(Char::isDigit)
        }
    val fractionalDigits =
        if (decimalIndex >= 0) {
            digitsAndSeparators.substring(decimalIndex + 1).filter(Char::isDigit).padEnd(2, '0').take(2)
        } else {
            "00"
        }
    val whole = wholeDigits.ifEmpty { "0" }.toLongOrNull() ?: return null
    val fraction = fractionalDigits.toLongOrNull() ?: return null
    if (whole > (Long.MAX_VALUE - fraction) / 100L) return null
    val cents = whole * 100L + fraction
    return if (negative) -cents else cents
}

private fun hasValidGrouping(integralPart: String): Boolean {
    val separators = integralPart.filter { it == ',' || it == '.' }
    if (separators.isEmpty()) return true
    if (separators.toSet().size != 1) return false
    val groups = integralPart.split(separators.first())
    return groups.first().length in 1..3 && groups.drop(1).all { it.length == 3 }
}

private fun parseUnambiguousDate(
    raw: String?,
    localeTag: String,
): LocalDate? {
    val value = raw?.trim()?.takeIf(String::isNotEmpty) ?: return null
    runCatching { LocalDate.parse(value) }.getOrNull()?.let { return it }
    val parts = value.split('/', '-', '.').map(String::trim)
    if (parts.size != 3 || parts.any { it.isEmpty() }) return null
    val numbers = parts.map { it.toIntOrNull() ?: return null }
    val yearPosition = numbers.indexOfFirst { it >= 1000 }
    if (yearPosition == -1) return null
    val year = numbers[yearPosition]
    val isMonthFirst = localeTag.lowercase().startsWith("en-us")
    val month: Int
    val day: Int
    when (yearPosition) {
        0 -> {
            month = numbers[1]
            day = numbers[2]
        }
        2 -> {
            month = if (isMonthFirst) numbers[0] else numbers[1]
            day = if (isMonthFirst) numbers[1] else numbers[0]
        }
        else -> return null
    }
    return runCatching { LocalDate(year, month, day) }.getOrNull()
}

private fun normalizeTransactionType(
    explicit: String?,
    evidence: String?,
): CandidateTransactionType {
    val normalizedExplicit = normalizeTransactionText(explicit.orEmpty())
    val normalizedEvidence = normalizeTransactionText(evidence.orEmpty())
    when {
        normalizedExplicit.containsAny("refund", "estorno", "reembolso") -> return CandidateTransactionType.REFUND
        normalizedExplicit.containsAny(
            "income",
            "receita",
            "deposit",
            "deposito",
            "pix recebido",
            "credito recebido",
            "salary",
            "salario",
            "wage",
            "renda",
        ) ||
            normalizedExplicit in setOf("credit", "credito") && !normalizedEvidence.hasExpenseEvidence() -> {
            return CandidateTransactionType.INCOME
        }
        normalizedExplicit.containsAny("transfer", "transferencia", "pix enviado") -> return CandidateTransactionType.TRANSFER
        normalizedExplicit.hasCardPurchaseEvidence() -> return CandidateTransactionType.EXPENSE
        normalizedExplicit.containsAny(
            "expense",
            "purchase",
            "compra",
            "debit",
            "debito",
            "pagamento",
            "paid",
            "fee",
            "charge",
            "bill",
            "tarifa",
            "boleto",
        ) -> {
            return CandidateTransactionType.EXPENSE
        }
    }

    val normalized = normalizedEvidence
    return when {
        normalized.containsAny("refund", "estorno", "reembolso") -> CandidateTransactionType.REFUND
        normalized.containsAny(
            "pix recebido",
            "income",
            "receita",
            "credito recebido",
            "valor creditado",
            "recebido",
            "deposit",
            "deposito",
            "salary",
            "salario",
            "wage",
            "renda",
        ) -> {
            CandidateTransactionType.INCOME
        }
        normalized.containsAny("transfer", "transferência", "pix enviado") -> {
            CandidateTransactionType.TRANSFER
        }
        normalized.containsAny(
            "expense",
            "purchase",
            "compra",
            "debit",
            "debito",
            "pagamento",
            "paid",
            "fee",
            "charge",
            "bill",
            "tarifa",
            "boleto",
        ) -> {
            CandidateTransactionType.EXPENSE
        }
        else -> CandidateTransactionType.UNKNOWN
    }
}

private fun String.hasCardPurchaseEvidence(): Boolean =
    containsAny("credit card purchase", "card purchase", "compra no credito", "compra cartao", "cartao de credito")

private fun String.hasExpenseEvidence(): Boolean =
    hasCardPurchaseEvidence() ||
        containsAny("purchase", "compra", "payment", "pagamento", "paid", "charge", "debit", "debito", "tarifa", "fee")

private fun String.containsAny(vararg needles: String): Boolean = needles.any(::contains)

private fun String.containsNormalizedPhrase(phrase: String): Boolean {
    if (phrase.isBlank()) return false
    return " $this ".contains(" $phrase ")
}

private fun String.containsNormalizedTerm(term: String): Boolean = split(' ').any { token -> token == term || token.startsWith(term) }

private fun deriveCandidateDescription(raw: RawTransactionExtraction): String? {
    raw.description?.sanitizeDescriptionEvidence()?.let { return it }
    raw.notes?.sanitizeDescriptionEvidence()?.let { return it }
    raw.supportingText?.sanitizeDescriptionEvidence()?.let {
        return it
    }
    raw.suggestedCategory?.sanitizeDescriptionEvidence()?.let { return it }
    return raw.accountOrPaymentMethod?.sanitizeDescriptionEvidence()
}

private fun String.sanitizeDescriptionEvidence(): String? =
    trim()
        .replace(Regex("(?i)(?:R\\$|US\\$|€|BRL|USD|EUR)?\\s*-?\\d[\\d.,]*"), " ")
        .replace(Regex("\\b\\d{1,4}[/.-]\\d{1,2}(?:[/.-]\\d{1,4})?\\b"), " ")
        .replace(Regex("\\s+"), " ")
        .trim(' ', '-', '·', ':', ';', ',')
        .take(MAX_CANDIDATE_DESCRIPTION_LENGTH)
        .takeIf(String::isNotEmpty)

private const val LOW_CONFIDENCE_THRESHOLD = 0.65
private const val SUPPORTED_LEDGER_CURRENCY = "BRL"
private const val MAX_CANDIDATE_DESCRIPTION_LENGTH = 120

private data class CategoryInferenceRule(
    val categoryTerms: Set<String>,
    val evidenceTerms: Set<String>,
)

private val CATEGORY_INFERENCE_RULES =
    listOf(
        CategoryInferenceRule(
            categoryTerms = setOf("moradia", "housing", "casa", "home"),
            evidenceTerms = setOf("aluguel", "condominio", "energia", "eletric", "agua", "gas", "internet", "imovel"),
        ),
        CategoryInferenceRule(
            categoryTerms = setOf("mercado", "food", "aliment", "grocer"),
            evidenceTerms = setOf("mercado", "supermerc", "padaria", "restaurante", "lanch", "delivery", "ifood", "comida"),
        ),
        CategoryInferenceRule(
            categoryTerms = setOf("transporte", "transport", "mobilidade"),
            evidenceTerms = setOf("uber", "99", "taxi", "onibus", "metro", "combust", "gasolina", "posto", "pedagio"),
        ),
        CategoryInferenceRule(
            categoryTerms = setOf("assinatura", "subscription", "servico"),
            evidenceTerms = setOf("netflix", "spotify", "youtube", "prime", "assinatura", "subscription", "mensalidade"),
        ),
    )
private val SUMMARY_ROW_TERMS =
    arrayOf(
        "statement total",
        "invoice total",
        "total da fatura",
        "total do extrato",
        "saldo total",
        "opening balance",
        "closing balance",
        "saldo anterior",
        "saldo final",
    )
