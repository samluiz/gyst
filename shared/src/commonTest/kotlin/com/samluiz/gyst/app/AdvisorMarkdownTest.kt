package com.samluiz.gyst.app

import kotlin.test.Test
import kotlin.test.assertEquals

class AdvisorMarkdownTest {
    @Test
    fun escapesCompleteRealSymbolsForMarkdownRendering() {
        assertEquals(
            "R\\$ 7.855 e R\\$ 676",
            prepareAdvisorMarkdown("R$ 7.855 e R$ 676"),
        )
    }

    @Test
    fun repairsIncompleteRealPrefixesBeforeRendering() {
        assertEquals(
            "Você terá R\\$ 1.493 livres.",
            prepareAdvisorMarkdown("Você terá R 1.493 livres."),
        )
    }

    @Test
    fun preservesAlreadyEscapedSymbolsAndOrdinaryText() {
        assertEquals(
            "Valor: R\\$ 10.000. Resposta Rápida.",
            prepareAdvisorMarkdown("Valor: R\\$ 10.000. Resposta Rápida."),
        )
    }
}
