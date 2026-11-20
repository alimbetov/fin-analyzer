package kz.fin.formlib.calc.fact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.formlib.calc.ingest.FormFactCandidateService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * FIN-DATA-R0.1: exact userId + projectId fact retrieval for future FEngine.
 * Reads ONLY active TEST_QUALIFIED F02 FactSets currently supported by FACT-1.B.
 * A synthetic test qualification is not S2 or production authorization.
 * No fallback to fin_intake drafts, no query by initiator instead of trusted IAM.
 */
@Service
public final class FinFactCalculationDataReader {
  public static final String DATA_STATUS="TEST_QUALIFIED_NOT_FINANCIAL_S2";
  public record Query(UUID userId,UUID projectId,UUID exactFactSetId,
      int pageSize,String pageToken) {}
  public record Observation(String metricCode,String contractVersion,
      String numericValue,String unitCode,String currencyCode,
      String sourceFieldCode,String sourceRowKey,UUID sourceRevisionId,
      String sourceContentSha256,String valueSha256,String observationKeySha256,
      String dimensionSha256,LocalDate periodStart,LocalDate periodEndExclusive,
      String periodSemantics) {}
  public record FactSet(UUID factSetId,UUID organizationId,UUID projectId,
      UUID initiatedByUserId,String datasetCode,String dataSpace,
      String coverage,String reportingBasis,String currencyCode,
      String periodType,String periodCode,LocalDate periodStart,
      LocalDate periodEndExclusive,UUID exactSourceRevisionId,
      String sourceContentSha256,String sourceSetSha256,String factSetSha256,
      String mappingBundleSha256,String qualificationBindingSha256,
      long qualificationEpoch,String status,List<Observation> observations) {
    public FactSet {observations=List.copyOf(observations);}
  }
  public record Page(UUID userId,UUID projectId,List<FactSet> factSets,
      String nextPageToken,String status,boolean financiallyApproved,
      boolean productionAdmitted) {
    public Page { factSets=List.copyOf(factSets); }
  }

  private record Cursor(Instant timestamp,UUID id) {}
  private record Header(UUID id,UUID org,UUID project,UUID user,
      String dataset,String scopeKey,String space,String coverage,
      String basis,String currency,String periodType,String periodCode,
      LocalDate start,LocalDate end,String sourceJson,
      String sourceSetSha,String mappingSha,String factSha,String bindingSha,
      long epoch,int count,int canonicalVersion,String issuer,
      UUID receiptUser,String receiptScope,String receiptFactSha,
      String receiptSourceSha,String receiptBinding,long receiptEpoch,
      UUID controlActive,boolean revoked,long controlEpoch,
      Instant createdAt) {}
  private record Obs(String metric,String contract,BigDecimal value,
      String unit,String currency,String field,String rowKey,
      UUID revision,UUID formVersion,String contentSha,String mappingSha,
      String valueSha,String keySha,String dimensionSha,String dimensions,
      LocalDate start,LocalDate end,String periodType,String periodSemantics,
      String valueKind,String valueState) {}

  private final JdbcTemplate db;
  private final ObjectMapper mapper;
  private final FormFactCandidateService candidates;
  private final F02SourceFactAssembler assembler;
  public FinFactCalculationDataReader(JdbcTemplate db,ObjectMapper mapper,
      FormFactCandidateService candidates,F02SourceFactAssembler assembler){
    this.db=Objects.requireNonNull(db);
    this.mapper=Objects.requireNonNull(mapper);
    this.candidates=Objects.requireNonNull(candidates);
    this.assembler=Objects.requireNonNull(assembler);
  }
  private static String trim(String x){return x==null?null:x.trim();}
  private static ResponseStatusException invalid(String code){
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,code);
  }
  private static ResponseStatusException forbidden(){
    return new ResponseStatusException(HttpStatus.FORBIDDEN,
      "FIN_FACT_VERIFIED_USER_ID_REQUIRED");
  }
  private static Cursor parseCursor(String encoded){
    if(encoded==null||encoded.isBlank())return null;
    try {
      String decoded=new String(Base64.getUrlDecoder().decode(encoded),
        java.nio.charset.StandardCharsets.UTF_8);
      var parts=decoded.split("\\|",-1);
      if(parts.length!=2)throw new IllegalArgumentException();
      return new Cursor(Instant.parse(parts[0]),UUID.fromString(parts[1]));
    }catch(RuntimeException e){throw invalid("FIN_FACT_PAGE_CURSOR_INVALID");}
  }
  private static String encodeCursor(Header h){
    String raw=h.createdAt()+"|"+h.id();
    return Base64.getUrlEncoder().withoutPadding().encodeToString(
      raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }
  private static Header header(ResultSet r,int i)throws SQLException {
    return new Header(
      r.getObject("fid",UUID.class),r.getObject("org",UUID.class),
      r.getObject("project",UUID.class),r.getObject("initiator",UUID.class),
      r.getString("dataset"),r.getString("scope_key"),
      r.getString("data_space"),r.getString("coverage"),
      r.getString("reporting_basis"),r.getString("currency_code"),
      r.getString("target_period_type"),r.getString("target_period_code"),
      r.getObject("target_period_start",LocalDate.class),
      r.getObject("target_period_end_exclusive",LocalDate.class),
      r.getString("sources_json"),trim(r.getString("source_sha")),
      trim(r.getString("mapping_sha")),trim(r.getString("fact_sha")),
      trim(r.getString("binding_sha")),r.getLong("epoch"),
      r.getInt("observation_count"),r.getInt("canonicalization_version"),
      r.getString("issuer"),r.getObject("receipt_user",UUID.class),
      trim(r.getString("receipt_scope")),trim(r.getString("receipt_fact_sha")),
      trim(r.getString("receipt_source_sha")),
      trim(r.getString("receipt_binding_sha")),r.getLong("receipt_epoch"),
      r.getObject("active_factset_id",UUID.class),
      r.getBoolean("revoked"),r.getLong("control_epoch"),
      r.getTimestamp("created_at").toInstant());
  }
  private static Obs observation(ResultSet r,int i)throws SQLException {
    return new Obs(r.getString("metric_code"),r.getString("metric_contract_version"),
      r.getBigDecimal("numeric_value"),r.getString("unit_code"),
      r.getString("currency_code"),r.getString("source_field_code"),
      r.getString("source_row_key"),r.getObject("source_revision_id",UUID.class),
      r.getObject("source_form_version_id",UUID.class),
      trim(r.getString("source_content_sha256")),
      trim(r.getString("mapping_sha256")),
      trim(r.getString("canonical_value_sha256")),
      trim(r.getString("observation_key_sha256")),
      trim(r.getString("dimension_digest")),
      r.getString("dimensions_json"),r.getObject("period_start",LocalDate.class),
      r.getObject("period_end_exclusive",LocalDate.class),
      r.getString("period_type"),r.getString("period_semantics"),
      r.getString("value_kind"),r.getString("value_state"));
  }
  private static void require(boolean valid,String reason){
    if(!valid)throw new IllegalStateException(reason);
  }
  private static String requiredText(JsonNode n,String key){
    JsonNode value=n.get(key);
    require(value!=null&&value.isTextual()&&!value.textValue().isBlank(),
      "FIN_FACT_SOURCE_PIN_MISSING_"+key);
    return value.textValue();
  }

  /**
   * authenticatedUserId comes ONLY from verified Spring Security JWT sub.
   * userId from the client is checked, never used as authorization itself.
   *
   * Pagination is keyset (created_at DESC, id DESC) so no Offset/N+1 scan
   * across other users or projects. Exact fact set is optional.
   */
  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
  public Page read(UUID authenticatedUserId,Query request){
    if(request==null||request.userId()==null||request.projectId()==null)
      throw invalid("FIN_FACT_USER_AND_PROJECT_REQUIRED");
    if(authenticatedUserId==null||!authenticatedUserId.equals(request.userId()))
      throw forbidden();
    int pageSize=request.pageSize()==0?25:request.pageSize();
    if(pageSize<1||pageSize>100)throw invalid("FIN_FACT_PAGE_SIZE_INVALID");
    Cursor after=parseCursor(request.pageToken());
    if(after!=null&&request.exactFactSetId()!=null)
      throw invalid("FIN_FACT_EXACT_ID_AND_CURSOR_CONFLICT");

    // Never use requested userId/projectId alone as proof of membership:
    // simultaneously match the qualification receipt, factset, and the
    // current unrevoked active scope pointer. Old/revoked sets are excluded.
    String sql="""
      SELECT f.id AS fid,f.organization_id AS org,f.project_id AS project,
        f.initiated_by_user_id AS initiator,f.dataset_code AS dataset,
        f.scope_key,f.data_space,f.coverage,f.reporting_basis,
        f.currency_code,f.target_period_type,f.target_period_code,
        f.target_period_start,f.target_period_end_exclusive,
        f.source_revisions_jsonb::text AS sources_json,
        f.source_set_sha256 AS source_sha,
        f.mapping_bundle_sha256 AS mapping_sha,
        f.factset_sha256 AS fact_sha,
        f.qualification_binding_sha256 AS binding_sha,
        f.qualification_epoch AS epoch,
        f.observation_count,f.canonicalization_version,f.issuer,
        q.initiated_by_user_id AS receipt_user,q.scope_key AS receipt_scope,
        q.candidate_factset_sha256 AS receipt_fact_sha,
        q.source_set_sha256 AS receipt_source_sha,
        q.binding_sha256 AS receipt_binding_sha,
        q.qualification_epoch AS receipt_epoch,
        c.active_factset_id,c.revoked,c.qualification_epoch AS control_epoch,
        f.created_at
      FROM fin_fact.fact_set f
      JOIN fin_fact.factset_qualification_receipt q
        ON q.factset_id=f.id AND q.organization_id=f.organization_id
           AND q.project_id=f.project_id
      JOIN fin_fact.factset_control c
        ON c.organization_id=f.organization_id
           AND c.stable_scope_key=f.scope_key
           AND c.active_factset_id=f.id AND c.revoked=false
      WHERE f.initiated_by_user_id=? AND q.initiated_by_user_id=?
        AND f.project_id=? AND f.dataset_code='F02_TEST_ONLY'
        AND f.qualification_mode='TEST_QUALIFIED'
        AND q.qualification_mode='TEST_QUALIFIED'
        AND f.issuer='FIN_TEST_ONLY_ISSUER'
      """;
    List<Object> args=new ArrayList<>(List.of(authenticatedUserId,
      authenticatedUserId,request.projectId()));
    if(request.exactFactSetId()!=null) {
      sql+=" AND f.id=? ";
      args.add(request.exactFactSetId());
    }
    if(after!=null){
      sql+=" AND (f.created_at < ? OR (f.created_at = ? AND f.id < ?)) ";
      args.add(java.sql.Timestamp.from(after.timestamp()));
      args.add(java.sql.Timestamp.from(after.timestamp()));
      args.add(after.id());
    }
    sql+=" ORDER BY f.created_at DESC,f.id DESC LIMIT ? ";
    args.add(pageSize+1);
    var page=db.query(sql,FinFactCalculationDataReader::header,args.toArray());
    boolean more=page.size()>pageSize;
    if(more)page=page.subList(0,pageSize);
    var sets=new ArrayList<FactSet>();
    for(Header h:page)sets.add(verify(h,request.userId(),request.projectId()));
    return new Page(request.userId(),request.projectId(),sets,
      more?encodeCursor(page.getLast()):null,
      DATA_STATUS,false,false);
  }

  private FactSet verify(Header h,UUID user,UUID project){
    require(user.equals(h.user())&&user.equals(h.receiptUser())
        &&project.equals(h.project())&&h.id().equals(h.controlActive())
        &&!h.revoked()&&h.controlEpoch()==h.epoch()
        &&h.epoch()>0&&h.epoch()==h.receiptEpoch()
        &&h.scopeKey().equals(h.receiptScope())
        &&h.factSha().equals(h.receiptFactSha())
        &&h.sourceSetSha().equals(h.receiptSourceSha())
        &&h.bindingSha().equals(h.receiptBindingSha())
        &&"F02_TEST_ONLY".equals(h.dataset())
        &&"FIN_TEST_ONLY_ISSUER".equals(h.issuer())
        &&h.count()==3&&h.canonicalVersion()==1,
      "FIN_FACT_EXACT_SCOPE_RECEIPT_OR_CONTROL_DRIFT");
    JsonNode json;
    try{json=mapper.readTree(h.sourceJson());}
    catch(Exception e){throw new IllegalStateException("FIN_FACT_SOURCE_JSON_CORRUPT",e);}
    require(json!=null&&json.isArray()&&json.size()==1,
      "FIN_FACT_ONE_SOURCE_REQUIRED");
    UUID revision;
    try{revision=UUID.fromString(requiredText(json.get(0),"revisionId"));}
    catch(IllegalArgumentException e){
      throw new IllegalStateException("FIN_FACT_SOURCE_REVISION_INVALID",e);
    }
    var source=candidates.extractExact(revision,h.org(),h.project());
    var mapping=F02MappingDescriptor.testOnlyPilot(source.formVersionId(),
      source.sourceSchemaSha256(),source.sourceMappingSha256());
    var candidate=assembler.assemble(source,mapping);
    var scope=candidate.scope();
    var pin=candidate.sources().getFirst();
    require(scope.organizationId().equals(h.org())
        &&scope.projectId().equals(h.project())
        &&scope.stableScopeKey().equals(h.scopeKey())
        &&scope.datasetCode().equals(h.dataset())
        &&scope.dataSpace().equals(h.space())
        &&scope.coverage().equals(h.coverage())
        &&scope.reportingBasis().equals(h.basis())
        &&scope.currencyCode().equals(h.currency())
        &&scope.periodType().equals(h.periodType())
        &&scope.periodCode().equals(h.periodCode())
        &&scope.periodStart().equals(h.start())
        &&scope.periodEndExclusive().equals(h.end())
        &&candidate.candidateFactsetSha256().equals(h.factSha())
        &&candidate.sourceSetSha256().equals(h.sourceSetSha())
        &&candidate.mappingBundleSha256().equals(h.mappingSha()),
      "FIN_FACT_SOURCE_FACTSET_RECONSTRUCTION_FAILED");
    JsonNode pinned=json.get(0);
    String[][] fields={
      {"revisionId",pin.revisionId().toString()},
      {"instanceId",pin.instanceId().toString()},
      {"formVersionId",pin.formVersionId().toString()},
      {"formCode",pin.formCode()},
      {"canonicalContentSha256",pin.canonicalContentSha256()},
      {"rowOrderSha256",pin.rowOrderSha256()},
      {"schemaSha256",pin.schemaSha256()},
      {"mappingSha256",pin.mappingSha256()},
      {"sourceRole",pin.sourceRole()}
    };
    for(var field:fields)
      require(field[1].equals(requiredText(pinned,field[0])),
        "FIN_FACT_SOURCE_PIN_DRIFT");

    List<Obs> stored=db.query("""
      SELECT metric_code,metric_contract_version,numeric_value,unit_code,
        currency_code,source_field_code,source_row_key,source_revision_id,
        source_form_version_id,source_content_sha256,mapping_sha256,
        canonical_value_sha256,observation_key_sha256,dimension_digest,
        dimensions_jsonb::text AS dimensions_json,period_start,
        period_end_exclusive,period_type,period_semantics,value_kind,value_state
      FROM fin_fact.fact_observation WHERE factset_id=?
      ORDER BY metric_code
      """,FinFactCalculationDataReader::observation,h.id());
    require(stored.size()==h.count()&&stored.size()==candidate.observations().size(),
      "FIN_FACT_OBSERVATION_ROW_COUNT_DRIFT");
    var result=new ArrayList<Observation>();
    for(int i=0;i<stored.size();i++){
      Obs actual=stored.get(i);
      TypedObservation expected=candidate.observations().get(i);
      require(actual.metric().equals(expected.metricCode())
          &&actual.contract().equals(expected.metricContractVersion())
          &&actual.value()!=null&&actual.value().compareTo(expected.value())==0
          &&actual.unit().equals(expected.unitCode())
          &&actual.currency().equals(expected.currencyCode())
          &&actual.field().equals(expected.sourceFieldCode())
          &&actual.rowKey().equals(expected.sourceRowKey())
          &&actual.revision().equals(pin.revisionId())
          &&actual.formVersion().equals(pin.formVersionId())
          &&actual.contentSha().equals(pin.canonicalContentSha256())
          &&actual.mappingSha().equals(pin.mappingSha256())
          &&actual.valueSha().equals(expected.valueSha256())
          &&actual.keySha().equals(expected.observationKeySha256())
          &&actual.dimensionSha().equals(expected.dimensionDigest())
          &&"{}".equals(actual.dimensions())
          &&actual.start().equals(expected.periodStart())
          &&actual.end().equals(expected.periodEndExclusive())
          &&actual.periodType().equals(scope.periodType())
          &&"INTERVAL".equals(actual.periodSemantics())
          &&"NUMERIC".equals(actual.valueKind())
          &&"PRESENT".equals(actual.valueState()),
        "FIN_FACT_OBSERVATION_SOURCE_OR_SHA_DRIFT");
      result.add(new Observation(actual.metric(),actual.contract(),
        expected.value().toPlainString(),actual.unit(),actual.currency(),
        actual.field(),actual.rowKey(),actual.revision(),
        pin.canonicalContentSha256(),actual.valueSha(),actual.keySha(),
        actual.dimensionSha(),actual.start(),actual.end(),
        actual.periodSemantics()));
    }
    return new FactSet(h.id(),h.org(),h.project(),h.user(),h.dataset(),
      h.space(),h.coverage(),h.basis(),h.currency(),h.periodType(),
      h.periodCode(),h.start(),h.end(),revision,pin.canonicalContentSha256(),
      h.sourceSetSha(),h.factSha(),h.mappingSha(),h.bindingSha(),
      h.epoch(),DATA_STATUS,result);
  }
}
