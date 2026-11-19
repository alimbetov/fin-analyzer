package kz.fin.formlib.calc.partition;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real PostgreSQL 16 UPGRADE rehearsal, not merely a clean-db install:
 * apply immutable 001-018, insert a historical revision, then run 001-021
 * against the SAME database and verify unchanged Liquibase checksums/data.
 */
@Testcontainers
@EnabledIfSystemProperty(named="fin.postgres.uat.enabled",matches="true")
class FactR0PostgresqlUpgradeTest {
  @Container
  static final PostgreSQLContainer<?> db=new PostgreSQLContainer<>("postgres:16-alpine")
      .withDatabaseName("fin_fact_upgrade_018_to_021")
      .withUsername("fin").withPassword("test_only_upgrade");

  private static void migrate(String changeLog) throws Exception {
    try(var connection=DriverManager.getConnection(
        db.getJdbcUrl(),db.getUsername(),db.getPassword())) {
      var database=DatabaseFactory.getInstance().findCorrectDatabaseImplementation(
          new JdbcConnection(connection));
      var runner=new Liquibase(changeLog,new ClassLoaderResourceAccessor(),database);
      runner.update(new Contexts(),new LabelExpression());
    }
  }

  @Test void upgradeFromExisting18PreservesSourceRowsAndChecksums() throws Exception {
    var jdbc=new JdbcTemplate(new DriverManagerDataSource(
        db.getJdbcUrl(),db.getUsername(),db.getPassword()));
    migrate("db/fact0-018-baseline.yaml");
    String originalSql="""
        SELECT id,author,filename,md5sum FROM databasechangelog
        ORDER BY id,author,filename
        """;
    var originalChecksums=jdbc.queryForList(originalSql);
    assertFalse(originalChecksums.isEmpty());

    UUID formId=UUID.randomUUID(),versionId=UUID.randomUUID();
    UUID instanceId=UUID.randomUUID(),revisionId=UUID.randomUUID();
    UUID org=UUID.randomUUID(),entity=UUID.randomUUID();
    UUID business=UUID.randomUUID(),project=UUID.randomUUID();
    String contentSha="a".repeat(64),orderSha="b".repeat(64);
    String sourceJson="{\"netRevenue\":\"125\"}";

    jdbc.update("""
        INSERT INTO fin_lib.lib_form_definition
          (id,form_code,catalog,legacy_capture_mode,current_version_id)
        VALUES(?,'UPGRADE-F02','FINANCE','SINGLE_RECORD',NULL)
        """,formId);
    jdbc.update("""
        INSERT INTO fin_lib.lib_form_version
          (id,form_id,version_label,content_mode,grain,qualification_state,
           schema_sha256,presentation_sha256,mapping_sha256,created_at)
        VALUES(?,?,'TEST-V1','SINGLE_RECORD','MONTH','CANDIDATE_UNQUALIFIED',
               ?,?,?,now())
        """,versionId,formId,"c".repeat(64),"d".repeat(64),"e".repeat(64));
    jdbc.update("""
        UPDATE fin_lib.lib_form_definition SET current_version_id=? WHERE id=?
        """,versionId,formId);
    // Historical 002 catalog constraints are real FKs, even in an
    // isolated upgrade rehearsal: never bypass or disable them.
    jdbc.update("""
        INSERT INTO fin_lib.lib_currency(code,iso_numeric,minor_units,status)
        VALUES ('KZT','398',2,'ACTIVE')
        """);
    jdbc.update("""
        INSERT INTO fin_lib.lib_period_type(code,sort_order,iso_calendar,status)
        VALUES ('M',1,'GREGORIAN','ACTIVE')
        """);
    jdbc.update("""
        INSERT INTO fin_intake.lib_form_instance
          (id,organization_id,economic_entity_id,business_scope_id,project_id,
           form_id,period_type,period_code,coverage,currency,reporting_basis,
           data_space,current_revision_id,latest_revision_no,row_version)
        VALUES (?,?,?,?,?,?,'M','2026-10','PERIOD_ONLY','KZT','MANAGEMENT',
                'SYNTHETIC',NULL,1,0)
        """,instanceId,org,entity,business,project,formId);
    jdbc.update("""
        INSERT INTO fin_intake.lib_form_revision
          (id,instance_id,revision_no,form_version_id,content_mode,values_json,
           canonical_content_sha256,row_order_sha256,source_kind,created_at,
           created_by,change_reason)
        VALUES (?,?,1,?,'SINGLE_RECORD',?,?,?,'MANUAL',now(),
                'upgrade-fixture','preservation check')
        """,revisionId,instanceId,versionId,sourceJson,contentSha,orderSha);
    jdbc.update("UPDATE fin_intake.lib_form_instance SET current_revision_id=? WHERE id=?",
        revisionId,instanceId);

    var before=jdbc.queryForMap("""
        SELECT values_json,canonical_content_sha256,row_order_sha256,revision_no
        FROM fin_intake.lib_form_revision WHERE id=?
        """,revisionId);

    migrate("db/changelog/db.changelog-master.yaml");
    var checksumsAfter=jdbc.queryForList("""
        SELECT id,author,filename,md5sum FROM databasechangelog
        WHERE filename NOT LIKE '%019-%'
          AND filename NOT LIKE '%020-%'
          AND filename NOT LIKE '%021-%'
          AND filename NOT LIKE '%022-%'
          AND filename NOT LIKE '%023-%'
          AND filename NOT LIKE '%024-%'
          AND filename NOT LIKE '%025-%'
          AND filename NOT LIKE '%026-%'
        ORDER BY id,author,filename
        """);
    assertEquals(originalChecksums,checksumsAfter,
        "Upgrading must not rewrite 001-018 executed checksums");
    assertEquals(before,jdbc.queryForMap("""
        SELECT values_json,canonical_content_sha256,row_order_sha256,revision_no
        FROM fin_intake.lib_form_revision WHERE id=?
        """,revisionId));
    assertEquals(revisionId,jdbc.queryForObject("""
        SELECT current_revision_id FROM fin_intake.lib_form_instance WHERE id=?
        """,UUID.class,instanceId));
    assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM fin_fact.fact_set",
        Integer.class),"No automatic financial fact promotion during upgrade");
    assertEquals("RANGE (period_start)",jdbc.queryForObject("""
        SELECT pg_get_partkeydef('fin_fact.fact_observation'::regclass)
        """,String.class));
    assertThrows(DataAccessException.class,()->jdbc.update("""
        UPDATE fin_intake.lib_form_revision SET values_json='{}' WHERE id=?
        """,revisionId),"New immutable source guard protects historical 018 data");
  }
}
