package kz.fin.formlib.calc.fact;

import kz.fin.formlib.calc.ingest.FormFactCandidateService;
import kz.fin.formlib.calc.ingest.FormFactCandidateExtractor;
import kz.fin.formlib.calc.fact.F02MappingDescriptor;
import kz.fin.formlib.calc.fact.F02SourceFactAssembler;
import kz.fin.formlib.calc.partition.FactMonthlyPartitionService;
import kz.fin.formlib.service.FormDraftService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FACT-1 architectural spike ONLY. This synthetic qualifier exists exclusively
 * under src/test, cannot run from the production application or HTTP endpoints.
 * It proves TX-Q, F02 mapping, immutable lineage and idempotency, NOT real S2.
 */
@SpringBootTest(properties={"fin.uat.editor.enabled=false"})
@Testcontainers
@EnabledIfSystemProperty(named="fin.postgres.uat.enabled",matches="true")
class Fact1F02TestOnlyQualificationTest {
  @Container
  static final PostgreSQLContainer<?> db=new PostgreSQLContainer<>("postgres:16-alpine")
      .withDatabaseName("fin_fact1_local_test_only")
      .withUsername("fin").withPassword("test_only_not_prod");

  @DynamicPropertySource
  static void dataSource(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url",db::getJdbcUrl);
    r.add("spring.datasource.username",db::getUsername);
    r.add("spring.datasource.password",db::getPassword);
    r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
  }

  private static final String PRODUCER="FACT1_F02_TEST_ONLY";
  private static final String MAPPING_SHA=sha("F02.TEST_ONLY.V1:netRevenue,cogs,operatingExpenses:MONEY:INTERVAL");
  @Autowired FormDraftService drafts;
  @Autowired FormFactCandidateService candidates;
  @Autowired F02SourceFactAssembler f02Assembler;
  @Autowired FactMonthlyPartitionService partitions;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager txManager;

  record Slot(UUID org,UUID entity,UUID business,UUID project) {
    static Slot fresh() {return new Slot(UUID.randomUUID(),UUID.randomUUID(),
        UUID.randomUUID(),UUID.randomUUID());}
  }
  record Qualified(UUID factSetId,String factsetSha,long epoch,String scopeKey,
                   UUID sourceRevisionId) {}

  private static String sha(String text) {
    try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(text.getBytes(StandardCharsets.UTF_8)));
    }catch(Exception ex){throw new IllegalStateException(ex);}
  }

  private FormDraftService.DraftReceipt save(Slot s,UUID instance,int expected,
      String opEx,String idem) {
    return drafts.save(new FormDraftService.DraftRequest(
        instance,s.org(),s.entity(),s.business(),s.project(),"F02",
        "M","2026-11","KZT","PERIOD_ONLY","MANAGEMENT","SYNTHETIC",
        expected,Map.of("netRevenue","12500000","cogs","7400000",
                       "operatingExpenses",opEx),
        List.of(),"fact1-test-issuer","fixture: no financial approval"),idem);
  }

  /**
   * Local test-only trusted fixture, never a public approval endpoint.
   * The data comes exclusively from INGEST-2 reverified exact source snapshots.
   */
  private Qualified commitF02(UUID revision,Slot tenant,boolean injectFailure) {
    var p=candidates.extractExact(revision,tenant.org(),tenant.project());
    assertEquals("SOURCE_SHA_REVERIFIED",p.sourceIntegrityState());
    assertEquals("UNQUALIFIED_SOURCE_PROJECTION",p.qualificationState());
    if(!"F02".equals(p.formCode())||!"M".equals(p.scope().periodType())
        ||!tenant.org().equals(p.scope().organizationId())
        ||!tenant.entity().equals(p.scope().economicEntityId())
        ||!tenant.business().equals(p.scope().businessScopeId())
        ||!tenant.project().equals(p.scope().projectId())
        ||!"KZT".equals(p.scope().currencyCode())||p.numeric().size()!=3
        ||!p.attributes().isEmpty()||!p.evidence().isEmpty()
        ||p.numeric().stream().anyMatch(n->n.rowId()!=null
            ||!"MONEY".equals(n.fieldType())
            ||!"PRESENT".equals(n.valueState())||n.numericValue()==null))
      throw new IllegalStateException("F02_TEST_CONTRACT_BLOCKED");
    Map<String,BigDecimal> values=new TreeMap<>();
    for(var n:p.numeric())
      if(values.putIfAbsent(n.sourceFieldCode(),n.numericValue())!=null)
        throw new IllegalStateException("F02_DUPLICATE_FIELD");
    if(!values.keySet().equals(java.util.Set.of("netRevenue","cogs","operatingExpenses")))
      throw new IllegalStateException("F02_SOURCE_SHAPE_BLOCKED");
    if(values.values().stream().anyMatch(v->v.signum()<0))
      throw new IllegalStateException("F02_TEST_COST_CONVENTION_BLOCKED");

    var scope=p.scope();
    String scopeKey=sha(String.join("|",
        tenant.org().toString(),tenant.entity().toString(),
        tenant.business().toString(),tenant.project().toString(),
        "F02_TEST_ONLY",scope.dataSpace(),scope.coverage(),
        scope.reportingBasis(),scope.currencyCode(),scope.periodType(),
        scope.periodCode(),scope.periodStart().toString(),
        scope.periodEndExclusive().toString()));
    String sourceSetSha=sha("F02.SOURCE.V1|"+revision+"|"+p.formVersionId()
        +"|"+p.sourceSha256Claim());
    String factsetSha=sha("F02.FACTSET.TEST_ONLY.V1|"+scopeKey+"|"+
        sourceSetSha+"|"+MAPPING_SHA+"|"+
        values.entrySet().stream().map(e->e.getKey()+"="+
          e.getValue().stripTrailingZeros().toPlainString()).toList());
    String bindingSha=sha("LOCAL_TEST_ISSUER|"+factsetSha);

    return new TransactionTemplate(txManager).execute(status->{
      // Obtain exactly one immutable active qualification row per scope.
      jdbc.update("""
          INSERT INTO fin_fact.factset_control
           (id,stable_scope_key,organization_id,qualification_epoch,revoked,row_version)
          VALUES (?,?,?,0,true,0)
          ON CONFLICT (stable_scope_key) DO NOTHING
          """,UUID.randomUUID(),scopeKey,tenant.org());
      Map<String,Object> control=jdbc.queryForMap("""
          SELECT organization_id,qualification_epoch,active_factset_id,revoked
          FROM fin_fact.factset_control
          WHERE stable_scope_key=? FOR UPDATE
          """,scopeKey);
      if(!tenant.org().equals(control.get("organization_id")))
        throw new IllegalStateException("F02_CROSS_TENANT_CONTROL");
      String queryExisting=jdbc.query("""
          SELECT payload_jsonb->>'factsetId'
          FROM fin_calc.calc_outbox_event
          WHERE producer=? AND producer_event_id=?
          """,(rs,i)->rs.getString(1),PRODUCER,revision.toString())
          .stream().findFirst().orElse(null);
      if(queryExisting!=null) {
        UUID existing=UUID.fromString(queryExisting);
        String stored=jdbc.queryForObject(
            "SELECT factset_sha256 FROM fin_fact.fact_set WHERE id=?",
            String.class,existing);
        if(!factsetSha.equals(stored))
          throw new IllegalStateException("F02_IDEMPOTENCY_DIGEST_MISMATCH");
        Number savedEpoch=jdbc.queryForObject(
            "SELECT qualification_epoch FROM fin_fact.fact_set WHERE id=?",
            Long.class,existing);
        return new Qualified(existing,factsetSha,savedEpoch.longValue(),scopeKey,revision);
      }
      long epoch=((Number)control.get("qualification_epoch")).longValue()+1;
      UUID id=UUID.randomUUID();
      var head=new MapSqlParameterSource()
          .addValue("id",id).addValue("org",tenant.org())
          .addValue("entity",tenant.entity()).addValue("business",tenant.business())
          .addValue("project",tenant.project()).addValue("scope",scopeKey)
          .addValue("periodType",scope.periodType()).addValue("periodCode",scope.periodCode())
          .addValue("start",scope.periodStart()).addValue("end",scope.periodEndExclusive())
          .addValue("dataSpace",scope.dataSpace()).addValue("coverage",scope.coverage())
          .addValue("basis",scope.reportingBasis()).addValue("currency",scope.currencyCode())
          .addValue("sources","[{\"formCode\":\"F02\",\"revisionId\":\""+revision+
              "\",\"formVersionId\":\""+p.formVersionId()+"\",\"contentSha256\":\""+
              p.sourceSha256Claim()+"\"}]")
          .addValue("sourceSetSha",sourceSetSha).addValue("mappingSha",MAPPING_SHA)
          .addValue("factsetSha",factsetSha).addValue("bindingSha",bindingSha)
          .addValue("epoch",epoch);
      new NamedParameterJdbcTemplate(jdbc).update("""
          INSERT INTO fin_fact.fact_set (
           id,organization_id,economic_entity_id,business_scope_id,project_id,
           dataset_code,scope_key,data_space,coverage,reporting_basis,currency_code,
           target_period_type,target_period_code,target_period_start,target_period_end_exclusive,
           source_revisions_jsonb,source_set_sha256,mapping_bundle_sha256,
           canonicalization_version,observation_count,factset_sha256,
           qualification_binding_sha256,qualification_epoch,qualification_mode,issuer)
          VALUES(
           :id,:org,:entity,:business,:project,'F02_TEST_ONLY',:scope,
           :dataSpace,:coverage,:basis,:currency,:periodType,:periodCode,:start,:end,
           CAST(:sources AS jsonb),:sourceSetSha,:mappingSha,1,3,:factsetSha,
           :bindingSha,:epoch,'TEST_QUALIFIED','FACT1_F02_SYNTHETIC_TEST')
          """,head);

      for(var entry:values.entrySet()) {
        var params=new MapSqlParameterSource()
            .addValue("start",scope.periodStart())
            .addValue("end",scope.periodEndExclusive())
            .addValue("id",UUID.randomUUID()).addValue("factset",id)
            .addValue("revision",revision).addValue("version",p.formVersionId())
            .addValue("sourceSha",p.sourceSha256Claim()).addValue("mapSha",MAPPING_SHA)
            .addValue("field",entry.getKey()).addValue("metric","F02."+entry.getKey())
            .addValue("keySha",sha("F02."+entry.getKey()+"|SINGLE|"+scope.periodStart()))
            .addValue("dimensionSha",sha("DIMENSIONLESS"))
            .addValue("amount",entry.getValue())
            .addValue("valueSha",sha(entry.getKey()+"|"+entry.getValue().stripTrailingZeros().toPlainString()));
        new NamedParameterJdbcTemplate(jdbc).update("""
            INSERT INTO fin_fact.fact_observation(
             period_start,observation_id,factset_id,source_revision_id,source_form_code,
             source_form_version_id,source_row_key,source_field_code,source_content_sha256,
             mapping_sha256,metric_code,metric_contract_version,period_type,period_end_exclusive,
             period_semantics,dimension_digest,dimensions_jsonb,observation_key_sha256,
             value_kind,value_state,numeric_value,unit_code,currency_code,canonical_value_sha256)
            VALUES(:start,:id,:factset,:revision,'F02',:version,'SINGLE',:field,
              :sourceSha,:mapSha,:metric,'TEST_ONLY_F02_V1','M',:end,'INTERVAL',
              :dimensionSha,'{}'::jsonb,:keySha,'NUMERIC','PRESENT',
              :amount,'KZT','KZT',:valueSha)
            """,params);
      }

      if(injectFailure)throw new IllegalStateException("FACT1_FORCED_ROLLBACK");
      jdbc.update("""
          UPDATE fin_fact.factset_control
          SET active_factset_id=?,qualification_epoch=?,revoked=false,
              row_version=row_version+1,updated_at=now()
          WHERE stable_scope_key=? AND organization_id=?
          """,id,epoch,scopeKey,tenant.org());
      jdbc.update("""
          INSERT INTO fin_calc.calc_outbox_event(
           id,organization_id,scope_key,producer,producer_event_id,event_type,payload_jsonb)
          VALUES(?,?,?,?,?,'FACTSET_QUALIFIED',
                 jsonb_build_object('factsetId',?,'factsetSha256',?,'epoch',?))
          """,UUID.randomUUID(),tenant.org(),scopeKey,PRODUCER,
          revision.toString(),id.toString(),factsetSha,epoch);
      return new Qualified(id,factsetSha,epoch,scopeKey,revision);
    });
  }

  @Test void f02ExactVerifiedRevisionCreatesThreeFactsAndOneOutbox() {
    partitions.ensureMonthlyRange(YearMonth.of(2026,11),YearMonth.of(2026,11),false);
    var slot=Slot.fresh();
    var saved=save(slot,null,0,"2100000","f1-first-"+slot.org());
    // A1 PostgreSQL proof: exact INGEST-2 reverified persisted revision,
    // including instance identity and independently computed row-order SHA.
    var exact=candidates.extractExact(saved.revisionId(),slot.org(),slot.project());
    var pinned=F02MappingDescriptor.testOnlyPilot(exact.formVersionId(),
        exact.sourceSchemaSha256(),exact.sourceMappingSha256());
    var unqualified=f02Assembler.assemble(exact,pinned);
    assertEquals("SOURCE_SHA_REVERIFIED",exact.sourceIntegrityState());
    assertEquals(saved.revisionId(),unqualified.sources().getFirst().revisionId());
    assertEquals(saved.instanceId(),unqualified.sources().getFirst().instanceId());
    assertEquals(exact.sourceSha256Claim(),
        unqualified.sources().getFirst().canonicalContentSha256());
    assertEquals(exact.rowOrderSha256(),
        unqualified.sources().getFirst().rowOrderSha256());
    assertEquals(3,unqualified.observations().size());
    assertEquals(List.of("F02.cogs","F02.netRevenue","F02.operatingExpenses"),
        unqualified.observations().stream().map(o->o.metricCode()).toList());
    assertEquals("TEST_ONLY_PILOT",pinned.reviewStatus());
    assertEquals(0,jdbc.queryForObject(
        "SELECT count(*) FROM fin_fact.fact_set WHERE organization_id=?",
        Integer.class,slot.org()));
    Qualified admitted=commitF02(saved.revisionId(),slot,false);
    assertEquals(1,admitted.epoch());
    assertEquals(3,jdbc.queryForObject(
        "SELECT count(*) FROM fin_fact.fact_observation WHERE factset_id=?",
        Integer.class,admitted.factSetId()));
    assertEquals(List.of("F02.cogs","F02.netRevenue","F02.operatingExpenses"),
        jdbc.query("""
           SELECT metric_code FROM fin_fact.fact_observation
           WHERE factset_id=? ORDER BY metric_code
           """,(rs,i)->rs.getString(1),admitted.factSetId()));
    assertEquals(1,jdbc.queryForObject("""
        SELECT count(*) FROM fin_calc.calc_outbox_event
        WHERE producer=? AND producer_event_id=? AND event_type='FACTSET_QUALIFIED'
        """,Integer.class,PRODUCER,saved.revisionId().toString()));
    assertEquals(0,jdbc.queryForObject(
        "SELECT count(*) FROM fin_calc.calc_job",Integer.class),
        "FACT-1 only persists trusted event; DAG/worker not yet implemented");
    Qualified replay=commitF02(saved.revisionId(),slot,false);
    assertEquals(admitted.factSetId(),replay.factSetId());
    assertEquals(admitted.factsetSha(),replay.factsetSha());
    assertEquals(3,jdbc.queryForObject(
        "SELECT count(*) FROM fin_fact.fact_observation WHERE factset_id=?",
        Integer.class,admitted.factSetId()));
  }

  @Test void newDraftDoesNotSupersedeBeforeTestOnlyQualification() {
    partitions.ensureMonthlyRange(YearMonth.of(2026,11),YearMonth.of(2026,11),false);
    var s=Slot.fresh();
    var old=save(s,null,0,"2100000","f1-old-"+s.org());
    var approved=commitF02(old.revisionId(),s,false);
    var revised=save(s,old.instanceId(),1,
        "2200000","f1-revised-"+s.org());
    assertEquals(approved.factSetId(),jdbc.queryForObject("""
        SELECT active_factset_id FROM fin_fact.factset_control WHERE stable_scope_key=?
        """,UUID.class,approved.scopeKey()));
    var next=commitF02(revised.revisionId(),s,false);
    assertNotEquals(approved.factSetId(),next.factSetId());
    assertEquals(approved.epoch()+1,next.epoch());
    assertEquals(6,jdbc.queryForObject("""
        SELECT count(*) FROM fin_fact.fact_observation
        WHERE factset_id IN (?,?)
        """,Integer.class,approved.factSetId(),next.factSetId()));
    assertEquals(next.factSetId(),jdbc.queryForObject(
        "SELECT active_factset_id FROM fin_fact.factset_control WHERE stable_scope_key=?",
        UUID.class,next.scopeKey()));
    assertEquals(approved.factSetId(),commitF02(old.revisionId(),s,false).factSetId());
    assertEquals(next.factSetId(),jdbc.queryForObject(
        "SELECT active_factset_id FROM fin_fact.factset_control WHERE stable_scope_key=?",
        UUID.class,next.scopeKey()),"historical duplicate must not rewind active pointer");
  }

  @Test void atomicTxRollsBackAllThreeFactsAndTrustedEventOnFault() {
    partitions.ensureMonthlyRange(YearMonth.of(2026,11),YearMonth.of(2026,11),false);
    var s=Slot.fresh();
    var saved=save(s,null,0,"2100000","f1-fault-"+s.org());
    var ex=assertThrows(IllegalStateException.class,
        ()->commitF02(saved.revisionId(),s,true));
    assertEquals("FACT1_FORCED_ROLLBACK",ex.getMessage());
    assertEquals(0,jdbc.queryForObject("""
        SELECT count(*) FROM fin_fact.fact_set WHERE organization_id=?
        """,Integer.class,s.org()));
    assertEquals(0,jdbc.queryForObject("""
        SELECT count(*) FROM fin_fact.fact_observation o
        JOIN fin_fact.fact_set f ON f.id=o.factset_id WHERE f.organization_id=?
        """,Integer.class,s.org()));
    assertEquals(0,jdbc.queryForObject("""
        SELECT count(*) FROM fin_calc.calc_outbox_event
        WHERE producer=? AND producer_event_id=?
        """,Integer.class,PRODUCER,saved.revisionId().toString()));
    assertEquals(0,jdbc.queryForObject("""
        SELECT count(*) FROM fin_fact.factset_control WHERE organization_id=?
        """,Integer.class,s.org()),"new scope/epoch must be rolled back too");
  }

  @Test void localTestRevocationMakesBindingNonCurrentWithoutHistoryDeletion() {
    partitions.ensureMonthlyRange(YearMonth.of(2026,11),YearMonth.of(2026,11),false);
    var s=Slot.fresh();
    var saved=save(s,null,0,"2100000","f1-revoke-"+s.org());
    var qualified=commitF02(saved.revisionId(),s,false);
    jdbc.update("""
        UPDATE fin_fact.factset_control
        SET revoked=true,active_factset_id=NULL,qualification_epoch=qualification_epoch+1,
            row_version=row_version+1,updated_at=now()
        WHERE stable_scope_key=? AND organization_id=?
        """,qualified.scopeKey(),s.org());
    assertTrue(jdbc.queryForObject("""
        SELECT revoked FROM fin_fact.factset_control WHERE stable_scope_key=?
        """,Boolean.class,qualified.scopeKey()));
    assertEquals(3,jdbc.queryForObject("""
        SELECT count(*) FROM fin_fact.fact_observation WHERE factset_id=?
        """,Integer.class,qualified.factSetId()),
        "revoke must preserve exact immutable observations");
    assertNull(jdbc.queryForObject("""
        SELECT active_factset_id FROM fin_fact.factset_control WHERE stable_scope_key=?
        """,UUID.class,qualified.scopeKey()));
  }
}
