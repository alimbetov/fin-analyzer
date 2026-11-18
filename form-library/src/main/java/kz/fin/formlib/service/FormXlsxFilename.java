package kz.fin.formlib.service;

import java.util.Locale;

/**
 * Public, filesystem-safe download name. The filename is descriptive only:
 * financial identity and all authorization checks remain inside the workbook
 * and the persisted server-side revision, never in the name.
 *
 * FIN_F02_M-2026-11_KZT_EXCEL_ru_FIN-FORM-V2_r0003_a1b2c3d4e5f6.xlsx
 */
public final class FormXlsxFilename {
  private FormXlsxFilename() {}

  private static String token(String value,int maxLength){
    if(value==null||value.isBlank())throw new IllegalArgumentException("XLSX_FILENAME_TOKEN_REQUIRED");
    String cleaned=value.trim().replaceAll("[^A-Za-z0-9._-]","-")
      .replaceAll("-+","-").replaceAll("^[.-]+|[.-]+$","");
    if(cleaned.isBlank())throw new IllegalArgumentException("XLSX_FILENAME_TOKEN_INVALID");
    return cleaned.substring(0,Math.min(maxLength,cleaned.length()));
  }

  public static String build(String formCode,String periodType,String periodCode,
                             String currency,FormXlsxCodec.Style style,String locale,
                             String formVersion,int revision,String contentSha256){
    if(revision<1)throw new IllegalArgumentException("XLSX_FILENAME_REVISION_INVALID");
    if(contentSha256==null||!contentSha256.matches("[a-fA-F0-9]{64}"))
      throw new IllegalArgumentException("XLSX_FILENAME_SHA_INVALID");
    return "FIN_"+token(formCode,20)+"_"+
      token(periodType,10)+"-"+token(periodCode,32)+"_"+
      token(currency,3)+"_"+style.name()+"_"+
      token(locale,12)+"_"+token(formVersion,38)+"_"+
      "r"+String.format(Locale.ROOT,"%04d",revision)+"_"+
      contentSha256.substring(0,12).toLowerCase(Locale.ROOT)+".xlsx";
  }
}
