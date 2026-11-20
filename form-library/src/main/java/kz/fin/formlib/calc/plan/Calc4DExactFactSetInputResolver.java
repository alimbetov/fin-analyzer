package kz.fin.formlib.calc.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.formlib.calc.ingest.FormFactCandidateExtractor;
import kz.fin.formlib.calc.ingest.FormFactSourceIntegrityVerifier;
import kz.fin.formlib.calc.ingest.FormFactSourceReader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * CALC-4D.1: read and revalidate exact active synthetic FactSets.
 * Reconstructs each source revision using INGEST-2 and checks the persisted
 * observation values/row IDs against the real canonical source. Does not
 * select 'latest', convert between currencies, infer SUM, or write results.
 */
@Service
public class Calc4DExactFactSetInputResolver {
  public record Observation(UUID sourceRevisionId,UUID formVersionId,
      String formCode,String metricCode,String rowKey,String sourceFieldCode,
      String dimensionDigest,String dimensionsJson,String unitCode,
      String periodSemantics,LocalDate start,LocalDate end,
      BigDecimal value,String sourceContentSha256,String valueSha256) {}
  public record Snapshot(UUID factsetId,UUID organizationId,UUID projectId,
      UUID economicEntityId,UUID businessScopeId,String scopeKey,
      String factsetSha256,String sourceSetSha256,String currency,
      LocalDate periodStart,LocalDate periodEnd,String periodType,
      long qualificationEpoch,String observationSha256,
      List<Observation> observations,boolean financiallyApproved) {
    public Snapshot{observations=List.copyOf(observations);}
  }
  private record Header(UUID id,UUID org,UUID project,UUID entity,UUID business,
      String scope,String sha,String sourceSha,String currency,
      LocalDate start,LocalDate end,String periodType,
      long epoch,int count,String sourcesJson,String dataSpace) {}
  private record Raw(UUID revision,UUID version,String form,String metric,String rowKey,
      String field,String dimension,String dimensionJson,String unit,
      String semantics,LocalDate start,LocalDate end,BigDecimal value,
      String contentSha,String valueSha,String currency,String state) {}

  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final FormFactSourceReader reader;
  private final FormFactSourceIntegrityVerifier verifier;
  private final FormFactCandidateExtractor extractor;

  public Calc4DExactFactSetInputResolver(JdbcTemplate jdbc,ObjectMapper json,
      FormFactSourceReader reader,FormFactSourceIntegrityVerifier verifier,
      FormFactCandidateExtractor extractor){
    this.jdbc=Objects.requireNonNull(jdbc);
    this.json=Objects.requireNonNull(json);
    this.reader=Objects.requireNonNull(reader);
    this.verifier=Objects.requireNonNull(verifier);
    this.extractor=Objects.requireNonNull(extractor);
  }

  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
  public Snapshot resolve(UUID organizationId,UUID exactFactsetId){
    if(organizationId==null||exactFactsetId==null)
      throw new IllegalArgumentException("CALC4D_EXACT_FACTSET_AND_ORG_REQUIRED");
    var found=jdbc.query("""
      SELECT s.id,s.organization_id,s.project_id,s.economic_entity_id,
        s.business_scope_id,s.scope_key,s.factset_sha256,s.source_set_sha256,
        s.currency_code,s.target_period_start,s.target_period_end_exclusive,
        s.target_period_type,s.qualification_epoch,s.observation_count,
        s.source_revisions_jsonb::text,s.data_space
      FROM fin_fact.fact_set s
      JOIN fin_fact.factset_control c ON c.organization_id=s.organization_id
        AND c.stable_scope_key=s.scope_key
        AND c.active_factset_id=s.id
        AND c.qualification_epoch=s.qualification_epoch
        AND c.revoked=false
      WHERE s.id=? AND s.organization_id=? AND s.qualification_mode='TEST_QUALIFIED'
        AND s.data_space='SYNTHETIC'
      """,(r,i)->new Header(r.getObject(1,UUID.class),r.getObject(2,UUID.class),
        r.getObject(3,UUID.class),r.getObject(4,UUID.class),
        r.getObject(5,UUID.class),r.getString(6).trim(),r.getString(7).trim(),
        r.getString(8).trim(),r.getString(9),r.getDate(10).toLocalDate(),
        r.getDate(11).toLocalDate(),r.getString(12),r.getLong(13),r.getInt(14),
        r.getString(15),r.getString(16)),exactFactsetId,organizationId);
    if(found.size()!=1)
      throw new IllegalStateException("CALC4D_FACTSET_NOT_ACTIVE_TEST_QUALIFIED");
    var h=found.getFirst();
    if(!h.scope().matches("[0-9a-f]{64}")||!h.sha().matches("[0-9a-f]{64}")
        ||h.count()<1||h.count()>50000||!h.start().isBefore(h.end()))
      throw new IllegalStateException("CALC4D_FACTSET_SCOPE_OR_SIZE_INVALID");
    JsonNode memberships;
    try{memberships=json.readTree(h.sourcesJson());}
    catch(Exception e){throw new IllegalStateException("CALC4D_SOURCE_MANIFEST_INVALID",e);}
    if(memberships==null||!memberships.isArray()||memberships.isEmpty())
      throw new IllegalStateException("CALC4D_EXACT_SOURCE_MEMBERS_REQUIRED");
    var members=new HashMap<UUID,JsonNode>();
    for(JsonNode m:memberships){
      if(!m.isObject())throw new IllegalStateException("CALC4D_SOURCE_MEMBER_INVALID");
      UUID id;
      try{id=UUID.fromString(m.path("revisionId").asText());}
      catch(Exception e){throw new IllegalStateException("CALC4D_SOURCE_REVISION_ID_INVALID",e);}
      if(members.putIfAbsent(id,m)!=null)
        throw new IllegalStateException("CALC4D_SOURCE_REVISION_DUPLICATE");
    }
    var raw=jdbc.query("""
      SELECT o.source_revision_id,o.source_form_version_id,o.source_form_code,
        o.metric_code,o.source_row_key,o.source_field_code,o.dimension_digest,
        o.dimensions_jsonb::text,o.unit_code,o.period_semantics,
        o.period_start,o.period_end_exclusive,o.numeric_value,
        o.source_content_sha256,o.canonical_value_sha256,o.currency_code,
        o.value_state
      FROM fin_fact.fact_observation o
      WHERE o.factset_id=?
      ORDER BY o.period_start,o.metric_code,o.source_row_key,o.observation_id
      LIMIT 50001
      """,(r,i)->new Raw(r.getObject(1,UUID.class),
        r.getObject(2,UUID.class),r.getString(3),r.getString(4),
        r.getString(5),r.getString(6),r.getString(7).trim(),
        r.getString(8),r.getString(9),r.getString(10),
        r.getDate(11).toLocalDate(),r.getDate(12).toLocalDate(),
        r.getBigDecimal(13),r.getString(14).trim(),r.getString(15).trim(),
        r.getString(16),r.getString(17)),h.id());
    if(raw.size()!=h.count())
      throw new IllegalStateException("CALC4D_FACTSET_OBSERVATION_COUNT_DRIFT");

    // The source revision is independently rehashed from fin_intake inside
    // this same REPEATABLE_READ database transaction, not merely trusted by SHA.
    var projected=new HashMap<UUID,FormFactCandidateExtractor.Projection>();
    var available=new HashMap<UUID,Map<String,BigDecimal>>();
    for(var entry:members.entrySet()){
      var source=reader.readExact(entry.getKey(),h.org(),h.project());
      if(!h.entity().equals(source.scope().economicEntityId())
          ||!h.business().equals(source.scope().businessScopeId())
          ||!h.currency().equals(source.scope().currencyCode())
          ||!h.start().equals(source.scope().periodStart())
          ||!h.end().equals(source.scope().periodEndExclusive())
          ||!h.periodType().equals(source.scope().periodType())
          ||!"SYNTHETIC".equals(source.scope().dataSpace()))
        throw new IllegalStateException("CALC4D_SOURCE_SCOPE_VERSION_DRIFT");
      verifier.verify(source);
      var projection=extractor.extract(source);
      if(!"UNQUALIFIED_SOURCE_PROJECTION".equals(projection.qualificationState()))
        throw new IllegalStateException("CALC4D_SOURCE_PROJECTION_UNEXPECTED");
      JsonNode m=entry.getValue();
      if(!source.formVersionId().toString().equals(m.path("formVersionId").asText())
          ||!source.formCode().equals(m.path("formCode").asText())
          ||!source.claimedCanonicalContentSha256().equals(
            m.path("canonicalContentSha256").asText())
          ||!source.sourceSchemaSha256().equals(m.path("schemaSha256").asText()))
        throw new IllegalStateException("CALC4D_SOURCE_MEMBER_PIN_MISMATCH");
      projected.put(entry.getKey(),projection);
      var index=new HashMap<String,BigDecimal>();
      for(var n:projection.numeric()){
        if(!"PRESENT".equals(n.valueState()))continue;
        String key=(n.rowId()==null?"SINGLE":n.rowId().toString())+"|"+n.sourceFieldCode();
        if(index.putIfAbsent(key,n.numericValue())!=null)
          throw new IllegalStateException("CALC4D_SOURCE_DUPLICATE_VALUE");
      }
      available.put(entry.getKey(),index);
    }
    var verified=new ArrayList<Observation>();
    var witnessed=new HashSet<UUID>();
    var canonical=new StringBuilder("FIN-CALC4D-EXACT-FACTSET-V1|")
      .append(h.id()).append('|').append(h.org()).append('|')
      .append(h.project()).append('|').append(h.scope()).append('|')
      .append(h.sha()).append('|').append(h.sourceSha()).append('|')
      .append(h.epoch()).append('|').append(h.currency()).append('|')
      .append(h.start()).append('|').append(h.end());
    for(var o:raw){
      var source=projected.get(o.revision());
      if(source==null||!o.version().equals(source.formVersionId())
          ||!o.form().equals(source.formCode())
          ||!o.contentSha().equals(source.sourceSha256Claim())
          ||!o.metric().equals(source.formCode()+"."+o.field())
          ||!"PRESENT".equals(o.state())||o.value()==null
          ||!o.start().isBefore(o.end())||o.start().isBefore(h.start())
          ||o.end().isAfter(h.end())||!h.currency().equals(o.currency()))
        throw new IllegalStateException("CALC4D_OBSERVATION_BINDING_DRIFT");
      BigDecimal expected=available.get(o.revision()).get(o.rowKey()+"|"+o.field());
      if(expected==null||expected.compareTo(o.value())!=0)
        throw new IllegalStateException("CALC4D_OBSERVATION_VALUE_SOURCE_MISMATCH");
      if(!o.dimension().matches("[0-9a-f]{64}")
          ||!o.valueSha().matches("[0-9a-f]{64}")
          ||o.unit()==null||o.unit().isBlank())
        throw new IllegalStateException("CALC4D_OBSERVATION_DIMENSION_OR_UNIT_INVALID");
      verified.add(new Observation(o.revision(),o.version(),o.form(),
        o.metric(),o.rowKey(),o.field(),o.dimension(),o.dimensionJson(),
        o.unit(),o.semantics(),o.start(),o.end(),o.value(),
        o.contentSha(),o.valueSha()));
      witnessed.add(o.revision());
      canonical.append("|O:").append(o.revision()).append(':').append(o.version())
        .append(':').append(o.metric()).append(':').append(o.rowKey())
        .append(':').append(o.field()).append(':').append(o.dimension())
        .append(':').append(o.dimensionJson()).append(':').append(o.unit())
        .append(':').append(o.semantics()).append(':').append(o.start())
        .append(':').append(o.end()).append(':').append(o.value().toPlainString())
        .append(':').append(o.valueSha());
    }
    if(!witnessed.equals(members.keySet()))
      throw new IllegalStateException("CALC4D_UNUSED_SOURCE_MEMBERS");
    return new Snapshot(h.id(),h.org(),h.project(),h.entity(),h.business(),
      h.scope(),h.sha(),h.sourceSha(),h.currency(),h.start(),h.end(),h.periodType(),
      h.epoch(),Calc4DTypedDagCompiler.sha(canonical.toString()),verified,false);
  }
}
