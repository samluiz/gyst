-- Frozen production schema from tag v1.4.4. Do not update this fixture when
-- changing the current schema; it exists to prove real v1.4.x upgrades.

CREATE TABLE category (
    id TEXT NOT NULL PRIMARY KEY,
    name TEXT NOT NULL,
    type TEXT NOT NULL,
    color TEXT,
    icon TEXT,
    CHECK(type IN ('ESSENTIAL', 'FIXED', 'VARIABLE', 'GOAL', 'RESERVE'))
);

CREATE TABLE budget_month (
    id TEXT NOT NULL PRIMARY KEY,
    year_month TEXT NOT NULL,
    total_income_cents INTEGER NOT NULL CHECK(total_income_cents >= 0),
    created_at TEXT NOT NULL
);

CREATE TABLE budget_allocation (
    id TEXT NOT NULL PRIMARY KEY,
    budget_month_id TEXT NOT NULL,
    category_id TEXT NOT NULL,
    planned_cents INTEGER NOT NULL CHECK(planned_cents >= 0),
    UNIQUE(budget_month_id, category_id),
    FOREIGN KEY (budget_month_id) REFERENCES budget_month(id) ON DELETE CASCADE,
    FOREIGN KEY (category_id) REFERENCES category(id) ON DELETE RESTRICT
);

CREATE TABLE expense (
    id TEXT NOT NULL PRIMARY KEY,
    occurred_at TEXT NOT NULL,
    amount_cents INTEGER NOT NULL CHECK(amount_cents >= 0),
    category_id TEXT NOT NULL,
    note TEXT,
    merchant TEXT,
    payment_method TEXT NOT NULL,
    recurrence_type TEXT NOT NULL DEFAULT 'ONE_TIME',
    created_at TEXT NOT NULL,
    schedule_item_id TEXT,
    recurrence_series_id TEXT,
    CHECK(payment_method IN ('PIX', 'DEBIT', 'CASH', 'TRANSFER')),
    CHECK(recurrence_type IN ('ONE_TIME', 'MONTHLY')),
    FOREIGN KEY (category_id) REFERENCES category(id) ON DELETE RESTRICT,
    FOREIGN KEY (recurrence_series_id) REFERENCES recurring_expense_series(id) ON DELETE SET NULL
);

CREATE TABLE recurring_expense_series (
    id TEXT NOT NULL PRIMARY KEY,
    start_year_month TEXT NOT NULL,
    end_year_month TEXT,
    day_of_month INTEGER NOT NULL CHECK(day_of_month >= 1 AND day_of_month <= 31),
    amount_cents INTEGER NOT NULL CHECK(amount_cents >= 0),
    category_id TEXT NOT NULL,
    note TEXT,
    merchant TEXT,
    payment_method TEXT NOT NULL,
    active INTEGER NOT NULL CHECK(active IN (0, 1)),
    CHECK(payment_method IN ('PIX', 'DEBIT', 'CASH', 'TRANSFER')),
    CHECK(end_year_month IS NULL OR end_year_month >= start_year_month),
    FOREIGN KEY (category_id) REFERENCES category(id) ON DELETE RESTRICT
);

CREATE TABLE subscription (
    id TEXT NOT NULL PRIMARY KEY,
    name TEXT NOT NULL,
    amount_cents INTEGER NOT NULL CHECK(amount_cents >= 0),
    billing_day INTEGER NOT NULL CHECK(billing_day >= 1 AND billing_day <= 31),
    category_id TEXT NOT NULL,
    active INTEGER NOT NULL,
    start_year_month TEXT NOT NULL,
    CHECK(active IN (0, 1)),
    FOREIGN KEY (category_id) REFERENCES category(id) ON DELETE RESTRICT
);

CREATE TABLE installment_plan (
    id TEXT NOT NULL PRIMARY KEY,
    name TEXT NOT NULL,
    total_installments INTEGER NOT NULL CHECK(total_installments >= 1),
    total_amount_cents INTEGER NOT NULL CHECK(total_amount_cents >= 0),
    monthly_amount_cents INTEGER NOT NULL CHECK(monthly_amount_cents >= 0),
    start_year_month TEXT NOT NULL,
    end_year_month TEXT NOT NULL,
    category_id TEXT NOT NULL,
    active INTEGER NOT NULL,
    CHECK(active IN (0, 1)),
    CHECK(end_year_month >= start_year_month),
    FOREIGN KEY (category_id) REFERENCES category(id) ON DELETE RESTRICT
);

CREATE TABLE payment_schedule_item (
    id TEXT NOT NULL PRIMARY KEY,
    kind TEXT NOT NULL,
    ref_id TEXT NOT NULL,
    category_id TEXT NOT NULL,
    subscription_id TEXT,
    installment_plan_id TEXT,
    due_date TEXT NOT NULL,
    amount_cents INTEGER NOT NULL,
    status TEXT NOT NULL,
    paid_at TEXT,
    CHECK(kind IN ('SUBSCRIPTION', 'INSTALLMENT')),
    CHECK(status IN ('DUE', 'PAID', 'SKIPPED')),
    CHECK(
      (kind = 'SUBSCRIPTION' AND subscription_id IS NOT NULL AND installment_plan_id IS NULL) OR
      (kind = 'INSTALLMENT' AND installment_plan_id IS NOT NULL AND subscription_id IS NULL)
    ),
    FOREIGN KEY (subscription_id) REFERENCES subscription(id) ON DELETE CASCADE,
    FOREIGN KEY (installment_plan_id) REFERENCES installment_plan(id) ON DELETE CASCADE,
    FOREIGN KEY (category_id) REFERENCES category(id) ON DELETE RESTRICT
);

CREATE TABLE app_setting (
    key TEXT NOT NULL PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_budget_month_year_month_unique
ON budget_month(year_month);

CREATE INDEX IF NOT EXISTS idx_budget_allocation_budget_month
ON budget_allocation(budget_month_id);

CREATE INDEX IF NOT EXISTS idx_expense_occurred_at
ON expense(occurred_at);

CREATE INDEX IF NOT EXISTS idx_expense_category_occurred
ON expense(category_id, occurred_at);

CREATE INDEX IF NOT EXISTS idx_expense_recurring_lookup
ON expense(recurrence_type, schedule_item_id, occurred_at);

CREATE INDEX IF NOT EXISTS idx_expense_recurrence_month
ON expense(recurrence_series_id, substr(occurred_at, 1, 7))
WHERE recurrence_series_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_recurring_expense_active_range
ON recurring_expense_series(active, start_year_month, end_year_month);

CREATE UNIQUE INDEX IF NOT EXISTS idx_schedule_unique_ref_kind_due
ON payment_schedule_item(ref_id, kind, due_date);

CREATE INDEX IF NOT EXISTS idx_schedule_due_date
ON payment_schedule_item(due_date);

CREATE INDEX IF NOT EXISTS idx_subscription_active
ON subscription(active, amount_cents);

CREATE INDEX IF NOT EXISTS idx_installment_active_end
ON installment_plan(active, end_year_month);

PRAGMA user_version=7;
