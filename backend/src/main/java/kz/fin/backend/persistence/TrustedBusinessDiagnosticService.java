package kz.fin.backend.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kz.fin.backend.analytics.AnalysisContext;
import kz.fin.backend.analytics.AutoIndicatorEngine.*;
import kz.fin.backend.diagnostics.BusinessDiagnosticEngine;
import kz.fin.backend.diagnostics.DecisionPlaybookRegistry;
import kz.fin.backend.diagnostics.BusinessDiagnosticEngine.*;
import kz.fin.backend.domain.EconomicKernel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * Server-owned experiment: calculate diagnostic findings only from a sealed,
 * source-verified synthetic FactSet in PostgreSQL and persist an immutable
 * signed snapshot. Not an auth boundary or a qualified advisory service.
 *
 * DO NOT add a public endpoint before OIDC/ABAC, FormRegistry and independent
 * diagnostic-rule/industry qualification have been implemented.
 */
@Service
public class TrustedBusinessDiagnosticService {
    private static final String RULE_SET_CODE="BUSINESS_HEALTH_PILOT";
    private static final String RULE_SET_VERSION="1.0.0";
    private static final List<String> METRICS=List.of("REV_NET","COGS","CASH_END","AR_END");
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper=new ObjectMapper();

    public TrustedBusinessDiagnosticService(JdbcTemplate jdbc){
        this.jdbc=jdbc;
    }

    public enum SourceFreshness { CURRENT_SOURCE, STALE_SOURCE, UNKNOWN_POINTER }

    public record Receipt(UUID id,boolean reused,String inputFingerprint,String snapshotHash) {}
    public record StoredAssessment(UUID id,UUID organizationId,UUID factSetRevisionId,
                                   YearMonth month,SourceFreshness sourceFreshness,
                                   String inputFingerprint,String snapshotHash,
                                   JsonNode snapshot) {}

    private record Publication(UUID revision,UUID org,UUID entity,UUID scope,
                               EconomicKernel.ReportingBasis basis,AnalysisContext.DataSpace space) {}
    private record MetricRow(String metric,String metricVersion,String contractKind,
                             String unit,String currency,String qualification,
                             String sourceUnit,String sourceKind,LocalDate start,LocalDate end,
                             String valueState,BigDecimal value,String method,
                             String dimensions) {}

    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public Receipt assessAndPublish(UUID authorizedOrg,UUID publishedRevision,YearMonth asOf){
        Objects.requireNonNull(authorizedOrg);
        Objects.requireNonNull(publishedRevision);
        Objects.requireNonNull(asOf);
        Publication publication=published(authorizedOrg,publishedRevision);
        if(publication==null)
            throw new IllegalArgumentException("DIAGNOSTIC_FACT_SET_NOT_PUBLISHED_OR_FOREIGN");
        if(publication.space()!=AnalysisContext.DataSpace.SYNTHETIC)
            throw new IllegalArgumentException("UNQUALIFIED_DIAGNOSTIC_RULES_FOR_ACTUAL");

        List<MetricHistory> history=trustedHistory(publication,asOf);
        Assessment assessment=BusinessDiagnosticEngine.assess(asOf,history,BusinessDiagnosticEngine.PILOT_RULES);
        String definitionHash=definitionHash();
        JsonNode snapshot=buildSnapshot(assessment,publication);
        String snapshotJson=snapshot.toString();
        UUID proposedId=UUID.randomUUID();
        int inserted=jdbc.update("""
            INSERT INTO fin_diag.assessment_run(
                id,organization_id,fact_set_revision_id,evaluation_month,
                rule_set_code,rule_set_version,engine_version,definition_hash,
                input_fingerprint,snapshot,snapshot_hash)
            VALUES(?,?,?,?,?,?,?,?,?,?::jsonb,?)
            ON CONFLICT (organization_id,fact_set_revision_id,evaluation_month,
                rule_set_code,rule_set_version) DO NOTHING
            """,proposedId,publication.org(),publication.revision(),asOf.atDay(1),
                RULE_SET_CODE,RULE_SET_VERSION,BusinessDiagnosticEngine.METHOD_VERSION,
                definitionHash,"0".repeat(64),snapshotJson,"0".repeat(64));

        UUID runId=proposedId;
        if(inserted==0){
            List<Existing> existing=jdbc.query("""
                SELECT id,definition_hash,snapshot
                FROM fin_diag.assessment_run WHERE organization_id=? AND
                fact_set_revision_id=? AND evaluation_month=? AND
                rule_set_code=? AND rule_set_version=?
                """,(rs,i)->new Existing(rs.getObject("id",UUID.class),
                        rs.getString("definition_hash"),readJson(rs.getString("snapshot"))),
                    publication.org(),publication.revision(),asOf.atDay(1),
                    RULE_SET_CODE,RULE_SET_VERSION);
            if(existing.size()!=1)throw new IllegalStateException("MISSING_DIAGNOSTIC_RUN_AFTER_CONFLICT");
            if(!definitionHash.equals(existing.getFirst().definitionHash())
                    || !snapshot.equals(existing.getFirst().snapshot()))
                throw new IllegalArgumentException("DIAGNOSTIC_VERSION_OR_RESULT_DRIFT");
            runId=existing.getFirst().id();
        }

        String[] hashes=jdbc.queryForObject("""
            SELECT input_fingerprint,snapshot_hash
            FROM fin_diag.assessment_run WHERE organization_id=? AND id=?
            """,(rs,i)->new String[]{rs.getString(1),rs.getString(2)},
                authorizedOrg,runId);
        if(hashes==null)throw new IllegalStateException("UNPERSISTED_DIAGNOSTIC");
        return new Receipt(runId,inserted==0,hashes[0],hashes[1]);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public StoredAssessment loadPublished(UUID authorizedOrg,UUID runId){
        Objects.requireNonNull(authorizedOrg);
        Objects.requireNonNull(runId);
        List<StoredAssessment> found=jdbc.query("""
            SELECT d.id,d.organization_id,d.fact_set_revision_id,d.evaluation_month,
                   d.input_fingerprint,d.snapshot_hash,d.snapshot::text AS snapshot_json,
                   encode(sha256(convert_to(d.snapshot::text,'UTF8')),'hex') AS verified_hash,
                   c.fact_set_revision_id AS current_revision
            FROM fin_diag.assessment_run d
            JOIN fin_fact.fact_set_publication p
              ON p.fact_set_revision_id=d.fact_set_revision_id
             AND p.organization_id=d.organization_id
            LEFT JOIN fin_fact.current_fact_set c
              ON c.organization_id=p.organization_id
             AND c.economic_entity_id=p.economic_entity_id
             AND c.business_scope_id=p.business_scope_id
             AND c.reporting_basis=p.reporting_basis
             AND c.data_space=p.data_space
            WHERE d.organization_id=? AND d.id=?
            """,(rs,i)->{
                String hash=rs.getString("snapshot_hash");
                if(!hash.equals(rs.getString("verified_hash")))
                    throw new IllegalStateException("DIAGNOSTIC_SNAPSHOT_INTEGRITY_FAILURE");
                UUID factSet=rs.getObject("fact_set_revision_id",UUID.class);
                UUID current=rs.getObject("current_revision",UUID.class);
                SourceFreshness freshness=current==null?SourceFreshness.UNKNOWN_POINTER:
                        current.equals(factSet)?SourceFreshness.CURRENT_SOURCE:SourceFreshness.STALE_SOURCE;
                return new StoredAssessment(rs.getObject("id",UUID.class),
                        rs.getObject("organization_id",UUID.class),
                        factSet,YearMonth.from(rs.getObject("evaluation_month",LocalDate.class)),
                        freshness,rs.getString("input_fingerprint"),hash,
                        readJson(rs.getString("snapshot_json")));
            },authorizedOrg,runId);
        if(found.size()!=1)throw new IllegalArgumentException("DIAGNOSTIC_NOT_FOUND_OR_FORBIDDEN");
        return found.getFirst();
    }

    private record Existing(UUID id,String definitionHash,JsonNode snapshot) {}

    private Publication published(UUID org,UUID revision) {
        List<Publication> rows=jdbc.query("""
            SELECT fact_set_revision_id,organization_id,economic_entity_id,
                   business_scope_id,reporting_basis,data_space
            FROM fin_fact.fact_set_publication
            WHERE organization_id=? AND fact_set_revision_id=?
            """,(rs,i)->new Publication(rs.getObject(1,UUID.class),
                    rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),
                    rs.getObject(4,UUID.class),
                    EconomicKernel.ReportingBasis.valueOf(rs.getString(5)),
                    AnalysisContext.DataSpace.valueOf(rs.getString(6))),org,revision);
        return rows.isEmpty()?null:rows.getFirst();
    }

    private List<MetricHistory> trustedHistory(Publication p,YearMonth current){
        LocalDate from=current.minusMonths(12).atDay(1);
        LocalDate end=current.plusMonths(1).atDay(1);
        List<MetricRow> raw=jdbc.query("""
            SELECT a.metric_code,a.metric_version,
                   c.measure_kind AS contract_kind,c.unit_code AS contract_unit,
                   a.currency_code,c.qualification,
                   a.unit_code,a.time_kind,a.start_inclusive,a.end_exclusive,
                   a.value_state,a.numeric_value,a.estimate_method_ref,
                   a.dimensions::text AS dimensions
            FROM fin_fact.fact_selection s
            JOIN fin_fact.fact_assertion a
              ON a.id=s.fact_assertion_id AND a.organization_id=s.organization_id
            LEFT JOIN fin_calc.metric_contract c
              ON c.metric_code=a.metric_code AND c.metric_version=a.metric_version
            WHERE s.organization_id=? AND s.fact_set_revision_id=?
              AND a.metric_code IN ('REV_NET','COGS','CASH_END','AR_END')
              AND a.start_inclusive>=? AND a.start_inclusive<?
            ORDER BY a.metric_code,a.start_inclusive,a.id
            """,(rs,i)->readRow(rs),p.org(),p.revision(),from,end);

        Map<String,SeriesKey> keys=new TreeMap<>();
        Map<String,List<Observation>> grouped=new TreeMap<>();
        for(MetricRow row:raw){
            if(row.contractKind()==null || row.qualification()==null)
                throw new IllegalArgumentException("UNKNOWN_DIAGNOSTIC_METRIC_METHOD");
            MeasureKind kind=MeasureKind.valueOf(row.contractKind());
            MeasureKind expected=Set.of("REV_NET","COGS").contains(row.metric())
                    ? MeasureKind.FLOW : MeasureKind.STOCK;
            if(kind!=expected || !"MONEY".equals(row.unit())
                    || !"MONEY".equals(row.sourceUnit())
                    || row.currency()==null || !row.currency().matches("[A-Z]{3}"))
                throw new IllegalArgumentException("DIAGNOSTIC_METRIC_CONTRACT_MISMATCH");
            if(!row.dimensions().equals("{}"))
                throw new IllegalArgumentException("DIAGNOSTIC_REQUIRES_UNDIMENSIONED_TOTAL");
            if(row.sourceKind().equals("AS_OF")!= (kind==MeasureKind.STOCK))
                throw new IllegalArgumentException("DIAGNOSTIC_TIME_KIND_MISMATCH");
            if(kind==MeasureKind.STOCK && !row.start().equals(
                    YearMonth.from(row.start()).atEndOfMonth()))
                throw new IllegalArgumentException("DIAGNOSTIC_STOCK_MUST_BE_MONTH_END");

            SeriesKey key=new SeriesKey(p.org(),p.entity(),p.scope(),p.revision(),
                    row.metric(),row.metricVersion(),kind,"MONEY",row.currency(),
                    p.basis(),p.space(),new TreeMap<>());
            SeriesKey earlier=keys.putIfAbsent(row.metric(),key);
            if(earlier!=null && !earlier.equals(key))
                throw new IllegalArgumentException("DIAGNOSTIC_MIXED_METRIC_VERSION_OR_CURRENCY");
            EconomicKernel.TemporalReference time=new EconomicKernel.TemporalReference(
                    EconomicKernel.TimeKind.valueOf(row.sourceKind()),row.start(),row.end());
            EconomicKernel.EconomicValue value=new EconomicKernel.EconomicValue(
                    EconomicKernel.ValueState.valueOf(row.valueState()),row.value(),row.method());
            grouped.computeIfAbsent(row.metric(),ignore->new ArrayList<>())
                    .add(new Observation(key,time,value));
        }
        List<MetricHistory> histories=new ArrayList<>();
        for(var entry:grouped.entrySet())
            histories.add(new MetricHistory(keys.get(entry.getKey()),entry.getValue()));
        return histories;
    }

    private static MetricRow readRow(ResultSet rs)throws SQLException{
        return new MetricRow(rs.getString("metric_code"),rs.getString("metric_version"),
                rs.getString("contract_kind"),rs.getString("contract_unit"),
                rs.getString("currency_code"),rs.getString("qualification"),
                rs.getString("unit_code"),rs.getString("time_kind"),
                rs.getObject("start_inclusive",LocalDate.class),
                rs.getObject("end_exclusive",LocalDate.class),
                rs.getString("value_state"),rs.getBigDecimal("numeric_value"),
                rs.getString("estimate_method_ref"),rs.getString("dimensions"));
    }

    private String definitionHash(){
        try{
            MessageDigest md=MessageDigest.getInstance("SHA-256");
            put(md,RULE_SET_CODE);
            put(md,RULE_SET_VERSION);
            put(md,BusinessDiagnosticEngine.METHOD_VERSION);
            for(RuleDefinition rule:BusinessDiagnosticEngine.PILOT_RULES){
                put(md,rule.code().name());put(md,rule.version());
                put(md,rule.explanationKey());put(md,rule.playbookCode());
                for(String dependency:rule.requiredMetrics()) put(md,dependency);
                DecisionPlaybookRegistry.byCode(rule.playbookCode());
            }
            for(var playbook:DecisionPlaybookRegistry.PILOT_PLAYBOOKS){
                put(md,playbook.code());put(md,playbook.version());
                for(var step:playbook.actions()){
                    put(md,step.actionCode());put(md,step.translationKey());
                    put(md,step.verificationMetricCode());
                    put(md,Boolean.toString(step.requiresHumanApproval()));
                }
            }
            return HexFormat.of().formatHex(md.digest());
        }catch(NoSuchAlgorithmException e){
            throw new IllegalStateException("SHA256_UNAVAILABLE",e);
        }
    }

    private static void put(MessageDigest digest,String value){
        byte[] bytes=value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private JsonNode buildSnapshot(Assessment assessment,Publication publication){
        ObjectNode root=mapper.createObjectNode();
        root.put("methodVersion",assessment.methodVersion());
        root.put("ruleSetCode",RULE_SET_CODE);
        root.put("ruleSetVersion",RULE_SET_VERSION);
        root.put("evaluationMonth",assessment.evaluatedMonth().toString());
        root.put("organizationId",publication.org().toString());
        root.put("factSetRevisionId",publication.revision().toString());
        root.put("dataSpace",publication.space().name());

        ObjectNode trends=root.putObject("trends");
        assessment.trends().forEach((metric,t)->{
            ObjectNode node=trends.putObject(metric);
            node.put("unit",t.unitCode());
            if(t.currencyCode()!=null)node.put("currency",t.currencyCode());
            writeCalculation(node,"current",t.current());
            writeCalculation(node,"previousMonth",t.previousMonth());
            writeCalculation(node,"yearAgo",t.yearAgo());
            writeCalculation(node,"momAbsolute",t.momAbsolute());
            writeCalculation(node,"momPercent",t.momPercent());
            writeCalculation(node,"yoyAbsolute",t.yoyAbsolute());
            writeCalculation(node,"yoyPercent",t.yoyPercent());
            writeCalculation(node,"ttm",t.ttm());
            writeCalculation(node,"movingAverage3",t.movingAverage3());
        });

        ArrayNode findings=root.putArray("findings");
        for(Finding f:assessment.findings()){
            ObjectNode n=findings.addObject();
            n.put("ruleCode",f.ruleCode().name());
            n.put("ruleVersion",f.ruleVersion());
            n.put("kind",f.kind().name());
            n.put("explanationKey",f.explanationKey());
            n.put("playbookCode",f.playbookCode());
            ArrayNode missing=n.putArray("missingMetrics");
            f.missingMetrics().forEach(missing::add);
            ArrayNode evidence=n.putArray("evidence");
            for(Evidence e:f.evidence()){
                ObjectNode v=evidence.addObject();
                v.put("metricCode",e.metricCode());
                v.put("month",e.month().toString());
                v.put("value",e.value().toPlainString());
                v.put("unit",e.unitCode());
                if(e.currencyCode()!=null)v.put("currency",e.currencyCode());
            }
        }
        ArrayNode playbooks=root.putArray("proposedDecisionPlaybooks");
        SortedSet<String> relevant=new TreeSet<>();
        for(Finding finding:assessment.findings()){
            if(finding.kind()!=FindingKind.MISSING_EVIDENCE)
                relevant.add(finding.playbookCode());
        }
        for(String code:relevant){
            var playbook=DecisionPlaybookRegistry.byCode(code);
            ObjectNode value=playbooks.addObject();
            value.put("code",playbook.code());
            value.put("version",playbook.version());
            value.put("status","PROPOSED_NOT_EXECUTED");
            ArrayNode actions=value.putArray("actions");
            for(var step:playbook.actions()){
                ObjectNode a=actions.addObject();
                a.put("code",step.actionCode());
                a.put("translationKey",step.translationKey());
                a.put("verificationMetricCode",step.verificationMetricCode());
                a.put("requiresHumanApproval",step.requiresHumanApproval());
            }
        }
        return root;
    }

    private static void writeCalculation(ObjectNode into,String name,Calculation value){
        ObjectNode cell=into.putObject(name);
        cell.put("status",value.status().name());
        cell.put("methodVersion",value.methodVersion());
        if(value.value()!=null)cell.put("value",value.value().toPlainString());
        if(value.reason()!=null)cell.put("reason",value.reason());
    }

    private JsonNode readJson(String json){
        try{return mapper.readTree(json);}
        catch(com.fasterxml.jackson.core.JsonProcessingException e){
            throw new IllegalStateException("CORRUPT_DIAGNOSTIC_JSON",e);
        }
    }
}
