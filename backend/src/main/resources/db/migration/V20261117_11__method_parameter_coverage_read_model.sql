-- FIN-MR-R2.4.A: evidence-aware, read-only coverage ledger.
-- Existing 259 candidate keys are not typed/approved calculation parameters.
-- No method S2, configuration activation, execution gate or historical row is changed.
CREATE VIEW fin_calc.method_parameter_coverage_view AS
SELECT
    f.method_code,
    COALESCE(c.candidate_count, 0)::bigint AS candidate_parameter_count,
    COALESCE(c.unreviewed_count, 0)::bigint AS unreviewed_parameter_count,
    COALESCE(k.contract_count, 0)::bigint AS parameter_contract_count,
    COALESCE(k.typed_contract_count, 0)::bigint AS typed_contract_count,
    COALESCE(k.blocked_contract_count, 0)::bigint AS not_executable_contract_count,
    CASE
      WHEN COALESCE(k.typed_contract_count, 0) > 0
        THEN 'TYPED_CONTRACT_PRESENT_UNQUALIFIED'
      ELSE 'NO_TYPED_EXECUTION_CONTRACT'
    END::text AS coverage_state,
    false AS method_s2_qualified_by_coverage,
    false AS execution_permitted_by_coverage
FROM fin_calc.method_family f
LEFT JOIN LATERAL (
    SELECT count(*) AS candidate_count,
           count(*) FILTER (WHERE p.review_state='PROPOSED_UNREVIEWED') AS unreviewed_count
    FROM fin_calc.method_parameter_candidate p
    WHERE p.method_code=f.method_code
) c ON true
LEFT JOIN LATERAL (
    SELECT count(*) AS contract_count,
           count(*) FILTER (WHERE x.validation_profile IN
              ('CON01_PARETO_FIXED_V1','CON02_ABC_TYPED_V2')) AS typed_contract_count,
           count(*) FILTER (WHERE x.validation_profile='NOT_EXECUTABLE') AS blocked_contract_count
    FROM fin_calc.method_parameter_contract x
    WHERE x.method_code=f.method_code
) k ON true;

COMMENT ON VIEW fin_calc.method_parameter_coverage_view IS
 'R2.4.A informational audit; 259 unreviewed candidates never imply typed or executable parameters; coverage is not S2 or execution authority';

-- Fail closed on divergent historic seed; do NOT manufacture or approve parameters.
DO $audit$
DECLARE families bigint;
DECLARE candidates bigint;
DECLARE distinct_candidates bigint;
DECLARE unreviewed bigint;
DECLARE negative_rows bigint;
BEGIN
    SELECT count(*),
           COALESCE(sum(candidate_parameter_count),0),
           count(*) FILTER (WHERE candidate_parameter_count>0),
           COALESCE(sum(unreviewed_parameter_count),0),
           count(*) FILTER (
              WHERE execution_permitted_by_coverage OR method_s2_qualified_by_coverage
           )
    INTO families,candidates,distinct_candidates,unreviewed,negative_rows
    FROM fin_calc.method_parameter_coverage_view;
    IF families<>60 OR candidates<>259 OR distinct_candidates<>60 OR
       unreviewed<>259 OR negative_rows<>0 THEN
       RAISE EXCEPTION 'FIN_MR_R24_COVERAGE_SEED_DRIFT: families %, candidates %, populated %, unreviewed %, auth %',
           families,candidates,distinct_candidates,unreviewed,negative_rows;
    END IF;
END;
$audit$;
