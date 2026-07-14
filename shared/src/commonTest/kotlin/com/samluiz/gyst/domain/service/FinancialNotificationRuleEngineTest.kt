package com.samluiz.gyst.domain.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FinancialNotificationRuleEngineTest {
    private val engine = FinancialNotificationRuleEngine()

    @Test
    fun detectsPortuguesePurchaseBeforeAi() {
        val result =
            engine.evaluate(
                FinancialNotificationText(
                    sourcePackage = "bank.app",
                    title = "Compra aprovada",
                    text = "Compra de R$ 42,90 aprovada em Example Store.",
                ),
            )

        val candidate = assertIs<FinancialNotificationRuleResult.Candidate>(result).extraction
        assertEquals("R$ 42,90", candidate.amount)
        assertEquals("expense", candidate.transactionType)
        assertEquals("Example Store", candidate.description)
    }

    @Test
    fun detectsEnglishRefundWithoutCoercingItToExpense() {
        val result =
            engine.evaluate(
                FinancialNotificationText(
                    sourcePackage = "card.app",
                    title = "Refund received",
                    text = "A refund of $18.25 from Example Market was completed.",
                ),
            )

        assertEquals("refund", assertIs<FinancialNotificationRuleResult.Candidate>(result).extraction.transactionType)
    }

    @Test
    fun detectsUngroupedFourDigitAmountWithoutTruncatingIt() {
        val result =
            engine.evaluate(
                FinancialNotificationText(
                    sourcePackage = "bank.app",
                    title = "Compra aprovada",
                    text = "Compra de R$ 1200,00 aprovada em Example Store.",
                ),
            )

        assertEquals(
            "R$ 1200,00",
            assertIs<FinancialNotificationRuleResult.Candidate>(result).extraction.amount,
        )
    }

    @Test
    fun rejectsAuthenticationCodesBeforePersistenceOrAi() {
        val result =
            engine.evaluate(
                FinancialNotificationText(
                    sourcePackage = "bank.app",
                    title = "Código de verificação",
                    text = "Seu código de segurança é 483921. Não compartilhe.",
                ),
            )

        assertEquals(
            NotificationIgnoreReason.SENSITIVE,
            assertIs<FinancialNotificationRuleResult.Ignored>(result).reason,
        )
    }

    @Test
    fun rejectsPromotionalMoneyMentions() {
        val result =
            engine.evaluate(
                FinancialNotificationText(
                    sourcePackage = "bank.app",
                    title = "Oferta especial",
                    text = "Aproveite um desconto de R$ 50,00 e compre agora.",
                ),
            )

        assertEquals(
            NotificationIgnoreReason.PROMOTIONAL,
            assertIs<FinancialNotificationRuleResult.Ignored>(result).reason,
        )
    }

    @Test
    fun requiresBothAmountAndFinancialAction() {
        val result =
            engine.evaluate(
                FinancialNotificationText(
                    sourcePackage = "calendar.app",
                    title = "Dinner",
                    text = "Reservation at Example Store at 20:00.",
                ),
            )

        assertEquals(
            NotificationIgnoreReason.NO_FINANCIAL_SIGNAL,
            assertIs<FinancialNotificationRuleResult.Ignored>(result).reason,
        )
    }
}
