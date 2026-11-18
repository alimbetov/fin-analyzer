package kz.fin.formlib;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.formlib.service.FormDraftService;
import kz.fin.formlib.bootstrap.CatalogCategoryBootstrap;
import org.springframework.boot.DefaultApplicationArguments;
import org.junit.jupiter.api.Test;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.mock.web.MockMultipartFile;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Opt-in PostgreSQL qualification for the real React REST and fin_intake
 * persistence. Never runs against the developer's persistent local database.
 */
@Testcontainers
@EnabledIfSystemProperty(named="fin.postgres.uat.enabled",matches="true")
@SpringBootTest(properties="fin.uat.editor.enabled=false")
@AutoConfigureMockMvc
class ReactLocalPostgresIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> db=new PostgreSQLContainer<>("postgres:16-alpine")
    .withDatabaseName("fin_form_qualification")
    .withUsername("fin").withPassword("pg_local_qualification");

  @DynamicPropertySource
  static void dataSource(DynamicPropertyRegistry properties){
    properties.add("spring.datasource.url",db::getJdbcUrl);
    properties.add("spring.datasource.username",db::getUsername);
    properties.add("spring.datasource.password",db::getPassword);
    properties.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired CatalogCategoryBootstrap categoryBootstrap;

  @Test
  void categorySeedIsAtomicCompleteAndIdempotentOnPostgresql() {
    assertEquals(47,jdbc.queryForObject(
      "SELECT count(*) FROM fin_lib.lib_catalog_category",Integer.class));
    assertEquals(188,jdbc.queryForObject(
      "SELECT count(*) FROM fin_lib.lib_catalog_category_translation",Integer.class));
    assertEquals(389,jdbc.queryForObject(
      "SELECT count(*) FROM fin_lib.lib_catalog_category_form",Integer.class));
    assertEquals(7,jdbc.queryForObject(
      "SELECT count(*) FROM fin_lib.lib_catalog_category WHERE category_type='ROOT'",
      Integer.class));
    assertEquals(40,jdbc.queryForObject(
      "SELECT count(*) FROM fin_lib.lib_catalog_category WHERE parent_code IS NOT NULL",
      Integer.class));
    assertEquals(166,jdbc.queryForObject("""
      SELECT count(DISTINCT f.form_code)
      FROM fin_lib.lib_catalog_category_form a
      JOIN fin_lib.lib_form_definition f ON f.id=a.form_id
      WHERE a.association_role='PRIMARY'
      """,Integer.class));
    for (String locale : List.of("ru","kk","en","zh-Hans")) {
      assertEquals(47,jdbc.queryForObject("""
        SELECT count(*) FROM fin_lib.lib_catalog_category_translation
        WHERE locale=?
        """,Integer.class,locale));
    }
    assertEquals(1,jdbc.queryForObject(
      "SELECT count(*) FROM fin_lib.lib_catalog_seed_manifest",Integer.class));
    String sha=jdbc.queryForObject("""
      SELECT source_sha256 FROM fin_lib.lib_catalog_seed_manifest
      WHERE catalog_code='FIN_CATEGORY_R14_V1'
      """,String.class);
    assertNotNull(sha);
    assertTrue(sha.matches("[0-9a-f]{64}"));

    // Same seed may be invoked repeatedly without a second manifest or
    // duplicate translation/link rows. This catches JDBC timestamp binding.
    categoryBootstrap.run(new DefaultApplicationArguments(new String[0]));
    assertEquals(1,jdbc.queryForObject(
      "SELECT count(*) FROM fin_lib.lib_catalog_seed_manifest",Integer.class));
    assertEquals(188,jdbc.queryForObject(
      "SELECT count(*) FROM fin_lib.lib_catalog_category_translation",Integer.class));
    assertEquals(389,jdbc.queryForObject(
      "SELECT count(*) FROM fin_lib.lib_catalog_category_form",Integer.class));
    assertEquals(sha,jdbc.queryForObject("""
      SELECT source_sha256 FROM fin_lib.lib_catalog_seed_manifest
      WHERE catalog_code='FIN_CATEGORY_R14_V1'
      """,String.class));
  }

  @Autowired FormDraftService draftService;

  @Test
  void concurrentFirstSaveCreatesExactlyOneSlotAndReturnsRecoverableConflict()
      throws Exception {
    UUID org=UUID.randomUUID(),entity=UUID.randomUUID(),scope=UUID.randomUUID(),
      project=UUID.randomUUID();
    var command=new FormDraftService.DraftRequest(null,org,entity,scope,project,
      "F02","M","2026-11","KZT","PERIOD_ONLY","MANAGEMENT","SYNTHETIC",
      0,Map.of("netRevenue","120","cogs","30","operatingExpenses","5"),
      List.of(),"pg-r3","initial concurrent");
    byte[] payload=json.writeValueAsBytes(command);
    var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
    var ready=new java.util.concurrent.CountDownLatch(2);
    var fire=new java.util.concurrent.CountDownLatch(1);
    try {
      List<java.util.concurrent.Future<Integer>> outcomes=new java.util.ArrayList<>();
      for(int i=0;i<2;i++){
        final int n=i;
        outcomes.add(pool.submit(()->{
          ready.countDown();
          if(!fire.await(10,java.util.concurrent.TimeUnit.SECONDS))
            throw new IllegalStateException("START_BARRIER_TIMEOUT");
          return mvc.perform(post("/api/v1/drafts")
              .header("Idempotency-Key","pg-r3-first-"+org+"-"+n)
              .contentType(MediaType.APPLICATION_JSON).content(payload))
            .andReturn().getResponse().getStatus();
        }));
      }
      assertTrue(ready.await(10,java.util.concurrent.TimeUnit.SECONDS));
      fire.countDown();
      List<Integer> statuses=new java.util.ArrayList<>();
      for(var f:outcomes)statuses.add(f.get(30,java.util.concurrent.TimeUnit.SECONDS));
      assertEquals(1,statuses.stream().filter(v->v==200).count(),statuses.toString());
      assertEquals(1,statuses.stream().filter(v->v==409).count(),statuses.toString());
    } finally {
      fire.countDown();
      pool.shutdownNow();
    }
    assertEquals(1,jdbc.queryForObject(
      "SELECT count(*) FROM fin_intake.lib_form_instance WHERE organization_id=?",
      Integer.class,org));
    assertEquals(1,jdbc.queryForObject("""
      SELECT count(*) FROM fin_intake.lib_form_revision r
      JOIN fin_intake.lib_form_instance i ON i.id=r.instance_id
      WHERE i.organization_id=?
      """,Integer.class,org));
    assertEquals(1,jdbc.queryForObject(
      "SELECT count(*) FROM fin_intake.lib_form_command_receipt WHERE organization_id=?",
      Integer.class,org));
  }

  @Test
  void competingWritesProduceOneImmutableRevisionAndOneConflict() throws Exception {
    UUID org=UUID.randomUUID(),entity=UUID.randomUUID(),scope=UUID.randomUUID(),
      project=UUID.randomUUID();
    var initial=new FormDraftService.DraftRequest(null,org,entity,scope,project,
      "F02","M","2026-11","KZT","PERIOD_ONLY","MANAGEMENT","SYNTHETIC",
      0,Map.of("netRevenue","100","cogs","20","operatingExpenses","5"),
      List.of(),"pg-r3","initial");
    var first=draftService.save(initial,"r3-create-"+org);
    UUID id=first.instanceId();
    assertEquals(1,first.revisionNo());
    var unchanged=new FormDraftService.DraftRequest(id,org,entity,scope,project,
      "F02","M","2026-11","KZT","PERIOD_ONLY","MANAGEMENT","SYNTHETIC",
      1,initial.values(),List.of(),"pg-r3","no change");
    mvc.perform(post("/api/v1/drafts")
        .header("Idempotency-Key","r3-nochange-"+org)
        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(unchanged)))
      .andExpect(status().isUnprocessableEntity())
      .andExpect(jsonPath("$.code").value("NO_FINANCIAL_CHANGES"));
    assertEquals(1,jdbc.queryForObject(
      "SELECT count(*) FROM fin_intake.lib_form_revision WHERE instance_id=?",
      Integer.class,id));

    var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
    var ready=new java.util.concurrent.CountDownLatch(2);
    var fire=new java.util.concurrent.CountDownLatch(1);
    try {
      List<java.util.concurrent.Future<String>> outcomes=new java.util.ArrayList<>();
      for(int k=0;k<2;k++){
        final int n=k;
        outcomes.add(executor.submit(()->{
          var next=new FormDraftService.DraftRequest(id,org,entity,scope,project,
            "F02","M","2026-11","KZT","PERIOD_ONLY","MANAGEMENT","SYNTHETIC",
            1,Map.of("netRevenue",n==0?"200":"300","cogs","20",
              "operatingExpenses","5"),List.of(),"pg-r3","competing");
          ready.countDown();
          if(!fire.await(10,java.util.concurrent.TimeUnit.SECONDS))
            throw new IllegalStateException("START_BARRIER_TIMEOUT");
          try {
            var result=draftService.save(next,"r3-concurrent-"+org+"-"+n);
            return "SAVED:"+result.revisionNo();
          } catch(org.springframework.web.server.ResponseStatusException e) {
            return "REJECTED:"+e.getReason();
          } catch(org.springframework.dao.DataIntegrityViolationException|
                  org.springframework.orm.ObjectOptimisticLockingFailureException e) {
            return "REJECTED:CONFLICT";
          }
        }));
      }
      assertTrue(ready.await(10,java.util.concurrent.TimeUnit.SECONDS));
      fire.countDown();
      List<String> result=new java.util.ArrayList<>();
      for(var f:outcomes)result.add(f.get(30,java.util.concurrent.TimeUnit.SECONDS));
      assertEquals(1,result.stream().filter(v->v.equals("SAVED:2")).count(),result.toString());
      assertEquals(1,result.stream().filter(v->v.startsWith("REJECTED:")).count(),result.toString());
    } finally {
      fire.countDown();
      executor.shutdownNow();
    }
    assertEquals(2,jdbc.queryForObject(
      "SELECT count(*) FROM fin_intake.lib_form_revision WHERE instance_id=?",
      Integer.class,id));
    assertEquals(2,jdbc.queryForObject(
      "SELECT latest_revision_no FROM fin_intake.lib_form_instance WHERE id=?",
      Integer.class,id));
    assertEquals(2,draftService.read(id,org).revisionNo());
    assertEquals("100",draftService.readRevision(id,org,1).values().get("netRevenue"));
    assertEquals(2,jdbc.queryForObject(
      "SELECT count(*) FROM fin_intake.lib_form_command_receipt WHERE organization_id=?",
      Integer.class,org));
  }

  @Test
  void postgresLiquibaseCatalogAndImmutableFinancialRevisions() throws Exception {
    assertEquals("fin_form_qualification",
      jdbc.queryForObject("select current_database()",String.class));
    assertEquals(166,jdbc.queryForObject(
      "select count(*) from fin_lib.lib_form_definition",Integer.class));
    assertEquals(47,jdbc.queryForObject(
      "select count(*) from fin_lib.lib_catalog_category",Integer.class));
    JsonNode categories=json.readTree(mvc.perform(get("/api/v1/catalog/category-tree")
      .param("locale","ru")).andExpect(status().isOk()).andReturn()
      .getResponse().getContentAsByteArray());
    assertEquals(389,categories.path("links").size());

    UUID org=UUID.randomUUID(),entity=UUID.randomUUID(),
      business=UUID.randomUUID(),project=UUID.randomUUID();
    var first=new FormDraftService.DraftRequest(null,org,entity,business,project,
      "F02","M","2026-11","KZT","PERIOD_ONLY","MANAGEMENT","SYNTHETIC",
      0,Map.of("netRevenue","125000.50","cogs","70000",
        "operatingExpenses","1000"),List.of(),"react-pg-uat","initial");
    JsonNode receipt1=json.readTree(mvc.perform(post("/api/v1/drafts")
      .header("Idempotency-Key","pg-first-"+org)
      .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(first)))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    UUID instance=UUID.fromString(receipt1.path("instanceId").asText());
    assertEquals(1,receipt1.path("revisionNo").asInt());

    var second=new FormDraftService.DraftRequest(instance,org,entity,business,project,
      "F02","M","2026-11","KZT","PERIOD_ONLY","MANAGEMENT","SYNTHETIC",
      1,Map.of("netRevenue","130000.50","cogs","70000",
        "operatingExpenses","1000"),List.of(),"react-pg-uat","changed");
    JsonNode receipt2=json.readTree(mvc.perform(post("/api/v1/drafts")
      .header("Idempotency-Key","pg-second-"+org)
      .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(second)))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    assertEquals(2,receipt2.path("revisionNo").asInt());
    assertNotEquals(receipt1.path("contentSha256").asText(),
      receipt2.path("contentSha256").asText());
    assertEquals(2,jdbc.queryForObject("""
      SELECT count(*) FROM fin_intake.lib_form_revision WHERE instance_id=?
      """,Integer.class,instance));

    JsonNode old=json.readTree(mvc.perform(get("/api/v1/drafts/"+instance+
      "/revisions/1").param("organizationId",org.toString()))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    JsonNode current=json.readTree(mvc.perform(get("/api/v1/drafts/"+instance)
      .param("organizationId",org.toString()))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    assertEquals("125000.5",old.path("values").path("netRevenue").asText());
    assertEquals("130000.5",current.path("values").path("netRevenue").asText());
    assertEquals(receipt2.path("contentSha256").asText(),current.path("sha256").asText());
    // React must be able to reconstruct the exact pinned slot from REST,
    // not invent fresh scope UUIDs when reopening a report created via MVC.
    assertEquals(org.toString(),current.path("organizationId").asText());
    assertEquals(entity.toString(),current.path("economicEntityId").asText());
    assertEquals(business.toString(),current.path("businessScopeId").asText());
    assertEquals(project.toString(),current.path("projectId").asText());
    // Cross-tenant reads must not reveal even historical financial data.
    UUID otherOrg=UUID.randomUUID();
    mvc.perform(get("/api/v1/drafts/"+instance)
      .param("organizationId",otherOrg.toString()))
      .andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/drafts/"+instance+"/revisions/1")
      .param("organizationId",otherOrg.toString()))
      .andExpect(status().isNotFound());
    // Invalid revision must never fall back to the current one.
    mvc.perform(get("/api/v1/drafts/"+instance+"/revisions/99")
      .param("organizationId",org.toString()))
      .andExpect(status().isNotFound());

    assertEquals("PERIOD_ONLY",current.path("coverage").asText());
    assertEquals("MANAGEMENT",current.path("reportingBasis").asText());
    assertEquals("SYNTHETIC",current.path("dataSpace").asText());

    mvc.perform(post("/api/v1/drafts")
      .header("Idempotency-Key","pg-stale-"+org)
      .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(second)))
      .andExpect(status().isConflict());
    assertEquals(2,jdbc.queryForObject(
      "SELECT count(*) FROM fin_intake.lib_form_revision WHERE instance_id=?",
      Integer.class,instance));

    // Full React XLSX round-trip on the same PostgreSQL instance.
    byte[] exported=mvc.perform(get("/api/v1/drafts/"+instance+"/export.xlsx")
      .param("organizationId",org.toString()).param("styleCode","EXCEL")
      .param("locale","ru")).andExpect(status().isOk()).andReturn()
      .getResponse().getContentAsByteArray();
    byte[] edited;
    try(var workbook=WorkbookFactory.create(new ByteArrayInputStream(exported));
        var out=new ByteArrayOutputStream()){
      workbook.getSheet("REPORT").getRow(4).getCell(3).setCellValue("140000.50");
      workbook.write(out);
      edited=out.toByteArray();
    }
    JsonNode version=json.readTree(mvc.perform(get("/api/v1/uat-helper/drafts/"+instance+
      "/version").param("organizationId",org.toString()))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    JsonNode profile=json.readTree(mvc.perform(post("/api/v1/import-profiles")
      .contentType(MediaType.APPLICATION_JSON)
      .content(json.writeValueAsBytes(Map.of(
        "formVersionId",version.path("formVersionId").asText()))))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    var upload=new MockMultipartFile("file","fin-form.xlsx",
      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",edited);
    String purl="/api/v1/import-profiles/"+profile.path("id").asText()+"/preview";
    JsonNode preview=json.readTree(mvc.perform(multipart(purl)
      .file(upload).param("instanceId",instance.toString())
      .param("organizationId",org.toString())
      .param("actor","postgres-uat").param("reason","preview-only"))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    assertEquals(2,preview.path("expectedRevision").asInt());
    assertEquals(2,jdbc.queryForObject(
      "SELECT count(*) FROM fin_intake.lib_form_revision WHERE instance_id=?",
      Integer.class,instance),"Preview must not persist a financial revision");

    JsonNode imported=json.readTree(mvc.perform(multipart(
      "/api/v1/import-profiles/previews/"+preview.path("previewId").asText()+"/commit")
      .file(upload).param("instanceId",instance.toString())
      .param("organizationId",org.toString())
      .param("actor","postgres-uat").param("reason","preview-only")
      .header("Idempotency-Key","pg-xlsx-commit-"+org))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    assertEquals(3,imported.path("revisionNo").asInt());
    assertEquals(3,jdbc.queryForObject(
      "SELECT count(*) FROM fin_intake.lib_form_revision WHERE instance_id=?",
      Integer.class,instance));
    JsonNode after=json.readTree(mvc.perform(get("/api/v1/drafts/"+instance)
      .param("organizationId",org.toString()))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    assertEquals("140000.5",after.path("values").path("netRevenue").asText());
    assertEquals(imported.path("contentSha256").asText(),after.path("sha256").asText());
    // The original immutable revision must still be accessible after import.
    mvc.perform(get("/api/v1/drafts/"+instance+"/revisions/1")
      .param("organizationId",org.toString())).andExpect(status().isOk());
    mvc.perform(multipart(
      "/api/v1/import-profiles/previews/"+preview.path("previewId").asText()+"/commit")
      .file(upload).param("instanceId",instance.toString())
      .param("organizationId",org.toString())
      .param("actor","postgres-uat").param("reason","preview-only")
      .header("Idempotency-Key","pg-xlsx-commit-replay-"+org))
      .andExpect(status().isConflict());
  }
}
