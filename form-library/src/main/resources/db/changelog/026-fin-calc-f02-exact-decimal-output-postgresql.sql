--liquibase formatted sql
--changeset fin-calc:026a dbms:postgresql splitStatements:false
-- CALC-SQL-01: exact eight-digit ratio even with >16 integer digits.
-- PostgreSQL numeric '/' can drop fractional digits at large magnitudes.
-- 024/025 are immutable historical migrations, not edited.
CREATE OR REPLACE FUNCTION fin_calc.f02_ratio_half_even(
    p_numerator NUMERIC,p_denominator NUMERIC)
RETURNS NUMERIC LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE AS $$
DECLARE
  numerator_scaled NUMERIC;
  denominator_cents NUMERIC;
  quotient NUMERIC;
  remainder_cents NUMERIC;
  whole NUMERIC;
  fractional NUMERIC;
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
  whole:=div(quotient,100000000);
  fractional:=mod(quotient,100000000)*0.00000001::numeric;
  RETURN sign(p_numerator)*(whole+fractional);
END;
$$;
