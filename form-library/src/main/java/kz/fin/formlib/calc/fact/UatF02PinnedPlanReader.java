package kz.fin.formlib.calc.fact;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.formlib.calc.ingest.FormFactCandidateService;
import kz.fin.formlib.calc.plan.F02CalcPlanDescriptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Exact, read-only UAT verification of a persisted CalcPlan and source facts.
 * No mutable current/latest form selection. Re-verifies source SHA and every
 * persisted observation hash against the typed F02 candidate, and recomputes
 * the FULL plan fingerprint / DAG. The stored plan is never self-approving.
 */
@Component
public final class UatF02PinnedPlanReader {
  public record Verified(
      UUID planId,UUID organizationId,UUID projectId,String scopeKey,
      UUID factsetId,String factsetSha256,String executionFingerprint,
      String bindingSha256,long epoch,UUID sourceRevisionId,
      UUID formVersionId,String sourceContentSha256,
      Map<String,BigDecimal> observations) {
    public Verified {
      observations=Map.copyOf(observations);
    }
  }
  private record Header(UUID planId,UUID org,UUID project,String scope,
      UUID factsetId,String execution,String binding,long epoch,
      String artifact,String config,String params,String dagJson,String planMode,
      String factSha,String sourceSetSha,String mappingSha,String sourceJson,
      String dataSpace,String coverage,String basis,String currency,
      String dataset,String periodType,String periodCode,LocalDate start,
      LocalDate end,UUID entity,UUID business,int count,int canonicalVersion,
      String factMode,String issuer,UUID receiptOrg,UUID receiptProject,
      UUID receiptUser,String receiptScope,String receiptFactSha,
      String receiptSourceSha,String receiptBinding,long receiptEpoch,
      String receiptMode) {}
  private record Obs(LocalDate start,LocalDate end,UUID revision,UUID version,
      String formCode,String rowKey,String field,String contentSha,String mappingSha,
      String metric,String contract,String periodType,String periodSemantics,
      String dimensionSha,String dimensions,String keySha,String kind,
      String state,BigDecimal amount,String unit,String currency,String valueSha) {}

  private final JdbcTemplate sql;
  private final FormFactCandidateService source;
  private final F02SourceFactAssembler assembler;
  private final ObjectMapper mapper;
  private final F02CalcPlanDescriptor descriptor;

  public UatF02PinnedPlanReader(JdbcTemplate sql,FormFactCandidateService source,
      F02SourceFactAssembler assembler,ObjectMapper mapper){
    this.sql=sql;this.source=source;this.assembler=assembler;this.mapper=mapper;
    this.descriptor=new F02CalcPlanDescriptor(mapper);
  }

  public Verified verify(UUID planId,UUID organization,String scopeKey){
    if(planId==null||organization==null||scopeKey==null)
      throw new IllegalArgumentException("CALC_R02_PLAN_SCOPE_REQUIRED");
    var headers=sql.query("""
        SELECT p.id pid,p.organization_id org,p.scope_key scope,p.factset_id fid,
          p.execution_fingerprint execution,p.qualified_binding_sha256 binding,
          p.qualification_epoch epoch,p.algorithm_artifact_sha256 artifact,
          p.config_sha256 cfg,p.params_sha256 params,p.dag_jsonb::text dag,
          p.qualification_mode plan_mode,
          f.project_id project,f.factset_sha256 fact_sha,
          f.source_set_sha256 source_sha,f.mapping_bundle_sha256 mapping_sha,
          f.source_revisions_jsonb::text source_json,
          f.data_space ds,f.coverage coverage,f.reporting_basis basis,
          f.currency_code currency,f.dataset_code dataset,
          f.target_period_type ptype,f.target_period_code pcode,
          f.target_period_start pstart,f.target_period_end_exclusive pend,
          f.economic_entity_id entity,f.business_scope_id business,
          f.observation_count obs_count,f.canonicalization_version canon,
          f.qualification_mode fact_mode,f.issuer issuer,
          r.organization_id rorg,r.project_id rproject,r.initiated_by_user_id ruser,
          r.scope_key rscope,r.candidate_factset_sha256 rfact_sha,
          r.source_set_sha256 rsource_sha,r.binding_sha256 rbinding,
          r.qualification_epoch repoch,r.qualification_mode rmode
        FROM fin_calc.calc_plan p
        JOIN fin_fact.fact_set f ON f.id=p.factset_id AND
          f.organization_id=p.organization_id AND f.scope_key=p.scope_key
        JOIN fin_fact.factset_qualification_receipt r ON r.factset_id=f.id
        WHERE p.id=? AND p.organization_id=? AND p.scope_key=?
        """,(r,i)->new Header(
            r.getObject("pid",UUID.class),r.getObject("org",UUID.class),
            r.getObject("project",UUID.class),r.getString("scope"),
            r.getObject("fid",UUID.class),r.getString("execution"),
            r.getString("binding"),r.getLong("epoch"),r.getString("artifact"),
            r.getString("cfg"),r.getString("params"),r.getString("dag"),
            r.getString("plan_mode"),r.getString("fact_sha"),
            r.getString("source_sha"),r.getString("mapping_sha"),
            r.getString("source_json"),r.getString("ds"),r.getString("coverage"),
            r.getString("basis"),r.getString("currency"),r.getString("dataset"),
            r.getString("ptype"),r.getString("pcode"),r.getDate("pstart").toLocalDate(),
            r.getDate("pend").toLocalDate(),r.getObject("entity",UUID.class),
            r.getObject("business",UUID.class),r.getInt("obs_count"),
            r.getInt("canon"),r.getString("fact_mode"),r.getString("issuer"),
            r.getObject("rorg",UUID.class),r.getObject("rproject",UUID.class),
            r.getObject("ruser",UUID.class),r.getString("rscope"),
            r.getString("rfact_sha"),r.getString("rsource_sha"),
            r.getString("rbinding"),r.getLong("repoch"),r.getString("rmode")),
        planId,organization,scopeKey);
    if(headers.size()!=1)throw new IllegalArgumentException("CALC_R02_QUALIFIED_PLAN_MISSING");
    Header f=headers.getFirst();
    if(!"TEST_QUALIFIED".equals(f.planMode)||!"TEST_QUALIFIED".equals(f.factMode)
        ||!"TEST_QUALIFIED".equals(f.receiptMode)
        ||!"FIN_TEST_ONLY_ISSUER".equals(f.issuer)
        ||f.receiptUser==null||f.project==null||!f.org.equals(f.receiptOrg)
        ||!f.project.equals(f.receiptProject)||!f.scope.equals(f.receiptScope)
        ||!f.factSha.equals(f.receiptFactSha)
        ||!f.sourceSetSha.equals(f.receiptSourceSha)
        ||!f.binding.equals(f.receiptBinding)
        ||f.epoch!=f.receiptEpoch||f.epoch<1||f.count!=3||f.canonicalVersion!=1
        ||!"F02_TEST_ONLY".equals(f.dataset))
      throw new IllegalArgumentException("CALC_R02_RECEIPT_OR_PLAN_INVALID");

    JsonNode sources=parse(f.sourceJson);
    if(!sources.isArray()||sources.size()!=1||sources.get(0).size()!=9)
      throw new IllegalArgumentException("CALC_R02_SOURCE_SHAPE_INVALID");
    JsonNode member=sources.get(0);
    UUID revisionId=UUID.fromString(required(member,"revisionId"));
    var exact=source.extractExact(revisionId,f.org,f.project);
    var mapping=F02MappingDescriptor.testOnlyPilot(exact.formVersionId(),
        exact.sourceSchemaSha256(),exact.sourceMappingSha256());
    CandidateFactSet candidate=assembler.assemble(exact,mapping);
    F02CandidateIntegrity.verify(candidate);
    var scope=candidate.scope();
    var pinned=candidate.sources().getFirst();
    Map<String,String> attributes=new LinkedHashMap<>();
    attributes.put("revisionId",pinned.revisionId().toString());
    attributes.put("instanceId",pinned.instanceId().toString());
    attributes.put("formVersionId",pinned.formVersionId().toString());
    attributes.put("formCode",pinned.formCode());
    attributes.put("canonicalContentSha256",pinned.canonicalContentSha256());
    attributes.put("rowOrderSha256",pinned.rowOrderSha256());
    attributes.put("schemaSha256",pinned.schemaSha256());
    attributes.put("mappingSha256",pinned.mappingSha256());
    attributes.put("sourceRole",pinned.sourceRole());
    for(var e:attributes.entrySet()){
      if(!e.getValue().equals(required(member,e.getKey())))
        throw new IllegalArgumentException("CALC_R02_PINNED_SOURCE_MISMATCH");
    }
    if(!f.factSha.equals(candidate.candidateFactsetSha256())
        ||!f.sourceSetSha.equals(candidate.sourceSetSha256())
        ||!f.mappingSha.equals(candidate.mappingBundleSha256())
        ||!f.scope.equals(scope.stableScopeKey())
        ||!f.org.equals(scope.organizationId())||!f.project.equals(scope.projectId())
        ||!f.entity.equals(scope.economicEntityId())
        ||!f.business.equals(scope.businessScopeId())
        ||!f.dataSpace.equals(scope.dataSpace())||!f.coverage.equals(scope.coverage())
        ||!f.basis.equals(scope.reportingBasis())||!f.currency.equals(scope.currencyCode())
        ||!f.periodType.equals(scope.periodType())||!f.periodCode.equals(scope.periodCode())
        ||!f.start.equals(scope.periodStart())||!f.end.equals(scope.periodEndExclusive()))
      throw new IllegalArgumentException("CALC_R02_SOURCE_BINDING_MISMATCH");

    var expectedPlan=descriptor.build(f.factsetId,candidate,f.binding,f.epoch);
    if(!f.execution.equals(expectedPlan.executionFingerprint())
        ||!f.artifact.equals(expectedPlan.algorithmArtifactSha256())
        ||!f.config.equals(expectedPlan.configSha256())
        ||!f.params.equals(expectedPlan.paramsSha256())
        ||!parse(f.dagJson).equals(parse(expectedPlan.dagJson())))
      throw new IllegalArgumentException("CALC_R02_PINNED_PLAN_MISMATCH");

    var rows=sql.query("""
        SELECT o.period_start,o.period_end_exclusive,o.source_revision_id,
          o.source_form_version_id,o.source_form_code,o.source_row_key,
          o.source_field_code,o.source_content_sha256,o.mapping_sha256,
          o.metric_code,o.metric_contract_version,o.period_type,o.period_semantics,
          o.dimension_digest,o.dimensions_jsonb::text dimensions,
          o.observation_key_sha256,o.value_kind,o.value_state,o.numeric_value,
          o.unit_code,o.currency_code,o.canonical_value_sha256
        FROM fin_fact.fact_observation o WHERE o.factset_id=?
        ORDER BY metric_code
        """,(r,i)->new Obs(
            r.getDate(1).toLocalDate(),r.getDate(2).toLocalDate(),
            r.getObject(3,UUID.class),r.getObject(4,UUID.class),r.getString(5),
            r.getString(6),r.getString(7),r.getString(8),r.getString(9),
            r.getString(10),r.getString(11),r.getString(12),r.getString(13),
            r.getString(14),r.getString(15),r.getString(16),r.getString(17),
            r.getString(18),r.getBigDecimal(19),r.getString(20),r.getString(21),
            r.getString(22)),f.factsetId);
    if(rows.size()!=3)throw new IllegalArgumentException("CALC_R02_OBSERVATION_COUNT_INVALID");
    Map<String,BigDecimal> amounts=new LinkedHashMap<>();
    for(int i=0;i<3;i++){
      var expected=candidate.observations().get(i);
      var r=rows.get(i);
      if(!expected.metricCode().equals(r.metric)
          ||!expected.sourceFieldCode().equals(r.field)
          ||!expected.sourceRevisionId().equals(r.revision)
          ||!pinned.formVersionId().equals(r.version)||!"F02".equals(r.formCode)
          ||!expected.sourceRowKey().equals(r.rowKey)
          ||!pinned.canonicalContentSha256().equals(r.contentSha)
          ||!pinned.mappingSha256().equals(r.mappingSha)
          ||!expected.metricContractVersion().equals(r.contract)
          ||!scope.periodType().equals(r.periodType)||!"INTERVAL".equals(r.periodSemantics)
          ||!expected.periodStart().equals(r.start)||!expected.periodEndExclusive().equals(r.end)
          ||!expected.dimensionDigest().equals(r.dimensionSha)
          ||!parse(r.dimensions).equals(mapper.createObjectNode())
          ||!"NUMERIC".equals(r.kind)||!"PRESENT".equals(r.state)
          ||r.amount==null||expected.value().compareTo(r.amount)!=0
          ||!expected.unitCode().equals(r.unit)||!expected.currencyCode().equals(r.currency)
          ||!expected.observationKeySha256().equals(r.keySha)
          ||!expected.valueSha256().equals(r.valueSha))
        throw new IllegalArgumentException("CALC_R02_OBSERVATION_SHA_INVALID");
      amounts.put(r.metric,r.amount);
    }
    return new Verified(f.planId,f.org,f.project,f.scope,f.factsetId,f.factSha,
        f.execution,f.binding,f.epoch,pinned.revisionId(),
        pinned.formVersionId(),pinned.canonicalContentSha256(),amounts);
  }

  private JsonNode parse(String value){
    if(value==null)throw new IllegalArgumentException("CALC_R02_JSON_REQUIRED");
    try(JsonParser parser=mapper.getFactory().createParser(value)){
      parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
      JsonNode node=mapper.readTree(parser);
      if(node==null||parser.nextToken()!=null)
        throw new IllegalArgumentException("CALC_R02_JSON_INVALID");
      return node;
    }catch(java.io.IOException ex){
      throw new IllegalArgumentException("CALC_R02_JSON_INVALID",ex);
    }
  }
  private static String required(JsonNode value,String key){
    JsonNode x=value.get(key);
    if(x==null||!x.isTextual()||x.textValue().isBlank())
      throw new IllegalArgumentException("CALC_R02_SOURCE_CLAIM_REQUIRED");
    return x.textValue();
  }
}
