package kz.fin.formlib.calc.worker;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bounded 2-4-way PostgreSQL UAT worker. Separate from R0.1 dispatcher.
 * Both worker and scheduler flags must be explicitly ON; default runtime OFF.
 * No auto-financial approval and no user-facing current result endpoint.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name={"fin.calc.uat.worker.enabled",
    "fin.calc.uat.worker.scheduler.enabled"},havingValue="true")
public class UatF02CalcScheduler {
  private static final org.slf4j.Logger LOG=
      org.slf4j.LoggerFactory.getLogger(UatF02CalcScheduler.class);

  private final UatF02CalcWorker worker;
  private final ExecutorService workers;
  private final int parallelism;
  private final String nodeId;
  private final AtomicBoolean inProgress=new AtomicBoolean(false);

  public UatF02CalcScheduler(UatF02CalcWorker worker,
      @Value("$"+"{fin.calc.uat.worker.parallelism:2}") int parallelism) {
    if(parallelism<2||parallelism>4)
      throw new IllegalArgumentException("CALC_R02_UAT_WORKERS_MUST_BE_2_TO_4");
    this.worker=worker;
    this.parallelism=parallelism;
    this.nodeId="calc-r02-uat-"+UUID.randomUUID();
    this.workers=Executors.newFixedThreadPool(parallelism,
        Thread.ofPlatform().name("calc-r02-uat-",0).factory());
  }

  @Scheduled(fixedDelayString="$"+"{fin.calc.uat.worker.scheduler.delay-ms:5000}")
  public void tick(){
    if(!inProgress.compareAndSet(false,true))return;
    try{
      var tasks=new ArrayList<Callable<UatF02CalcWorker.WorkResult>>();
      for(int i=0;i<parallelism;i++){
        final String owner=nodeId+"-"+i;
        tasks.add(()->worker.runOne(owner));
      }
      // The scheduler thread may wait for 2-4 jobs, but *only* the bounded
      // dedicated pool does DB/CPU work. No common ForkJoin pool.
      for(Future<UatF02CalcWorker.WorkResult> future:workers.invokeAll(tasks)){
        try{
          var work=future.get();
          if(work!=null)
            LOG.info("CALC_R02_JOB_DONE job={} result={} publication={}",
                work.claim().jobId(),work.completion().resultId(),
                work.publication());
        }catch(ExecutionException ex){
          // A crashed worker leaves a fenced RUNNING lease that may be
          // reclaimed after expiry, never an unguarded result write.
          LOG.error("CALC_R02_UAT_JOB_FAILED; fenced lease retained for recovery",
              ex.getCause());
        }
      }
    }catch(InterruptedException ex){
      Thread.currentThread().interrupt();
      LOG.warn("CALC_R02_UAT_POLL_INTERRUPTED");
    }catch(RuntimeException ex){
      LOG.error("CALC_R02_UAT_POLL_FAILED",ex);
    }finally{
      inProgress.set(false);
    }
  }

  @PreDestroy public void shutdown(){
    workers.shutdownNow();
  }
}
