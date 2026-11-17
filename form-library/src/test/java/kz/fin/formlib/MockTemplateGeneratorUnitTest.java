package kz.fin.formlib;

import kz.fin.formlib.api.ReferenceCatalogController;
import kz.fin.formlib.support.MockTemplateGenerator;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MockTemplateGeneratorUnitTest {
  private static ReferenceCatalogController.FormDetail f02(){
    var form=new ReferenceCatalogController.FormView("F02","FIN-FORM-V2",
      "TYPED_DRAFT","SINGLE_RECORD","CANDIDATE_UNQUALIFIED","ru",
      "Форма F02",null,"PROVISIONAL_NOT_S2");
    var sections=List.of(new ReferenceCatalogController.SectionView("MAIN",10,"SINGLE_RECORD"));
    var fields=List.of(
      new ReferenceCatalogController.FieldView("netRevenue","MONEY","MANUAL",true,
        10,"MAIN","Выручка","PROVISIONAL_NOT_S2","CURRENCY"),
      new ReferenceCatalogController.FieldView("cogs","MONEY","MANUAL",true,
        20,"MAIN","Себестоимость","PROVISIONAL_NOT_S2","CURRENCY"),
      new ReferenceCatalogController.FieldView("operatingExpenses","MONEY","MANUAL",true,
        30,"MAIN","Расходы","PROVISIONAL_NOT_S2","CURRENCY")
    );
    return new ReferenceCatalogController.FormDetail(form,sections,fields);
  }
  @Test void generatesEmptyHorizontalF02WithoutSyntheticFinancialValues(){
    var mock=MockTemplateGenerator.generate(f02(),
      new MockTemplateGenerator.Scenario("f02-horizontal","ru","M",
        List.of("2026-01","2026-02","2026-03"),"COLUMNS"));
    assertEquals("MOCK_ONLY_NOT_FACT",mock.fixtureType());
    assertEquals("F02",mock.formCode());
    assertEquals(3,mock.rowHeaders().size());
    assertEquals(3,mock.columnHeaders().size());
    assertEquals(9,mock.cells().size());
    assertTrue(mock.inputPreviewSupported());
    assertEquals("netRevenue",mock.cells().get(0).fieldCode());
    assertEquals("2026-01",mock.cells().get(0).periodCode());
    assertTrue(mock.cells().stream().allMatch(c->c.value()==null&&c.state().equals("MISSING")));
    assertTrue(mock.cells().stream().noneMatch(c->c.rowKey().equals("__TOTAL__")||
        c.columnKey().equals("__TOTAL__")));
  }
  @Test void pivotPreservesSameLogicalCoordinatesAndMissingStates(){
    var horizontal=MockTemplateGenerator.generate(f02(),
      new MockTemplateGenerator.Scenario("horizontal","ru","M",
        List.of("2026-01","2026-02"),"COLUMNS"));
    var vertical=MockTemplateGenerator.generate(f02(),
      new MockTemplateGenerator.Scenario("vertical","ru","M",
        List.of("2026-01","2026-02"),"ROWS"));
    assertEquals(6,vertical.cells().size());
    assertEquals("2026-01",vertical.rowHeaders().get(0).code());
    assertEquals("netRevenue",vertical.columnHeaders().get(0).code());
    assertNotEquals(horizontal.cells().get(1).periodCode(),vertical.cells().get(1).periodCode());
    var lhs=horizontal.cells().stream().map(c->c.fieldCode()+"|"+c.periodCode()).sorted().toList();
    var rhs=vertical.cells().stream().map(c->c.fieldCode()+"|"+c.periodCode()).sorted().toList();
    assertEquals(lhs,rhs);
  }
  @Test void deniesInvalidAxisPeriodsAndCatalogBinding(){
    assertThrows(IllegalArgumentException.class,()->MockTemplateGenerator.generate(f02(),
      new MockTemplateGenerator.Scenario("bad","ru","M",List.of("2026-01"),"DIAGONAL")));
    assertThrows(IllegalArgumentException.class,()->MockTemplateGenerator.generate(f02(),
      new MockTemplateGenerator.Scenario("duplicate","ru","M",
        List.of("2026-01","2026.01"),"COLUMNS")));
    assertThrows(IllegalArgumentException.class,()->MockTemplateGenerator.generate(f02(),
      new MockTemplateGenerator.Scenario("locale","en","M",List.of("2026-01"),"ROWS")));
    assertThrows(IllegalArgumentException.class,()->MockTemplateGenerator.generate(f02(),
      new MockTemplateGenerator.Scenario("week","ru","W",List.of("2026-W54"),"ROWS")));
    var invalid=new ReferenceCatalogController.FormDetail(f02().form(),f02().sections(),
      List.of(f02().fields().get(0),f02().fields().get(0)));
    assertThrows(IllegalArgumentException.class,()->MockTemplateGenerator.generate(invalid,
      new MockTemplateGenerator.Scenario("binding","ru","M",List.of("2026-01"),"ROWS")));
  }
  @Test void evidenceAndRowsetDoNotFabricateInputData(){
    var evidence=new ReferenceCatalogController.FormDetail(
      new ReferenceCatalogController.FormView("E1","CATALOG-V1","EVIDENCE_ONLY",
        "EVIDENCE","CANDIDATE_UNQUALIFIED","ru","Документ",null,"PENDING_TRANSLATION"),
      List.of(new ReferenceCatalogController.SectionView("MAIN",10,"EVIDENCE")),List.of());
    var e=MockTemplateGenerator.generate(evidence,
      new MockTemplateGenerator.Scenario("evidence","ru","Y",List.of("2026"),"COLUMNS"));
    assertFalse(e.inputPreviewSupported());
    assertEquals(0,e.cells().size());
    assertEquals(0,e.columnHeaders().size());
    assertTrue(e.warnings().contains("EVIDENCE_UPLOAD_NOT_IMPLEMENTED"));
    var rowset=new ReferenceCatalogController.FormDetail(
      new ReferenceCatalogController.FormView("DF-CSH-01","CATALOG-V1",
        "TYPED_DRAFT","ROWSET","CANDIDATE_UNQUALIFIED","ru","Платежи",null,"PROVISIONAL_NOT_S2"),
      List.of(new ReferenceCatalogController.SectionView("MAIN",10,"ROWSET")),
      List.of(new ReferenceCatalogController.FieldView("amount","MONEY","MANUAL",false,
        10,"MAIN","Сумма","PROVISIONAL_NOT_S2","CURRENCY")));
    var r=MockTemplateGenerator.generate(rowset,
      new MockTemplateGenerator.Scenario("rowset","ru","M",List.of("2026-02"),"COLUMNS"));
    assertEquals(0,r.cells().size());
    assertEquals(1,r.columnHeaders().size());
    assertEquals("amount",r.columnHeaders().get(0).code());
    assertTrue(r.warnings().contains("ROWSET_NO_SYNTHETIC_TRANSACTIONS"));
  }
}
