import { evaluateSectorForms, FORM_FIELDS } from './index.mjs';
import { diagnoseSectorOutput } from './diagnostics.mjs';

/** Smoke uses synthetic local snapshots only; not a credit, tax or audit opinion. */
const isMoneyLike = value => typeof value === 'string' && /^-?\d+\.\d{2}$/.test(value);
function deltaTwo(before, after) {
  if (!isMoneyLike(before) || !isMoneyLike(after)) return null;
  const cents = s => {
    const negative = s[0] === '-';
    const [whole, fractional] = (negative ? s.slice(1) : s).split('.');
    const amount = BigInt(whole) * 100n + BigInt(fractional);
    return negative ? -amount : amount;
  };
  const difference = cents(after) - cents(before);
  const abs = difference < 0n ? -difference : difference;
  return (difference < 0n ? '-' : '') + String(abs / 100n) + '.' +
    String(abs % 100n).padStart(2, '0');
}
function month(row) {
  const output = evaluateSectorForms([row]).find(item => item.windowType === 'MONTH');
  if (!output) throw new Error('SMOKE_MONTH_MISSING');
  return output;
}

/** Explicit counterfactual arithmetic, not a projection or promise. */
export function simulateSectorWhatIf(row, overrides) {
  if (!row || !FORM_FIELDS[row.formCode] ||
      !overrides || typeof overrides !== 'object' || Array.isArray(overrides) ||
      Object.keys(overrides).length === 0 ||
      Object.keys(overrides).some(key => !FORM_FIELDS[row.formCode].includes(key))) {
    throw new Error('INVALID_WHAT_IF_OVERRIDES');
  }
  const original = month(row);
  if (original.status !== 'CALCULATED')
    throw new Error('WHAT_IF_REQUIRES_COMPLETE_BASELINE');
  // Never publish this hypothetical input as an approved revision. The engine
  // uses approved=true solely to reuse its pure numeric evaluator in isolation.
  const hypothetical = month({...row, values: {...row.values, ...overrides}});
  if (hypothetical.status !== 'CALCULATED')
    throw new Error('WHAT_IF_REQUIRES_COMPLETE_HYPOTHETICAL');
  const impacts = {};
  for (const [code, actual] of Object.entries(original.metrics)) {
    const proposed = hypothetical.metrics[code];
    if (actual.status === 'CALCULATED' && proposed?.status === 'CALCULATED' &&
        actual.unit === proposed.unit) {
      impacts[code] = {
        status: 'SIMULATED_ONLY',
        before: actual.value,
        after: proposed.value,
        delta: deltaTwo(actual.value, proposed.value),
        unit: actual.unit
      };
    } else {
      impacts[code] = { status: 'NOT_COMPUTABLE', reason: 'NONCOMPARABLE_METRIC' };
    }
  }
  return Object.freeze({
    mode: 'SYNTHETIC_ONLY',
    status: 'ARITHMETIC_WHAT_IF_NOT_FORECAST',
    formCode: row.formCode,
    sourceRevision: row.revisionId,
    assumptions: Object.freeze({...overrides}),
    impacts: Object.freeze(impacts)
  });
}

/** Always evaluates five question gates, explicitly blocking unsupported claims. */
export function fiveQuestionSmoke(row, overrides) {
  const base = month(row);
  const findings = diagnoseSectorOutput(base);
  const actionable = findings.filter(f => f.kind === 'OBSERVATION');
  const scenario = base.status === 'CALCULATED' && overrides
    ? simulateSectorWhatIf(row, overrides) : null;
  return Object.freeze({
    formCode: row.formCode, sourceRevision: row.revisionId,
    methodology: 'PILOT_UNQUALIFIED', evidenceSpace: 'SYNTHETIC',
    questions: {
      what: {status: base.status === 'CALCULATED' ? 'CALCULATED' : 'BLOCKED',
        metrics: base.metrics},
      why: {status: 'HYPOTHESIS_ONLY', reason: 'CAUSAL_ATTRIBUTION_NOT_ESTABLISHED',
        candidateRules: actionable.map(f => f.ruleCode)},
      seriousness: {status: 'NOT_ASSESSED', reason: 'NO_QUALIFIED_MATERIALITY_POLICY'},
      action: {status: actionable.length ? 'REVIEW_REQUIRED' : 'NO_QUALIFIED_TRIGGER',
        proposedActions: actionable.map(f => f.actionCode), humanApprovalRequired: true},
      effect: {status: scenario ? 'SIMULATED_ONLY' : 'BLOCKED',
        scenario, reason: scenario ? 'CONDITIONAL_ARITHMETIC_ONLY' : 'NO_APPROVED_ASSUMPTIONS'}
    },
    findings
  });
}
