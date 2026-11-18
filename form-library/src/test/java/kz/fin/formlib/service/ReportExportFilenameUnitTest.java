package kz.fin.formlib.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReportExportFilenameUnitTest {
  private static final String SHA="a1b2c3d4e5f6"+"0".repeat(52);

  private static ReportExportFilename.Identity identity(){
    return new ReportExportFilename.Identity("F02","M","2026-11","KZT",
      "EXCEL","ru","FIN-FORM-V2",3,SHA);
  }

  @Test
  void sameImmutableRevisionHasOneStemAcrossAllRenderers(){
    var xlsx=ReportExportFilename.build(identity(),ReportExportFilename.Format.XLSX);
    var pdf=ReportExportFilename.build(identity(),ReportExportFilename.Format.PDF);
    var html=ReportExportFilename.build(identity(),ReportExportFilename.Format.HTML);
    String stem="FIN_F02_M-2026-11_KZT_EXCEL_ru_FIN-FORM-V2_r0003_a1b2c3d4e5f6";
    assertEquals(stem+".xlsx",xlsx);
    assertEquals(stem+".pdf",pdf);
    assertEquals(stem+".html",html);
    assertEquals(stem+".xlsx",FormXlsxFilename.build("F02","M","2026-11",
      "KZT",FormXlsxCodec.Style.EXCEL,"ru","FIN-FORM-V2",3,SHA));
    assertEquals("attachment; filename=\""+stem+".pdf\"",
      ReportExportFilename.attachment(identity(),ReportExportFilename.Format.PDF));
  }

  @Test
  void explicitMimeTypesAndRevisionChange(){
    assertEquals("application/pdf",ReportExportFilename.Format.PDF.mediaType());
    assertEquals("text/html",ReportExportFilename.Format.HTML.mediaType());
    assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
      ReportExportFilename.Format.XLSX.mediaType());
    var old=ReportExportFilename.build(identity(),ReportExportFilename.Format.PDF);
    var changed=new ReportExportFilename.Identity("F02","M","2026-11","KZT",
      "EXCEL","ru","FIN-FORM-V2",4,"f".repeat(64));
    assertNotEquals(old,ReportExportFilename.build(changed,ReportExportFilename.Format.PDF));
    assertTrue(ReportExportFilename.build(changed,ReportExportFilename.Format.PDF)
      .contains("_r0004_ffffffffffff.pdf"));
  }

  @Test
  void allPeriodTypesAndLanguagesProduceSafeNames(){
    String[][] cases={{"D","2026-11-18"},{"W","2026-W47"},
      {"DEC","2026-11-2"},{"M","2026-11"},{"Q","2026-Q4"},
      {"H","2026-H2"},{"Y","2026"}};
    for(var p:cases)for(String locale:new String[]{"ru","kk","en","zh-Hans"})
      for(var f:ReportExportFilename.Format.values()){
        var identity=new ReportExportFilename.Identity("F02",p[0],p[1],"USD",
          "DASHBOARD",locale,"FIN-FORM-V2",11,SHA);
        String name=ReportExportFilename.build(identity,f);
        assertTrue(name.matches("[A-Za-z0-9._-]+"),name);
        assertFalse(name.contains(".."),name);
        assertTrue(name.endsWith("."+f.extension()));
      }
  }

  @Test
  void rejectsUnsupportedOrUnsafeMetadata(){
    var invalid=new ReportExportFilename.Identity("F02","M","2026-11",
      "KZT","EXCEL","ru","FIN-FORM-V2",0,SHA);
    assertThrows(IllegalArgumentException.class,()->ReportExportFilename.build(
      invalid,ReportExportFilename.Format.HTML));
    assertThrows(IllegalArgumentException.class,()->ReportExportFilename.build(
      new ReportExportFilename.Identity("F02","M","2026-11","KZT",
        "EXCEL","ru","FIN-FORM-V2",3,"bad"),
      ReportExportFilename.Format.XLSX));
    assertThrows(IllegalArgumentException.class,()->ReportExportFilename.build(
      new ReportExportFilename.Identity("F02","M","2026-11","KZT",
        "EXCEL","unknown","FIN-FORM-V2",3,SHA),
      ReportExportFilename.Format.PDF));
    assertThrows(IllegalArgumentException.class,()->ReportExportFilename.build(
      identity(),null));
    assertThrows(NullPointerException.class,()->ReportExportFilename.build(
      null,ReportExportFilename.Format.HTML));
    String safe=ReportExportFilename.build(new ReportExportFilename.Identity(
      "F02","M","../2026:11","KZT","EXCEL","ru","V2\\billing",3,SHA),
      ReportExportFilename.Format.HTML);
    assertFalse(safe.contains(".."));
    assertFalse(safe.contains(":"));
    assertFalse(safe.contains("\\"));
  }
}
