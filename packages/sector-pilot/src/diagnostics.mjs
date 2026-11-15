/**
 * Deterministic, non-causal review hints over independently calculated sector
 * KPI outputs. Never recompute financial numbers or classify business health.
 * The caller must NOT treat local approval as server-side source authority.
 */
export const DIAGNOSTIC_METHOD_VERSION = 'SECTOR_DIAGNOSTIC_V1';
const NEGATIVE_CONTRIBUTION = Object.freeze({
  SKU_GROSS_PROFIT: ['NEGATIVE_SKU_GROSS_PROFIT', 'VERIFY_REVENUE_COGS_AND_RETURNS'],
  ENGAGEMENT_CONTRIBUTION: ['NEGATIVE_ENGAGEMENT_CONTRIBUTION', 'RECONCILE_CONTRACT_COSTS'],
  SERVICE_UNIT_MARGIN: ['NEGATIVE_SERVICE_UNIT_MARGIN', 'REVIEW_SERVICE_UNIT_ECONOMICS']
});
function check(condition, error) { if (!condition) throw new Error(error); }
function reference(result) {
  return {
    formCode: result.formCode, businessId: result.businessId,
    dimensionId: result.dimensionId, currency: result.currency,
    windowType: result.windowType, windowCode: result.windowCode,
    sourceRevisions: Object.freeze([...result.sourceRevisions])
  };
}
function finding(result, kind, ruleCode, actionCode, options = {}) {
  return Object.freeze({
    methodVersion: DIAGNOSTIC_METHOD_VERSION,
    methodQualification: 'PILOT_UNQUALIFIED',
    kind, ruleCode, actionCode,
    ...reference(result), ...options
  });
}
/** No tunable thresholds or unsupported causal explanations. */
export function diagnoseSectorOutput(result) {
  check(result && typeof result === 'object' && result.metrics &&
    typeof result.metrics === 'object' && Array.isArray(result.sourceRevisions) &&
    Array.isArray(result.missingPeriods) && Array.isArray(result.missingFields),
    'INVALID_SECTOR_DIAGNOSTIC_INPUT');
  if (result.status === 'INCOMPLETE') {
    return [finding(result, 'MISSING_EVIDENCE', 'SOURCE_COVERAGE_INCOMPLETE',
      'COMPLETE_SOURCE_COVERAGE', {
        missingPeriods: Object.freeze([...result.missingPeriods]),
        missingFields: Object.freeze([...result.missingFields]),
        evidence: Object.freeze([])
      })];
  }
  check(result.status === 'CALCULATED', 'UNKNOWN_SECTOR_RESULT_STATUS');
  const output = [];
  for (const [metricCode, metric] of Object.entries(result.metrics)) {
    check(metric && typeof metric === 'object', 'INVALID_SECTOR_DIAGNOSTIC_METRIC');
    if (metric.status === 'NOT_COMPUTABLE') {
      output.push(finding(result, 'NOT_COMPUTABLE',
        metric.reason === 'ZERO_DENOMINATOR' ? 'ZERO_DENOMINATOR' : 'METRIC_NOT_COMPUTABLE',
        'VERIFY_METRIC_INPUTS', {
          metricCode, metricReason: metric.reason ?? 'UNKNOWN',
          evidence: Object.freeze([])
        }));
      continue;
    }
    check(metric.status === 'CALCULATED' && typeof metric.value === 'string',
      'INVALID_CALCULATED_METRIC');
    const candidate = NEGATIVE_CONTRIBUTION[metricCode];
    // String comparison protects against Number rounding and overflow.
    if (candidate && /^-[0-9]+(?:\.[0-9]+)?$/.test(metric.value) &&
        /[1-9]/.test(metric.value)) {
      output.push(finding(result, 'OBSERVATION', candidate[0], candidate[1], {
        metricCode,
        evidence: Object.freeze([Object.freeze({
          metricCode, value: metric.value, unit: metric.unit ?? null
        })])
      }));
    }
  }
  return Object.freeze(output);
}
