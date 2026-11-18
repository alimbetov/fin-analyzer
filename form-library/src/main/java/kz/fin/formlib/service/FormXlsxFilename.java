package kz.fin.formlib.service;

/**
 * Compatibility facade for the existing XLSX controller.
 * The canonical policy is ReportExportFilename, shared with PDF and HTML.
 */
public final class FormXlsxFilename {
  private FormXlsxFilename() {}

  public static String build(String formCode,String periodType,String periodCode,
                             String currency,FormXlsxCodec.Style style,String locale,
                             String formVersion,int revision,String contentSha256){
    return ReportExportFilename.build(new ReportExportFilename.Identity(
      formCode,periodType,periodCode,currency,style.name(),locale,
      formVersion,revision,contentSha256),ReportExportFilename.Format.XLSX);
  }
}
