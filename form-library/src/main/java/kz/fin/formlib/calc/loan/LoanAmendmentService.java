package kz.fin.formlib.calc.loan;

import kz.fin.formlib.calc.loan.LoanAmendmentCalculator.Event;
import kz.fin.formlib.calc.loan.LoanAmendmentCalculator.Line;
import kz.fin.formlib.calc.loan.LoanAmendmentCalculator.Result;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Exact C05 -> exact A05 schedule -> immutable amendment scenario only. */
@Service
public class LoanAmendmentService {
  public record Command(UUID organizationId,UUID projectId,
      UUID parentScheduleRevisionId,Event event) {}
  public record View(UUID amendmentRevisionId,UUID parentScheduleRevisionId,
      UUID exactSourceRevisionId,String sourceSha256,String termsSha256,
      String parentResultSha256,String engineDescriptorSha256,
      String eventSha256,String scenarioSha256,String resultSha256,
      Event event,Result result,String commandResult,
      boolean financiallyApproved,boolean productionAdmitted) {}
  public record Summary(UUID amendmentRevisionId,String eventKind,
      String eventSha256,String resultSha256,LocalDate actualMaturity,
      String state) {}
  private record Stored(UUID id,UUID parent,UUID source,UUID contract,
      UUID app,String sourceSha,String termsSha,String parentSha,
      String engineSha,String eventSha,String scenarioSha,String resultSha,
      Event event,String method,LocalDate maturity,BigDecimal opening,
      BigDecimal principal,BigDecimal extra,BigDecimal capitalized,
      BigDecimal interest,BigDecimal fees,BigDecimal cash,BigDecimal finalBalance,
      int count,String state) {}

  private final JdbcTemplate jdbc;
  private final LoanContractIntakeService loans;
  private final LoanScheduleService schedules;
  private final LoanAmendmentCalculator calculator;
  public LoanAmendmentService(JdbcTemplate jdbc,LoanContractIntakeService loans,
      LoanScheduleService schedules,LoanAmendmentCalculator calculator) {
    this.jdbc=Objects.requireNonNull(jdbc);
    this.loans=Objects.requireNonNull(loans);
    this.schedules=Objects.requireNonNull(schedules);
    this.calculator=Objects.requireNonNull(calculator);
  }
  private static ResponseStatusException invalid(String code){
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,code);
  }
  private static String decimal(BigDecimal value){
    return value==null?"NONE":value.stripTrailingZeros().toPlainString();
  }
  private static String text(Object value){
    return value==null?"NONE":value.toString();
  }
  private static String eventDigest(Event event){
    return LoanScheduleService.sha("FIN-A05-R12-EVENT-V1|"+text(event.kind())+
      "|"+text(event.effectiveDate())+"|"+decimal(event.prepaymentAmount())+
      "|"+text(event.prepaymentPolicy())+"|"+text(event.graceThroughDate())+
      "|"+text(event.graceMode())+"|"+decimal(event.revisedAnnualRatePct()));
  }
  private static String resultDigest(Result result){
    var b=new StringBuilder("FIN-A05-R12-RESULT-V1|")
      .append(result.method()).append('|').append(result.actualMaturityDate());
    for(BigDecimal v:List.of(result.originalOpeningPrincipal(),
        result.totalScheduledPrincipal(),result.totalExtraPrincipal(),
        result.totalCapitalizedInterest(),result.totalCashInterest(),
        result.totalScheduledFees(),result.totalCashDue(),result.finalPrincipal()))
      b.append('|').append(decimal(v));
    for(var line:result.lines()){
      b.append("|L:").append(line.installmentNo()).append('|')
        .append(line.accrualFrom()).append('|').append(line.dueAt());
      for(BigDecimal v:List.of(line.openingPrincipal(),line.annualRatePct(),
          line.contractualInterestRaw(),line.cashInterestDue(),
          line.capitalizedInterest(),line.scheduledPrincipal(),
          line.extraPrincipal(),line.feesDue(),line.closingPrincipal(),
          line.totalCashDue())) b.append('|').append(decimal(v));
      line.feeBreakdown().entrySet().stream().sorted(Map.Entry.comparingByKey())
        .forEach(e->b.append("|F:").append(e.getKey()).append('=')
          .append(decimal(e.getValue())));
    }
    b.append('|').append(result.state());
    return LoanScheduleService.sha(b.toString());
  }
  private static Stored row(ResultSet r,int i)throws SQLException{
    return new Stored(r.getObject(1,UUID.class),r.getObject(2,UUID.class),
      r.getObject(3,UUID.class),r.getObject(4,UUID.class),
      r.getObject(5,UUID.class),r.getString(6).trim(),r.getString(7).trim(),
      r.getString(8).trim(),r.getString(9).trim(),r.getString(10).trim(),
      r.getString(11).trim(),r.getString(12).trim(),
      new Event(r.getString(13),r.getObject(14,LocalDate.class),
        r.getBigDecimal(15),r.getString(16),r.getObject(17,LocalDate.class),
        r.getString(18),r.getBigDecimal(19)),r.getString(20),
      r.getObject(21,LocalDate.class),r.getBigDecimal(22),r.getBigDecimal(23),
      r.getBigDecimal(24),r.getBigDecimal(25),r.getBigDecimal(26),
      r.getBigDecimal(27),r.getBigDecimal(28),r.getBigDecimal(29),
      r.getInt(30),r.getString(31));
  }

  @Transactional(isolation=Isolation.READ_COMMITTED)
  public View calculateAndCommit(Command command,String idempotencyKey){
    if(command==null||command.organizationId()==null
       ||command.projectId()==null||command.parentScheduleRevisionId()==null
       ||command.event()==null||idempotencyKey==null
       ||idempotencyKey.isBlank()||idempotencyKey.length()>200)
      throw invalid("A05_R12_PARENT_SCOPE_EVENT_KEY_REQUIRED");
    // Lock by exact organization+command idempotency, not global/loan-level.
    jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
      (r,i)->r.getString(1),command.organizationId()+"|A05-R12|"+idempotencyKey);
    String eventSha=eventDigest(command.event());
    String requestSha=LoanScheduleService.sha("FIN-A05-R12-COMMAND-V1|"+
      command.organizationId()+"|"+command.projectId()+"|"+
      command.parentScheduleRevisionId()+"|"+eventSha);
    var receipts=jdbc.query("""
      SELECT amendment_revision_id,request_sha256
      FROM fin_calc.a05_amendment_command_receipt
      WHERE organization_id=? AND idempotency_key=?
      """,(r,i)->new String[]{r.getString(1),r.getString(2).trim()},
      command.organizationId(),idempotencyKey);
    if(!receipts.isEmpty()){
      if(!requestSha.equals(receipts.getFirst()[1]))
        throw new ResponseStatusException(HttpStatus.CONFLICT,
          "A05_R12_IDEMPOTENCY_KEY_REUSED");
      return readExact(command.organizationId(),command.projectId(),
        UUID.fromString(receipts.getFirst()[0]),"IDEMPOTENT_REPLAY");
    }
    var parent=schedules.readExact(command.organizationId(),command.projectId(),
      command.parentScheduleRevisionId());
    var source=loans.readExact(command.organizationId(),command.projectId(),
      parent.exactSourceRevisionId());
    Result result=calculator.calculate(source.terms(),source.fees(),
      parent.result(),command.event());
    String engineSha=LoanScheduleService.sha(LoanAmendmentCalculator.ENGINE_ID);
    String scenarioSha=LoanScheduleService.sha("FIN-A05-R12-SCENARIO-V1|"+
      parent.scheduleRevisionId()+"|"+parent.resultSha256()+"|"+
      source.receipt().termsSha256()+"|"+parent.appContractRevisionId()+"|"+
      engineSha+"|"+eventSha);
    String resultSha=resultDigest(result);
    UUID id=UUID.randomUUID();
    int inserted=jdbc.update("""
      INSERT INTO fin_calc.a05_amendment_revision(
      id,organization_id,project_id,parent_schedule_revision_id,
      source_revision_id,contract_revision_id,app_contract_revision_id,
      source_content_sha256,terms_sha256,parent_result_sha256,
      engine_descriptor_sha256,event_sha256,scenario_sha256,result_sha256,
      event_kind,effective_date,prepayment_amount,prepayment_policy,
      grace_through_date,grace_mode,revised_annual_rate_pct,
      repayment_method,actual_maturity_date,original_opening_principal,
      total_scheduled_principal,total_extra_principal,total_capitalized_interest,
      total_cash_interest,total_scheduled_fees,total_cash_due,final_principal,row_count)
      VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
      ON CONFLICT(parent_schedule_revision_id,scenario_sha256) DO NOTHING
      """,id,command.organizationId(),command.projectId(),
      parent.scheduleRevisionId(),parent.exactSourceRevisionId(),
      parent.contractRevisionId(),parent.appContractRevisionId(),
      parent.sourceContentSha256(),parent.termsSha256(),parent.resultSha256(),
      engineSha,eventSha,scenarioSha,resultSha,
      command.event().kind(),command.event().effectiveDate(),
      command.event().prepaymentAmount(),command.event().prepaymentPolicy(),
      command.event().graceThroughDate(),command.event().graceMode(),
      command.event().revisedAnnualRatePct(),result.method(),
      result.actualMaturityDate(),result.originalOpeningPrincipal(),
      result.totalScheduledPrincipal(),result.totalExtraPrincipal(),
      result.totalCapitalizedInterest(),result.totalCashInterest(),
      result.totalScheduledFees(),result.totalCashDue(),
      result.finalPrincipal(),result.lines().size());
    if(inserted==1){
      for(var line:result.lines()){
        jdbc.update("""
          INSERT INTO fin_calc.a05_amendment_line(
          amendment_revision_id,installment_no,accrual_from,due_at,
          opening_principal,annual_rate_pct,contractual_interest_raw,
          cash_interest_due,capitalized_interest,scheduled_principal,
          extra_principal,fees_due,closing_principal,total_cash_due)
          VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
          """,id,line.installmentNo(),line.accrualFrom(),line.dueAt(),
          line.openingPrincipal(),line.annualRatePct(),line.contractualInterestRaw(),
          line.cashInterestDue(),line.capitalizedInterest(),
          line.scheduledPrincipal(),line.extraPrincipal(),line.feesDue(),
          line.closingPrincipal(),line.totalCashDue());
        for(var entry:line.feeBreakdown().entrySet())
          jdbc.update("""
            INSERT INTO fin_calc.a05_amendment_line_fee(
              amendment_revision_id,installment_no,fee_code,fee_amount)
            VALUES(?,?,?,?)
            """,id,line.installmentNo(),entry.getKey(),entry.getValue());
      }
    }else{
      var existing=jdbc.query("""
        SELECT id,result_sha256 FROM fin_calc.a05_amendment_revision
        WHERE parent_schedule_revision_id=? AND scenario_sha256=?
        """,(r,i)->new String[]{r.getString(1),r.getString(2).trim()},
        parent.scheduleRevisionId(),scenarioSha);
      if(existing.size()!=1||!resultSha.equals(existing.getFirst()[1]))
        throw new IllegalStateException("A05_R12_VARIANT_NONDETERMINISM");
      id=UUID.fromString(existing.getFirst()[0]);
    }
    jdbc.update("""
      INSERT INTO fin_calc.a05_amendment_command_receipt
      (id,organization_id,idempotency_key,request_sha256,amendment_revision_id)
      VALUES(?,?,?,?,?)
      """,UUID.randomUUID(),command.organizationId(),idempotencyKey,requestSha,id);
    return readExact(command.organizationId(),command.projectId(),id,
      inserted==1?"UAT_COMMITTED":"EXISTING_VARIANT_REPLAY");
  }

  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
  public View readExact(UUID organizationId,UUID projectId,UUID amendmentId){
    return readExact(organizationId,projectId,amendmentId,"READ_ONLY");
  }
  private View readExact(UUID organizationId,UUID projectId,
      UUID amendmentId,String commandResult) {
    if(organizationId==null||projectId==null||amendmentId==null)
      throw invalid("A05_R12_EXACT_AMENDMENT_SCOPE_REQUIRED");
    var rows=jdbc.query("""
      SELECT id,parent_schedule_revision_id,source_revision_id,
      contract_revision_id,app_contract_revision_id,source_content_sha256,
      terms_sha256,parent_result_sha256,engine_descriptor_sha256,event_sha256,
      scenario_sha256,result_sha256,event_kind,effective_date,
      prepayment_amount,prepayment_policy,grace_through_date,grace_mode,
      revised_annual_rate_pct,repayment_method,actual_maturity_date,
      original_opening_principal,total_scheduled_principal,total_extra_principal,
      total_capitalized_interest,total_cash_interest,total_scheduled_fees,
      total_cash_due,final_principal,row_count,state
      FROM fin_calc.a05_amendment_revision
      WHERE id=? AND organization_id=? AND project_id=?
      """,LoanAmendmentService::row,amendmentId,organizationId,projectId);
    if(rows.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,
      "A05_R12_AMENDMENT_NOT_FOUND");
    var s=rows.getFirst();
    var parent=schedules.readExact(organizationId,projectId,s.parent());
    var loan=loans.readExact(organizationId,projectId,s.source());
    if(!s.source().equals(parent.exactSourceRevisionId())
        ||!s.contract().equals(parent.contractRevisionId())
        ||!s.app().equals(parent.appContractRevisionId())
        ||!s.sourceSha().equals(parent.sourceContentSha256())
        ||!s.termsSha().equals(parent.termsSha256())
        ||!s.parentSha().equals(parent.resultSha256())
        ||!s.engineSha().equals(LoanScheduleService.sha(LoanAmendmentCalculator.ENGINE_ID))
        ||!s.eventSha().equals(eventDigest(s.event()))
        ||!"UAT_SCENARIO_NOT_PAYMENT_FACT".equals(s.state()))
      throw new IllegalStateException("A05_R12_PARENT_OR_EVENT_PIN_DRIFT");
    String scenario=LoanScheduleService.sha("FIN-A05-R12-SCENARIO-V1|"+
      parent.scheduleRevisionId()+"|"+parent.resultSha256()+"|"+
      loan.receipt().termsSha256()+"|"+parent.appContractRevisionId()+"|"+
      s.engineSha()+"|"+s.eventSha());
    var rechecked=calculator.calculate(loan.terms(),loan.fees(),parent.result(),s.event());
    if(!scenario.equals(s.scenarioSha())||!resultDigest(rechecked).equals(s.resultSha()))
      throw new IllegalStateException("A05_R12_CALCULATION_SHA_DRIFT");
    var feeEntries=jdbc.query("""
      SELECT installment_no,fee_code,fee_amount
      FROM fin_calc.a05_amendment_line_fee WHERE amendment_revision_id=?
      ORDER BY installment_no,fee_code
      """,(r,i)->new Object[]{r.getInt(1),r.getString(2),r.getBigDecimal(3)},s.id());
    Map<Integer,Map<String,BigDecimal>> feeMap=new LinkedHashMap<>();
    for(var row:feeEntries)
      feeMap.computeIfAbsent((Integer)row[0],ignored->new LinkedHashMap<>())
        .put((String)row[1],(BigDecimal)row[2]);
    List<Line> lines=jdbc.query("""
      SELECT installment_no,accrual_from,due_at,opening_principal,
        annual_rate_pct,contractual_interest_raw,cash_interest_due,
        capitalized_interest,scheduled_principal,extra_principal,fees_due,
        closing_principal,total_cash_due
      FROM fin_calc.a05_amendment_line WHERE amendment_revision_id=?
      ORDER BY installment_no
      """,(r,i)->new Line(r.getInt(1),r.getObject(2,LocalDate.class),
        r.getObject(3,LocalDate.class),r.getBigDecimal(4),r.getBigDecimal(5),
        r.getBigDecimal(6),r.getBigDecimal(7),r.getBigDecimal(8),
        r.getBigDecimal(9),r.getBigDecimal(10),r.getBigDecimal(11),
        r.getBigDecimal(12),r.getBigDecimal(13),
        feeMap.getOrDefault(r.getInt(1),Map.of())),s.id());
    var storedResult=new Result(s.method(),s.maturity(),lines,s.opening(),
      s.principal(),s.extra(),s.capitalized(),s.interest(),s.fees(),
      s.cash(),s.finalBalance(),s.state());
    if(lines.size()!=s.count()||!resultDigest(storedResult).equals(s.resultSha()))
      throw new IllegalStateException("A05_R12_PERSISTED_LINES_OR_FEES_SHA_DRIFT");
    return new View(s.id(),s.parent(),s.source(),s.sourceSha(),s.termsSha(),
      s.parentSha(),s.engineSha(),s.eventSha(),s.scenarioSha(),s.resultSha(),
      s.event(),storedResult,commandResult,false,false);
  }

  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
  public List<Summary> list(UUID organizationId,UUID projectId,UUID parentScheduleId){
    if(organizationId==null||projectId==null||parentScheduleId==null)
      throw invalid("A05_R12_PARENT_ID_REQUIRED");
    schedules.readExact(organizationId,projectId,parentScheduleId);
    return jdbc.query("""
      SELECT id,event_kind,event_sha256,result_sha256,actual_maturity_date,state
      FROM fin_calc.a05_amendment_revision WHERE organization_id=?
       AND project_id=? AND parent_schedule_revision_id=?
      ORDER BY created_at,id
      """,(r,i)->new Summary(r.getObject(1,UUID.class),r.getString(2),
        r.getString(3).trim(),r.getString(4).trim(),
        r.getObject(5,LocalDate.class),r.getString(6)),
      organizationId,projectId,parentScheduleId);
  }
}
