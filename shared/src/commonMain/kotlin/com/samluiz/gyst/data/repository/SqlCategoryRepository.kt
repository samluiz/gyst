package com.samluiz.gyst.data.repository

import com.samluiz.gyst.domain.model.*
import com.samluiz.gyst.domain.repository.*
import com.samluiz.gyst.domain.usecase.id

class SqlCategoryRepository(private val holder: DatabaseHolder) : CategoryRepository {
    private val q get() = holder.db.financeQueries

    override suspend fun list(): List<Category> =
        q.selectAllCategories().executeAsList().map {
            Category(
                id = it.id,
                name = it.name,
                type = CategoryType.valueOf(it.type),
                color = it.color,
                icon = it.icon,
            )
        }

    override suspend fun upsert(category: Category) {
        q.upsertCategory(
            name = category.name,
            type = category.type.name,
            color = category.color,
            icon = category.icon,
            id = category.id,
            id_ = category.id,
            name_ = category.name,
            type_ = category.type.name,
            color_ = category.color,
            icon_ = category.icon,
        )
    }

    override suspend fun delete(id: String) {
        q.deleteCategory(id)
    }

    override suspend fun usageCount(id: String): Long = q.countCategoryUsage(id).executeAsOne()
}
