package kz.fin.formlib.calc.partition;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PostgreSQL 16 fin_fact.fact_observation monthly partition maintenance.
 *
 * Not called during FormDraftService.save / FactSet TX-Q. Whitelisted parent
 * only; no arbitrary SQL identifiers, auto-DROP, DEFAULT partition or HTTP API.
 */
@Service
public final class FactMonthlyPartitionService {
  public enum State { EXISTS, CREATED, WOULD_CREATE, MISSING, INVALID }
  public record MonthStatus(YearMonth month, State state, String detail) {}
  public record PartitionStats(List<MonthStatus> months) {
    public long created() { return months.stream().filter(x->x.state()==State.CREATED).count(); }
    public long existed() { return months.stream().filter(x->x.state()==State.EXISTS).count(); }
  }
  public record PartitionHealth(List<MonthStatus> months) {
    public boolean complete() {
      return months.stream().allMatch(x->x.state()==State.EXISTS);
    }
  }

  private static final String PARENT = "fin_fact.fact_observation";
  private static final int MAX_MONTHS = 60;
  private static final Pattern DATE_TOKEN=Pattern.compile("'(\\d{4}-\\d{2}-\\d{2})'");
  private final JdbcTemplate jdbc;
  private final PlatformTransactionManager txManager;

  public FactMonthlyPartitionService(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
    this.jdbc=Objects.requireNonNull(jdbc);
    this.txManager=Objects.requireNonNull(txManager);
  }

  public PartitionStats ensureMonthlyRange(YearMonth first, YearMonth last, boolean dryRun) {
    checkNoCallerTransaction();
    var months=months(first,last);
    var results=new ArrayList<MonthStatus>();
    for(var month:months) {
      if(dryRun) {
        var status=inspectOne(month);
        if(status.state()==State.MISSING)
          status=new MonthStatus(month,State.WOULD_CREATE,"no DDL executed");
        results.add(status);
      }else{
        results.add(ensureOne(month));
      }
    }
    return new PartitionStats(List.copyOf(results));
  }

  public PartitionHealth inspectRequiredCoverage(YearMonth first, YearMonth last) {
    var checked=new ArrayList<MonthStatus>();
    for(var month:months(first,last))checked.add(inspectOne(month));
    return new PartitionHealth(List.copyOf(checked));
  }

  private MonthStatus ensureOne(YearMonth month) {
    for(int attempt=1;attempt<=5;attempt++) {
      try{
        var tx=new TransactionTemplate(txManager);
        tx.setTimeout(20);
        tx.setName("fin-fact-monthly-partition-"+month);
        return Objects.requireNonNull(tx.execute(status -> {
          jdbc.execute("SET LOCAL lock_timeout = '1500ms'");
          jdbc.execute("SET LOCAL statement_timeout = '12000ms'");
          Boolean locked=jdbc.queryForObject(
              "SELECT pg_try_advisory_xact_lock(hashtext(?),hashtext(?))",
              Boolean.class,PARENT,month.toString());
          if(!Boolean.TRUE.equals(locked))
            throw new IllegalStateException("FACT_PARTITION_LOCK_BUSY:"+month);
          var existing=inspectOne(month);
          if(existing.state()==State.EXISTS)return existing;
          if(existing.state()!=State.MISSING)
            throw new IllegalStateException("FACT_PARTITION_CORRUPT:"+month+":"+existing.detail());
          String partition=childName(month);
          String start=month.atDay(1).toString();
          String end=month.plusMonths(1).atDay(1).toString();
          jdbc.execute("CREATE TABLE fin_fact."+partition+
              " PARTITION OF "+PARENT+" FOR VALUES FROM ('"+start+"') TO ('"+end+"')");
          var verified=inspectOne(month);
          if(verified.state()!=State.EXISTS)
            throw new IllegalStateException("FACT_PARTITION_CREATE_INTEGRITY:"+month);
          return new MonthStatus(month,State.CREATED,"created and verified");
        }));
      }catch(RuntimeException ex) {
        if(attempt==5 || !retryable(ex)) throw ex;
        try{Thread.sleep(75L*attempt);}catch(InterruptedException interrupted){
          Thread.currentThread().interrupt();
          throw new IllegalStateException("FACT_PARTITION_INTERRUPTED",interrupted);
        }
      }
    }
    throw new IllegalStateException("FACT_PARTITION_RETRIES_EXHAUSTED");
  }

  private MonthStatus inspectOne(YearMonth month) {
    String definition=jdbc.queryForObject(
        "SELECT pg_get_partkeydef('fin_fact.fact_observation'::regclass)",String.class);
    String columnType=jdbc.queryForObject("""
        SELECT a.atttypid::regtype::text
        FROM pg_attribute a
        WHERE a.attrelid='fin_fact.fact_observation'::regclass
          AND a.attname='period_start' AND a.attnum>0
        """,String.class);
    if(!"RANGE (period_start)".equals(definition)||!"date".equals(columnType))
      throw new IllegalStateException("FACT_PARTITION_PARENT_INVALID");

    String child=childName(month);
    var matches=jdbc.query("""
        SELECT c.relispartition, inh.inhparent::regclass::text,
               pg_get_expr(c.relpartbound,c.oid)
        FROM pg_class c
        JOIN pg_namespace n ON n.oid=c.relnamespace
        LEFT JOIN pg_inherits inh ON inh.inhrelid=c.oid
        WHERE n.nspname='fin_fact' AND c.relname=?
        """,(rs,i)->new Object[]{rs.getBoolean(1),rs.getString(2),rs.getString(3)},child);
    if(matches.isEmpty())return new MonthStatus(month,State.MISSING,"not provisioned");
    if(matches.size()!=1)return new MonthStatus(month,State.INVALID,"duplicate catalog child");
    Object[] match=matches.get(0);
    String bound=(String)match[2];
    if(!Boolean.TRUE.equals(match[0])||!PARENT.equals(match[1])||bound==null)
      return new MonthStatus(month,State.INVALID,"wrong partition parent");
    Matcher found=DATE_TOKEN.matcher(bound);
    var dates=new ArrayList<String>();
    while(found.find())dates.add(found.group(1));
    if(!bound.startsWith("FOR VALUES FROM")||dates.size()!=2
        ||!month.atDay(1).toString().equals(dates.get(0))
        ||!month.plusMonths(1).atDay(1).toString().equals(dates.get(1)))
      return new MonthStatus(month,State.INVALID,"unexpected partition bound:"+bound);
    return new MonthStatus(month,State.EXISTS,"bounds and parent verified");
  }

  private static String childName(YearMonth month) {
    return "fact_observation_"+month.toString().replace('-','_');
  }

  private static List<YearMonth> months(YearMonth first, YearMonth last) {
    if(first==null||last==null||first.isAfter(last))
      throw new IllegalArgumentException("FACT_PARTITION_RANGE_INVALID");
    var count=first.until(last,java.time.temporal.ChronoUnit.MONTHS)+1;
    if(count>MAX_MONTHS)throw new IllegalArgumentException("FACT_PARTITION_RANGE_TOO_LARGE");
    var list=new ArrayList<YearMonth>();
    for(int i=0;i<count;i++)list.add(first.plusMonths(i));
    return List.copyOf(list);
  }

  private static void checkNoCallerTransaction(){
    if(TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("FACT_PARTITION_DDL_INSIDE_BUSINESS_TX");
  }

  private static boolean retryable(Throwable error) {
    if(error instanceof IllegalStateException e && e.getMessage()!=null
        && e.getMessage().startsWith("FACT_PARTITION_LOCK_BUSY"))return true;
    Throwable cause=error;
    while(cause!=null){
      if(cause instanceof SQLException sql
          && ("40001".equals(sql.getSQLState())
              ||"40P01".equals(sql.getSQLState())
              ||"55P03".equals(sql.getSQLState()))) return true;
      cause=cause.getCause();
    }
    return false;
  }
}
