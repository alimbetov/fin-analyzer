package kz.fin.formlib;

import kz.fin.formlib.service.IndicatorNavigatorCatalog;
import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class IndicatorNavigatorCatalogUnitTest {
  private final IndicatorNavigatorCatalog catalog=new IndicatorNavigatorCatalog();

  @Test
  void exactSourceGraphHasSevenGoalsFortyTwoModelsAndFortySixUniqueOutputs() {
    var goals=catalog.goals("ru");
    assertEquals(7,goals.size());
    assertEquals(42,catalog.modelCount());
    assertEquals(46,catalog.indicators().size());
    assertEquals(7,goals.stream().map(IndicatorNavigatorCatalog.Goal::code).distinct().count());
    assertEquals(101,goals.stream().mapToInt(x->x.forms().size()).sum());
    assertTrue(goals.stream().allMatch(x->"CANDIDATE_NOT_S2".equals(x.qualificationStatus())));
    assertTrue(catalog.indicators().stream().allMatch(x->
        "CATALOG_UNQUALIFIED".equals(x.qualificationStatus())));
    assertEquals(46,catalog.indicators().stream().map(x->x.code()).distinct().count());
  }

  @Test
  void sourceModelsAndDirectGoalLinksNeverCreateFakeReadiness() {
    var npv=catalog.indicator("PROJECT_NPV").orElseThrow();
    assertTrue(npv.inputFormCodes().containsAll(Set.of("P07","P03","P05")));
    assertEquals("CALC_PILOT",npv.models().get(0).qualificationStatus());
    var investor=catalog.goal("INVESTMENT_APPRAISAL","ru").orElseThrow();
    assertEquals("Оценка инвестиционного проекта",investor.title());
    assertTrue(investor.forms().stream().anyMatch(x->
        x.formCode().equals("P07") && x.role().equals("CORE")));
    assertTrue(investor.models().stream().anyMatch(x->
        x.outputMetricCodes().contains("PROJECT_NPV")));
    assertEquals("CANDIDATE_NOT_S2",investor.qualificationStatus());
  }

  @Test
  void everyFormIsMappedOrExplicitlyUnmappedAndLocalizationNeverFaked(){
    var ru=catalog.goals("ru");
    var kk=catalog.goals("kk");
    assertEquals(ru.size(),kk.size());
    assertTrue(kk.stream().allMatch(x->
        x.title()==null && x.translationStatus().equals("PENDING_TRANSLATION")));
    assertEquals("Financial health diagnostics",
        catalog.goal("FINANCIAL_HEALTH","en").orElseThrow().title());
    Set<String> used=new HashSet<>();
    for(var goal:ru)for(var form:goal.forms())used.add(form.formCode());
    for(var indicator:catalog.indicators())used.addAll(indicator.inputFormCodes());
    assertEquals(71,used.size());
    assertEquals("NOT_YET_MAPPED",
        catalog.formUsage("DF-AST-01").map(IndicatorNavigatorCatalog.FormUsage::qualificationStatus)
            .orElse("NOT_FOUND"));
    assertFalse(catalog.formUsage("NOT-A-FORM").isPresent());
    assertThrows(IllegalArgumentException.class,()->catalog.goals("fr"));
  }
}
