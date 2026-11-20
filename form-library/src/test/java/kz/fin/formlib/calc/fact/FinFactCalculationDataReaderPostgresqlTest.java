package kz.fin.formlib.calc.fact;

import kz.fin.formlib.calc.ingest.FormFactCandidateService;
import kz.fin.formlib.calc.partition.FactMonthlyPartitionService;
import kz.fin.formlib.service.FormDraftService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * FIN-DATA-R0.1: PostgreSQL16 origin-user/project fact retrieval and real
 * servlet security filters. jwt() builds a test authenticated principal;
 * signature/JWKS verification remains a separate IAM integration gate.
 */
@SpringBootTest(properties={
  "fin.uat.editor.enabled=false",
  "fin.fact.uat-committer.enabled=true",
  "fin.fact.data-reader.enabled=true",
  "fin.fact.data-reader.issuer=https://iam.fin-uat.example",
  "fin.fact.data-reader.jwk-set-uri=https://iam.fin-uat.example/jwks",
  "fin.fact.data-reader.audience=fin-fact-calculation-data/v1"
})
@AutoConfigureMockMvc
@Testcontainers
@EnabledIfSystemProperty(named="fin.postgres.uat.enabled",matches="true")
class FinFactCalculationDataReaderPostgresqlTest {
  @Container static final PostgreSQLContainer<?> pg=
    new PostgreSQLContainer<>("postgres:16-alpine")
      .withDatabaseName("fin_data_r01")
      .withUsername("fin").withPassword("uat_test_only");
  @DynamicPropertySource static void config(DynamicPropertyRegistry r){
    r.add("spring.datasource.url",pg::getJdbcUrl);
    r.add("spring.datasource.username",pg::getUsername);
    r.add("spring.datasource.password",pg::getPassword);
    r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
  }

  @Autowired FormDraftService drafts;
  @Autowired FormFactCandidateService sources;
  @Autowired F02SourceFactAssembler assembler;
  @Autowired UatF02FactSetCommitter committer;
  @Autowired FactMonthlyPartitionService partition;
  @Autowired FinFactCalculationDataReader reader;
  @Autowired JdbcTemplate db;
  @Autowired MockMvc mvc;

  record Scope(UUID org,UUID entity,UUID business,UUID project){
    static Scope fresh(){return new Scope(UUID.randomUUID(),UUID.randomUUID(),
      UUID.randomUUID(),UUID.randomUUID());}
  }
  record Saved(Scope scope,UUID user,FormDraftService.DraftReceipt draft,
      FactSetCommitReceipt receipt) {}
  private static String endpoint(UUID user,UUID project){
    return "/internal/fin-fact/data/resolve?user_id="+user+"&project_id="+project;
  }
  private RequestPostProcessor bearer(UUID subject,boolean role,boolean audience){
    return jwt().jwt(b->b.issuer("https://iam.fin-uat.example")
      .subject(subject.toString())
      .audience(audience?"fin-fact-calculation-data/v1":"other-audience")
      .expiresAt(Instant.now().plusSeconds(300))
      .claim("realm_access",Map.of("roles",
        role?List.of("FIN_FACT_READ"):List.of("FIN_NO_FACT_PERMISSION"))));
  }
  private void month(){
    partition.ensureMonthlyRange(YearMonth.of(2026,11),YearMonth.of(2026,11),false);
  }
  private FormDraftService.DraftReceipt draft(Scope scope,UUID instance,int expected,
      String opex){
    return drafts.save(new FormDraftService.DraftRequest(instance,scope.org(),
      scope.entity(),scope.business(),scope.project(),"F02","M","2026-11",
      "KZT","PERIOD_ONLY","MANAGEMENT","SYNTHETIC",expected,
      Map.of("netRevenue","12500000","cogs","7400000",
        "operatingExpenses",opex),
      List.of(),"fin-data-r01-uat","F02 test-only data export"),
      "fin-data-draft-"+UUID.randomUUID());
  }
  private Saved commit(Scope s,UUID user,UUID instance,int revision,
      String opex,long epoch){
    month();
    var saved=draft(s,instance,revision,opex);
    var verified=sources.extractExact(saved.revisionId(),s.org(),s.project());
    var descriptor=F02MappingDescriptor.testOnlyPilot(
      verified.formVersionId(),verified.sourceSchemaSha256(),
      verified.sourceMappingSha256());
    var candidate=assembler.assemble(verified,descriptor);
    var permit=TestOnlyF02Approval.create(candidate,user,epoch);
    var result=committer.commitExact(candidate,descriptor,
      permit.verified(),permit.principal(),user);
    return new Saved(s,user,saved,result);
  }
  private static FinFactCalculationDataReader.Query query(
      UUID user,UUID project,UUID factSetId,int limit,String cursor){
    return new FinFactCalculationDataReader.Query(
      user,project,factSetId,limit,cursor);
  }

  @Test void exactAuthorizedUserAndProjectReturnThreeVerifiedNumericFacts() throws Exception{
    var s=Scope.fresh();UUID user=UUID.randomUUID();
    var c=commit(s,user,null,0,"2100000",1);
    var response=reader.read(user,query(user,s.project(),null,25,null));
    assertEquals(FinFactCalculationDataReader.DATA_STATUS,response.status());
    assertFalse(response.financiallyApproved());
    assertFalse(response.productionAdmitted());
    assertEquals(1,response.factSets().size());
    var f=response.factSets().getFirst();
    assertEquals(c.receipt.factSetId(),f.factSetId());
    assertEquals(c.receipt.factsetSha256(),f.factSetSha256());
    assertEquals(s.project(),f.projectId());
    assertEquals(user,f.initiatedByUserId());
    assertEquals(c.draft.revisionId(),f.exactSourceRevisionId());
    assertEquals(3,f.observations().size());
    assertEquals(List.of("F02.cogs","F02.netRevenue","F02.operatingExpenses"),
      f.observations().stream().map(FinFactCalculationDataReader.Observation::metricCode).toList());
    assertEquals("12500000",f.observations().get(1).numericValue());
    assertEquals("INTERVAL",f.observations().get(1).periodSemantics());
    assertTrue(f.observations().stream().allMatch(x->x.valueSha256().length()==64));

    mvc.perform(get(endpoint(user,s.project())).with(bearer(user,true,true)))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.factSets.length()").value(1))
      .andExpect(jsonPath("$.factSets[0].observations.length()").value(3))
      .andExpect(jsonPath("$.factSets[0].observations[1].numericValue").value("12500000"))
      .andExpect(jsonPath("$.financiallyApproved").value(false))
      .andExpect(jsonPath("$.productionAdmitted").value(false));
  }

  @Test void unauthenticatedWrongRoleAndWrongAudienceNeverReachFacts() throws Exception{
    var s=Scope.fresh();UUID user=UUID.randomUUID();
    commit(s,user,null,0,"2100000",1);
    mvc.perform(get(endpoint(user,s.project())))
      .andExpect(status().isUnauthorized());
    mvc.perform(get(endpoint(user,s.project())).with(bearer(user,false,true)))
      .andExpect(status().isForbidden());
    mvc.perform(get(endpoint(user,s.project())).with(bearer(user,true,false)))
      .andExpect(status().isForbidden());
  }

  @Test void clientCannotSwapUserIdOrReadAnotherUsersFactset() throws Exception{
    var s=Scope.fresh();UUID owner=UUID.randomUUID(),outsider=UUID.randomUUID();
    commit(s,owner,null,0,"2100000",1);
    mvc.perform(get(endpoint(owner,s.project())).with(bearer(outsider,true,true)))
      .andExpect(status().isForbidden());
    mvc.perform(get(endpoint(outsider,s.project())).with(bearer(outsider,true,true)))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.factSets.length()").value(0));
    assertEquals(403,assertThrows(ResponseStatusException.class,()->
      reader.read(outsider,query(owner,s.project(),null,10,null)))
        .getStatusCode().value());
  }

  @Test void otherProjectOrFactsetIdIsNotDiscoverable() throws Exception {
    var s=Scope.fresh();UUID owner=UUID.randomUUID();
    var c=commit(s,owner,null,0,"2100000",1);
    UUID otherProject=UUID.randomUUID();
    mvc.perform(get(endpoint(owner,otherProject)).with(bearer(owner,true,true)))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.factSets.length()").value(0));
    mvc.perform(get(endpoint(owner,otherProject)+"&fact_set_id="+
        c.receipt.factSetId()).with(bearer(owner,true,true)))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.factSets.length()").value(0));
    mvc.perform(get(endpoint(owner,s.project())+"&fact_set_id="+UUID.randomUUID())
        .with(bearer(owner,true,true)))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.factSets.length()").value(0));
  }

  @Test void uncommittedIntakeDraftIsNotPromotedToFinancialFact() throws Exception{
    var s=Scope.fresh();UUID user=UUID.randomUUID();
    draft(s,null,0,"2100000");
    var response=reader.read(user,query(user,s.project(),null,25,null));
    assertTrue(response.factSets().isEmpty());
    mvc.perform(get(endpoint(user,s.project())).with(bearer(user,true,true)))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.factSets.length()").value(0));
  }

  @Test void revokedOrReplacedFactsetIsNeverReturnedAsActive(){
    var s=Scope.fresh();UUID user=UUID.randomUUID();
    var old=commit(s,user,null,0,"2100000",1);
    var next=commit(s,user,old.draft.instanceId(),1,"2200000",2);
    var active=reader.read(user,query(user,s.project(),null,25,null));
    assertEquals(1,active.factSets().size());
    assertEquals(next.receipt.factSetId(),active.factSets().getFirst().factSetId());
    assertTrue(reader.read(user,query(user,s.project(),
      old.receipt.factSetId(),25,null)).factSets().isEmpty());
    db.update("""
      UPDATE fin_fact.factset_control
      SET revoked=true,row_version=row_version+1
      WHERE stable_scope_key=? AND organization_id=?
      """,next.receipt.stableScopeKey(),s.org());
    assertTrue(reader.read(user,query(user,s.project(),null,25,null))
      .factSets().isEmpty());
  }

  @Test void stableKeysetPaginationDoesNotDuplicateOrLeakAcrossScopes(){
    UUID user=UUID.randomUUID(),project=UUID.randomUUID();
    var a=Scope.fresh();a=new Scope(a.org(),a.entity(),a.business(),project);
    var b=Scope.fresh();b=new Scope(b.org(),b.entity(),b.business(),project);
    var one=commit(a,user,null,0,"2100000",1);
    var two=commit(b,user,null,0,"2200000",1);
    var first=reader.read(user,query(user,project,null,1,null));
    assertEquals(1,first.factSets().size());
    assertNotNull(first.nextPageToken());
    var second=reader.read(user,query(user,project,null,1,first.nextPageToken()));
    assertEquals(1,second.factSets().size());
    assertNull(second.nextPageToken());
    assertNotEquals(first.factSets().getFirst().factSetId(),
      second.factSets().getFirst().factSetId());
    assertEquals(Set.of(one.receipt.factSetId(),two.receipt.factSetId()),
      Set.of(first.factSets().getFirst().factSetId(),
        second.factSets().getFirst().factSetId()));
    assertEquals(422,assertThrows(ResponseStatusException.class,()->
      reader.read(user,query(user,project,null,101,null)))
      .getStatusCode().value());
    assertEquals(422,assertThrows(ResponseStatusException.class,()->
      reader.read(user,query(user,project,null,1,"not-a-cursor")))
      .getStatusCode().value());
  }

  @Test void sourceAndStoredObservationsMustMatchExactShaNotJustCount(){
    var s=Scope.fresh();UUID user=UUID.randomUUID();
    var saved=commit(s,user,null,0,"2100000",1);
    // Immutability protects normal UPDATE/DELETE.
    assertThrows(DataAccessException.class,()->db.update("""
      UPDATE fin_fact.fact_observation SET numeric_value=999
      WHERE factset_id=?
      """,saved.receipt.factSetId()));
    // A privileged direct INSERT into a partition is still detected on read.
    db.update("""
      INSERT INTO fin_fact.fact_observation(
        period_start,observation_id,factset_id,source_revision_id,
        source_form_code,source_form_version_id,source_row_key,
        source_field_code,source_content_sha256,mapping_sha256,metric_code,
        metric_contract_version,period_type,period_end_exclusive,
        period_semantics,dimension_digest,dimensions_jsonb,
        observation_key_sha256,value_kind,value_state,numeric_value,
        unit_code,currency_code,canonical_value_sha256)
      SELECT period_start,?,factset_id,source_revision_id,
        source_form_code,source_form_version_id,source_row_key,
        source_field_code,source_content_sha256,mapping_sha256,metric_code,
        metric_contract_version,period_type,period_end_exclusive,
        period_semantics,dimension_digest,dimensions_jsonb,
        ?,'NUMERIC','PRESENT',numeric_value,
        unit_code,currency_code,canonical_value_sha256
      FROM fin_fact.fact_observation
      WHERE factset_id=? AND source_field_code='netRevenue'
      """,UUID.randomUUID(),"f".repeat(64),saved.receipt.factSetId());
    assertThrows(IllegalStateException.class,()->
      reader.read(user,query(user,s.project(),null,25,null)));
  }

  @Test void explicitFactsetAndCursorCannotBeMixed(){
    var s=Scope.fresh();UUID user=UUID.randomUUID();
    var saved=commit(s,user,null,0,"2100000",1);
    assertEquals(422,assertThrows(ResponseStatusException.class,()->
      reader.read(user,query(user,s.project(),saved.receipt.factSetId(),
        25,"some-token"))).getStatusCode().value());
  }
}
