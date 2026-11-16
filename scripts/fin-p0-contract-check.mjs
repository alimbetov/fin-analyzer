/**
 * FIN P0 acceptance registry *structural* proof.
 * No execution of finance methods, CI jobs, independent S2 or E2E is implied.
 *
 * node --test scripts/fin-p0-contract.test.mjs
 * node scripts/fin-p0-contract-check.mjs --evidence
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = fileURLToPath(new URL("../", import.meta.url));
const STAGES = Array.from({ length: 7 }, (_, i) => "P0." + (i + 1));
const STATES = new Set([
  "SPECIFIED", "CANDIDATE_UNREVIEWED", "CODED_UNVERIFIED",
  "PARTIAL", "PILOT_ONLY", "ENGINEERING_VERIFIED", "ACCEPTED"
]);
const FINANCE = [
  "F01", "F02", "F03", "DF-CSH-01", "DF-CSH-02", "DF-CSH-03",
  "DF-CSH-04", "DF-CSH-05", "DF-CSH-06"
];
const INDUSTRY = [
  "SF-G01", "SF-G02", "SF-G03", "SF-M01", "SF-M02",
  "SF-S01", "SF-C01", "SF-C02", "SF-C03"
];
function check(condition, reason) {
  if (!condition) throw new Error("FIN_P0_CONTRACT_" + reason);
}
function unique(items, reason) {
  check(new Set(items).size === items.length, reason);
}
function lines(source, expectedHeader) {
  const all = source.trimEnd().split(/\r?\n/);
  check(all.shift() === expectedHeader, "HEADER_DRIFT");
  return all.map(line => line.split("\t"));
}
function read(relativePath) {
  return fs.readFileSync(path.join(ROOT, relativePath), "utf8");
}
export function loadP0Sources() {
  return {
    fields: read("docs/CAT-A2-PILOT-FIELD-REVIEW.tsv"),
    forms: read("backend/src/main/resources/fin/form-compatibility.tsv"),
    sectors: read("backend/src/main/resources/fin/sector-business-model-packs-v2.tsv"),
    methods: JSON.parse(read("backend/src/main/resources/fin/methodology/methodology-catalog-r1.json")),
    workflow: read(".github/workflows/ci.yml")
  };
}
export function verifyP0Contract(registry, source, sourceExists = relative => fs.existsSync(path.join(ROOT, relative))) {
  check(registry && registry.schemaVersion === "FIN_P0_ACCEPTANCE_V1", "SCHEMA");
  check(registry.releaseDecision === "HOLD", "RELEASE_MUST_HOLD");
  check(registry.evidencePolicy === "ONE_PUSH_SHA_PLUS_INDEPENDENT_S2_AND_MANUAL_UAT", "EVIDENCE_POLICY");
  const metric = registry.metrics;
  check(metric && Object.values(metric).every(x => Number.isInteger(x) && x > 0), "COUNTS_MALFORMED");

  const fields = lines(source.fields,
    "formCode\tfieldCode\tfieldType\tcandidateSemanticRole\tunitPolicy\trequirednessReview\tqualificationStatus\treviewerQuestion");
  check(fields.every(f => f.length === 8), "FIELD_ROW_WIDTH");
  const fieldKeys = fields.map(f => f[0] + "/" + f[1]);
  unique(fieldKeys, "FIELD_DUPLICATE");
  check(fields.length === metric.pilotReviewFields && fields.length === 92, "PILOT_92_DRIFT");
  const financeFields = fields.filter(f => FINANCE.includes(f[0]));
  const industryFields = fields.filter(f => INDUSTRY.includes(f[0]));
  check(financeFields.length === metric.financeFirstWaveFields && financeFields.length === 43,
    "FIRST_WAVE_NOT_43");
  check(industryFields.length === metric.industrySecondWaveFields && industryFields.length === 49,
    "SECOND_WAVE_NOT_49");
  check(financeFields.length + industryFields.length === fields.length, "UNCLASSIFIED_PILOT_FIELD");
  check(fields.every(f => f[5] === "PENDING_METHOD_REVIEW" && f[6] === "CANDIDATE_NOT_APPROVED"),
    "FIELD_WITHOUT_INDEPENDENT_REVIEW_MARKED_APPROVED");
  check(Array.isArray(registry.scope?.financeForms) &&
    JSON.stringify(registry.scope.financeForms) === JSON.stringify(FINANCE), "FINANCE_FORM_SCOPE");
  check(Array.isArray(registry.scope?.industryForms) &&
    JSON.stringify(registry.scope.industryForms) === JSON.stringify(INDUSTRY), "INDUSTRY_FORM_SCOPE");

  const formRows = lines(source.forms,
    "formCode\tcatalog\tformKind\taggregateRoot\tgrain\tfieldContract\tfieldEvidence\tqualificationStatus");
  const formCodes = formRows.map(x => x[0]);
  unique(formCodes, "FORM_DUPLICATE");
  check(formRows.every(r => r.length === 8) &&
    formRows.length === metric.registeredFormCodes && formRows.length === 166, "FORM_166_DRIFT");
  const known = new Set(formCodes);
  for (const f of [...FINANCE, ...INDUSTRY]) check(known.has(f), "PILOT_FORM_UNKNOWN_" + f);
  const sectorRows = lines(source.sectors,
    "section\tbusinessModelVariants\tsectorFormCodes\tbaseDataFormCodes\toperationalGrain\tanalysisFamilies\trequiredExtensionSheets\tmethodologyRisk\tqualificationStatus");
  check(sectorRows.map(r => r[0]).join("") === "ABCDEFGHIJKLMNOPQRSTU" &&
    sectorRows.length === metric.sectorSections && sectorRows.length === 21, "SECTOR_21_DRIFT");
  const variants = sectorRows.flatMap(r => r[1].split(";"));
  const sectorForms = sectorRows.flatMap(r => r[2].split(";"));
  unique(variants, "VARIANT_DUPLICATE");
  unique(sectorForms, "SECTOR_FORM_DUPLICATE");
  check(variants.length === metric.businessModelVariants && variants.length === 64,
    "MODEL_64_DRIFT");
  check(sectorForms.length === 63 && sectorForms.every(f => known.has(f)),
    "SECTOR_FORMS_63_INVALID");

  const catalog = source.methods;
  check(catalog && catalog.releaseGate === "HOLD" && Array.isArray(catalog.methods),
    "METHOD_CATALOG_RELEASE");
  check(catalog.methods.length === metric.registeredMethodFamilies &&
    catalog.methods.length === 60, "METHOD_60_DRIFT");
  unique(catalog.methods.map(m => m.code), "METHOD_FAMILY_DUPLICATE");
  check(catalog.methods.every(m => m.releaseQualified === false && m.autoRunAllowed === false),
    "UNREVIEWED_METHOD_PROMOTED");

  const mandatoryWorkflow = [
    "  verify:", "  desktop-macos:", "  backend-verification:", "  acceptance-gate:",
    "needs: [verify, backend-verification, desktop-macos]",
    "mvn -B -ntp -f backend/pom.xml verify",
    "npm run test:e2e -- --project=chromium"
  ];
  for (const item of mandatoryWorkflow) check(source.workflow.includes(item),
    "WORKFLOW_MISSING_" + item.trim().replaceAll(" ", "_"));

  check(Array.isArray(registry.stages) && registry.stages.length === 7, "STAGE_COUNT");
  check(JSON.stringify(registry.stages.map(s => s.id)) === JSON.stringify(STAGES), "STAGE_ORDER");
  for (const [index, stage] of registry.stages.entries()) {
    check(typeof stage.title === "string" && stage.title.length >= 8, "STAGE_TITLE");
    check(STATES.has(stage.status), "STAGE_STATUS_" + stage.id);
    check(Array.isArray(stage.dependsOn), "STAGE_DEPENDENCIES");
    unique(stage.dependsOn, "STAGE_DUPLICATE_DEPENDENCY");
    check(stage.dependsOn.every(dep => STAGES.slice(0, index).includes(dep)),
      "STAGE_INVALID_DEPENDENCY_" + stage.id);
    for (const member of ["acceptance", "negativeTests", "sourceRefs"]) {
      check(Array.isArray(stage[member]) && stage[member].length > 0, "STAGE_" + member);
    }
    check(stage.negativeTests.length >= 3, "STAGE_NEGATIVES_TOO_FEW");
    for (const file of stage.sourceRefs) {
      check(typeof file === "string" && !path.isAbsolute(file) &&
        !file.split("/").includes("..") && sourceExists(file), "STAGE_SOURCE_MISSING_" + file);
    }
    if (stage.status === "ACCEPTED") {
      const proof = stage.evidence;
      check(proof && /^[a-f0-9]{40}$/.test(proof.sha ?? "") &&
        /^https:\/\/github\.com\/[^/]+\/[^/]+\/actions\/runs\/\d+$/.test(proof.workflowUrl ?? "") &&
        proof.workflowSha === proof.sha &&
        ["verify", "backend-verification", "desktop-macos", "acceptance-gate"]
          .every(job => proof.jobs?.[job] === "success"), "STAGE_ACCEPTED_WITHOUT_GREEN_EVIDENCE");
      if (stage.id !== "P0.1") {
        check(proof.reviewDecisionId && proof.manualUatId &&
          proof.methodS2Approved === true, "STAGE_ACCEPTED_WITHOUT_INDEPENDENT_REVIEW");
      }
    } else check(stage.evidence === null, "UNACCEPTED_STAGE_HAS_FAKE_PROOF");
  }
  check(registry.stages.find(s => s.id === "P0.7")?.dependsOn.includes("P0.4"),
    "CORE_MISSING_EXECUTOR_DEPENDENCY");
  check(!registry.stages.find(s => s.id === "P0.7")?.dependsOn.includes("P0.5") &&
    !registry.stages.find(s => s.id === "P0.7")?.dependsOn.includes("P0.6"),
    "CORE_INCORRECTLY_BLOCKED_BY_OPTIONAL_METHOD");
  check(Array.isArray(registry.acceptanceMilestones) &&
    registry.acceptanceMilestones.length === 7, "MILESTONES");
  return Object.freeze({
    schemaVersion: "FIN_P0_STATIC_PROOF_V1",
    result: "STRUCTURAL_CONTRACT_ONLY_NOT_CI_GREEN_NOT_S2",
    registeredForms: formRows.length,
    sectorSections: sectorRows.length,
    businessModelVariants: variants.length,
    methodFamilies: catalog.methods.length,
    candidateFields: fields.length,
    financeFields: financeFields.length,
    industryFields: industryFields.length,
    stages: registry.stages.map(({id,status}) => ({id,status})),
    releaseDecision: registry.releaseDecision
  });
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const registry = JSON.parse(read("docs/FIN-P0-ACCEPTANCE-MATRIX-V1.json"));
  const report = verifyP0Contract(registry, loadP0Sources());
  if (process.argv.includes("--evidence")) {
    fs.mkdirSync(path.join(ROOT, "test-results"), { recursive:true });
    fs.writeFileSync(path.join(ROOT, "test-results/fin-p0-static-contract.json"),
      JSON.stringify(report, null, 2) + "\n");
  }
  process.stdout.write(JSON.stringify(report, null, 2) + "\n");
}
