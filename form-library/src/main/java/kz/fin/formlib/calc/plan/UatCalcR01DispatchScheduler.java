package kz.fin.formlib.calc.plan;

import kz.fin.formlib.calc.fact.UatCalcR01OutboxDispatcher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Deliberate opt-in, LOCAL UAT only. Disabled by default. No scheduler is
 * registered unless both the TEST_ONLY dispatcher and UAT schedule flags
 * have been explicitly enabled.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name={"fin.calc.uat.dispatch.enabled",
    "fin.calc.uat.scheduler.enabled"},havingValue="true")
public class UatCalcR01DispatchScheduler {
  private final UatCalcR01OutboxDispatcher dispatcher;
  public UatCalcR01DispatchScheduler(UatCalcR01OutboxDispatcher dispatcher){
    this.dispatcher=dispatcher;
  }

  @Scheduled(fixedDelayString="$"+"{fin.calc.uat.scheduler.delay-ms:5000}")
  public void poll(){
    try{
      dispatcher.dispatchBatch(16);
    }catch(RuntimeException error){
      // Non-acknowledged events remain pending. No permissive fallback or
      // poison-event auto-confirmation on any integrity/DB error.
      org.slf4j.LoggerFactory.getLogger(getClass())
          .error("CALC_R01_DISPATCH_FAILED: pending event retained",error);
    }
  }
}
