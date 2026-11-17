package kz.fin.formlib.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * Canonical, deterministic consecutive M/D reporting-period selection.
 * Calendar colors are presentation metadata and not part of financial content.
 * Saturday and Sunday are weekends; public holidays require a separate calendar.
 */
public final class PeriodBatchPlanner {
  private PeriodBatchPlanner() {}
  public record CalendarPeriod(String periodType,String periodCode,
      LocalDate startInclusive,LocalDate endExclusive,
      String dayOfWeek,boolean saturday,boolean sunday,boolean weekend,
      String calendarStyleToken) {}
  public record PeriodBatch(String periodType,String startPeriod,int periodCount,
      int maxPeriodCount,LocalDate rangeStartInclusive,
      LocalDate rangeEndExclusive,List<CalendarPeriod> periods) {}

  private static ResponseStatusException invalid(String reason) {
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,reason);
  }
  public static CalendarPeriod decorate(PeriodResolver.Range p){
    if(!"D".equals(p.periodType()))
      return new CalendarPeriod(p.periodType(),p.periodCode(),p.startInclusive(),
        p.endExclusive(),null,false,false,false,"PERIOD_STANDARD");
    DayOfWeek day=p.startInclusive().getDayOfWeek();
    boolean saturday=day==DayOfWeek.SATURDAY,sunday=day==DayOfWeek.SUNDAY;
    return new CalendarPeriod(p.periodType(),p.periodCode(),p.startInclusive(),
      p.endExclusive(),day.name(),saturday,sunday,saturday||sunday,
      saturday?"WEEKEND_SATURDAY":sunday?"WEEKEND_SUNDAY":"WEEKDAY");
  }
  public static PeriodBatch plan(String periodType,String startPeriod,Integer count) {
    if(count==null)throw invalid("PERIOD_COUNT_REQUIRED");
    PeriodResolver.Range first;
    try{ first=PeriodResolver.resolve(periodType,startPeriod); }
    catch(IllegalArgumentException e){ throw invalid("INVALID_START_PERIOD"); }
    String canonicalType=first.periodType();
    int max=switch(canonicalType) {
      case "M"->12;
      case "D"->31;
      default->throw invalid("RANGE_ONLY_DAILY_OR_MONTHLY");
    };
    if(count<1||count>max)throw invalid("PERIOD_COUNT_OUT_OF_RANGE");
    List<CalendarPeriod> values=new ArrayList<>(count);
    for(int i=0;i<count;i++){
      String code=canonicalType.equals("M")
        ?YearMonth.from(first.startInclusive()).plusMonths(i).toString()
        :first.startInclusive().plusDays(i).toString();
      values.add(decorate(PeriodResolver.resolve(canonicalType,code)));
    }
    var last=values.getLast();
    return new PeriodBatch(canonicalType,first.periodCode(),count,max,
      first.startInclusive(),last.endExclusive(),List.copyOf(values));
  }
}
