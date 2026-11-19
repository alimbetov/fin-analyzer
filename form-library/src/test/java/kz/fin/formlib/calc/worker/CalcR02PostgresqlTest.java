package kz.fin.formlib.calc.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.formlib.calc.fact.*;
import kz.fin.formlib.calc.ingest.FormFactCandidateService;
import kz.fin.formlib.calc.partition.FactMonthlyPartitionService;
import kz.fin.formlib.service.FormDraftService;
import org.junit.jupiter.api.AfterEach;
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

/** Exactly pinned F02 -> TX-Q -> TX-B -> TX-C/D/E on real PostgreSQL 16. */
@SpringBootTest(properties={
    "fin.uat.editor.enabled=false",
    "fin.fact.uat-committer.enabled=true",
    "fin.calc.uat.dispatch.enabled=true",
    "fin.calc.uat.worker.enabled=true"
})
@Testcontainers
@EnabledIfSystemProperty(named="fin.postgres.uat.enabled",matches="true")
class CalcR02PostgresqlTest {
  @Container
  static final PostgreSQLContainer<?> pg=new PostgreSQLContainer<>("postgres:16-alpine")
      .withDatabaseName("calc_r02_isolated_test").withUsername("fin")
      .withPassword("synthetic_test_only");
  @DynamicPropertySource static void pg(DynamicPropertyRegistry r){
    r.add("spring.datasource.url",pg::getJdbcUrl);
    r.add("spring.datasource.username",pg::getUsername);
    r.add("spring.datasource.password",pg::getPassword);
    r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
  }

  @Autowired JdbcTemplate sql;
  @Autowired ObjectMapper mapper;
  @Autowired FormDraftService drafts;
  @Autowired FormFactCandidateService candidates;
  @Autowired F02SourceFactAssembler assembler;
  @Autowired UatF02FactSetCommitter committer;
  @Autowired UatCalcR01OutboxDispatcher dispatcher;
  @Autowired UatF02CalcWorker worker;
  @Autowired FactMonthlyPartitionService partitions;

  record Slot(UUID org,UUID entity,UUID business,UUID project){
    static Slot create(){return new Slot(UUID.randomUUID(),UUID.randomUUID(),
        UUID.randomUUID(),UUID.randomUUID());}
  }
  record Input(Slot slot,UUID user,UUID instance,UUID factsetId,
      String scope,long epoch,UUID planId){}

  @AfterEach void isolateUnfinishedClaims(){
    // Every test has its own source scope; no READY/RUNNING left for another
    // test's worker claim. This is test isolation, never production reaping.
    sql.update("""
        UPDATE fin_calc.calc_job SET state='BLOCKED',
          lease_owner=NULL,lease_until=NULL
        WHERE state IN ('READY','RETRY','RUNNING')
        """);
  }

  private Input prepare(Slot s,UUID user,UUID instance,int expected,long epoch,
      String revenue,String cogs,String opex){
    partitions.ensureMonthlyRange(YearMonth.of(2026,11),YearMonth.of(2026,11),false);
    var receipt=drafts.save(new FormDraftService.DraftRequest(
        instance,s.org,s.entity,s.business,s.project,"F02","M","2026-11",
        "KZT","PERIOD_ONLY","MANAGEMENT","SYNTHETIC",expected,
        Map.of("netRevenue",revenue,"cogs",cogs,"operatingExpenses",opex),
        List.of(),"calc-r02-qa","synthetic E2E"),UUID.randomUUID().toString());
    var exact=candidates.extractExact(receipt.revisionId(),s.org,s.project);
    var mapping=F02MappingDescriptor.testOnlyPilot(exact.formVersionId(),
        exact.sourceSchemaSha256(),exact.sourceMappingSha256());
    var fact=assembler.assemble(exact,mapping);
    var admission=TestOnlyF02Approval.create(fact,user,epoch);
    var principal=admission.principal();
    var trusted=admission.verified();
    var persisted=committer.commitExact(fact,mapping,trusted,principal,user);
    assertEquals(1,dispatcher.dispatchBatch(32).planned());
    UUID planId=sql.queryForObject("""
        SELECT id FROM fin_calc.calc_plan WHERE factset_id=?
        """,UUID.class,persisted.factSetId());
    return new Input(s,user,receipt.instanceId(),persisted.factSetId(),
        persisted.stableScopeKey(),epoch,planId);
  }
  private Input fresh(){
    return prepare(Slot.create(),UUID.randomUUID(),null,0,1,
        "12500000","7400000","2100000");
  }
  private int results(Slot s){
    return sql.queryForObject("""
        SELECT count(*) FROM fin_calc.calc_result WHERE organization_id=?
        """,Integer.class,s.org);
  }
  private UUID current(Input in){
    return sql.queryForObject("""
        SELECT result_id FROM fin_calc.calc_current_result
        WHERE organization_id=? AND scope_key=?
        """,UUID.class,in.slot.org,in.scope);
  }

  @Test void r02_01EndToEndPersistsCorrectImmutableFinancialResult() throws Exception{
    var f=fresh();
    var work=worker.runOne("f02-worker-1");
    assertNotNull(work);
    assertEquals(UatF02CalcWorker.Publication.PUBLISHED,work.publication());
    assertEquals(f.planId,work.completion().planId());
    assertEquals(work.completion().resultId(),current(f));
    var row=sql.queryForMap("""
        SELECT r.result_jsonb::text result,r.lineage_jsonb::text lineage,
          r.output_values_sha256,r.result_sha256,r.qualification_mode,
          j.state,j.fencing_token,j.attempts,j.lease_owner
        FROM fin_calc.calc_result r
        JOIN fin_calc.calc_job j ON j.plan_id=r.plan_id
        WHERE r.id=?
        """,work.completion().resultId());
    var json=mapper.readTree((String)row.get("result"));
    assertEquals("5100000.00",json.at("/outputs/grossProfit/value").asText());
    assertEquals("0.40800000",json.at("/outputs/grossMargin/value").asText());
    assertEquals("3000000.00",json.at("/outputs/operatingResult/value").asText());
    assertEquals("PRESENT",json.at("/outputs/grossMargin/state").asText());
    assertEquals(work.completion().outputValuesSha256(),row.get("output_values_sha256"));
    assertEquals(work.completion().resultSha256(),row.get("result_sha256"));
    assertEquals("LOCAL_TEST_ONLY",row.get("qualification_mode"));
    assertEquals("SUCCEEDED",row.get("state"));
    assertEquals(1L,((Number)row.get("fencing_token")).longValue());
    assertEquals(1,((Number)row.get("attempts")).intValue());
    assertNull(row.get("lease_owner"));
    var lineage=mapper.readTree((String)row.get("lineage"));
    assertEquals(f.factsetId.toString(),lineage.get("factsetId").asText());
    assertEquals(f.slot.project.toString(),lineage.get("projectId").asText());
    assertEquals(f.epoch,lineage.get("qualificationEpoch").asLong());
    assertEquals(1,results(f.slot));
    assertNull(worker.runOne("f02-worker-1"));
  }

  @Test void r02_02ZeroRevenueRetainsInvalidRatioInsteadOfZero() throws Exception{
    var f=prepare(Slot.create(),UUID.randomUUID(),null,0,1,"0","1","2");
    var c=worker.runOne("zero-worker");
    assertEquals(UatF02CalcWorker.Publication.PUBLISHED,c.publication());
    String json=sql.queryForObject("""
        SELECT result_jsonb::text FROM fin_calc.calc_result WHERE id=?
        """,String.class,c.completion().resultId());
    var r=mapper.readTree(json);
    assertEquals("INVALID",r.at("/outputs/grossMargin/state").asText());
    assertEquals("DIVISION_BY_ZERO",r.at("/outputs/grossMargin/errorCode").asText());
    assertTrue(r.at("/outputs/grossMargin/value").isNull());
    assertEquals("-1.00",r.at("/outputs/grossProfit/value").asText());
    assertEquals("-3.00",r.at("/outputs/operatingResult/value").asText());
  }

  @Test void r02_03ClaimIsExclusiveAndFencedOnLeaseRecovery(){
    var f=fresh();
    var original=worker.claimNext("worker-old",60);
    assertEquals(f.planId,original.planId());
    assertNull(worker.claimNext("worker-competing",60));
    assertTrue(worker.heartbeat(original,30));
    sql.update("""
        UPDATE fin_calc.calc_job SET lease_until=now()-interval '1 second'
        WHERE id=?
        """,original.jobId());
    var recovered=worker.claimNext("worker-new",60);
    assertNotNull(recovered);
    assertEquals(original.jobId(),recovered.jobId());
    assertEquals(original.fencingToken()+1,recovered.fencingToken());
    assertEquals("CALC_R02_LEASE_LOST",
        assertThrows(IllegalStateException.class,()->
            worker.executeClaim(original)).getMessage());
    var completed=worker.executeClaim(recovered);
    assertEquals(UatF02CalcWorker.Publication.PUBLISHED,
        worker.publishCurrent(completed));
    assertEquals(1,results(f.slot));
  }

  @ParameterizedTest
  @EnumSource(value=UatF02CalcWorker.FaultPoint.class,
      names={"BEFORE_RESULT_INSERT","AFTER_RESULT_INSERT","BEFORE_JOB_SUCCESS"})
  void r02_04TxDInjectedFaultHasFullRollback(
      UatF02CalcWorker.FaultPoint fault){
    var f=fresh();
    var claim=worker.claimNext("rollback-worker",60);
    assertEquals("CALC_R02_FORCED_ROLLBACK",
        assertThrows(IllegalStateException.class,()->
            worker.executeClaimWithFault(claim,fault)).getMessage());
    assertEquals(0,results(f.slot));
    assertEquals(0,sql.queryForObject("""
        SELECT count(*) FROM fin_calc.calc_current_result
        WHERE organization_id=?
        """,Integer.class,f.slot.org));
    assertEquals("RUNNING",sql.queryForObject("""
        SELECT state FROM fin_calc.calc_job WHERE id=?
        """,String.class,claim.jobId()));
    var completed=worker.executeClaim(claim);
    assertEquals(UatF02CalcWorker.Publication.PUBLISHED,
        worker.publishCurrent(completed));
    assertEquals(1,results(f.slot));
  }

  @Test void r02_05RevokeBetweenTxDAndTxEPreventsPublication(){
    var f=fresh();
    var claim=worker.claimNext("revoke-worker",60);
    var result=worker.executeClaim(claim);
    sql.update("""
        UPDATE fin_fact.factset_control
        SET revoked=true,active_factset_id=NULL,qualification_epoch=qualification_epoch+1
        WHERE stable_scope_key=?
        """,f.scope);
    assertEquals(UatF02CalcWorker.Publication.OBSOLETE_OR_REVOKED,
        worker.publishCurrent(result));
    assertEquals(1,results(f.slot),"Historical result stays immutable");
    assertEquals(0,sql.queryForObject("""
        SELECT count(*) FROM fin_calc.calc_current_result WHERE scope_key=?
        """,Integer.class,f.scope));
  }

  @Test void r02_06NewerFactsetWinsAndOlderResultCannotRewindCurrent(){
    var f=fresh();
    var first=worker.runOne("first-worker");
    assertEquals(UatF02CalcWorker.Publication.PUBLISHED,first.publication());
    var next=prepare(f.slot,f.user,f.instance,1,2,
        "12500000","7400000","2200000");
    var second=worker.runOne("second-worker");
    assertEquals(UatF02CalcWorker.Publication.PUBLISHED,second.publication());
    assertEquals(2,results(f.slot));
    assertNotEquals(first.completion().resultId(),current(next));
    assertEquals(second.completion().resultId(),current(next));
    assertEquals(UatF02CalcWorker.Publication.OBSOLETE_OR_REVOKED,
        worker.publishCurrent(first.completion()));
    assertEquals(second.completion().resultId(),current(next));
  }

  @Test void r02_07ResultTableAppendOnlyEvenAfterCurrentPublished(){
    var f=fresh();
    var r=worker.runOne("immutable-worker");
    assertThrows(org.springframework.dao.DataAccessException.class,()->
        sql.update("""
            UPDATE fin_calc.calc_result SET output_values_sha256=? WHERE id=?
            ""","a".repeat(64),r.completion().resultId()));
    assertThrows(org.springframework.dao.DataAccessException.class,()->
        sql.update("DELETE FROM fin_calc.calc_result WHERE id=?",
            r.completion().resultId()));
    assertEquals(1,results(f.slot));
    assertEquals(r.completion().resultId(),current(f));
  }

  @Test void r02_08PublishIdempotencyDoesNotAdvancePointerVersion(){
    var f=fresh();
    var w=worker.runOne("once-worker");
    long version=sql.queryForObject("""
        SELECT row_version FROM fin_calc.calc_current_result WHERE scope_key=?
        """,Long.class,f.scope);
    assertEquals(UatF02CalcWorker.Publication.ALREADY_CURRENT,
        worker.publishCurrent(w.completion()));
    assertEquals(version,sql.queryForObject("""
        SELECT row_version FROM fin_calc.calc_current_result WHERE scope_key=?
        """,Long.class,f.scope));
  }

  @Test void r02_09WrongOwnerOrFenceCannotWriteResult(){
    var f=fresh();
    var claim=worker.claimNext("right-worker",60);
    var forged=new UatF02CalcWorker.Claim(claim.jobId(),claim.planId(),
        claim.organizationId(),claim.scopeKey(),"other-worker",
        claim.fencingToken(),claim.leaseUntil());
    assertFalse(worker.heartbeat(forged,60));
    assertEquals("CALC_R02_LEASE_LOST",
        assertThrows(IllegalStateException.class,()->
            worker.executeClaim(forged)).getMessage());
    assertEquals(0,results(f.slot));
    assertNotNull(worker.executeClaim(claim));
  }

  @Test void r02_10EightIndependentJobsRunWithoutCrossScopeBleed() throws Exception{
    List<Input> fixtures=new ArrayList<>();
    for(int i=0;i<8;i++)fixtures.add(fresh());
    try(var pool=Executors.newFixedThreadPool(4)){
      var futures=pool.invokeAll(java.util.stream.IntStream.range(0,8)
          .mapToObj(i->(Callable<UatF02CalcWorker.WorkResult>)
              ()->worker.runOne("pool-"+i)).toList());
      var ids=new HashSet<UUID>();
      for(var future:futures){
        var result=future.get(75,TimeUnit.SECONDS);
        assertNotNull(result);
        assertEquals(UatF02CalcWorker.Publication.PUBLISHED,result.publication());
        ids.add(result.completion().resultId());
      }
      assertEquals(8,ids.size());
    }
    for(var f:fixtures){
      assertEquals(1,results(f.slot));
      assertNotNull(current(f));
    }
  }

  @Test void r02_11DisabledWorkerDeniedByDefault(){
    var denied=new UatF02CalcWorker(null,null,null,mapper,false);
    assertEquals("CALC_R02_UAT_WORKER_DISABLED",
        assertThrows(IllegalStateException.class,()->
            denied.claimNext("any-worker",60)).getMessage());
  }
}
