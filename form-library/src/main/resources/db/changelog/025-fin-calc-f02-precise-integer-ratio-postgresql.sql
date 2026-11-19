--liquibase formatted sql
--changeset fin-calc:025a dbms:postgresql splitStatements:false
-- CALC-SQL-01 precision hardening (F02 TEST_ONLY).
-- A numeric / 100 division can lose low-order fractional digits for huge
-- magnitudes due to the result scale choice. Validate exact cents via MOD,
-- and compute quotient with numeric DIV (integer truncation) instead.
-- Deliberately keep original migration 024 unchanged for upgrade safety.
CREATE OR REPLACE FUNCTION fin_calc.f02_ratio_half_even(
    p_numerator NUMERIC,p_denominator NUMERIC)
RETURNS NUMERIC LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE AS $$
DECLARE
  numerator_scaled NUMERIC;
  denominator_cents NUMERIC;
  quotient NUMERIC;
  remainder_cents NUMERIC;
BEGIN
  IF p_numerator IS NULL OR p_denominator IS NULL OR p_denominator<=0
     OR mod(p_numerator*100,1)<>0
     OR mod(p_denominator*100,1)<>0 THEN
    RAISE EXCEPTION 'CALC_SQL_F02_RATIO_INPUT_INVALID' USING ERRCODE='22003';
  END IF;
  numerator_scaled:=abs(p_numerator*100)*100000000;
  denominator_cents:=p_denominator*100;
  quotient:=div(numerator_scaled,denominator_cents);
  remainder_cents:=mod(numerator_scaled,denominator_cents);
  IF remainder_cents*2>denominator_cents
     OR (remainder_cents*2=denominator_cents AND mod(quotient,2)=1) THEN
    quotient:=quotient+1;
  END IF;
  RETURN sign(p_numerator)*quotient/100000000;
END;
$$;
