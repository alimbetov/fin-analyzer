-- F01 liquidity intake V1: reviewable source schema for FIN-04.
-- Existing FIN-PILOT-V1 submissions, source mappings and XLSX remain valid.
ALTER TABLE fin_intake.submission
    DROP CONSTRAINT IF EXISTS submission_form_version_check;

ALTER TABLE fin_intake.submission
    ADD CONSTRAINT ck_intake_approved_form_version
    CHECK (
        form_version='FIN-PILOT-V1'
        OR (form_code='F01' AND form_version='FIN-LIQUIDITY-V1')
    );

INSERT INTO fin_fact.pilot_field_mapping
    (form_code,form_version,source_field,metric_code,metric_version,required_time_kind)
VALUES
    ('F01','FIN-LIQUIDITY-V1','assets','ASSETS','1.0.0','AS_OF'),
    ('F01','FIN-LIQUIDITY-V1','liabilities','LIABILITIES','1.0.0','AS_OF'),
    ('F01','FIN-LIQUIDITY-V1','equity','EQUITY','1.0.0','AS_OF'),
    ('F01','FIN-LIQUIDITY-V1','cash','CASH_END','1.0.0','AS_OF'),
    ('F01','FIN-LIQUIDITY-V1','receivables','AR_END','1.0.0','AS_OF');
-- currentAssets and currentLiabilities are mapped by V20261116_05.
-- Qualification of CURRENT_ASSETS/CURRENT_LIABILITIES metric contracts
-- is a separate authority-controlled step; this migration does not grant it.
