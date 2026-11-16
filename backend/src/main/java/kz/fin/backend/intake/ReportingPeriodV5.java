package kz.fin.backend.intake;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** ISO-calendar, non-overlapping half-open source periods, without assuming YearMonth for all forms. */
public final class ReportingPeriodV5 {
    public enum Type { WEEK, MONTH, QUARTER, HALF_YEAR, YEAR, AS_OF }
    public enum Coverage { PERIOD_ONLY, YEAR_TO_DATE, LIFE_TO_DATE, AS_OF }
    public record Period(Type type,String code,String calendarId,String calendarVersion,
                         Coverage coverage,LocalDate startInclusive,LocalDate endExclusive,
                         LocalDate asOfDate) {}
    private ReportingPeriodV5(){}
    private static Matcher match(String pattern,String code,String error){
        Matcher m=Pattern.compile(pattern).matcher(code);
        if(!m.matches())throw new IllegalArgumentException(error);
        return m;
    }
    private static int y(String text){
        int year=Integer.parseInt(text);
        if(year<1900||year>9998)throw new IllegalArgumentException("INVALID_PERIOD_YEAR");
        return year;
    }
    public static Period resolve(Type type,String code,Coverage coverage){
        if(type==null||code==null||coverage==null)
            throw new IllegalArgumentException("INVALID_PERIOD_INPUT");
        if((type==Type.AS_OF)!=(coverage==Coverage.AS_OF))
            throw new IllegalArgumentException("PERIOD_COVERAGE_MISMATCH");
        LocalDate start;LocalDate end;
        try{
            switch(type){
                case WEEK -> {
                    Matcher m=match("(\\d{4})-W(\\d{2})",code,"INVALID_ISO_WEEK");
                    int year=y(m.group(1));int week=Integer.parseInt(m.group(2));
                    if(week<1||week>53)throw new IllegalArgumentException("INVALID_ISO_WEEK");
                    start=LocalDate.of(year,1,4)
                      .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(week-1);
                    if(start.get(IsoFields.WEEK_BASED_YEAR)!=year||
                       start.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)!=week)
                       throw new IllegalArgumentException("INVALID_ISO_WEEK");
                    end=start.plusWeeks(1);
                }
                case MONTH -> {
                    Matcher m=match("(\\d{4})\\.(0[1-9]|1[0-2])",code,"INVALID_MONTH");
                    start=YearMonth.of(y(m.group(1)),Integer.parseInt(m.group(2))).atDay(1);
                    end=start.plusMonths(1);
                }
                case QUARTER -> {
                    Matcher m=match("(\\d{4})-Q([1-4])",code,"INVALID_QUARTER");
                    start=LocalDate.of(y(m.group(1)),(Integer.parseInt(m.group(2))-1)*3+1,1);
                    end=start.plusMonths(3);
                }
                case HALF_YEAR -> {
                    Matcher m=match("(\\d{4})-H([12])",code,"INVALID_HALF_YEAR");
                    start=LocalDate.of(y(m.group(1)),(Integer.parseInt(m.group(2))-1)*6+1,1);
                    end=start.plusMonths(6);
                }
                case YEAR -> {
                    match("\\d{4}",code,"INVALID_PERIOD_YEAR");
                    start=LocalDate.of(y(code),1,1);end=start.plusYears(1);
                }
                case AS_OF -> {
                    match("\\d{4}-\\d{2}-\\d{2}",code,"INVALID_AS_OF");
                    LocalDate date=LocalDate.parse(code);
                    if(date.getYear()<1900||date.getYear()>9998)
                        throw new IllegalArgumentException("INVALID_AS_OF");
                    return new Period(type,code,"ISO_CALENDAR","1",coverage,null,null,date);
                }
                default -> throw new IllegalArgumentException("INVALID_PERIOD_TYPE");
            }
        }catch(java.time.DateTimeException e){throw new IllegalArgumentException("INVALID_PERIOD_DATE",e);}
        return new Period(type,code,"ISO_CALENDAR","1",coverage,start,end,null);
    }
}
