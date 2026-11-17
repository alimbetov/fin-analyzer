package kz.fin.formlib;

import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.formlib.api.ReferenceCatalogController;
import kz.fin.formlib.repository.*;
import kz.fin.formlib.support.MockTemplateGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Production catalog -> test-only mock templates, with no facts/revisions created. */
@SpringBootTest
class MockTemplateCatalogIntegrationTest {
  @Autowired ReferenceCatalogController catalog;
  @Autowired FormDefinitionRepository forms;
  @Autowired FormVersionRepository versions;
  @Autowired FormInstanceRepository instances;
  @Autowired FormRevisionRepository formRevisions;
  @Autowired LibReportDefinitionRepository reportDefinitions;
  @Autowired LibReportRevisionRepository reportRevisions;
  @Autowired ObjectMapper mapper;

  private MockTemplateGenerator.MockTemplate create(String code,String locale,
      String axis,String periodType,List<String> periods,String scenario){
    var form=catalog.form(code,locale);
    return MockTemplateGenerator.generate(form,
      new MockTemplateGenerator.Scenario(scenario,locale,periodType,periods,axis));
  }
  private void writeFixture(String file,MockTemplateGenerator.MockTemplate template)throws Exception{
    Path dir=Path.of("target","mock-templates");
    Files.createDirectories(dir);
    Files.writeString(dir.resolve(file),
      mapper.writerWithDefaultPrettyPrinter().writeValueAsString(template),
      StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);
  }

  @Test void generatesStructuralMockForAll166CatalogEntriesWithoutFacts()throws Exception{
    long beforeForms=forms.count(),beforeVersions=versions.count();
    long beforeInstances=instances.count(),beforeRevisions=formRevisions.count();
    long beforeReports=reportDefinitions.count(),beforeReportRevisions=reportRevisions.count();

    var definitions=catalog.forms("ru");
    assertEquals(166,definitions.size());
    int typed=0,evidence=0,unapproved=0;
    var visited=new HashSet<String>();
    for(var definition:definitions){
      assertTrue(visited.add(definition.formCode()),"duplicate "+definition.formCode());
      var preview=create(definition.formCode(),"ru","COLUMNS",
        "M",List.of("2026-02"),"catalog-smoke-"+definition.formCode());
      assertEquals("MOCK_ONLY_NOT_FACT",preview.fixtureType());
      assertEquals(definition.formCode(),preview.formCode());
      assertEquals(definition.version(),preview.formVersion());
      assertEquals("CANDIDATE_UNQUALIFIED",preview.qualificationState());
      assertEquals(0,preview.cells().stream().filter(c->c.value()!=null).count());
      assertTrue(preview.cells().stream().allMatch(c->"MISSING".equals(c.state())));
      assertTrue(preview.cells().stream().noneMatch(c->"__TOTAL__".equals(c.rowKey())
        ||"__TOTAL__".equals(c.columnKey())));
      assertEquals(preview.fields().stream().map(f->f.fieldCode()).distinct().count(),
        preview.fields().size(),"duplicate field in "+definition.formCode());
      if("TYPED_DRAFT".equals(definition.captureMode())){
        typed++;
        assertTrue(preview.inputPreviewSupported());
      }else if("EVIDENCE_ONLY".equals(definition.captureMode())){
        evidence++;
        assertFalse(preview.inputPreviewSupported());
        assertTrue(preview.cells().isEmpty());
      }else{
        unapproved++;
        assertEquals("SCHEMA_NOT_APPROVED",definition.captureMode());
        assertFalse(preview.inputPreviewSupported());
      }
    }
    assertEquals(122,typed);
    assertEquals(12,evidence);
    assertEquals(32,unapproved);
    assertEquals(beforeForms,forms.count());
    assertEquals(beforeVersions,versions.count());
    assertEquals(beforeInstances,instances.count());
    assertEquals(beforeRevisions,formRevisions.count());
    assertEquals(beforeReports,reportDefinitions.count());
    assertEquals(beforeReportRevisions,reportRevisions.count());
  }

  @Test void writesFiveInspectibleMockOnlyFixturesAcrossAxesAndLocales()throws Exception{
    long beforeInstances=instances.count(),beforeReports=reportDefinitions.count();
    long beforeFormRevisions=formRevisions.count(),beforeReportRevisions=reportRevisions.count();

    var f02ru=create("F02","ru","COLUMNS","M",
      List.of("2026-01","2026-02","2026-03"),"f02-ru-month-columns");
    var f02en=create("F02","en","ROWS","M",
      List.of("2026-01","2026-02","2026-03"),"f02-en-month-rows");
    var f02kk=create("F02","kk","COLUMNS","Q",
      List.of("2026-Q1","2026-Q2"),"f02-kk-quarter-columns");
    var f02zh=create("F02","zh-Hans","ROWS","Y",
      List.of("2025","2026"),"f02-zh-year-rows");
    var rowset=create("DF-CSH-01","ru","COLUMNS","M",
      List.of("2026-02"),"cash-transaction-rowset");

    assertEquals(9,f02ru.cells().size());
    assertEquals(9,f02en.cells().size());
    assertEquals(6,f02kk.cells().size());
    assertEquals(6,f02zh.cells().size());
    assertEquals("ROWS",f02en.timeAxis());
    assertEquals("COLUMNS",f02ru.timeAxis());
    assertEquals(0,rowset.cells().size());
    assertEquals("ROWSET",rowset.contentMode());
    var metricList=catalog.metrics("ru","SOURCE_FIELD","F02");
    Set<String> qualifiedMetricCodes=new HashSet<>();
    for(var m:metricList) qualifiedMetricCodes.add(m.metricCode());
    for(var f:f02ru.fields())
      assertTrue(qualifiedMetricCodes.contains("F02."+f.fieldCode()),
        "Missing original system metric code: "+f.fieldCode());
    // Missing translations stay explicit and must not be fabricated by the mock.
    assertEquals(f02ru.fields().stream().map(f->f.fieldCode()).toList(),
      f02zh.fields().stream().map(f->f.fieldCode()).toList());
    assertTrue(f02zh.warnings().stream().allMatch(w->!w.isBlank()));
    for(var mock:List.of(f02ru,f02en,f02kk,f02zh,rowset))
      assertTrue(mock.cells().stream().allMatch(c->c.value()==null));
    writeFixture("F02-ru-month-COLUMNS.json",f02ru);
    writeFixture("F02-en-month-ROWS.json",f02en);
    writeFixture("F02-kk-quarter-COLUMNS.json",f02kk);
    writeFixture("F02-zh-Hans-year-ROWS.json",f02zh);
    writeFixture("DF-CSH-01-ru-ROWSET.json",rowset);
    String evidenceCode=catalog.forms("ru").stream()
      .filter(f->"EVIDENCE_ONLY".equals(f.captureMode())).findFirst().orElseThrow().formCode();
    var evidence=create(evidenceCode,"ru","COLUMNS","M",
      List.of("2026-02"),"evidence-metadata-only");
    assertFalse(evidence.inputPreviewSupported());
    assertTrue(evidence.cells().isEmpty());
    writeFixture("EVIDENCE-ru-metadata-only.json",evidence);

    assertEquals(beforeInstances,instances.count());
    assertEquals(beforeReports,reportDefinitions.count());
    assertEquals(beforeFormRevisions,formRevisions.count());
    assertEquals(beforeReportRevisions,reportRevisions.count());
  }
}
