package kz.fin.formlib.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class ImportNumberNormalizerUnitTest {
  @Test
  void attachedF02WorkbookValuesNormalizeWithoutRounding(){
    assertEquals("88.56",ImportNumberNormalizer.normalize("88,56","MONEY","ru","netRevenue"));
    assertEquals("99.66",ImportNumberNormalizer.normalize("99,66","MONEY","ru","cogs"));
    assertEquals("777.66",ImportNumberNormalizer.normalize("777.66","MONEY","ru","operatingExpenses"));
  }

  @Test
  void supportsValidatedNonBreakingSpaceGroupingAndDifferentLocales(){
    assertEquals("1234567.89",ImportNumberNormalizer.normalize("1 234 567,89","MONEY","ru","netRevenue"));
    assertEquals("1234567.89",ImportNumberNormalizer.normalize("1\u00a0234\u202f567.89","MONEY","en","netRevenue"));
    assertEquals("-0.01",ImportNumberNormalizer.normalize("-0,01","MONEY","kk","netRevenue"));
    assertEquals("0",ImportNumberNormalizer.normalize("0","MONEY","zh-Hans","netRevenue"));
  }

  @Test
  void leavesTextAndDatesUnchanged(){
    assertEquals("ABC,123",ImportNumberNormalizer.normalize("ABC,123","STRING","ru","note"));
    assertEquals("2026-11-18",ImportNumberNormalizer.normalize("2026-11-18","DATE","ru","date"));
  }

  private void rejected(String value,String type,String reason){
    var error=assertThrows(ResponseStatusException.class,
      ()->ImportNumberNormalizer.normalize(value,type,"ru","netRevenue"));
    assertEquals(HttpStatus.UNPROCESSABLE_ENTITY,error.getStatusCode());
    assertTrue(error.getReason().contains("IMPORT_FIELD:netRevenue:"+reason),
      error.getReason());
  }

  @Test
  void failsClosedOnAmbiguousOrMalformedFinancialNumbers(){
    rejected("1,234","MONEY","AMBIGUOUS_NUMBER");
    rejected("1.234","MONEY","AMBIGUOUS_NUMBER");
    rejected("1,234.56","MONEY","MIXED_DECIMAL_SEPARATORS");
    rejected("1.234,56","MONEY","MIXED_DECIMAL_SEPARATORS");
    rejected("12 34,56","MONEY","INVALID_GROUPING");
    rejected("1,,2","MONEY","AMBIGUOUS_NUMBER");
    rejected("NaN","MONEY","INVALID_NUMBER_FORMAT");
    rejected("=SUM(A1:A2)","MONEY","INVALID_NUMBER_FORMAT");
    rejected("1,5","INTEGER","INVALID_INTEGER");
  }
}
