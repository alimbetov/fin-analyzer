package kz.fin.formlib.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.Set;

/**
 * Import boundary only. Financial canonicalization remains in FormDraftService.
 * Accept decimal comma or dot when unambiguous; never guess grouping from
 * punctuation. A space/NBSP group separator is accepted only in 3-digit groups.
 */
public final class ImportNumberNormalizer {
  private ImportNumberNormalizer() {}

  public static String normalize(String raw,String fieldType,String locale,String fieldCode){
    if(raw==null)throw invalid(fieldCode,"NULL_VALUE");
    if(!Set.of("MONEY","DECIMAL","INTEGER").contains(fieldType))return raw;
    if(locale==null||!Set.of("ru","kk","en","zh-Hans").contains(locale))
      throw invalid(fieldCode,"UNSUPPORTED_LOCALE");
    String value=raw.trim().replace('\u00a0',' ').replace('\u202f',' ');
    if(value.isEmpty())return "";
    if(!value.matches("-?[0-9 ,\.]+"))
      throw invalid(fieldCode,"INVALID_NUMBER_FORMAT");
    boolean comma=value.indexOf(',')>=0, dot=value.indexOf('.')>=0;
    if(comma&&dot)throw invalid(fieldCode,"MIXED_DECIMAL_SEPARATORS");

    String sign=value.startsWith("-")?"-":"";
    String number=sign.isEmpty()?value:value.substring(1);
    String sep=comma?",":dot?".":null;
    String whole, fraction="";
    if(sep!=null){
      int first=number.indexOf(sep),last=number.lastIndexOf(sep);
      if(first!=last)throw invalid(fieldCode,"AMBIGUOUS_NUMBER");
      whole=number.substring(0,first);
      fraction=number.substring(first+1);
      if(fraction.isEmpty()||fraction.length()>12)
        throw invalid(fieldCode,"INVALID_FRACTION");
      // A lone comma or dot followed by 3 digits can denote grouping, not decimal.
      // Require an explicit external import profile before accepting it.
      if(fraction.length()==3&&whole.matches("[0-9]{1,3}"))
        throw invalid(fieldCode,"AMBIGUOUS_NUMBER");
    }else{
      whole=number;
    }
    if(whole.isBlank()||!whole.matches("[0-9]+( [0-9]{3})*"))
      throw invalid(fieldCode,"INVALID_GROUPING");
    if(sep==null&&number.indexOf(' ')>=0 && !whole.matches("[0-9]{1,3}( [0-9]{3})+"))
      throw invalid(fieldCode,"INVALID_GROUPING");
    if(whole.indexOf(' ')>=0&&!whole.matches("[0-9]{1,3}( [0-9]{3})+"))
      throw invalid(fieldCode,"INVALID_GROUPING");
    String digits=whole.replace(" ","");
    if(digits.length()>26)throw invalid(fieldCode,"NUMBER_TOO_LONG");
    if("INTEGER".equals(fieldType)&&sep!=null)throw invalid(fieldCode,"INVALID_INTEGER");
    return sign+digits+(sep==null?"":"."+fraction);
  }

  private static ResponseStatusException invalid(String field,String reason){
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
      "IMPORT_FIELD:"+field+":"+reason);
  }
}
