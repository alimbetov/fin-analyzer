package kz.fin.formlib.service;

import java.time.*;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.time.temporal.IsoFields;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** ISO week-based year. D/DEC/M/Q/H/Y are based on the proleptic Gregorian calendar. */
public final class PeriodResolver {
  private PeriodResolver(){}
  public record Range(String periodType, String periodCode,
                      LocalDate startInclusive, LocalDate endExclusive) {}
  private static final Pattern WEEK=Pattern.compile("(\\d{4})-W(\\d{2})");
  private static final Pattern DECAD=Pattern.compile("(\\d{4})-(\\d{2})-([123])");
  private static final Pattern QUARTER=Pattern.compile("(\\d{4})-Q([1-4])");
  private static final Pattern HALF=Pattern.compile("(\\d{4})-H([12])");
  private static final Pattern YEAR=Pattern.compile("\\d{4}");

  public static Range resolve(String requestedType,String requestedCode) {
    if(requestedType==null||requestedCode==null) throw new IllegalArgumentException("PERIOD_REQUIRED");
    String type=requestedType.trim().toUpperCase(java.util.Locale.ROOT);
    type=switch(type) {
      case "DAY","DAILY" -> "D";
      case "WEEK","WEEKLY" -> "W";
      case "DECADE","DECAD","TEN_DAY" -> "DEC";
      case "MONTH","MONTHLY" -> "M";
      case "QUARTER","QUARTERLY" -> "Q";
      case "HALF_YEAR","HALFYEAR","HALF_YEARLY" -> "H";
      case "YEAR","YEARLY","ANNUAL" -> "Y";
      default -> type;
    };
    String code=requestedCode.trim();
    try {
      LocalDate start,end;
      switch(type) {
        case "D" -> {start=LocalDate.parse(code);end=start.plusDays(1);code=start.toString();}
        case "W" -> {
          Matcher m=WEEK.matcher(code);
          if(!m.matches()) throw new IllegalArgumentException("INVALID_WEEK_CODE");
          int year=Integer.parseInt(m.group(1)), week=Integer.parseInt(m.group(2));
          start=LocalDate.of(year,1,4)
            .with(IsoFields.WEEK_OF_WEEK_BASED_YEAR,week)
            .with(ChronoField.DAY_OF_WEEK,1);
          if(start.get(IsoFields.WEEK_BASED_YEAR)!=year ||
             start.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)!=week ||
             week<1 || week>53) throw new IllegalArgumentException("INVALID_ISO_WEEK");
          end=start.plusDays(7);
          code=String.format(java.util.Locale.ROOT,"%04d-W%02d",year,week);
        }
        case "DEC" -> {
          Matcher m=DECAD.matcher(code);
          if(!m.matches()) throw new IllegalArgumentException("INVALID_DECAD_CODE");
          int y=Integer.parseInt(m.group(1)), mo=Integer.parseInt(m.group(2));
          int dec=Integer.parseInt(m.group(3));
          YearMonth ym=YearMonth.of(y,mo);
          start=ym.atDay(dec==1?1:dec==2?11:21);
          end=dec==3?ym.plusMonths(1).atDay(1):start.plusDays(10);
          code=ym+"-"+dec;
        }
        case "M" -> {
          // Backwards-compatible F02 pilot notation 2026.02.
          if(code.matches("\\d{4}\\.\\d{2}"))code=code.replace('.','-');
          YearMonth ym=YearMonth.parse(code);
          start=ym.atDay(1);end=ym.plusMonths(1).atDay(1);code=ym.toString();
        }
        case "Q" -> {
          Matcher m=QUARTER.matcher(code);
          if(!m.matches()) throw new IllegalArgumentException("INVALID_QUARTER_CODE");
          int y=Integer.parseInt(m.group(1)), q=Integer.parseInt(m.group(2));
          start=LocalDate.of(y,(q-1)*3+1,1);end=start.plusMonths(3);
          code=y+"-Q"+q;
        }
        case "H" -> {
          Matcher m=HALF.matcher(code);
          if(!m.matches()) throw new IllegalArgumentException("INVALID_HALF_YEAR_CODE");
          int y=Integer.parseInt(m.group(1)), h=Integer.parseInt(m.group(2));
          start=LocalDate.of(y,(h-1)*6+1,1);end=start.plusMonths(6);
          code=y+"-H"+h;
        }
        case "Y" -> {
          if(!YEAR.matcher(code).matches())throw new IllegalArgumentException("INVALID_YEAR_CODE");
          int y=Integer.parseInt(code);start=LocalDate.of(y,1,1);end=start.plusYears(1);
          code=String.format(java.util.Locale.ROOT,"%04d",y);
        }
        default -> throw new IllegalArgumentException("UNSUPPORTED_PERIOD_TYPE");
      }
      return new Range(type,code,start,end);
    }catch(DateTimeException|NumberFormatException e){
      throw new IllegalArgumentException("INVALID_PERIOD_CODE",e);
    }
  }
}
