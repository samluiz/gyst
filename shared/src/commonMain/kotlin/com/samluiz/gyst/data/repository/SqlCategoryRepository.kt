package com.samluiz.gyst.data.repository

import com.samluiz.gyst.domain.model.*
import com.samluiz.gyst.domain.repository.*

class SqlCategoryRepository(private val holder: DatabaseHolder) : CategoryRepository {
    override suspend fun list(): List<Category> =
        holder.withDatabase { db ->
            db.financeQueries.selectAllCategories().executeAsList().map {
                Category(
                    id = it.id,
                    name = it.name,
                    type = CategoryType.valueOf(it.type),
                    color = it.color,
                    icon = it.icon,
                )
            }
        }

    override suspend fun upsert(category: Category) {
        holder.withDatabase { db ->
            db.financeQueries.upsertCategory(
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
    }

    override suspend fun delete(id: String) {
        holder.withDatabase { db ->
            db.financeQueries.deleteCategory(id)
        }
    }

    override suspend fun usageCount(id: String): Long =
        holder.withDatabase { db ->
            db.financeQueries.countCategoryUsage(id).executeAsOne()
        }
}
