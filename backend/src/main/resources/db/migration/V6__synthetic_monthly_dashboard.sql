-- FIN-SIM-R1.1: independent read-only SQL dashboard for synthetic scenarios.
-- Strictly a benchmark visualization, not a financial-reporting view.
CREATE VIEW fin_sim.monthly_dashboard AS
WITH monthly AS (
    SELECT
        scenario_code,
        source_revision,
        period_month,
        MAX(numeric_value) FILTER (WHERE metric_code='REV_NET'
             AND value_state IN ('PRESENT','EXPLICIT_ZERO')) AS revenue,
        MAX(currency_code) FILTER (WHERE metric_code='REV_NET') AS revenue_currency,
        MAX(numeric_value) FILTER (WHERE metric_code='COGS'
             AND value_state IN ('PRESENT','EXPLICIT_ZERO')) AS cogs,
        MAX(numeric_value) FILTER (WHERE metric_code='OPEX'
             AND value_state IN ('PRESENT','EXPLICIT_ZERO')) AS opex,
        MAX(numeric_value) FILTER (WHERE metric_code='NET_PROFIT'
             AND value_state IN ('PRESENT','EXPLICIT_ZERO')) AS modeled_net_result,
        MAX(numeric_value) FILTER (WHERE metric_code='CASH_END'
             AND value_state IN ('PRESENT','EXPLICIT_ZERO')) AS cash_end,
        MAX(numeric_value) FILTER (WHERE metric_code='AR_END'
             AND value_state IN ('PRESENT','EXPLICIT_ZERO')) AS ar_end,
        MAX(numeric_value) FILTER (WHERE metric_code='AP_END'
             AND value_state IN ('PRESENT','EXPLICIT_ZERO')) AS ap_end,
        MAX(numeric_value) FILTER (WHERE metric_code='INVENTORY_END'
             AND value_state IN ('PRESENT','EXPLICIT_ZERO')) AS inventory_end,
        MAX(numeric_value) FILTER (WHERE metric_code='DEBT_END'
             AND value_state IN ('PRESENT','EXPLICIT_ZERO')) AS debt_end,
        COUNT(DISTINCT currency_code) FILTER (
             WHERE unit_code='MONEY' AND numeric_value IS NOT NULL) AS monetary_currency_count
    FROM fin_sim.observation
    GROUP BY scenario_code,source_revision,period_month
),
series AS (
    SELECT
        m.*,
        monetary_currency_count=1 AS consistent_currency,
        LAG(period_month) OVER w AS previous_month,
        LAG(period_month,12) OVER w AS prior_year_month,
        LAG(revenue) OVER w AS previous_revenue,
        LAG(revenue,12) OVER w AS year_ago_revenue,
        LAG(revenue_currency) OVER w AS previous_revenue_currency,
        LAG(revenue_currency,12) OVER w AS year_ago_revenue_currency,
        LAG(monetary_currency_count=1) OVER w AS previous_currency_ok,
        LAG(monetary_currency_count=1,12) OVER w AS year_ago_currency_ok,
        LAG(cash_end) OVER w AS previous_cash,
        LAG(ar_end) OVER w AS previous_ar,
        LAG(cogs,12) OVER w AS year_ago_cogs,
        SUM(revenue) OVER w12 AS trailing_revenue_sum,
        COUNT(revenue) OVER w12 AS trailing_revenue_count,
        MIN(period_month) OVER w12 AS trailing_first_month,
        MIN(revenue_currency) OVER w12 AS trailing_min_currency,
        MAX(revenue_currency) OVER w12 AS trailing_max_currency,
        MIN((monetary_currency_count=1)::integer) OVER w12 AS trailing_all_currency_ok,
        SUM(revenue) OVER w3 AS rolling_revenue_sum,
        COUNT(revenue) OVER w3 AS rolling_revenue_count,
        MIN(period_month) OVER w3 AS rolling_first_month,
        MIN((monetary_currency_count=1)::integer) OVER w3 AS rolling_all_currency_ok,
        MIN(revenue_currency) OVER w3 AS rolling_min_currency,
        MAX(revenue_currency) OVER w3 AS rolling_max_currency
    FROM monthly m
    WINDOW w AS (
        PARTITION BY scenario_code,source_revision ORDER BY period_month
    ), w12 AS (
        PARTITION BY scenario_code,source_revision ORDER BY period_month
        ROWS BETWEEN 11 PRECEDING AND CURRENT ROW
    ), w3 AS (
        PARTITION BY scenario_code,source_revision ORDER BY period_month
        ROWS BETWEEN 2 PRECEDING AND CURRENT ROW
    )
)
SELECT
    scenario_code,source_revision,period_month,revenue_currency,
    consistent_currency,revenue,cogs,opex,modeled_net_result,
    cash_end,ar_end,ap_end,inventory_end,debt_end,
    CASE WHEN consistent_currency AND revenue IS NOT NULL AND cogs IS NOT NULL
         THEN revenue-cogs END AS gross_profit,
    CASE WHEN consistent_currency AND revenue>0 AND cogs IS NOT NULL
         THEN ROUND((revenue-cogs)/revenue*100,6) END AS gross_margin_percent,
    CASE WHEN consistent_currency AND previous_currency_ok
         AND revenue_currency=previous_revenue_currency
         AND previous_month=(period_month-INTERVAL '1 month')::date
         AND previous_revenue>0 AND revenue IS NOT NULL
         THEN ROUND((revenue-previous_revenue)/previous_revenue*100,6)
    END AS revenue_mom_percent,
    CASE WHEN consistent_currency AND year_ago_currency_ok
         AND revenue_currency=year_ago_revenue_currency
         AND prior_year_month=(period_month-INTERVAL '12 months')::date
         AND year_ago_revenue>0 AND revenue IS NOT NULL
         THEN ROUND((revenue-year_ago_revenue)/year_ago_revenue*100,6)
    END AS revenue_yoy_percent,
    CASE WHEN consistent_currency AND year_ago_currency_ok
         AND revenue_currency=year_ago_revenue_currency
         AND prior_year_month=(period_month-INTERVAL '12 months')::date
         AND revenue>0 AND year_ago_revenue>0
         AND cogs IS NOT NULL AND year_ago_cogs IS NOT NULL
         THEN ROUND(((revenue-cogs)/revenue -
                (year_ago_revenue-year_ago_cogs)/year_ago_revenue)*100,6)
    END AS gross_margin_yoy_points,
    CASE WHEN consistent_currency AND previous_currency_ok
         AND revenue_currency=previous_revenue_currency
         AND previous_month=(period_month-INTERVAL '1 month')::date
         AND cash_end IS NOT NULL AND previous_cash IS NOT NULL
         THEN cash_end-previous_cash END AS cash_mom_change,
    CASE WHEN consistent_currency AND previous_currency_ok
         AND revenue_currency=previous_revenue_currency
         AND previous_month=(period_month-INTERVAL '1 month')::date
         AND ar_end IS NOT NULL AND previous_ar IS NOT NULL
         THEN ar_end-previous_ar END AS ar_mom_change,
    CASE WHEN trailing_revenue_count=12 AND trailing_all_currency_ok=1
         AND trailing_min_currency=trailing_max_currency
         AND trailing_first_month=(period_month-INTERVAL '11 months')::date
         THEN trailing_revenue_sum END AS trailing_12m_revenue,
    CASE WHEN rolling_revenue_count=3 AND rolling_all_currency_ok=1
         AND rolling_min_currency=rolling_max_currency
         AND rolling_first_month=(period_month-INTERVAL '2 months')::date
         THEN ROUND(rolling_revenue_sum/3,6) END AS moving_3m_revenue
FROM series;
