package kz.fin.formlib.calc.loan;

import kz.fin.formlib.calc.loan.LoanPaymentScheduleCalculator.CustomInstallment;
import kz.fin.formlib.calc.loan.LoanPaymentScheduleCalculator.Funding;
import kz.fin.formlib.calc.loan.LoanPaymentScheduleCalculator.Result;
import kz.fin.formlib.calc.loan.LoanPaymentScheduleCalculator.ScheduleLine;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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

/**
 * A05-R1.1 immutable UAT contractual CASH schedule. Exact source pins and
 * catalog CALC-A05 contract (revision 1) are mandatory. The specialized
 * BigDecimal kernel is a node of CALC-A05, not a new universal formula engine.
 * This DOES NOT issue a qualified CalcPlan or calculate IFRS EIR/KZ GESV.
 */
@Service
public class LoanScheduleService {
  public record ScheduleCommand(UUID organizationId,UUID projectId,
      UUID exactSourceRevisionId,List<CustomInstallment> customInstallments) {
    public ScheduleCommand {
      customInstallments=customInstallments==null?List.of():List.copyOf(customInstallments);
    }
  }
  public record ScheduleView(UUID scheduleRevisionId,UUID exactSourceRevisionId,
      UUID contractRevisionId,UUID appContractRevisionId,
      String sourceContentSha256,String termsSha256,
      String engineDescriptorSha256,String customInputSha256,
      String variantSha256,String resultSha256,String state,
      Result result,String commandResult,boolean financiallyApproved,
      boolean productionAdmitted) {}

  private record AppContract(UUID id,String sha) {}
  private record Header(UUID id,UUID sourceId,UUID contractId,UUID appContractId,
      String appSha,String sourceSha,String termsSha,String engineSha,
      String customSha,String variantSha,String resultSha,String method,
      String currency,String dayCount,int scale,String rounding,
      Funding funding,BigDecimal totalPrincipal,BigDecimal totalInterest,
      BigDecimal totalFees,BigDecimal totalCash,BigDecimal finalBalance,
      int rowCount,String state) {}

  private final JdbcTemplate db;
  private final LoanContractIntakeService loan;
  private final LoanPaymentScheduleCalculator calculator;

  public LoanScheduleService(JdbcTemplate db,LoanContractIntakeService loan,
      LoanPaymentScheduleCalculator calculator) {
    this.db=Objects.requireNonNull(db);
    this.loan=Objects.requireNonNull(loan);
    this.calculator=Objects.requireNonNull(calculator);
  }

  private static ResponseStatusException invalid(String code){
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,code);
  }
  private static ResponseStatusException conflict(String code){
    return new ResponseStatusException(HttpStatus.CONFLICT,code);
  }
  static String sha(String value){
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(value.getBytes(StandardCharsets.UTF_8)));
    }catch(Exception e){throw new IllegalStateException("A05_R11_SHA_UNAVAILABLE",e);}
  }
  private static String dec(BigDecimal number){
    return number.stripTrailingZeros().toPlainString();
  }
  private static String customDigest(List<CustomInstallment> values){
    StringBuilder b=new StringBuilder("FIN-A05-R11-CUSTOM-INPUT-V1");
    for(var row:values)
      b.append('|').append(row.dueDate()).append('=').append(dec(row.principalDue()));
    return sha(b.toString());
  }
  private static String digest(Result result){
    var f=result.funding();
    StringBuilder b=new StringBuilder("FIN-A05-R11-SCHEDULE-RESULT-V1|")
      .append(result.repaymentMethod()).append('|')
      .append(result.currency()).append('|')
      .append(result.dayCountConvention()).append('|')
      .append(result.roundingScale()).append('|').append(result.roundingMode());
    for(var v:List.of(f.drawdown(),f.financedFees(),f.withheldFees(),
        f.upfrontPaidFees(),f.netCashProceeds(),f.openingLiability(),
        result.totalPrincipal(),result.totalContractualInterest(),
        result.totalScheduledFees(),result.totalScheduledCash(),result.finalBalance()))
      b.append('|').append(dec(v));
    for(ScheduleLine line:result.lines()){
      b.append("|L:").append(line.installmentNo()).append('|')
        .append(line.accrualFrom()).append('|').append(line.dueAt());
      for(var v:List.of(line.yearFractionRaw(),line.openingPrincipal(),
          line.contractualInterestRaw(),line.contractualInterestDue(),
          line.principalDue(),line.feesDue(),line.closingPrincipal(),
          line.totalCashDue())) b.append('|').append(dec(v));
      line.feeBreakdown().entrySet().stream().sorted(Map.Entry.comparingByKey())
        .forEach(entry->b.append("|F:").append(entry.getKey())
          .append('=').append(dec(entry.getValue())));
    }
    for(var d:result.diagnostics())b.append("|D:").append(d);
    return sha(b.toString());
  }

  private AppContract appContract(){
    // Exact version 1 is a deliberate design pin: no "active/latest" query.
    var result=db.query("""
      SELECT id,contract_sha256 FROM fin_calc.calc_application_contract_revision
      WHERE app_code='CALC-A05' AND revision_number=1
        AND contract_state='SCHEMA_CANDIDATE'
        AND financial_s2_approved=false AND production_admitted=false
      """,(r,i)->new AppContract(r.getObject(1,UUID.class),
        r.getString(2).trim()));
    if(result.size()!=1)throw new IllegalStateException("A05_R11_APP_CONTRACT_NOT_PINNED");
    return result.getFirst();
  }

  private String requestDigest(ScheduleCommand request,String customSha){
    return sha("FIN-A05-R11-COMMAND-V1|"+request.organizationId()+"|"+
      request.projectId()+"|"+request.exactSourceRevisionId()+"|"+customSha);
  }

  @Transactional(isolation=Isolation.READ_COMMITTED)
  public ScheduleView calculateAndCommit(ScheduleCommand request,
      String idempotencyKey){
    if(request==null||request.organizationId()==null
        ||request.projectId()==null||request.exactSourceRevisionId()==null
        ||idempotencyKey==null||idempotencyKey.isBlank()||idempotencyKey.length()>200)
      throw invalid("A05_R11_EXACT_SOURCE_AND_IDEMPOTENCY_REQUIRED");
    var custom=request.customInstallments();
    if(custom.size()>600)throw invalid("A05_R11_TOO_MANY_CUSTOM_INSTALLMENTS");
    for(var item:custom)
      if(item==null||item.dueDate()==null||item.principalDue()==null)
        throw invalid("A05_R11_CUSTOM_INPUT_REQUIRED");
    String customSha=customDigest(custom);
    String commandSha=requestDigest(request,customSha);
    var replay=db.query("""
      SELECT schedule_revision_id,request_sha256
      FROM fin_calc.a05_schedule_command_receipt
      WHERE organization_id=? AND idempotency_key=?
      """,(r,i)->new String[]{r.getString(1),r.getString(2).trim()},
      request.organizationId(),idempotencyKey);
    if(!replay.isEmpty()) {
      if(replay.size()!=1||!commandSha.equals(replay.getFirst()[1]))
        throw conflict("A05_R11_IDEMPOTENCY_KEY_REUSED");
      return readExact(request.organizationId(),request.projectId(),
        UUID.fromString(replay.getFirst()[0]),"IDEMPOTENT_REPLAY");
    }

    var typed=loan.readExact(request.organizationId(),request.projectId(),
      request.exactSourceRevisionId());
    var result=calculator.calculate(typed.terms(),typed.fees(),custom);
    var app=appContract();
    String engineSha=sha(LoanPaymentScheduleCalculator.ENGINE_ID);
    String variantSha=sha("FIN-A05-R11-VARIANT-V1|"+
      request.exactSourceRevisionId()+"|"+typed.receipt().termsSha256()+
      "|"+app.id()+"|"+app.sha()+"|"+engineSha+"|"+customSha);
    String resultSha=digest(result);
    UUID id=UUID.randomUUID();

    int inserted=db.update("""
      INSERT INTO fin_calc.a05_schedule_revision(
        id,organization_id,project_id,source_revision_id,contract_revision_id,
        app_contract_revision_id,app_contract_sha256,
        source_content_sha256,terms_sha256,engine_descriptor_sha256,
        custom_input_sha256,variant_sha256,result_sha256,
        repayment_method,currency_code,day_count_convention,rounding_scale,
        rounding_mode,drawdown,financed_fees,withheld_fees,upfront_paid_fees,
        net_cash_proceeds,opening_liability,
        total_principal,total_contractual_interest,total_scheduled_fees,
        total_scheduled_cash,final_balance,row_count)
      VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
      ON CONFLICT(source_revision_id,variant_sha256) DO NOTHING
      """,
      id,request.organizationId(),request.projectId(),
      request.exactSourceRevisionId(),typed.receipt().contractRevisionId(),
      app.id(),app.sha(),typed.receipt().sourceContentSha256(),
      typed.receipt().termsSha256(),engineSha,customSha,variantSha,resultSha,
      result.repaymentMethod(),result.currency(),result.dayCountConvention(),
      result.roundingScale(),result.roundingMode(),
      result.funding().drawdown(),result.funding().financedFees(),
      result.funding().withheldFees(),result.funding().upfrontPaidFees(),
      result.funding().netCashProceeds(),result.funding().openingLiability(),
      result.totalPrincipal(),result.totalContractualInterest(),
      result.totalScheduledFees(),result.totalScheduledCash(),
      result.finalBalance(),result.lines().size());

    if(inserted==1){
      for(int index=0;index<custom.size();index++){
        var row=custom.get(index);
        db.update("""
          INSERT INTO fin_calc.a05_schedule_custom_installment
          (schedule_revision_id,installment_no,due_at,requested_principal)
          VALUES(?,?,?,?)
          """,id,index+1,row.dueDate(),row.principalDue());
      }
      for(var line:result.lines()){
        db.update("""
          INSERT INTO fin_calc.a05_schedule_line
          (schedule_revision_id,installment_no,accrual_from,due_at,
           year_fraction_raw,opening_principal,contractual_interest_raw,
           contractual_interest_due,principal_due,fees_due,
           closing_principal,total_cash_due)
          VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
          """,id,line.installmentNo(),line.accrualFrom(),line.dueAt(),
          line.yearFractionRaw(),line.openingPrincipal(),
          line.contractualInterestRaw(),line.contractualInterestDue(),
          line.principalDue(),line.feesDue(),line.closingPrincipal(),
          line.totalCashDue());
        for(var entry:line.feeBreakdown().entrySet())
          db.update("""
            INSERT INTO fin_calc.a05_schedule_line_fee
             (schedule_revision_id,installment_no,fee_code,fee_amount)
            VALUES(?,?,?,?)
            """,id,line.installmentNo(),entry.getKey(),entry.getValue());
      }
    }else{
      var stored=db.query("""
        SELECT id,result_sha256
        FROM fin_calc.a05_schedule_revision
        WHERE source_revision_id=? AND variant_sha256=?
        """,(r,i)->new String[]{r.getString(1),r.getString(2).trim()},
        request.exactSourceRevisionId(),variantSha);
      if(stored.size()!=1||!resultSha.equals(stored.getFirst()[1]))
        throw new IllegalStateException("A05_R11_DETERMINISTIC_VARIANT_COLLISION");
      id=UUID.fromString(stored.getFirst()[0]);
    }
    db.update("""
      INSERT INTO fin_calc.a05_schedule_command_receipt
      (id,organization_id,idempotency_key,request_sha256,schedule_revision_id)
      VALUES(?,?,?,?,?)
      """,UUID.randomUUID(),request.organizationId(),idempotencyKey,commandSha,id);
    return readExact(request.organizationId(),request.projectId(),id,
      inserted==1?"UAT_COMMITTED":"EXISTING_VARIANT_REPLAY");
  }

  private static Header header(ResultSet r,int i)throws SQLException {
    return new Header(r.getObject(1,UUID.class),r.getObject(2,UUID.class),
      r.getObject(3,UUID.class),r.getObject(4,UUID.class),
      r.getString(5).trim(),r.getString(6).trim(),r.getString(7).trim(),
      r.getString(8).trim(),r.getString(9).trim(),r.getString(10).trim(),
      r.getString(11).trim(),r.getString(12),r.getString(13),
      r.getString(14),r.getInt(15),r.getString(16),
      new Funding(r.getBigDecimal(17),r.getBigDecimal(18),r.getBigDecimal(19),
        r.getBigDecimal(20),r.getBigDecimal(21),r.getBigDecimal(22)),
      r.getBigDecimal(23),r.getBigDecimal(24),r.getBigDecimal(25),
      r.getBigDecimal(26),r.getBigDecimal(27),r.getInt(28),r.getString(29));
  }

  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
  public ScheduleView readExact(UUID organizationId,UUID projectId,UUID scheduleId){
    return readExact(organizationId,projectId,scheduleId,"READ_ONLY");
  }
  private ScheduleView readExact(UUID organizationId,UUID projectId,
      UUID scheduleId,String commandResult) {
    if(organizationId==null||projectId==null||scheduleId==null)
      throw invalid("A05_R11_EXACT_SCHEDULE_SCOPE_REQUIRED");
    var rows=db.query("""
      SELECT id,source_revision_id,contract_revision_id,app_contract_revision_id,
        app_contract_sha256,source_content_sha256,terms_sha256,
        engine_descriptor_sha256,custom_input_sha256,variant_sha256,result_sha256,
        repayment_method,currency_code,day_count_convention,rounding_scale,
        rounding_mode,drawdown,financed_fees,withheld_fees,upfront_paid_fees,
        net_cash_proceeds,opening_liability,total_principal,
        total_contractual_interest,total_scheduled_fees,total_scheduled_cash,
        final_balance,row_count,state
      FROM fin_calc.a05_schedule_revision
      WHERE id=? AND organization_id=? AND project_id=?
      """,LoanScheduleService::header,scheduleId,organizationId,projectId);
    if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,
      "A05_R11_SCHEDULE_NOT_FOUND");
    if(rows.size()!=1)throw new IllegalStateException("A05_R11_SCHEDULE_IDENTITY_NOT_UNIQUE");
    var h=rows.getFirst();
    var source=loan.readExact(organizationId,projectId,h.sourceId());
    var app=appContract();
    if(!h.contractId().equals(source.receipt().contractRevisionId())
       ||!h.sourceSha().equals(source.receipt().sourceContentSha256())
       ||!h.termsSha().equals(source.receipt().termsSha256())
       ||!h.appContractId().equals(app.id())||!h.appSha().equals(app.sha())
       ||!h.engineSha().equals(sha(LoanPaymentScheduleCalculator.ENGINE_ID))
       ||!"UAT_UNQUALIFIED".equals(h.state()))
      throw new IllegalStateException("A05_R11_SCHEDULE_EXACT_PIN_DRIFT");
    var custom=db.query("""
      SELECT due_at,requested_principal FROM fin_calc.a05_schedule_custom_installment
      WHERE schedule_revision_id=? ORDER BY installment_no
      """,(r,i)->new CustomInstallment(r.getObject(1,LocalDate.class),
        r.getBigDecimal(2)),h.id());
    if(!customDigest(custom).equals(h.customSha()))
      throw new IllegalStateException("A05_R11_CUSTOM_INPUT_DIGEST_DRIFT");
    var rechecked=calculator.calculate(source.terms(),source.fees(),custom);
    String variant=sha("FIN-A05-R11-VARIANT-V1|"+h.sourceId()+"|"+
      h.termsSha()+"|"+app.id()+"|"+app.sha()+"|"+h.engineSha()+"|"+h.customSha());
    if(!variant.equals(h.variantSha())||!digest(rechecked).equals(h.resultSha()))
      throw new IllegalStateException("A05_R11_RECALCULATION_DIGEST_DRIFT");

    // Reconstruct and compare the actual typed PostgreSQL line rows, not
    // only the trusted recomputed Java result. No hidden JSON-only results.
    var fees=db.query("""
      SELECT installment_no,fee_code,fee_amount
      FROM fin_calc.a05_schedule_line_fee
      WHERE schedule_revision_id=? ORDER BY installment_no,fee_code
      """,(r,i)->new Object[]{r.getInt(1),r.getString(2),r.getBigDecimal(3)},h.id());
    Map<Integer,Map<String,BigDecimal>> feeMap=new LinkedHashMap<>();
    for(var item:fees) {
      feeMap.computeIfAbsent((Integer)item[0],unused->new LinkedHashMap<>())
        .put((String)item[1],(BigDecimal)item[2]);
    }
    var lines=db.query("""
      SELECT installment_no,accrual_from,due_at,year_fraction_raw,
        opening_principal,contractual_interest_raw,
        contractual_interest_due,principal_due,fees_due,
        closing_principal,total_cash_due
      FROM fin_calc.a05_schedule_line WHERE schedule_revision_id=?
      ORDER BY installment_no
      """,(r,i)->new ScheduleLine(r.getInt(1),
        r.getObject(2,LocalDate.class),r.getObject(3,LocalDate.class),
        r.getBigDecimal(4),r.getBigDecimal(5),r.getBigDecimal(6),
        r.getBigDecimal(7),r.getBigDecimal(8),r.getBigDecimal(9),
        r.getBigDecimal(10),r.getBigDecimal(11),
        feeMap.getOrDefault(r.getInt(1),Map.of())),h.id());
    var persisted=new Result(h.method(),h.currency(),h.dayCount(),h.scale(),
      h.rounding(),h.funding(),lines,h.totalPrincipal(),h.totalInterest(),
      h.totalFees(),h.totalCash(),h.finalBalance(),rechecked.diagnostics());
    if(lines.size()!=h.rowCount()||!digest(persisted).equals(h.resultSha()))
      throw new IllegalStateException("A05_R11_PERSISTED_LINE_OR_FEE_DIGEST_DRIFT");
    return new ScheduleView(h.id(),h.sourceId(),h.contractId(),h.appContractId(),
      h.sourceSha(),h.termsSha(),h.engineSha(),h.customSha(),h.variantSha(),
      h.resultSha(),h.state(),persisted,commandResult,false,false);
  }
}
