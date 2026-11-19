package kz.fin.formlib.calc.fact;

import kz.fin.formlib.calc.ingest.FormFactCandidateService;
import kz.fin.formlib.calc.partition.FactMonthlyPartitionService;
import kz.fin.formlib.service.FormDraftService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real PostgreSQL16, independent connections, NO production IAM or external S2.
 * Package-scoped synthetic marker is never part of application runtime.
 */
@SpringBootTest(properties={
    "fin.uat.editor.enabled=false",
    "fin.fact.uat-committer.enabled=true"
})
@Testcontainers
@EnabledIfSystemProperty(named="fin.postgres.uat.enabled",matches="true")
class Fact1BUatCommitterPostgresqlTest {
  @Container
  static final PostgreSQLContainer<?> pg=new PostgreSQLContainer<>("postgres:16-alpine")
      .withDatabaseName("fact1b_isolated_qa")
      .withUsername("fin").withPassword("qa_test_only");
  @DynamicPropertySource static void pg(DynamicPropertyRegistry r){
    r.add("spring.datasource.url",pg::getJdbcUrl);
    r.add("spring.datasource.username",pg::getUsername);
    r.add("spring.datasource.password",pg::getPassword);
    r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
  }

  @Autowired FormDraftService drafts;
  @Autowired FormFactCandidateService candidates;
  @Autowired F02SourceFactAssembler assembler;
  @Autowired UatF02FactSetCommitter committer;
  @Autowired FactMonthlyPartitionService partitions;
  @Autowired JdbcTemplate sql;

  record Slot(UUID org,UUID entity,UUID business,UUID project) {
    static Slot fresh(){return new Slot(UUID.randomUUID(),UUID.randomUUID(),
        UUID.randomUUID(),UUID.randomUUID());}
  }
  record Context(Slot slot,UUID user,FormDraftService.DraftReceipt saved,
      CandidateFactSet candidate,F02MappingDescriptor descriptor,
      TrustedRequestPrincipal principal,VerifiedQualification approval){}

  private static final String PERIOD="2026-11";
  private void partition(){
    partitions.ensureMonthlyRange(YearMonth.of(2026,11),YearMonth.of(2026,11),false);
  }
  private FormDraftService.DraftReceipt save(Slot s,UUID instance,int expected,String expenses){
    return drafts.save(new FormDraftService.DraftRequest(
        instance,s.org,s.entity,s.business,s.project,"F02",
        "M",PERIOD,"KZT","PERIOD_ONLY","MANAGEMENT","SYNTHETIC",expected,
        Map.of("netRevenue","12500000","cogs","7400000",
            "operatingExpenses",expenses),
        List.of(),"fact1b-qa","Synthetic UAT; never financial S2"),UUID.randomUUID().toString());
  }
  private static QualificationDecision decision(CandidateFactSet c,
      TrustedRequestPrincipal actor,UUID decisionId,long epoch){
    Instant issued=Instant.now().minusSeconds(1);
    Instant expires=issued.plusSeconds(120);
    String blank="0".repeat(64);
    var initial=new QualificationDecision(decisionId,
        UatF02FactSetCommitter.ISSUER,"SYNTHETIC_KEY_ONLY","Ed25519",
        blank,"TEST_ONLY_NOT_SIGNED",QualificationDecision.AUDIENCE,
        actor.expectedNonce(),issued,issued,expires,c.scope().stableScopeKey(),
        c.candidateFactsetSha256(),c.sourceSetSha256(),c.descriptorSha256(),
        c.mappingBundleSha256(),blank,epoch,QualificationDecision.TEST_ONLY_MODE);
    var bound=new QualificationDecision(decisionId,initial.issuerId(),
        initial.keyId(),initial.signatureAlgorithm(),blank,
        initial.signatureBase64(),initial.audience(),initial.nonce(),
        issued,issued,expires,initial.stableScopeKey(),
        initial.candidateFactsetSha256(),initial.sourceSetSha256(),
        initial.descriptorSha256(),initial.mappingBundleSha256(),
        initial.canonicalBinding(),epoch,initial.qualificationMode());
    return new QualificationDecision(decisionId,bound.issuerId(),
        bound.keyId(),bound.signatureAlgorithm(),bound.canonicalPayload(),
        bound.signatureBase64(),bound.audience(),bound.nonce(),
        bound.issuedAt(),bound.notBefore(),bound.expiresAt(),
        bound.stableScopeKey(),bound.candidateFactsetSha256(),
        bound.sourceSetSha256(),bound.descriptorSha256(),
        bound.mappingBundleSha256(),bound.bindingSha256(),
        bound.issuerEpoch(),bound.qualificationMode());
  }
  private static VerifiedQualification synthetic(CandidateFactSet c,
      TrustedRequestPrincipal principal,UUID id,long epoch){
    return new VerifiedQualification(decision(c,principal,id,epoch),principal,Instant.now());
  }
  private Context context(Slot s,UUID user,UUID instance,int expected,
      String expenses,UUID decisionId,long epoch){
    var saved=save(s,instance,expected,expenses);
    var source=candidates.extractExact(saved.revisionId(),s.org,s.project);
    assertEquals("SOURCE_SHA_REVERIFIED",source.sourceIntegrityState());
    var descriptor=F02MappingDescriptor.testOnlyPilot(source.formVersionId(),
        source.sourceSchemaSha256(),source.sourceMappingSha256());
    var candidate=assembler.assemble(source,descriptor);
    var principal=new TrustedRequestPrincipal(user.toString(),
        UatF02FactSetCommitter.PROVIDER,candidate.scope(),
        UUID.randomUUID().toString(),Set.of(TrustedRequestPrincipal.QUALIFY_ACTION));
    var permit=synthetic(candidate,principal,decisionId,epoch);
    return new Context(s,user,saved,candidate,descriptor,principal,permit);
  }
  private Context fresh(){
    partition();
    return context(Slot.fresh(),UUID.randomUUID(),null,0,"2100000",
        UUID.randomUUID(),1);
  }
  private FactSetCommitReceipt commit(Context c){
    return committer.commitExact(c.candidate,c.descriptor,c.approval,c.principal,c.user);
  }
  private int count(String table,UUID org){
    return sql.queryForObject("SELECT count(*) FROM "+table+
        " WHERE organization_id=?",Integer.class,org);
  }

  @Test void qf11OneAtomicFactSetAndThreeExactObservationsPlusOutbox(){
    var c=fresh();
    var out=commit(c);
    assertEquals("TEST_QUALIFIED",out.result());
    assertEquals(c.saved.revisionId(),out.sourceRevisionId());
    assertEquals(c.user,out.userId());
    assertEquals(c.slot.project,out.projectId());
    assertEquals(c.candidate.candidateFactsetSha256(),out.factsetSha256());
    assertEquals(1,out.qualificationEpoch());
    assertEquals(3,sql.queryForObject("""
        SELECT count(*) FROM fin_fact.fact_observation WHERE factset_id=?
        """,Integer.class,out.factSetId()));
    assertEquals(List.of("F02.cogs","F02.netRevenue","F02.operatingExpenses"),
        sql.query("""
            SELECT metric_code FROM fin_fact.fact_observation
            WHERE factset_id=? ORDER BY metric_code
            """,(r,i)->r.getString(1),out.factSetId()));
    var header=sql.queryForMap("""
        SELECT initiated_by_user_id,project_id,source_set_sha256,
          factset_sha256,qualification_mode,source_revisions_jsonb->0->>'revisionId' source_revision
        FROM fin_fact.fact_set WHERE id=?
        """,out.factSetId());
    assertEquals(c.user,header.get("initiated_by_user_id"));
    assertEquals(c.slot.project,header.get("project_id"));
    assertEquals(c.candidate.sourceSetSha256(),header.get("source_set_sha256"));
    assertEquals(c.candidate.candidateFactsetSha256(),header.get("factset_sha256"));
    assertEquals(c.saved.revisionId().toString(),header.get("source_revision"));
    assertEquals("TEST_QUALIFIED",header.get("qualification_mode"));
    assertEquals(1,count("fin_fact.factset_qualification_receipt",c.slot.org));
    assertEquals(1,count("fin_calc.calc_outbox_event",c.slot.org));
    assertEquals(0,sql.queryForObject("SELECT count(*) FROM fin_calc.calc_job",
        Integer.class),"FACT-1.B must not invent a running CALC worker");
  }

  @Test void qf12ReplayAndNewerRevisionPreserveHistoryAndNeverRewindPointer(){
    var c=fresh();
    var original=commit(c);
    var replay=commit(c);
    assertEquals("IDEMPOTENT_REPLAY",replay.result());
    assertEquals(original.factSetId(),replay.factSetId());
    assertEquals(1,count("fin_fact.fact_set",c.slot.org));
    assertEquals(1,count("fin_calc.calc_outbox_event",c.slot.org));
    var next=context(c.slot,c.user,c.saved.instanceId(),1,"2200000",
        UUID.randomUUID(),2);
    var added=commit(next);
    assertEquals(2,added.qualificationEpoch());
    assertNotEquals(original.factSetId(),added.factSetId());
    assertEquals(2,count("fin_fact.fact_set",c.slot.org));
    assertEquals(6,sql.queryForObject("""
        SELECT count(*) FROM fin_fact.fact_observation
        WHERE factset_id IN (?,?)
        """,Integer.class,original.factSetId(),added.factSetId()));
    assertEquals(original.factSetId(),commit(c).factSetId());
    assertEquals(added.factSetId(),sql.queryForObject("""
        SELECT active_factset_id FROM fin_fact.factset_control
        WHERE stable_scope_key=?
        """,UUID.class,added.stableScopeKey()));
    var newerPermit=synthetic(c.candidate,c.principal,UUID.randomUUID(),3);
    assertEquals("FACT1B_OLDER_REVISION_BLOCKED",
        assertThrows(IllegalArgumentException.class,()->
            committer.commitExact(c.candidate,c.descriptor,newerPermit,
                c.principal,c.user)).getMessage());
  }

  @Test void qf13SameIssuerDecisionForDifferentSourceCannotWrite(){
    var c=fresh();
    commit(c);
    var next=context(c.slot,c.user,c.saved.instanceId(),1,"2300000",
        c.approval.decisionId(),2);
    assertEquals("QUALIFICATION_REPLAY_CONFLICT",
        assertThrows(IllegalArgumentException.class,()->commit(next)).getMessage());
    assertEquals(1,count("fin_fact.fact_set",c.slot.org));
    assertEquals(1,count("fin_fact.factset_qualification_receipt",c.slot.org));
  }

  @Test void qf14EightConcurrentIdenticalDecisionsProduceOneFactSet() throws Exception {
    var c=fresh();
    try(var pool=Executors.newFixedThreadPool(8)){
      List<Callable<FactSetCommitReceipt>> jobs=java.util.stream.IntStream.range(0,8)
          .mapToObj(i->(Callable<FactSetCommitReceipt>)(()->commit(c))).toList();
      var futures=pool.invokeAll(jobs);
      java.util.Set<UUID> ids=new java.util.HashSet<>();
      for(var future:futures)ids.add(future.get(60,TimeUnit.SECONDS).factSetId());
      assertEquals(1,ids.size());
    }
    assertEquals(1,count("fin_fact.fact_set",c.slot.org));
    assertEquals(1,count("fin_fact.factset_qualification_receipt",c.slot.org));
    assertEquals(1,count("fin_calc.calc_outbox_event",c.slot.org));
  }

  @ParameterizedTest
  @EnumSource(value=UatF02FactSetCommitter.FaultPoint.class,
      names={"AFTER_HEADER","AFTER_SECOND_OBSERVATION","BEFORE_OUTBOX"})
  void qf18InjectedFailureRollsBackEverything(UatF02FactSetCommitter.FaultPoint fault){
    var c=fresh();
    assertEquals("FACT1B_FORCED_ROLLBACK",
        assertThrows(IllegalStateException.class,()->
            committer.commitWithFault(c.candidate,c.descriptor,c.approval,
                c.principal,c.user,fault)).getMessage());
    assertEquals(0,count("fin_fact.fact_set",c.slot.org));
    assertEquals(0,count("fin_fact.factset_qualification_receipt",c.slot.org));
    assertEquals(0,count("fin_calc.calc_outbox_event",c.slot.org));
    assertEquals(0,count("fin_fact.factset_control",c.slot.org));
  }

  @Test void qf07ForeignUserOrProjectCannotCommit(){
    var c=fresh();
    assertEquals("FACT1B_ADMISSION_MISMATCH",
        assertThrows(IllegalArgumentException.class,()->
            committer.commitExact(c.candidate,c.descriptor,c.approval,
                c.principal,UUID.randomUUID())).getMessage());
    var foreign=new TrustedRequestPrincipal(c.user.toString(),
        UatF02FactSetCommitter.PROVIDER,new FactScope(UUID.randomUUID(),
          c.candidate.scope().economicEntityId(),
          c.candidate.scope().businessScopeId(),c.slot.project,
          c.candidate.scope().datasetCode(),c.candidate.scope().dataSpace(),
          c.candidate.scope().coverage(),c.candidate.scope().reportingBasis(),
          c.candidate.scope().currencyCode(),c.candidate.scope().periodType(),
          c.candidate.scope().periodCode(),c.candidate.scope().periodStart(),
          c.candidate.scope().periodEndExclusive()),
        UUID.randomUUID().toString(),Set.of(TrustedRequestPrincipal.QUALIFY_ACTION));
    assertThrows(IllegalArgumentException.class,()->
        committer.commitExact(c.candidate,c.descriptor,c.approval,foreign,c.user));
    assertEquals(0,count("fin_fact.fact_set",c.slot.org));
  }

  @Test void qf19MissingMonthCannotCreatePartitionOrFactSet(){
    var c=fresh();
    var nonexistent=YearMonth.of(2026,4);
    assertFalse(partitions.inspectRequiredCoverage(nonexistent,nonexistent).complete());
    // A different historical source month would require its own exact projection.
    assertEquals(0,count("fin_fact.fact_set",c.slot.org));
  }

  @Test void qf24NormalRuntimeIsDeniedWithoutUatExplicitFlag(){
    var c=fresh();
    var denied=new UatF02FactSetCommitter(
        sql,new org.springframework.jdbc.datasource.DataSourceTransactionManager(
            java.util.Objects.requireNonNull(sql.getDataSource())),
        null,null,null,null,null,null,false);
    assertEquals("FACT1B_UAT_DISABLED",
        assertThrows(IllegalStateException.class,()->
            denied.commitExact(c.candidate,c.descriptor,c.approval,c.principal,c.user))
            .getMessage());
    assertEquals(0,count("fin_fact.fact_set",c.slot.org));
  }
}
