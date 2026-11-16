-- FIN METHOD-R1 / FIN-04: explicit F01 current-balance field bindings.
-- The earlier FIN-PILOT-V1 F01 assets/liabilities refer to TOTAL balance
-- amounts and MUST NOT be used as current assets/current liabilities.
-- These rows define mapping candidates, NOT qualified metric contracts.
-- Automatic FIN-04 execution still requires server-approved source intake,
-- matching fact_origin, and explicitly QUALIFIED metric_contract rows.
INSERT INTO fin_fact.pilot_field_mapping
 (form_code,form_version,source_field,metric_code,metric_version,required_time_kind)
VALUES
 ('F01','FIN-LIQUIDITY-V1','currentAssets','CURRENT_ASSETS','1.0.0','AS_OF'),
 ('F01','FIN-LIQUIDITY-V1','currentLiabilities','CURRENT_LIABILITIES','1.0.0','AS_OF');
