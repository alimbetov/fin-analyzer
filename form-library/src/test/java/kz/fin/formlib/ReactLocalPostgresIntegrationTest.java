package kz.fin.formlib;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.formlib.service.FormDraftService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.DynamicPropertyRegistry;
import org.springframework.boot.test.context.DynamicPropertySource;
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
  }
}
