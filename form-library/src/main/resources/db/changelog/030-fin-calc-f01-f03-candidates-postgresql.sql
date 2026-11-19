--liquibase formatted sql
--changeset fin-calc:030a dbms:postgresql splitStatements:false
-- Unqualified F01 reconciliation HYPOTHESIS, not an accounting approval.
-- F01 source fields: assets, liabilities, equity, asOf (DATE).
-- Returns a SIGNED residual only, never a financially approved PASS/FAIL.
CREATE FUNCTION fin_calc.f01_candidate_balance_residual(
    p_asof DATE,p_assets NUMERIC,p_liabilities NUMERIC,p_equity NUMERIC)
RETURNS NUMERIC LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE AS $$
BEGIN
  IF p_asof IS NULL OR p_assets IS NULL OR p_liabilities IS NULL
      OR p_equity IS NULL OR scale(p_assets)>12
      OR scale(p_liabilities)>12 OR scale(p_equity)>12
      OR length(replace(replace(abs(p_assets)::text,'.',''),'-',''))>38
      OR length(replace(replace(abs(p_liabilities)::text,'.',''),'-',''))>38
      OR length(replace(replace(abs(p_equity)::text,'.',''),'-',''))>38 THEN
    RAISE EXCEPTION 'CALC_F01_CANDIDATE_INPUT_INVALID' USING ERRCODE='22003';
  END IF;
  RETURN p_assets-p_liabilities-p_equity;
END;
$$;

--changeset fin-calc:030b dbms:postgresql splitStatements:false
-- Unqualified F03 cash roll-forward HYPOTHESIS, signed CFO/CFI/CFF.
-- A nonzero residual must not be automatically interpreted as a financial
-- error before F03 economic semantics, FX and cash definitions are approved.
CREATE FUNCTION fin_calc.f03_candidate_cash_residual(
    p_start DATE,p_end_exclusive DATE,
    p_opening NUMERIC,p_cfo NUMERIC,p_cfi NUMERIC,p_cff NUMERIC,p_closing NUMERIC)
RETURNS NUMERIC LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE AS $$
BEGIN
  IF p_start IS NULL OR p_end_exclusive IS NULL OR p_start>=p_end_exclusive
     OR p_opening IS NULL OR p_cfo IS NULL OR p_cfi IS NULL
     OR p_cff IS NULL OR p_closing IS NULL
     OR scale(p_opening)>12 OR scale(p_cfo)>12
     OR scale(p_cfi)>12 OR scale(p_cff)>12 OR scale(p_closing)>12 THEN
    RAISE EXCEPTION 'CALC_F03_CANDIDATE_INPUT_INVALID' USING ERRCODE='22003';
  END IF;
  RETURN p_opening+p_cfo+p_cfi+p_cff-p_closing;
END;
$$;
