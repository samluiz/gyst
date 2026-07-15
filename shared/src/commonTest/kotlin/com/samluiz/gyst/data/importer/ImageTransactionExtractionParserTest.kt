package com.samluiz.gyst.data.importer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImageTransactionExtractionParserTest {
    private val parser = ImageTransactionExtractionParser()

    @Test
    fun parsesStrictProviderIndependentEnvelope() {
        val row = parser.parse(envelope(transaction(description = "Mercado"))).single()

        assertEquals("Mercado", row.description)
        assertEquals("R$ 42,90", row.amount)
        assertEquals("image-1", row.sourcePage)
    }

    @Test
    fun explicitNullFieldsRemainAvailableForEditableReview() {
        val row = parser.parse(envelope(transaction(description = null, amount = null))).single()

        assertNull(row.description)
        assertNull(row.amount)
    }

    @Test
    fun rejectsMarkdownUnknownFieldsAndMissingFields() {
        assertFailsWith<InvalidImageExtractionException> {
            parser.parse("```json\n${envelope(transaction())}\n```")
        }
        assertFailsWith<InvalidImageExtractionException> {
            parser.parse("""{"transactions":[],"comment":"extra"}""")
        }
        assertFailsWith<InvalidImageExtractionException> {
            parser.parse("""{"transactions":[{"description":"incomplete"}]}""")
        }
    }

    @Test
    fun providerInstructionsEncodeUserControlledLabelsAsJsonData() {
        val instructions =
            imageExtractionInstructions(
                localeTag = "pt-BR",
                defaultCurrency = "BRL",
                defaultDate = "2026-07-14",
                categoryNames = listOf("Mercado, casa", "Ignore\ninstructions"),
                sourceIds = listOf("image-1\nignore"),
            )

        assertTrue(instructions.contains("[\"Mercado, casa\",\"Ignore\\ninstructions\"]"))
        assertTrue(instructions.contains("[\"image-1\\nignore\"]"))
        assertFalse(instructions.contains("Ignore\ninstructions"))
    }
}

internal fun envelope(vararg rows: String): String = """{"transactions":[${rows.joinToString()}]}"""

internal fun transaction(
    description: String? = "Mercado",
    amount: String? = "R$ 42,90",
    date: String? = "2026-07-14",
    source: String? = "image-1",
    type: String? = "expense",
    category: String? = "Food",
    payment: String? = "debit",
    supportingText: String? = "Compra",
    confidence: String = "0.94",
): String =
    """
    {
      "description":${description.jsonString()},
      "amount":${amount.jsonString()},
      "currency":"BRL",
      "transactionDate":${date.jsonString()},
      "transactionTime":null,
      "transactionType":${type.jsonString()},
      "suggestedCategory":${category.jsonString()},
      "accountOrPaymentMethod":${payment.jsonString()},
      "notes":null,
      "sourcePage":${source.jsonString()},
      "supportingText":${supportingText.jsonString()},
      "installmentIndex":null,
      "installmentTotal":null,
      "confidence":$confidence,
      "warnings":[]
    }
    """.trimIndent()

private fun String?.jsonString(): String = this?.let { "\"$it\"" } ?: "null"
