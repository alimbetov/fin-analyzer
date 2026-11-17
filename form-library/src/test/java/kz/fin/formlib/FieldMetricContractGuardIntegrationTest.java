package kz.fin.formlib;

import kz.fin.formlib.service.FieldMetricContractGuard;
import kz.fin.formlib.service.InputTemplatePreviewService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Negative isolation: a semantic field/mapping edit cannot remain writable. */
@SpringBootTest
@AutoConfigureMockMvc
class FieldMetricContractGuardIntegrationTest {
  @Autowired JdbcTemplate db;
  @Autowired FieldMetricContractGuard guard;
  @Autowired InputTemplatePreviewService preview;

  private UUID version(String form){
    return Objects.requireNonNull(db.queryForObject("""
      SELECT current_version_id FROM fin_lib.lib_form_definition
      WHERE form_code=?
      """,UUID.class,form));
  }

  @Test void onlyRealPinnedFormVersionPassesTheGate(){
    UUID f02=version("F02");
    assertTrue(guard.matches("F02",f02));
    assertFalse(guard.matches("F01",f02));
    assertFalse(guard.matches("F02",UUID.randomUUID()));
    assertFalse(guard.matches(null,f02));
    assertTrue(guard.matches("DF-CSH-01",version("DF-CSH-01")));
  }

  @Test
  @Transactional
  void mismatchToPhysicalTypeDisablesEveryEditablePreviewCell(){
    UUID id=version("F02");
    assertTrue(guard.matches("F02",id));
    db.update("""
      UPDATE fin_lib.lib_field_binding_contract SET field_type='REF'
      WHERE form_code='F02' AND field_code='netRevenue'
      """);
    assertFalse(guard.matches("F02",id));
    var output=preview.preview("F02","ru","M",List.of("2026-01"),"EXCEL_GRID");
    assertTrue(output.warnings().contains("FIELD_METRIC_CONTRACT_STALE_PREVIEW_READ_ONLY"));
    assertTrue(output.cells().stream().noneMatch(
      InputTemplatePreviewService.CellDescriptor::editable));
    // @Transactional test rollback protects the shared test fixture.
  }

  @Test
  @Transactional
  void changeOfUnitPolicyIsNotAcceptedAsTheSameMetricBinding(){
    UUID id=version("F02");
    assertTrue(guard.matches("F02",id));
    db.update("""
      UPDATE fin_lib.lib_field_binding_contract SET unit_policy='BAD_POLICY'
      WHERE form_code='F02' AND field_code='cogs'
      """);
    assertFalse(guard.matches("F02",id));
  }
}
