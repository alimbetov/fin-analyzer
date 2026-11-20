package kz.fin.formlib.calc.methodology;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FinancialPolicyGuardrailsTest {
  @Test void mixedIfsAndManagementBasesFailClosed(){
    assertEquals(FinancialPolicyGuardrails.ReportingBasis.IFRS,
      FinancialPolicyGuardrails.requireSingleReportingBasis(
        List.of(FinancialPolicyGuardrails.ReportingBasis.IFRS)));
    assertThrows(IllegalStateException.class,()->
      FinancialPolicyGuardrails.requireSingleReportingBasis(List.of(
        FinancialPolicyGuardrails.ReportingBasis.IFRS,
        FinancialPolicyGuardrails.ReportingBasis.MANAGEMENT)));
  }

  @Test void thresholdsRequireExplicitPolicyAndMayOnlyTighten(){
    assertThrows(IllegalStateException.class,()->
      FinancialPolicyGuardrails.approvedTolerance(null,BigDecimal.ZERO));
    assertEquals(0,FinancialPolicyGuardrails.approvedTolerance(
      new BigDecimal("100"),new BigDecimal("50"))
      .compareTo(new BigDecimal("50")));
    assertEquals(0,FinancialPolicyGuardrails.approvedTolerance(
      new BigDecimal("100"),null).compareTo(new BigDecimal("100")));
    assertThrows(IllegalStateException.class,()->
      FinancialPolicyGuardrails.approvedTolerance(
        new BigDecimal("100"),new BigDecimal("100.01")));
  }

  @Test void analyticalDscrCannotClaimContractCovenantCompliance(){
    assertEquals("ANALYTICAL_DSCR_NOT_COVENANT",
      FinancialPolicyGuardrails.dscrResultClass(
        FinancialPolicyGuardrails.DscrPurpose.ANALYTICAL,false));
    assertThrows(IllegalStateException.class,()->
      FinancialPolicyGuardrails.dscrResultClass(
        FinancialPolicyGuardrails.DscrPurpose.COVENANT,false));
    assertEquals("CONTRACTUAL_COVENANT_DSCR_UAT_CANDIDATE",
      FinancialPolicyGuardrails.dscrResultClass(
        FinancialPolicyGuardrails.DscrPurpose.COVENANT,true));
  }

  @Test void inventoryCostFormulaMustBeKnownForEachNatureUseClass(){
    assertDoesNotThrow(()->
      FinancialPolicyGuardrails.requireUniformInventoryClassFormula(
        Map.of("INTERCHANGEABLE_A","FIFO","NON_INTERCHANGEABLE_B",
          "SPECIFIC_IDENTIFICATION"),
        List.of("INTERCHANGEABLE_A","NON_INTERCHANGEABLE_B")));
    assertThrows(IllegalStateException.class,()->
      FinancialPolicyGuardrails.requireUniformInventoryClassFormula(
        Map.of("INTERCHANGEABLE_A","FIFO"),
        List.of("INTERCHANGEABLE_A","UNCLASSIFIED")));
    assertDoesNotThrow(()->FinancialPolicyGuardrails
      .requireCostFormulaConsistentWithInterchangeability("FIFO",true));
    assertDoesNotThrow(()->FinancialPolicyGuardrails
      .requireCostFormulaConsistentWithInterchangeability(
        "SPECIFIC_IDENTIFICATION",false));
    assertThrows(IllegalStateException.class,()->FinancialPolicyGuardrails
      .requireCostFormulaConsistentWithInterchangeability(
        "SPECIFIC_IDENTIFICATION",true));
    assertThrows(IllegalStateException.class,()->FinancialPolicyGuardrails
      .requireCostFormulaConsistentWithInterchangeability("FIFO",false));
  }
}
