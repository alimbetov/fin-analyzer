package kz.fin.formlib;

import kz.fin.formlib.service.PeriodBatchPlanner;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PeriodBatchPlannerUnitTest {
  @Test void monthBatchSupportsOneAndTwelveAcrossYearBoundary(){
    var one=PeriodBatchPlanner.plan("M","2026-12",1);
    assertEquals(1,one.periods().size());
    assertEquals("2026-12",one.periods().getFirst().periodCode());
    assertEquals(LocalDate.of(2027,1,1),one.rangeEndExclusive());
    var all=PeriodBatchPlanner.plan("MONTH","2026-03",12);
    assertEquals("M",all.periodType());
    assertEquals(12,all.maxPeriodCount());
    assertEquals(12,all.periodCount());
    assertEquals("2027-02",all.periods().getLast().periodCode());
    assertEquals(LocalDate.of(2027,3,1),all.rangeEndExclusive());
    assertTrue(all.periods().stream().noneMatch(p->p.weekend()||p.dayOfWeek()!=null));
    assertTrue(all.periods().stream().allMatch(p->p.calendarStyleToken().equals("PERIOD_STANDARD")));
  }

  @Test void dayBatchSupportsOneAndThirtyOneAcrossMonthBoundary(){
    var one=PeriodBatchPlanner.plan("D","2026-11-17",1);
    assertEquals("TUESDAY",one.periods().getFirst().dayOfWeek());
    assertEquals("2026-11-17",one.periods().getFirst().periodCode());
    var all=PeriodBatchPlanner.plan("DAY","2026-11-17",31);
    assertEquals(31,all.periodCount());
    assertEquals(31,all.maxPeriodCount());
    assertEquals("2026-12-17",all.periods().getLast().periodCode());
    assertEquals(LocalDate.of(2026,12,18),all.rangeEndExclusive());
  }

  @Test void saturdayAndSundayHaveDifferentStyleTokensAndNoHolidayGuessing(){
    var plan=PeriodBatchPlanner.plan("D","2026-11-01",31);
    assertEquals(31,plan.periods().size());
    assertEquals("SUNDAY",plan.periods().get(0).dayOfWeek());
    assertTrue(plan.periods().get(0).sunday());
    assertEquals("WEEKEND_SUNDAY",plan.periods().get(0).calendarStyleToken());
    assertEquals("SATURDAY",plan.periods().get(6).dayOfWeek());
    assertTrue(plan.periods().get(6).saturday());
    assertEquals("WEEKEND_SATURDAY",plan.periods().get(6).calendarStyleToken());
    assertEquals("WEEKDAY",plan.periods().get(1).calendarStyleToken());
    assertEquals(9,plan.periods().stream().filter(p->p.weekend()).count());
    assertEquals(4,plan.periods().stream().filter(p->p.saturday()).count());
    assertEquals(5,plan.periods().stream().filter(p->p.sunday()).count());
  }

  @Test void leapYearAndDateBoundariesAreCalendarCorrect(){
    var plan=PeriodBatchPlanner.plan("D","2028-02-28",3);
    assertEquals(List.of("2028-02-28","2028-02-29","2028-03-01"),
      plan.periods().stream().map(p->p.periodCode()).toList());
    assertEquals(LocalDate.of(2028,3,2),plan.rangeEndExclusive());
  }

  @Test void invalidRequestsCannotCreateUnboundedBatches(){
    for(int count:List.of(0,-1,13,100))
      assertThrows(ResponseStatusException.class,()->PeriodBatchPlanner.plan("M","2026-01",count));
    for(int count:List.of(0,-1,32,100))
      assertThrows(ResponseStatusException.class,()->PeriodBatchPlanner.plan("D","2026-11-01",count));
    assertThrows(ResponseStatusException.class,()->PeriodBatchPlanner.plan("M","2026-01",null));
    assertThrows(ResponseStatusException.class,()->PeriodBatchPlanner.plan("Q","2026-Q1",2));
    assertThrows(ResponseStatusException.class,()->PeriodBatchPlanner.plan("D","2026-02-30",1));
    assertThrows(ResponseStatusException.class,()->PeriodBatchPlanner.plan("M","2026-13",1));
    assertThrows(ResponseStatusException.class,()->PeriodBatchPlanner.plan("M",null,1));
  }
}
