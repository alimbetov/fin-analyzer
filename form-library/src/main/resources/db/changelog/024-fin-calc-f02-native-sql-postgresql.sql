--liquibase formatted sql
--changeset fin-calc:024a dbms:postgresql splitStatements:false
-- CALC-SQL-01: UAT-only deterministic financial arithmetic. Does not read
-- latest forms, issue qualifications, or mutate fin_calc.current/result.
-- PostgreSQL round(numeric,n) differs from Java BigDecimal HALF_EVEN.
-- This exact decimal implementation must be parity-tested before selection.
CREATE FUNCTION fin_calc.f02_round_half_even(p_amount NUMERIC,p_decimals INTEGER)
RETURNS NUMERIC LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE AS $$
DECLARE
  factor NUMERIC;
  magnitude NUMERIC;
  integer_part NUMERIC;
  remainder_part NUMERIC;
BEGIN
  IF p_amount IS NULL OR p_decimals IS NULL OR p_decimals < 0 OR p_decimals > 12 THEN
    RAISE EXCEPTION 'CALC_SQL_F02_ROUND_INPUT_INVALID' USING ERRCODE='22003';
  END IF;
  factor := power(10::numeric,p_decimals);
  magnitude := abs(p_amount)*factor;
  integer_part := trunc(magnitude);
  remainder_part := magnitude-integer_part;
  IF remainder_part > 0.5 OR (remainder_part=0.5 AND mod(integer_part,2)=1) THEN
    integer_part := integer_part+1;
  END IF;
  RETURN sign(p_amount)*integer_part/factor;
END;
$$;

--changeset fin-calc:024b dbms:postgresql splitStatements:false
-- Use exact integer ratios rather than PostgreSQL repeating NUMERIC division
-- and then rounding. Both operands are already exact 2dp money amounts.
CREATE FUNCTION fin_calc.f02_ratio_half_even(p_numerator NUMERIC,p_denominator NUMERIC)
RETURNS NUMERIC LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE AS $$
DECLARE
  numerator_scaled NUMERIC;
  denominator_cents NUMERIC;
  quotient NUMERIC;
  remainder_cents NUMERIC;
BEGIN
  IF p_numerator IS NULL OR p_denominator IS NULL OR p_denominator<=0
     OR p_numerator<>trunc(p_numerator*100)/100
     OR p_denominator<>trunc(p_denominator*100)/100 THEN
    RAISE EXCEPTION 'CALC_SQL_F02_RATIO_INPUT_INVALID' USING ERRCODE='22003';
  END IF;
  numerator_scaled := abs(p_numerator*100)*100000000;
  denominator_cents := p_denominator*100;
  quotient := trunc(numerator_scaled/denominator_cents);
  remainder_cents := mod(numerator_scaled,denominator_cents);
  IF remainder_cents*2 > denominator_cents
      OR (remainder_cents*2=denominator_cents AND mod(quotient,2)=1) THEN
    quotient := quotient+1;
  END IF;
  RETURN sign(p_numerator)*quotient/100000000;
END;
$$;

--changeset fin-calc:024c dbms:postgresql splitStatements:false
-- Pure, typed SQL alternative for F02_TEST_ONLY_V1 (not production S2).
-- Money nonnegative source magnitudes; derived profit may be negative.
-- No function result is written to fin_calc.calc_result until Java parity
-- and existing lease/fencing/epoch checks have independently succeeded.
CREATE FUNCTION fin_calc.f02_native_calculate(
    p_net_revenue NUMERIC,
    p_cogs NUMERIC,
    p_operating_expenses NUMERIC)
RETURNS TABLE (
    gross_profit NUMERIC,
    gross_margin NUMERIC,
    operating_result NUMERIC,
    margin_state TEXT)
LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE AS $$
DECLARE
  revenue NUMERIC;
  cost NUMERIC;
  expenses NUMERIC;
  profit NUMERIC;
BEGIN
  IF p_net_revenue IS NULL OR p_cogs IS NULL OR p_operating_expenses IS NULL
     OR p_net_revenue<0 OR p_cogs<0 OR p_operating_expenses<0
     OR scale(p_net_revenue)>12 OR scale(p_cogs)>12
     OR scale(p_operating_expenses)>12 THEN
    RAISE EXCEPTION 'CALC_SQL_F02_INPUT_VALUE_INVALID' USING ERRCODE='22003';
  END IF;
  revenue:=fin_calc.f02_round_half_even(p_net_revenue,2);
  cost:=fin_calc.f02_round_half_even(p_cogs,2);
  expenses:=fin_calc.f02_round_half_even(p_operating_expenses,2);
  profit:=revenue-cost;
  gross_profit:=profit;
  operating_result:=profit-expenses;
  IF revenue=0 THEN
    gross_margin:=NULL;
    margin_state:='INVALID';
  ELSE
    gross_margin:=fin_calc.f02_ratio_half_even(profit,revenue);
    margin_state:='PRESENT';
  END IF;
  RETURN NEXT;
END;
$$;
