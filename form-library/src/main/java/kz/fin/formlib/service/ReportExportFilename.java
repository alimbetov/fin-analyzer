package kz.fin.formlib.service;

import java.util.Locale;
import java.util.Objects;

/**
 * Renderer-independent naming contract. The supplied identity MUST originate
 * from an authorized, persisted, revision-pinned snapshot, never from request
 * query parameters or a user-provided workbook.
 *
 * FIN_F02_M-2026-11_KZT_EXCEL_ru_FIN-FORM-V2_r0003_abcdef123456.pdf
 *
 * The SHA prefix is descriptive, not a signature or an access-control token.
 */
public final class ReportExportFilename {
  private ReportExportFilename() {}

  public enum Format {
    XLSX("xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    PDF("pdf","application/pdf"),
    HTML("html","text/html");

    private final String extension;
    private final String mediaType;

    Format(String extension,String mediaType){
      this.extension=extension;
      this.mediaType=mediaType;
    }

    public String extension(){return extension;}
    public String mediaType(){return mediaType;}
  }

  public record Identity(String formCode,String periodType,String periodCode,
                         String currency,String style,String locale,
                         String formVersion,int revision,String contentSha256) {}

  private static String token(String value,int maxLength){
    if(value==null||value.isBlank())
      throw new IllegalArgumentException("REPORT_FILENAME_TOKEN_REQUIRED");
    String cleaned=value.trim().replaceAll("[^A-Za-z0-9._-]","-")
      .replaceAll("-+","-").replaceAll("^[.-]+|[.-]+$","");
    if(cleaned.isBlank())
      throw new IllegalArgumentException("REPORT_FILENAME_TOKEN_INVALID");
    return cleaned.substring(0,Math.min(maxLength,cleaned.length()));
  }

  public static String build(Identity identity,Format format){
    Objects.requireNonNull(identity,"identity");
    Objects.requireNonNull(format,"format");
    if(identity.revision()<1)
      throw new IllegalArgumentException("REPORT_FILENAME_REVISION_INVALID");
    if(identity.contentSha256()==null ||
       !identity.contentSha256().matches("[a-fA-F0-9]{64}"))
      throw new IllegalArgumentException("REPORT_FILENAME_SHA_INVALID");
    if(!java.util.Set.of("CLASSIC","EXCEL","DASHBOARD").contains(identity.style()))
      throw new IllegalArgumentException("REPORT_FILENAME_STYLE_INVALID");
    if(!java.util.Set.of("ru","kk","en","zh-Hans").contains(identity.locale()))
      throw new IllegalArgumentException("REPORT_FILENAME_LOCALE_INVALID");

    return "FIN_"+token(identity.formCode(),20)+"_"+
      token(identity.periodType(),10)+"-"+token(identity.periodCode(),32)+"_"+
      token(identity.currency(),3)+"_"+identity.style()+"_"+
      token(identity.locale(),12)+"_"+token(identity.formVersion(),38)+"_"+
      "r"+String.format(Locale.ROOT,"%04d",identity.revision())+"_"+
      identity.contentSha256().substring(0,12).toLowerCase(Locale.ROOT)+"."+
      format.extension();
  }

  /** HTTP transport contract shared by the future PDF/HTML renderer controllers. */
  public static String attachment(Identity identity,Format format){
    return "attachment; filename=\""+build(identity,format)+"\"";
  }
}
