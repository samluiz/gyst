package com.samluiz.gyst.app

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState

@Composable
internal fun AdvisorMarkdown(
    content: String,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    val markdownState = rememberMarkdownState(prepareAdvisorMarkdown(content), retainState = true)
    val colors = MaterialTheme.colorScheme

    SelectionContainer {
        Markdown(
            markdownState = markdownState,
            modifier = modifier.fillMaxWidth(),
            colors =
                markdownColor(
                    text = colors.onSurface,
                    codeBackground = colors.surfaceVariant.copy(alpha = 0.7f),
                    dividerColor = colors.outlineVariant,
                    tableBackground = colors.surfaceVariant.copy(alpha = 0.35f),
                ),
            typography =
                markdownTypography(
                    h1 = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    h2 = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    h3 = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    h4 = textStyle.copy(fontWeight = FontWeight.Bold),
                    h5 = textStyle.copy(fontWeight = FontWeight.Bold),
                    h6 = textStyle.copy(fontWeight = FontWeight.SemiBold),
                    text = textStyle,
                    paragraph = textStyle,
                    ordered = textStyle,
                    bullet = textStyle,
                    list = textStyle,
                    quote = textStyle.copy(fontStyle = FontStyle.Italic, color = colors.onSurfaceVariant),
                    code = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    inlineCode = textStyle.copy(fontFamily = FontFamily.Monospace),
                    textLink =
                        TextLinkStyles(
                            style =
                                textStyle
                                    .copy(
                                        color = colors.primary,
                                        fontWeight = FontWeight.Medium,
                                        textDecoration = TextDecoration.Underline,
                                    ).toSpanStyle(),
                        ),
                    table = textStyle,
                ),
        )
    }
}

private val incompleteRealPrefix = Regex("""\bR[ \u00A0]+(?=\d)""")

internal fun prepareAdvisorMarkdown(content: String): String {
    val completedCurrencyPrefixes =
        incompleteRealPrefix.replace(content) {
            "R${'$'} "
        }

    return buildString(completedCurrencyPrefixes.length) {
        completedCurrencyPrefixes.forEachIndexed { index, character ->
            val isUnescapedRealSymbol =
                character == '$' &&
                    index > 0 &&
                    completedCurrencyPrefixes[index - 1] == 'R' &&
                    (index < 2 || completedCurrencyPrefixes[index - 2] != '\\')
            if (isUnescapedRealSymbol) append('\\')
            append(character)
        }
    }
}
