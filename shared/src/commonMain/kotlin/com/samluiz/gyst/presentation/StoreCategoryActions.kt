package com.samluiz.gyst.presentation

import com.samluiz.gyst.domain.model.Category
import com.samluiz.gyst.domain.model.CategoryType
import com.samluiz.gyst.domain.repository.CategoryRepository
import com.samluiz.gyst.domain.usecase.id

internal class StoreCategoryActions(
    private val categoryRepository: CategoryRepository,
    private val refresh: suspend (Boolean) -> Unit,
) {
    suspend fun add(
        name: String,
        onCreated: ((String) -> Unit)?,
    ) {
        val normalized = name.trim()
        if (normalized.isBlank()) return
        val existing = categoryRepository.list().firstOrNull { it.name.equals(normalized, ignoreCase = true) }
        if (existing != null) {
            onCreated?.invoke(existing.id)
            refresh(false)
            return
        }
        val categoryId = id("cat")
        categoryRepository.upsert(
            Category(
                id = categoryId,
                name = normalized,
                type = CategoryType.VARIABLE,
            ),
        )
        onCreated?.invoke(categoryId)
        refresh(false)
    }

    suspend fun rename(
        categoryId: String,
        name: String,
        onDone: ((Boolean) -> Unit)?,
    ) {
        val normalized = name.trim()
        if (normalized.isBlank()) {
            onDone?.invoke(false)
            return
        }
        val categories = categoryRepository.list()
        val current = categories.firstOrNull { it.id == categoryId }
        if (current == null || categories.any { it.id != categoryId && it.name.equals(normalized, ignoreCase = true) }) {
            onDone?.invoke(false)
            return
        }
        categoryRepository.upsert(current.copy(name = normalized))
        onDone?.invoke(true)
        refresh(false)
    }

    suspend fun delete(
        categoryId: String,
        onDone: ((Boolean, Long) -> Unit)?,
    ) {
        val usageCount = categoryRepository.usageCount(categoryId)
        if (usageCount > 0L) {
            onDone?.invoke(false, usageCount)
            return
        }
        categoryRepository.delete(categoryId)
        onDone?.invoke(true, 0L)
        refresh(false)
    }
}
