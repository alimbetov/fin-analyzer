package kz.fin.formlib.calc.readiness;

import kz.fin.formlib.calc.readiness.FormCalculationCoverageService.FormCoverage;
import kz.fin.formlib.calc.readiness.FormCalculationCoverageService.FieldState;
import kz.fin.formlib.calc.readiness.FormCalculationCoverageService.Readiness;
import kz.fin.formlib.calc.readiness.FormCalculationCoverageService.Snapshot;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * One real PG16 dynamic testcase PER current physical form (166).
 * The report is rebuilt from live fin_lib rows, never from guessed source
 * fields or 166 artificial calculations. No R7.B/S2 approvals are issued.
 */
@SpringBootTest(properties={"fin.uat.editor.enabled=false"})
@Testcontainers
@EnabledIfSystemProperty(named="fin.postgres.uat.enabled",matches="true")
class Calc166ReadinessPostgresqlTest {
  @Container
  static final PostgreSQLContainer<?> pg=new PostgreSQLContainer<>("postgres:16-alpine")
      .withDatabaseName("calc_166_readiness_test")
      .withUsername("fin").withPassword("synthetic_pg_test_only");

  @DynamicPropertySource
  static void db(DynamicPropertyRegistry r){
    r.add("spring.datasource.url",pg::getJdbcUrl);
    r.add("spring.datasource.username",pg::getUsername);
    r.add("spring.datasource.password",pg::getPassword);
    r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
  }

  @Autowired FormCalculationCoverageService coverage;

  @Test void r16a_01PhysicalInventoryFromActualPostgresNotTSV() throws Exception {
    Snapshot s=coverage.snapshot();
    assertEquals(166,s.forms().size());
    assertEquals(877,s.physicalFields());
    assertEquals(793,s.sourceFieldCandidates());
    assertEquals(84,s.evidenceFields());
    assertEquals(0,s.missingBindings());
    assertEquals(0,s.driftedBindings());
    assertEquals(1,s.testOnlyExecutableForms());
    assertEquals(0,s.financiallyQualifiedForms());
    assertEquals(64,s.ingestionManifestSha256().length());
    assertEquals(166,new HashSet<>(s.forms().stream().map(FormCoverage::formCode).toList()).size());
    assertEquals(3,s.forms().stream().filter(f->f.contentMode().equals("SINGLE_RECORD")).count());
    assertEquals(151,s.forms().stream().filter(f->f.contentMode().equals("ROWSET")).count());
    assertEquals(12,s.forms().stream().filter(f->f.contentMode().equals("EVIDENCE")).count());

    Path folder=Path.of("target/fin-calc166");
    Files.createDirectories(folder);
    StringBuilder forms=new StringBuilder(
        "form_code\tform_version_id\tcontent_mode\tschema_sha256\tversion_qualification\t"
        +"physical_fields\tsource_fields\tnumeric_candidates\tevidence_fields\tmissing\t"
        +"drift\tversioned_metric_bindings\treadiness\tblocker\tfinancial_qualified\n");
    StringBuilder fields=new StringBuilder(
        "form_code\tform_version_id\tfield_code\tfield_type\tmetric_code\tsemantic_role\t"
        +"review_state\tversioned_binding\tbinding_state\n");
    for(FormCoverage f:s.forms()){
      forms.append(String.join("\t",f.formCode(),f.formVersionId().toString(),
          f.contentMode(),f.schemaSha256(),f.versionQualification(),
          Integer.toString(f.physicalFields()),Integer.toString(f.sourceFieldCandidates()),
          Integer.toString(f.numericFieldCandidates()),Integer.toString(f.evidenceFields()),
          Integer.toString(f.missingBindings()),Integer.toString(f.driftedBindings()),
          Integer.toString(f.versionedMetricBindings()),
          f.readiness().name(),f.blocker(),Boolean.toString(f.financialQualified()))).append('\n');
      for(var field:f.fields()){
        fields.append(String.join("\t",field.formCode(),field.formVersionId().toString(),
            field.fieldCode(),field.fieldType(),text(field.metricCode()),
            text(field.semanticRole()),text(field.reviewState()),
            Boolean.toString(field.versionedBinding()),
            field.state().name())).append('\n');
      }
    }
    Files.writeString(folder.resolve("166-form-calculation-readiness.tsv"),
        forms.toString(),StandardCharsets.UTF_8);
    Files.writeString(folder.resolve("877-field-calculation-contracts.tsv"),
        fields.toString(),StandardCharsets.UTF_8);
    System.out.printf("[CALC-166] actual PG16 forms=%d fields=%d source=%d evidence=%d "
        +"financialApproved=%d uatF02=%d%n",s.forms().size(),
        s.physicalFields(),s.sourceFieldCandidates(),s.evidenceFields(),
        s.financiallyQualifiedForms(),s.testOnlyExecutableForms());
  }

  private static String text(String s){return s==null?"":s;}

  @Test void r16a_02OnlyPinnedF02UatCanPassAndNoFormGetsProductionS2() {
    var s=coverage.snapshot();
    var f02=s.require("F02");
    assertEquals(Readiness.UAT_F02_TEST_ONLY,f02.readiness());
    assertEquals(3,f02.physicalFields());
    assertEquals(3,f02.numericFieldCandidates());
    assertEquals(0,f02.missingBindings());
    assertEquals(0,f02.driftedBindings());
    assertEquals(f02.formVersionId(),
        coverage.requireExistingUatPilot("F02",f02.formVersionId()).formVersionId());
    assertThrows(IllegalStateException.class,()->
        coverage.requireExistingUatPilot("F01",s.require("F01").formVersionId()));
    assertThrows(IllegalStateException.class,()->
        coverage.requireExistingUatPilot("F03",s.require("F03").formVersionId()));
    assertThrows(IllegalStateException.class,()->
        coverage.requireExistingUatPilot("F02",UUID.randomUUID()));
    assertThrows(IllegalArgumentException.class,()->s.require("UNKNOWN-FORM"));
    assertTrue(s.forms().stream().noneMatch(FormCoverage::financialQualified));
  }

  @TestFactory Stream<DynamicTest> r16a_03EveryFormIndependentPg16ContractGate(){
    var s=coverage.snapshot();
    return s.forms().stream().map(f->DynamicTest.dynamicTest(
        "R16A_FORM_"+f.formCode(),()->{
          assertNotNull(f.formVersionId());
          assertEquals(64,f.schemaSha256().length());
          assertTrue(f.physicalFields()>0);
          assertEquals(f.physicalFields(),f.fields().size());
          assertEquals(0,f.missingBindings(),"Missing field mapping: "+f.formCode());
          assertEquals(0,f.driftedBindings(),"Schema/type/metric drift: "+f.formCode());
          assertTrue(f.fields().stream()
              .allMatch(x->x.state()==FieldState.CANDIDATE_UNQUALIFIED),
              "Draft candidate must not be treated as financially approved: "+f.formCode());
          assertFalse(f.financialQualified());
          if(f.formCode().equals("F02")){
            assertEquals(Readiness.UAT_F02_TEST_ONLY,f.readiness());
            assertEquals(3,f.physicalFields());
          }else if(f.contentMode().equals("EVIDENCE")){
            assertEquals(Readiness.EVIDENCE_SUPPORT_ONLY,f.readiness());
            assertEquals(0,f.sourceFieldCandidates());
            assertTrue(f.evidenceFields()>0);
            assertEquals(0,f.numericFieldCandidates());
          }else{
            assertEquals(Readiness.BLOCKED_FINANCIAL_REVIEW,f.readiness());
            assertFalse(f.testOnlyExecutable());
          }
        }));
  }
}
