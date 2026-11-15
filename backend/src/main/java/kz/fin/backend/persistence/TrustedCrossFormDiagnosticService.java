package kz.fin.backend.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kz.fin.backend.analytics.AnalysisContext;
import kz.fin.backend.diagnostics.CrossFormDiagnosticEngine;
import kz.fin.backend.diagnostics.CrossFormEvidencePlanner;
import kz.fin.backend.diagnostics.CrossFormDiagnosticEngine.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * FIN R4.5: same-tenant, same-published-revision multi-form diagnostic orchestration.
 *
 * NO EXTERNAL API. "authorizedOrg" must eventually come from verified OIDC/ABAC.
 * Only SYNTHETIC is allowed while methodology and source intake await S2.
 *
 * Every source group that contributes to a number must have an immutable,
 * independently reviewed intake digest, exact mapping, control totals and
 * complete selected FactSet population. An unknown form cannot contribute.
 */
@Service
public class TrustedCrossFormDiagnosticService {
    private static final String RULE_SET_CODE="CROSS_FORM_PILOT";
    private static final String RULE_SET_VERSION="1.0.0";
    private static final Set<String> STOCK_METRICS=Set.of(
            "ASSETS","LIABILITIES","EQUITY","CASH_END","AR_END");
    private static final Set<String> SUPPORTED_METRICS;
    static {
        Set<String> all=new TreeSet<>();
        CrossFormDiagnosticEngine.REQUIRED_KEYS.forEach(k->all.add(k.code()));
        SUPPORTED_METRICS=Set.copyOf(all);
    }
    private final JdbcTemplate jdbc;
    private final TrustedFinancialPopulationService population;
    private final ObjectMapper mapper=new ObjectMapper();

    public TrustedCrossFormDiagnosticService(JdbcTemplate jdbc,TrustedFinancialPopulationService population){
        this.jdbc=jdbc;this.population=population;
    }
    private record Publication(UUID revision,UUID org,UUID entity,UUID businessScope,
                               String basis,AnalysisContext.DataSpace dataSpace) {}
    private record FactRow(UUID factId,UUID sourceRevision,String metric,String metricVersion,
                           String timeKind,LocalDate start,LocalDate end,
                           String unit,String currency,String valueState,BigDecimal amount,
                           String dimensions,String formCode,String formVersion,String rowId,
                           String field,String mappingKind,String sourceDocumentHash,
                           String expectedDocumentHash) {}
    private record Group(UUID source,String metric,LocalDate start,LocalDate end) {}
    private record Existing(UUID id,String hash,JsonNode snapshot) {}
    public record Receipt(UUID id,boolean reused,String inputFingerprint,String snapshotHash) {}
    public enum Freshness { CURRENT_SOURCE, STALE_SOURCE, UNKNOWN_POINTER }
    public record Stored(UUID id,UUID organizationId,UUID factSetRevisionId,YearMonth month,
                         Freshness freshness,String inputFingerprint,String snapshotHash,JsonNode snapshot) {}

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Report evaluate(UUID authorizedOrg,UUID publishedRevision,YearMonth month) {
        return assessInternal(authorizedOrg,publishedRevision,month);
    }
    private Report assessInternal(UUID org,UUID revision,YearMonth month){
        Objects.requireNonNull(org);Objects.requireNonNull(revision);Objects.requireNonNull(month);
        List<Publication> publications=jdbc.query("""
            SELECT fact_set_revision_id,organization_id,economic_entity_id,
                   business_scope_id,reporting_basis,data_space
            FROM fin_fact.fact_set_publication
            WHERE organization_id=? AND fact_set_revision_id=?
            """,(rs,i)->new Publication(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                rs.getObject(3,UUID.class),rs.getObject(4,UUID.class),
                rs.getString(5),AnalysisContext.DataSpace.valueOf(rs.getString(6))),org,revision);
        if(publications.size()!=1)
            throw new IllegalArgumentException("CROSS_FORM_FACT_SET_NOT_PUBLISHED_OR_FOREIGN");
        Publication pub=publications.getFirst();
        if(pub.dataSpace()!=AnalysisContext.DataSpace.SYNTHETIC)
            throw new IllegalArgumentException("CROSS_FORM_ACTUAL_NOT_QUALIFIED");

        // Fixed metric whitelist is also enforced in memory; raw SQL is server-owned.
        List<FactRow> raw=jdbc.query("""
            SELECT a.id,a.source_revision_id,a.metric_code,a.metric_version,
                   a.time_kind,a.start_inclusive,a.end_exclusive,a.unit_code,
                   a.currency_code,a.value_state,a.numeric_value,
                   a.dimensions::text AS dimensions,
                   sr.form_code,sr.form_version,o.source_row_id,o.source_field,
                   m.required_time_kind,i.source_document_hash,c.source_document_hash
            FROM fin_fact.fact_selection selection
            JOIN fin_fact.fact_assertion a
              ON a.id=selection.fact_assertion_id
             AND a.organization_id=selection.organization_id
            LEFT JOIN fin_fact.fact_origin o
              ON o.fact_assertion_id=a.id AND o.organization_id=a.organization_id
            LEFT JOIN fin_fact.approved_source_revision sr
              ON sr.organization_id=a.organization_id
             AND sr.source_revision_id=a.source_revision_id
            LEFT JOIN fin_fact.pilot_field_mapping m
              ON m.form_code=sr.form_code AND m.form_version=sr.form_version
             AND m.source_field=o.source_field AND m.metric_code=a.metric_code
             AND m.metric_version=a.metric_version
            LEFT JOIN fin_fact.approved_source_intake i
              ON i.organization_id=a.organization_id
             AND i.source_revision_id=a.source_revision_id
            LEFT JOIN fin_fact.source_population_control c
              ON c.organization_id=a.organization_id
             AND c.source_revision_id=a.source_revision_id
             AND c.metric_code=a.metric_code
             AND c.time_start=a.start_inclusive AND c.time_end=a.end_exclusive
            WHERE selection.organization_id=? AND selection.fact_set_revision_id=?
              AND a.metric_code IN ('ASSETS','LIABILITIES','EQUITY','CASH_END',
                                    'AR_END','REV_NET','COGS','OPEX','CFO','CFI',
                                    'CFF','CASH_IN','CASH_OUT')
              AND a.start_inclusive>=? AND a.start_inclusive<?
            ORDER BY a.metric_code,a.start_inclusive,a.source_revision_id,a.id
            """,(rs,i)->new FactRow(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                rs.getString(3),rs.getString(4),rs.getString(5),
                rs.getObject(6,LocalDate.class),rs.getObject(7,LocalDate.class),
                rs.getString(8),rs.getString(9),rs.getString(10),rs.getBigDecimal(11),
                rs.getString(12),rs.getString(13),rs.getString(14),rs.getString(15),
                rs.getString(16),rs.getString(17),rs.getString(18),rs.getString(19)),
                org,revision,month.minusMonths(1).atDay(1),month.plusMonths(1).atDay(1));

        Map<MetricKey,List<FactRow>> groups=new HashMap<>();
        SortedSet<Group> validatedGroups=new TreeSet<>(
                Comparator.comparing((Group g)->g.source().toString())
                    .thenComparing(Group::metric).thenComparing(Group::start).thenComparing(Group::end));
        Set<String> currencies=new HashSet<>();
        for(FactRow r:raw){
            if(!SUPPORTED_METRICS.contains(r.metric()))continue;
            if(r.sourceRevision()==null || r.formCode()==null || r.formVersion()==null
                    || r.rowId()==null || r.field()==null || r.mappingKind()==null
                    || r.sourceDocumentHash()==null || r.expectedDocumentHash()==null
                    || !r.sourceDocumentHash().equals(r.expectedDocumentHash()))
                throw new IllegalArgumentException("CROSS_FORM_UNVERIFIED_SOURCE_OR_CONTROL");
            if(!r.formVersion().equals("FIN-PILOT-V1") || !r.metricVersion().equals("1.0.0")
                    || !r.unit().equals("MONEY") || !r.mappingKind().equals(r.timeKind())
                    || !Set.of("PRESENT","EXPLICIT_ZERO").contains(r.valueState()) || r.amount()==null)
                throw new IllegalArgumentException("CROSS_FORM_UNQUALIFIED_METRIC_SEMANTICS");
            if(r.currency()==null || !r.currency().matches("[A-Z]{3}"))
                throw new IllegalArgumentException("CROSS_FORM_INVALID_CURRENCY");
            currencies.add(r.currency());
            boolean stock=STOCK_METRICS.contains(r.metric());
            if(!r.timeKind().equals(stock?"AS_OF":"INTERVAL"))
                throw new IllegalArgumentException("CROSS_FORM_STOCK_FLOW_MISMATCH");
            YearMonth m=YearMonth.from(r.start());
            if(stock){
                if(!r.start().equals(m.atEndOfMonth()) || !r.end().equals(r.start().plusDays(1)))
                    throw new IllegalArgumentException("CROSS_FORM_STOCK_NOT_MONTH_END");
            }else if(!r.start().equals(m.atDay(1))
                    || !r.end().equals(m.plusMonths(1).atDay(1)))
                throw new IllegalArgumentException("CROSS_FORM_PARTIAL_FLOW_PERIOD");
            // Reject accidental detail vs consolidated-source double counting.
            if(r.formCode().startsWith("DF-CSH-")){
                JsonNode dimensions=readJson(r.dimensions());
                if(dimensions.size()!=1 ||
                        !r.rowId().equals(dimensions.path("sourceRow").asText()))
                    throw new IllegalArgumentException("CROSS_FORM_CASH_DETAIL_DIMENSION_MISMATCH");
            }else if(!r.dimensions().equals("{}"))
                throw new IllegalArgumentException("CROSS_FORM_UNEXPECTED_DIMENSIONS");

            // Previous-period flows are not needed, but are still validated if found.
            TimeRole role=stock?(m.equals(month)?TimeRole.CURRENT_STOCK:TimeRole.PRIOR_STOCK)
                    :TimeRole.CURRENT_FLOW;
            MetricKey key=new MetricKey(r.metric(),role);
            if(!CrossFormDiagnosticEngine.REQUIRED_KEYS.contains(key)
                    || !stock && !m.equals(month))continue;
            if(stock && !(m.equals(month)||m.equals(month.minusMonths(1))))
                continue;
            validatedGroups.add(new Group(r.sourceRevision(),r.metric(),r.start(),r.end()));
            groups.computeIfAbsent(key,ignore->new ArrayList<>()).add(r);
        }
        if(currencies.size()>1)throw new IllegalArgumentException("CROSS_FORM_MIXED_CURRENCIES");
        if(!validatedGroups.isEmpty()){
            List<TrustedFinancialPopulationService.RequiredGroup> required=validatedGroups.stream()
                    .map(g->new TrustedFinancialPopulationService.RequiredGroup(
                            g.source(),g.metric(),g.start(),g.end())).toList();
            population.requireComplete(org,revision,required);
        }
        List<Evidence> evidences=new ArrayList<>();
        for(var group:groups.entrySet()){
            MetricKey key=group.getKey();
            List<FactRow> rows=group.getValue();
            if(rows.stream().map(FactRow::sourceRevision).distinct().count()!=1)
                throw new IllegalArgumentException("CROSS_FORM_AMBIGUOUS_SOURCE_REVISIONS");
            if(!Set.of("CASH_IN","CASH_OUT").contains(key.code()) && rows.size()!=1)
                throw new IllegalArgumentException("CROSS_FORM_DUPLICATED_FINANCIAL_TOTAL");
            BigDecimal value=BigDecimal.ZERO;
            List<Source> sources=new ArrayList<>();
            for(FactRow r:rows){
                value=value.add(r.amount());
                sources.add(new Source(r.formCode(),r.sourceRevision(),r.rowId(),r.field(),r.factId()));
            }
            sources.sort(Comparator.comparing(Source::rowId).thenComparing(x->x.factId().toString()));
            YearMonth period=key.role()==TimeRole.PRIOR_STOCK?month.minusMonths(1):month;
            evidences.add(new Evidence(key,period,value,rows.getFirst().currency(),sources));
        }
        String currency=currencies.isEmpty()?"KZT":currencies.iterator().next();
        // No inferred currency allowed if even one recognized amount is missing.
        // For an empty FactSet from unrelated reports, a caller must supply the currency via context;
        // this adapter cannot do so until an authoritative case/report-currency registry exists.
        if(currencies.isEmpty())
            throw new IllegalArgumentException("CROSS_FORM_NO_RELEVANT_VERIFIED_EVIDENCE");
        Scope scope=new Scope(pub.org(),pub.entity(),pub.businessScope(),pub.revision(),
                pub.dataSpace(),currency,pub.basis(),month);
        return CrossFormDiagnosticEngine.evaluate(scope,evidences);
    }

    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public Receipt assessAndPublish(UUID authorizedOrg,UUID publishedRevision,YearMonth month){
        Report report=assessInternal(authorizedOrg,publishedRevision,month);
        String definitionHash=definitionHash();
        JsonNode snapshot=toSnapshot(report);
        UUID proposedId=UUID.randomUUID();
        int inserted=jdbc.update("""
            INSERT INTO fin_diag.assessment_run(
                id,organization_id,fact_set_revision_id,evaluation_month,
                rule_set_code,rule_set_version,engine_version,definition_hash,
                input_fingerprint,snapshot,snapshot_hash)
            VALUES (?,?,?,?,?,?,?,?,?,?::jsonb,?)
            ON CONFLICT (organization_id,fact_set_revision_id,evaluation_month,
                         rule_set_code,rule_set_version) DO NOTHING
            """,proposedId,authorizedOrg,publishedRevision,month.atDay(1),
                RULE_SET_CODE,RULE_SET_VERSION,CrossFormDiagnosticEngine.METHOD_VERSION,
                definitionHash,"0".repeat(64),snapshot.toString(),"0".repeat(64));
        UUID id=proposedId;
        if(inserted==0){
            List<Existing> found=jdbc.query("""
                SELECT id,definition_hash,snapshot::text FROM fin_diag.assessment_run
                WHERE organization_id=? AND fact_set_revision_id=?
                AND evaluation_month=? AND rule_set_code=? AND rule_set_version=?
                """,(rs,i)->new Existing(rs.getObject(1,UUID.class),rs.getString(2),
                    readJson(rs.getString(3))),authorizedOrg,publishedRevision,
                    month.atDay(1),RULE_SET_CODE,RULE_SET_VERSION);
            if(found.size()!=1 || !found.getFirst().hash().equals(definitionHash)
                    || !found.getFirst().snapshot().equals(snapshot))
                throw new IllegalArgumentException("CROSS_FORM_VERSION_OR_RESULT_DRIFT");
            id=found.getFirst().id();
        }
        List<String[]> receipt=jdbc.query("""
            SELECT input_fingerprint,snapshot_hash FROM fin_diag.assessment_run
            WHERE organization_id=? AND id=?
            """,(rs,i)->new String[]{rs.getString(1),rs.getString(2)},authorizedOrg,id);
        if(receipt.size()!=1)throw new IllegalStateException("CROSS_FORM_SNAPSHOT_NOT_PERSISTED");
        return new Receipt(id,inserted==0,receipt.getFirst()[0],receipt.getFirst()[1]);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Stored loadPublished(UUID authorizedOrg,UUID assessmentId){
        Objects.requireNonNull(authorizedOrg);Objects.requireNonNull(assessmentId);
        List<Stored> result=jdbc.query("""
            SELECT a.id,a.organization_id,a.fact_set_revision_id,a.evaluation_month,
                   a.input_fingerprint,a.snapshot_hash,a.snapshot::text,
                   encode(sha256(convert_to(a.snapshot::text,'UTF8')),'hex'),
                   current_set.fact_set_revision_id
            FROM fin_diag.assessment_run a
            JOIN fin_fact.fact_set_publication p
            ON p.fact_set_revision_id=a.fact_set_revision_id AND p.organization_id=a.organization_id
            LEFT JOIN fin_fact.current_fact_set current_set
            ON current_set.organization_id=p.organization_id
            AND current_set.economic_entity_id=p.economic_entity_id
            AND current_set.business_scope_id=p.business_scope_id
            AND current_set.reporting_basis=p.reporting_basis
            AND current_set.data_space=p.data_space
            WHERE a.organization_id=? AND a.id=? AND a.rule_set_code=?
            """,(rs,i)->{
                if(!rs.getString(6).equals(rs.getString(8)))
                    throw new IllegalStateException("CROSS_FORM_SNAPSHOT_HASH_MISMATCH");
                UUID pointer=rs.getObject(9,UUID.class);
                UUID revision=rs.getObject(3,UUID.class);
                Freshness freshness=pointer==null?Freshness.UNKNOWN_POINTER:
                        pointer.equals(revision)?Freshness.CURRENT_SOURCE:Freshness.STALE_SOURCE;
                return new Stored(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                        revision,YearMonth.from(rs.getObject(4,LocalDate.class)),
                        freshness,rs.getString(5),rs.getString(6),readJson(rs.getString(7)));
            },authorizedOrg,assessmentId,RULE_SET_CODE);
        if(result.size()!=1)throw new IllegalArgumentException("CROSS_FORM_NOT_FOUND_OR_FORBIDDEN");
        return result.getFirst();
    }
    private static String definitionHash(){
        try{
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            feed(digest,RULE_SET_CODE);feed(digest,RULE_SET_VERSION);
            feed(digest,CrossFormDiagnosticEngine.METHOD_VERSION);
            for(Rule rule:CrossFormDiagnosticEngine.RULES){
                feed(digest,rule.code());feed(digest,rule.type().name());
                for(MetricKey key:rule.required()){
                    feed(digest,key.code());feed(digest,key.role().name());
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        }catch(NoSuchAlgorithmException e){throw new IllegalStateException("SHA256_UNAVAILABLE",e);}
    }
    private static void feed(MessageDigest digest,String value){
        byte[] bytes=value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
    }
    private JsonNode readJson(String json){
        try{return mapper.readTree(json);}
        catch(com.fasterxml.jackson.core.JsonProcessingException e){
            throw new IllegalArgumentException("CROSS_FORM_INVALID_JSON",e);
        }
    }
    private JsonNode toSnapshot(Report report){
        ObjectNode root=mapper.createObjectNode();
        root.put("methodVersion",report.methodVersion());
        root.put("ruleSetCode",RULE_SET_CODE);root.put("ruleSetVersion",RULE_SET_VERSION);
        root.put("organizationId",report.scope().organizationId().toString());
        root.put("entityId",report.scope().entityId().toString());
        root.put("businessScopeId",report.scope().businessScopeId().toString());
        root.put("factSetRevisionId",report.scope().factSetRevisionId().toString());
        root.put("month",report.scope().month().toString());
        root.put("currency",report.scope().currency());
        root.put("reportingBasis",report.scope().reportingBasis());
        root.put("qualification",report.qualification());
        ArrayNode evidence=root.putArray("evidence");
        report.evidence().entrySet().stream().sorted(Comparator.comparing(
                (Map.Entry<MetricKey,Evidence> e)->e.getKey().code()).thenComparing(
                e->e.getKey().role().name())).forEach(entry->{
            Evidence e=entry.getValue();
            ObjectNode node=evidence.addObject();
            node.put("metricCode",e.key().code());
            node.put("timeRole",e.key().role().name());
            node.put("period",e.period().toString());
            node.put("value",e.amount().toPlainString());
            node.put("currency",e.currency());
            ArrayNode source=node.putArray("sources");
            e.sources().forEach(s->writeSource(source,s));
        });
        CrossFormEvidencePlanner.Plan evidencePlan=CrossFormEvidencePlanner.plan(report);
        ObjectNode plan=root.putObject("nextEvidencePlan");
        plan.put("status",evidencePlan.status());
        ArrayNode next=plan.putArray("nextForms");
        evidencePlan.nextForms().forEach(n->{
            ObjectNode item=next.addObject();
            item.put("formCode",n.formCode());
            item.put("action",n.action().name());
            ArrayNode needed=item.putArray("neededMetrics");
            n.requiredSlots().forEach(k->{
                ObjectNode slot=needed.addObject();
                slot.put("metricCode",k.code());slot.put("timeRole",k.role().name());
            });
            ArrayNode rules=item.putArray("unlocksRuleCodes");
            n.relatedRules().forEach(rules::add);
        });
        ArrayNode findings=root.putArray("findings");
        for(Finding f:report.findings()){
            ObjectNode node=findings.addObject();
            node.put("ruleCode",f.ruleCode());
            node.put("relationType",f.type().name());
            node.put("verdict",f.verdict().name());
            node.put("reasonCode",f.reasonCode());
            node.put("qualification",f.qualification());
            ObjectNode computed=node.putObject("computed");
            f.computed().forEach((key,value)->computed.put(key,value.toPlainString()));
            ArrayNode missing=node.putArray("missing");
            f.missing().forEach(m->{
                ObjectNode item=missing.addObject();
                item.put("metricCode",m.code());item.put("role",m.role().name());
            });
            ArrayNode sources=node.putArray("sources");
            f.sources().forEach(s->writeSource(sources,s));
        }
        return root;
    }
    private static void writeSource(ArrayNode array,Source source){
        ObjectNode node=array.addObject();
        node.put("formCode",source.formCode());
        node.put("sourceRevision",source.sourceRevision().toString());
        node.put("rowId",source.rowId());node.put("sourceField",source.sourceField());
        node.put("factId",source.factId().toString());
    }
}
