package kz.fin.formlib.calc.fact;

import kz.fin.formlib.calc.ingest.FormFactCandidateService;
import kz.fin.formlib.calc.partition.FactMonthlyPartitionService;
import kz.fin.formlib.calc.plan.F02CalcPlanDescriptor;
import kz.fin.formlib.service.FormDraftService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.YearMonth;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CALC-R0.1 real PostgreSQL16 TX-B qualification. Only synthetic F02 fixtures;
 * proves a durable READY job, NOT running CALC-R0.2 or production financial S2.
 */
@SpringBootTest(properties={
    "fin.uat.editor.enabled=false",
    "fin.fact.uat-committer.enabled=true",
    "fin.calc.uat.dispatch.enabled=true"
})
@Testcontainers
@EnabledIfSystemProperty(named="fin.postgres.uat.enabled",matches="true")
class CalcR01PostgresqlTest {
  @Container
  static final PostgreSQLContainer<?> pg=new PostgreSQLContainer<>("postgres:16-alpine")
      .withDatabaseName("calc_r01_test_only").withUsername("fin")
      .withPassword("test_only_not_production");

  @DynamicPropertySource static void pg(DynamicPropertyRegistry r){
    r.add("spring.datasource.url",pg::getJdbcUrl);
    r.add("spring.datasource.username",pg::getUsername);
    r.add("spring.datasource.password",pg::getPassword);
    r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
  }
  @Autowired JdbcTemplate sql;
  @Autowired FormDraftService drafts;
  @Autowired FormFactCandidateService candidates;
  @Autowired F02SourceFactAssembler f02;
  @Autowired UatF02FactSetCommitter committer;
  @Autowired UatCalcR01OutboxDispatcher dispatcher;
  @Autowired FactMonthlyPartitionService partitions;
  @Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;

  record Slot(UUID org,UUID entity,UUID business,UUID project){
    static Slot newSlot(){return new Slot(UUID.randomUUID(),UUID.randomUUID(),
        UUID.randomUUID(),UUID.randomUUID());}
  }
  record Fixture(Slot slot,UUID user,FormDraftService.DraftReceipt source,
      CandidateFactSet candidate,F02MappingDescriptor mapping,
      TrustedRequestPrincipal principal,VerifiedQualification approval,
      FactSetCommitReceipt fact){}

  private Fixture commit(Slot slot,UUID user,UUID instance,int expected,
      String costs,long epoch){
    var draft=drafts.save(new FormDraftService.DraftRequest(
        instance,slot.org,slot.entity,slot.business,slot.project,"F02",
        "M","2026-11","KZT","PERIOD_ONLY","MANAGEMENT","SYNTHETIC",
        expected,Map.of("netRevenue","12500000","cogs","7400000",
            "operatingExpenses",costs),List.of(),
        "calc-r01-uat","synthetic test only"),UUID.randomUUID().toString());
    var source=candidates.extractExact(draft.revisionId(),slot.org,slot.project);
    var mapping=F02MappingDescriptor.testOnlyPilot(source.formVersionId(),
        source.sourceSchemaSha256(),source.sourceMappingSha256());
    var fact=f02.assemble(source,mapping);
    var principal=new TrustedRequestPrincipal(user.toString(),
        UatF02FactSetCommitter.PROVIDER,fact.scope(),
        UUID.randomUUID().toString(),Set.of(TrustedRequestPrincipal.QUALIFY_ACTION));
    var decision=syntheticDecision(fact,principal,epoch);
    var permission=new VerifiedQualification(decision,principal,Instant.now());
    var receipt=committer.commitExact(fact,mapping,permission,principal,user);
    return new Fixture(slot,user,draft,fact,mapping,principal,permission,receipt);
  }

  private static QualificationDecision syntheticDecision(CandidateFactSet c,
      TrustedRequestPrincipal principal,long epoch){
    UUID decisionId=UUID.randomUUID();
    Instant now=Instant.now().minusSeconds(1);
    String empty="0".repeat(64);
    var base=new QualificationDecision(decisionId,
        UatF02FactSetCommitter.ISSUER,"SYNTHETIC_NOT_SIGNED","Ed25519",
        empty,"FAKE_TEST_ONLY",QualificationDecision.AUDIENCE,
        principal.expectedNonce(),now,now,now.plusSeconds(60),
        c.scope().stableScopeKey(),c.candidateFactsetSha256(),
        c.sourceSetSha256(),c.descriptorSha256(),c.mappingBundleSha256(),
        empty,epoch,QualificationDecision.TEST_ONLY_MODE);
    return new QualificationDecision(
        decisionId,base.issuerId(),base.keyId(),base.signatureAlgorithm(),
        base.signedPayload(),base.signatureBase64(),base.audience(),base.nonce(),
        base.issuedAt(),base.notBefore(),base.expiresAt(),
        base.stableScopeKey(),base.candidateFactsetSha256(),base.sourceSetSha256(),
        base.descriptorSha256(),base.mappingBundleSha256(),
        base.canonicalBinding(),epoch,base.qualificationMode());
  }

  private Fixture fresh(){
    partitions.ensureMonthlyRange(YearMonth.of(2026,11),YearMonth.of(2026,11),false);
    return commit(Slot.newSlot(),UUID.randomUUID(),null,0,"2100000",1);
  }
  private int count(String table,UUID organization){
    return sql.queryForObject(
        "SELECT count(*) FROM "+table+" WHERE organization_id=?",
        Integer.class,organization);
  }
  private UUID eventId(Fixture f){
    return sql.queryForObject("""
        SELECT id FROM fin_calc.calc_outbox_event
        WHERE organization_id=? AND producer='FACT1B_F02_UAT'
          AND payload_jsonb->>'factsetId'=?
        """,UUID.class,f.slot.org,f.fact.factSetId().toString());
  }
  private Object[] storedPlan(Fixture f){
    return sql.queryForObject("""
        SELECT p.id,p.execution_fingerprint,p.factset_id,p.qualification_epoch,
          p.algorithm_artifact_sha256,p.config_sha256,p.params_sha256,
          p.dag_jsonb::text
        FROM fin_calc.calc_plan p WHERE organization_id=?
        """,(r,i)->new Object[]{r.getObject(1,UUID.class),r.getString(2),
          r.getObject(3,UUID.class),r.getLong(4),r.getString(5),
          r.getString(6),r.getString(7),r.getString(8)},f.slot.org);
  }

  @Test void r01_01PublishedUatFactsetCreatesPlanAndReadyJobWithExactFingerprint() throws Exception {
    var f=fresh();
    assertEquals(0,count("fin_calc.calc_job",f.slot.org));
    var batch=dispatcher.dispatchBatch(8);
    assertEquals(1,batch.planned());
    assertEquals(0,batch.reused());
    assertEquals(0,batch.stale());
    assertEquals(1,count("fin_calc.calc_plan",f.slot.org));
    assertEquals(1,count("fin_calc.calc_job",f.slot.org));
    Object[] stored=storedPlan(f);
    var expected=new F02CalcPlanDescriptor(mapper).build(
        f.fact.factSetId(),f.candidate,f.fact.bindingSha256(),
        f.fact.qualificationEpoch());
    assertEquals(expected.executionFingerprint(),stored[1]);
    assertEquals(f.fact.factSetId(),stored[2]);
    assertEquals(1L,stored[3]);
    assertEquals(expected.algorithmArtifactSha256(),stored[4]);
    assertEquals(expected.configSha256(),stored[5]);
    assertEquals(expected.paramsSha256(),stored[6]);
    assertEquals(mapper.valueToTree(mapper.readTree(expected.dagJson())),
        mapper.valueToTree(mapper.readTree((String)stored[7])));
    var job=sql.queryForMap("""
        SELECT state,attempts,fencing_token,lease_owner,lease_until
        FROM fin_calc.calc_job WHERE plan_id=?
        """,stored[0]);
    assertEquals("READY",job.get("state"));
    assertEquals(0,((Number)job.get("attempts")).intValue());
    assertEquals(0L,((Number)job.get("fencing_token")).longValue());
    assertNull(job.get("lease_owner"));
    assertNull(job.get("lease_until"));
    assertNotNull(sql.queryForObject("""
        SELECT processed_at FROM fin_calc.calc_outbox_event WHERE id=?
        """,java.time.OffsetDateTime.class,eventId(f)));
    assertEquals(0,sql.queryForObject("SELECT count(*) FROM fin_calc.calc_result",
        Integer.class));
    assertEquals(0,sql.queryForObject("SELECT count(*) FROM fin_calc.calc_current_result",
        Integer.class));
  }

  @Test void r01_02DuplicateDeliveryReusesExactPlanAndJobWithoutReset(){
    var f=fresh();
    assertEquals(1,dispatcher.dispatchBatch(1).planned());
    UUID plan=(UUID)storedPlan(f)[0];
    sql.update("UPDATE fin_calc.calc_outbox_event SET processed_at=NULL WHERE id=?",eventId(f));
    var result=dispatcher.dispatchBatch(1);
    assertEquals(1,result.reused());
    assertEquals(1,count("fin_calc.calc_plan",f.slot.org));
    assertEquals(1,count("fin_calc.calc_job",f.slot.org));
    assertEquals(plan,storedPlan(f)[0]);
    assertEquals(2,sql.queryForObject("""
        SELECT attempts FROM fin_calc.calc_outbox_event WHERE id=?
        """,Integer.class,eventId(f)));
  }

  @Test void r01_03EightConcurrentConsumersClaimOnce() throws Exception{
    var f=fresh();
    try(var pool=Executors.newFixedThreadPool(8)){
      var calls=java.util.stream.IntStream.range(0,8)
          .mapToObj(i->(Callable<UatCalcR01OutboxDispatcher.BatchResult>)
              ()->dispatcher.dispatchBatch(1)).toList();
      var futures=pool.invokeAll(calls);
      int dispatched=0;
      for(var future:futures)dispatched+=future.get(60,TimeUnit.SECONDS).processed();
      assertEquals(1,dispatched);
    }
    assertEquals(1,count("fin_calc.calc_plan",f.slot.org));
    assertEquals(1,count("fin_calc.calc_job",f.slot.org));
  }

  @Test void r01_04DraftEventCannotCreateQualifiedPlan(){
    var f=fresh();
    sql.update("""
        UPDATE fin_calc.calc_outbox_event
        SET next_attempt_at=now()+interval '1 day' WHERE id=?
        """,eventId(f));
    UUID fake=UUID.randomUUID();
    sql.update("""
        INSERT INTO fin_calc.calc_outbox_event(
          id,organization_id,scope_key,producer,producer_event_id,
          event_type,payload_jsonb)
        VALUES(?,?,?,'FIN_DRAFT_TEST',?,'DRAFT_REVISION_SAVED','{}'::jsonb)
        """,fake,f.slot.org,f.fact.stableScopeKey(),UUID.randomUUID().toString());
    assertEquals(0,dispatcher.dispatchBatch(8).processed());
    assertEquals(0,count("fin_calc.calc_job",f.slot.org));
    assertNull(sql.queryForObject("""
        SELECT processed_at FROM fin_calc.calc_outbox_event WHERE id=?
        """,java.time.OffsetDateTime.class,fake));
  }

  @Test void r01_05RevokedFactsetAcknowledgedAsStaleWithoutJob(){
    var f=fresh();
    sql.update("""
        UPDATE fin_fact.factset_control
        SET revoked=true,active_factset_id=NULL,
          qualification_epoch=qualification_epoch+1
        WHERE stable_scope_key=?
        """,f.fact.stableScopeKey());
    var result=dispatcher.dispatchBatch(1);
    assertEquals(1,result.stale());
    assertEquals(0,count("fin_calc.calc_plan",f.slot.org));
    assertEquals(0,count("fin_calc.calc_job",f.slot.org));
    assertEquals("CALC_R01_STALE_OR_REVOKED",
        sql.queryForObject("""
          SELECT last_error_code FROM fin_calc.calc_outbox_event WHERE id=?
          """,String.class,eventId(f)));
  }

  @Test void r01_06TamperedEventCannotEnqueueOrAcknowledge(){
    var f=fresh();
    UUID id=eventId(f);
    try{
      sql.update("""
          UPDATE fin_calc.calc_outbox_event
          SET payload_jsonb=jsonb_set(payload_jsonb,'{factsetSha256}',
              to_jsonb(?::text)) WHERE id=?
          ""","a".repeat(64),id);
      assertEquals("CALC_R01_EVENT_BINDING_MISMATCH",
          assertThrows(IllegalArgumentException.class,
              ()->dispatcher.dispatchBatch(1)).getMessage());
      assertEquals(0,count("fin_calc.calc_plan",f.slot.org));
      assertEquals(0,count("fin_calc.calc_job",f.slot.org));
      assertNull(sql.queryForObject("""
          SELECT processed_at FROM fin_calc.calc_outbox_event WHERE id=?
          """,java.time.OffsetDateTime.class,id));
    }finally{
      sql.update("UPDATE fin_calc.calc_outbox_event SET next_attempt_at=now()+interval '5 years' WHERE id=?",id);
    }
  }

  @ParameterizedTest
  @EnumSource(value=UatCalcR01OutboxDispatcher.FaultPoint.class,
      names={"AFTER_PLAN","AFTER_JOB","BEFORE_ACK"})
  void r01_07FailpointRollsBackPlanJobAndAck(
      UatCalcR01OutboxDispatcher.FaultPoint fault){
    var f=fresh();
    assertEquals("CALC_R01_FORCED_ROLLBACK",
        assertThrows(IllegalStateException.class,
            ()->dispatcher.dispatchBatchWithFault(1,fault)).getMessage());
    assertEquals(0,count("fin_calc.calc_plan",f.slot.org));
    assertEquals(0,count("fin_calc.calc_job",f.slot.org));
    assertNull(sql.queryForObject("""
        SELECT processed_at FROM fin_calc.calc_outbox_event WHERE id=?
        """,java.time.OffsetDateTime.class,eventId(f)));
    assertEquals(1,dispatcher.dispatchBatch(1).planned());
  }

  @Test void r01_08OlderRevisionCannotBeEnqueuedAfterNewActiveFactset(){
    var f=fresh();
    var next=commit(f.slot,f.user,f.source.instanceId(),1,"2200000",2);
    var result=dispatcher.dispatchBatch(8);
    assertEquals(1,result.stale());
    assertEquals(1,result.planned());
    assertEquals(1,count("fin_calc.calc_job",f.slot.org));
    assertEquals(next.fact.factSetId(),storedPlan(f)[2]);
    assertEquals(2,count("fin_fact.fact_set",f.slot.org));
    assertEquals(2,sql.queryForObject("""
        SELECT count(*) FROM fin_calc.calc_outbox_event
        WHERE organization_id=? AND processed_at IS NOT NULL
        """,Integer.class,f.slot.org));
  }

  @Test void r01_09DisabledDefaultRejectsDispatch(){
    var f=fresh();
    var denied=new UatCalcR01OutboxDispatcher(
        null,null,null,null,null,null,null,false);
    assertEquals("CALC_R01_DISPATCH_DISABLED",
        assertThrows(IllegalStateException.class,()->denied.dispatchBatch(1))
            .getMessage());
    assertEquals(0,count("fin_calc.calc_job",f.slot.org));
    // This negative test must not leave a READY event for a different test.
    sql.update("UPDATE fin_calc.calc_outbox_event SET next_attempt_at=now()+interval '1 day' WHERE id=?",eventId(f));
  }
}
