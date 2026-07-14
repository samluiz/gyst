package com.samluiz.gyst.data.repository

import com.samluiz.gyst.domain.model.*
import com.samluiz.gyst.domain.repository.*

class SqlSubscriptionRepository(private val holder: DatabaseHolder) : SubscriptionRepository {
    override suspend fun upsert(subscription: Subscription) {
        holder.withDatabase { db ->
            db.financeQueries.upsertSubscription(
                name = subscription.name,
                amount_cents = subscription.amountCents,
                billing_day = subscription.billingDay.toLong(),
                category_id = subscription.categoryId,
                active = if (subscription.active) 1 else 0,
                start_year_month = subscription.startYearMonth.toString(),
                id = subscription.id,
                id_ = subscription.id,
                name_ = subscription.name,
                amount_cents_ = subscription.amountCents,
                billing_day_ = subscription.billingDay.toLong(),
                category_id_ = subscription.categoryId,
                active_ = if (subscription.active) 1 else 0,
                start_year_month_ = subscription.startYearMonth.toString(),
            )
        }
    }

    override suspend fun list(): List<Subscription> =
        holder.withDatabase { db ->
            db.financeQueries.selectSubscriptions().executeAsList().map {
                Subscription(
                    id = it.id,
                    name = it.name,
                    amountCents = it.amount_cents,
                    billingDay = it.billing_day.toInt(),
                    categoryId = it.category_id,
                    active = it.active == 1L,
                    startYearMonth = YearMonth.parse(it.start_year_month),
                )
            }
        }

    override suspend fun listActive(): List<Subscription> =
        holder.withDatabase { db ->
            db.financeQueries.selectActiveSubscriptions().executeAsList().map {
                Subscription(
                    id = it.id,
                    name = it.name,
                    amountCents = it.amount_cents,
                    billingDay = it.billing_day.toInt(),
                    categoryId = it.category_id,
                    active = it.active == 1L,
                    startYearMonth = YearMonth.parse(it.start_year_month),
                )
            }
        }

    override suspend fun deactivate(id: String) {
        holder.withDatabase { db ->
            db.financeQueries.deactivateSubscription(id)
        }
    }
}
