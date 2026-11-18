package kz.fin.formlib.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FormXlsxFilenameUnitTest {
  private static final String SHA="abcdef123456"+"0".repeat(52);
  private static final String SHA2="987654fedcba"+"1".repeat(52);

  @Test
  void encodesFinancialIdentityAndImmutableRevision() {
    String name=FormXlsxFilename.build("F02","M","2026-11","KZT",
      FormXlsxCodec.Style.EXCEL,"ru","FIN-FORM-V2",3,SHA);
    assertEquals("FIN_F02_M-2026-11_KZT_EXCEL_ru_FIN-FORM-V2_r0003_abcdef123456.xlsx",name);
    assertFalse(name.contains(":"));
    assertFalse(name.contains("/"));
    assertFalse(name.contains("\\"));
    assertFalse(name.contains(" "));
    assertNotEquals(name,FormXlsxFilename.build("F02","M","2026-11","KZT",
      FormXlsxCodec.Style.EXCEL,"ru","FIN-FORM-V2",4,SHA2));
  }

  @Test
  void differentPeriodsCurrenciesStylesLocalesAndVersionsProduceDifferentNames() {
    String base=FormXlsxFilename.build("F02","M","2026-11","KZT",
      FormXlsxCodec.Style.CLASSIC,"ru","FIN-FORM-V2",1,SHA);
    assertNotEquals(base,FormXlsxFilename.build("F02","Q","2026-Q4","KZT",
      FormXlsxCodec.Style.CLASSIC,"ru","FIN-FORM-V2",1,SHA));
    assertNotEquals(base,FormXlsxFilename.build("F02","M","2026-11","USD",
      FormXlsxCodec.Style.CLASSIC,"ru","FIN-FORM-V2",1,SHA));
    assertNotEquals(base,FormXlsxFilename.build("F02","M","2026-11","KZT",
      FormXlsxCodec.Style.DASHBOARD,"ru","FIN-FORM-V2",1,SHA));
    assertNotEquals(base,FormXlsxFilename.build("F02","M","2026-11","KZT",
      FormXlsxCodec.Style.CLASSIC,"kk","FIN-FORM-V2",1,SHA));
    assertNotEquals(base,FormXlsxFilename.build("F02","M","2026-11","KZT",
      FormXlsxCodec.Style.CLASSIC,"ru","FIN-FORM-V3",1,SHA));
  }

  @Test
  void rejectsInvalidShaOrRevisionAndSanitizesUntrustedTokens() {
    assertThrows(IllegalArgumentException.class,()->FormXlsxFilename.build(
      "F02","M","2026-11","KZT",FormXlsxCodec.Style.EXCEL,
      "ru","FIN-FORM-V2",0,SHA));
    assertThrows(IllegalArgumentException.class,()->FormXlsxFilename.build(
      "F02","M","2026-11","KZT",FormXlsxCodec.Style.EXCEL,
      "ru","FIN-FORM-V2",1,"short"));
    String safe=FormXlsxFilename.build("F02","M","../2026:11","KZT",
      FormXlsxCodec.Style.EXCEL,"zh-Hans","V2\\finance",1,SHA);
    assertFalse(safe.contains(".."));
    assertFalse(safe.contains(":"));
    assertFalse(safe.contains("\\"));
    assertTrue(safe.endsWith(".xlsx"));
    assertTrue(safe.length()<180);
  }
}
