package kz.fin.formlib.calc.loan;

import kz.fin.formlib.calc.ingest.FinIntakeUatSourceAdapter;
import kz.fin.formlib.service.FormDraftService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * A05-R1.0: one Spring transaction for the EXISTING C05 fin_intake writer
 * and its typed loan, rate, calendar, disbursement and fee companions.
 * No additional financial writer, schedule engine or production admission.
 */
@Service
public class LoanContractIntakeService {
  public record Terms(String loanBusinessKey,String agreementRef,String creditorRef,
      LocalDate contractDate,LocalDate disbursementDate,LocalDate accrualStartDate,
      LocalDate maturityDate,BigDecimal principal,String jurisdiction,
      String perspective,String repaymentMethod,String paymentFrequency,
      int dueDay,String interestCompounding,String dayCountConvention,
      String businessDayAdjustment,int roundingScale,String roundingMode,
      String finalResidualRule,String rateKind,BigDecimal nominalAnnualRatePct) {}

  public record Fee(String feeCode,String feeRuleType,String feeSettlement,
      String calculationBasis,BigDecimal amountOrRate,String frequency,
      String eventDateRule,String ifrsEirClassification,
      String kzGesvClassification) {}

  public record Command(FormDraftService.DraftRequest draft,
      Terms terms,List<Fee> fees) {}

  public record Receipt(UUID instanceId,UUID sourceRevisionId,int revisionNo,
      String sourceContentSha256,UUID contractRevisionId,String termsSha256,
      String result,boolean financiallyApproved,boolean productionAdmitted) {}

  public record ContractView(Receipt receipt,Terms terms,List<Fee> fees,
      UUID loanIdentityId,UUID rateRuleRevisionId,UUID calendarRuleRevisionId,
      UUID drawdownRevisionId,String sourceIntegrityState) {
    public ContractView { fees=List.copyOf(fees); }
  }

  private record Identity(UUID id,UUID rowId,String key,String agreement,String creditor) {}
  private record Existing(UUID contractId,UUID sourceId,int revision,
      String sourceSha,String termsSha,String key) {}
  private record Header(UUID contractId,UUID identityId,UUID sourceId,int revisionNo,
      String sourceSha,String termsSha,UUID rateId,UUID calendarId,
      LocalDate contractDate,LocalDate disbursement,LocalDate accrual,LocalDate maturity,
      BigDecimal principal,String jurisdiction,String perspective,
      String repayment,String frequency,int dueDay,String compounding,String dayCount,
      String adjustment,int roundingScale,String roundingMode,String residualRule,
      String rateKind,BigDecimal nominalRate,String businessKey,String agreement,String creditor) {}

  private final JdbcTemplate db;
  private final FormDraftService drafts;
  private final FinIntakeUatSourceAdapter intake;

  public LoanContractIntakeService(JdbcTemplate db,FormDraftService drafts,
      FinIntakeUatSourceAdapter intake) {
    this.db=Objects.requireNonNull(db);
    this.drafts=Objects.requireNonNull(drafts);
    this.intake=Objects.requireNonNull(intake);
  }

  private static ResponseStatusException invalid(String code) {
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,code);
  }
  private static ResponseStatusException conflict(String code) {
    return new ResponseStatusException(HttpStatus.CONFLICT,code);
  }
  private static void require(boolean ok,String code) {
    if(!ok)throw invalid(code);
  }
  private static String normalized(BigDecimal v) {
    return v.stripTrailingZeros().toPlainString();
  }
  private static String sha(String text) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(text.getBytes(StandardCharsets.UTF_8)));
    }catch(Exception e){throw new IllegalStateException("A05_SHA256_UNAVAILABLE",e);}
  }

  private static void requireChoice(String value,Set<String> choices,String code) {
    require(value!=null&&choices.contains(value),code);
  }
  private static void validate(Command command) {
    require(command!=null&&command.draft()!=null&&command.terms()!=null
        &&command.fees()!=null,"A05_INPUT_REQUIRED");
    var d=command.draft();
    var t=command.terms();
    require("C05".equals(d.formCode())&&"KZT".equals(d.currency())
      &&"MANAGEMENT".equals(d.reportingBasis())
      &&"SYNTHETIC".equals(d.dataSpace()),"A05_R10_C05_UAT_CONTEXT_REQUIRED");
    require(d.organizationId()!=null&&d.projectId()!=null
      &&d.economicEntityId()!=null&&d.businessScopeId()!=null
      &&d.rows()!=null&&d.rows().size()==1&&d.values()!=null
      &&d.values().isEmpty()&&d.rows().getFirst()!=null
      &&d.rows().getFirst().rowId()!=null,"A05_ONE_C05_ROW_WITH_STABLE_UUID_REQUIRED");
    require(t.loanBusinessKey()!=null&&
      t.loanBusinessKey().matches("[A-Za-z0-9][A-Za-z0-9._:-]{2,119}"),
      "A05_LOAN_BUSINESS_KEY_INVALID");
    require(t.agreementRef()!=null&&!t.agreementRef().isBlank()
      &&t.agreementRef().length()<=255
      &&t.creditorRef()!=null&&!t.creditorRef().isBlank()
      &&t.creditorRef().length()<=255,"A05_COUNTERPARTY_OR_AGREEMENT_REQUIRED");
    require(t.contractDate()!=null&&t.disbursementDate()!=null
      &&t.accrualStartDate()!=null&&t.maturityDate()!=null
      &&!t.contractDate().isAfter(t.disbursementDate())
      &&!t.disbursementDate().isAfter(t.accrualStartDate())
      &&t.accrualStartDate().isBefore(t.maturityDate()),"A05_CONTRACT_DATES_INVALID");
    require(t.principal()!=null&&t.principal().signum()>0
      &&t.principal().scale()<=2&&t.principal().precision()<=38,
      "A05_PRINCIPAL_INVALID");
    requireChoice(t.jurisdiction(),Set.of("KZ"),"A05_JURISDICTION_UNSUPPORTED");
    requireChoice(t.perspective(),Set.of("BORROWER"),"A05_PERSPECTIVE_UNSUPPORTED");
    requireChoice(t.repaymentMethod(),Set.of("ANNUITY","EQUAL_PRINCIPAL","CUSTOM"),
      "A05_REPAYMENT_METHOD_INVALID");
    requireChoice(t.paymentFrequency(),Set.of("MONTHLY"),"A05_FREQUENCY_UNSUPPORTED");
    require(t.dueDay()>=1&&t.dueDay()<=28,"A05_DUE_DAY_UNSUPPORTED");
    requireChoice(t.interestCompounding(),Set.of("SIMPLE"),"A05_COMPOUNDING_UNSUPPORTED");
    requireChoice(t.dayCountConvention(),Set.of("ACT_365_FIXED","ACT_360","THIRTY_E_360"),
      "A05_DAY_COUNT_INVALID");
    requireChoice(t.businessDayAdjustment(),Set.of("NONE"),
      "A05_BUSINESS_DAY_CALENDAR_NOT_YET_SUPPORTED");
    require(t.roundingScale()>=0&&t.roundingScale()<=2,"A05_ROUNDING_SCALE_INVALID");
    requireChoice(t.roundingMode(),Set.of("HALF_EVEN","HALF_UP"),"A05_ROUNDING_MODE_INVALID");
    requireChoice(t.finalResidualRule(),Set.of("ADJUST_LAST_PAYMENT"),
      "A05_RESIDUAL_RULE_UNSUPPORTED");
    requireChoice(t.rateKind(),Set.of("FIXED"),"A05_RATE_KIND_R12_NOT_IMPLEMENTED");
    require(t.nominalAnnualRatePct()!=null&&t.nominalAnnualRatePct().signum()>=0
      &&t.nominalAnnualRatePct().compareTo(new BigDecimal("500"))<=0
      &&t.nominalAnnualRatePct().scale()<=10,"A05_NOMINAL_RATE_INVALID");

    Map<String,String> row=d.rows().getFirst().values();
    require(row!=null&&t.agreementRef().equals(row.get("agreementRef"))
      &&t.creditorRef().equals(row.get("creditor"))
      &&d.currency().equals(row.get("currency"))
      &&t.rateKind().equals(row.get("rateType"))
      &&row.get("interestRate")!=null
      &&new BigDecimal(row.get("interestRate"))
        .compareTo(t.nominalAnnualRatePct())==0,
      "A05_C05_ROW_AND_TERMS_MISMATCH");
    require(command.fees().size()<=100,"A05_TOO_MANY_FEES");
    var seen=new HashSet<String>();
    for(var fee:command.fees()) {
      require(fee!=null&&fee.feeCode()!=null
        &&fee.feeCode().matches("[A-Z][A-Z0-9_]{1,79}")
        &&seen.add(fee.feeCode()),"A05_DUPLICATE_OR_INVALID_FEE");
      requireChoice(fee.feeRuleType(),Set.of("FIXED","RECURRING_FIXED",
        "PERCENT_OF_DISBURSEMENT","PERCENT_OF_ORIGINAL",
        "PERCENT_OF_OUTSTANDING","PERCENT_OF_LIMIT"),"A05_FEE_TYPE_INVALID");
      requireChoice(fee.feeSettlement(),Set.of("UPFRONT_PAID","WITHHELD",
        "FINANCED","RECURRING","AT_MATURITY"),"A05_FEE_SETTLEMENT_INVALID");
      requireChoice(fee.calculationBasis(),Set.of("FIXED_AMOUNT","DISBURSEMENT",
        "ORIGINAL_PRINCIPAL","OUTSTANDING","LIMIT"),"A05_FEE_BASIS_INVALID");
      requireChoice(fee.frequency(),Set.of("ONCE","MONTHLY"),"A05_FEE_FREQUENCY_INVALID");
      requireChoice(fee.eventDateRule(),Set.of("DISBURSEMENT_DATE",
        "SCHEDULE_DUE_DATE","MATURITY_DATE"),"A05_FEE_DATE_RULE_INVALID");
      requireChoice(fee.ifrsEirClassification(),Set.of("UNREVIEWED"),
        "A05_IFRS_EIR_CLASSIFICATION_NOT_APPROVED");
      requireChoice(fee.kzGesvClassification(),Set.of("UNREVIEWED"),
        "A05_KZ_GESV_CLASSIFICATION_NOT_APPROVED");
      require(fee.amountOrRate()!=null&&fee.amountOrRate().signum()>0
        &&fee.amountOrRate().precision()<=38
        &&fee.amountOrRate().scale()<=10,"A05_FEE_VALUE_INVALID");
      boolean fixed=Set.of("FIXED","RECURRING_FIXED").contains(fee.feeRuleType());
      if(fixed) {
        require("FIXED_AMOUNT".equals(fee.calculationBasis())
          &&fee.amountOrRate().stripTrailingZeros().scale()<=2,
          "A05_FIXED_FEE_MUST_BE_MONEY");
      } else {
        String basis=switch(fee.feeRuleType()){
          case "PERCENT_OF_DISBURSEMENT" -> "DISBURSEMENT";
          case "PERCENT_OF_ORIGINAL" -> "ORIGINAL_PRINCIPAL";
          case "PERCENT_OF_OUTSTANDING" -> "OUTSTANDING";
          default -> "LIMIT";
        };
        require(basis.equals(fee.calculationBasis())
          &&fee.amountOrRate().compareTo(new BigDecimal("100"))<=0,
          "A05_PERCENT_FEE_BASIS_INVALID");
      }
      boolean recurring="RECURRING".equals(fee.feeSettlement());
      require(recurring==("MONTHLY".equals(fee.frequency()))
        &&(!recurring||"SCHEDULE_DUE_DATE".equals(fee.eventDateRule()))
        &&(!"RECURRING_FIXED".equals(fee.feeRuleType())||recurring),
        "A05_FEE_FREQUENCY_SETTLEMENT_INCOMPATIBLE");
    }
  }

  /** Canonical digest over the EXACT terms + full fee rule set, order-independent. */
  public static String termsSha256(Terms t,List<Fee> fees) {
    StringBuilder b=new StringBuilder("FIN-A05-TERMS-V1");
    b.append('|').append(t.loanBusinessKey()).append('|').append(t.agreementRef())
      .append('|').append(t.creditorRef()).append('|').append(t.contractDate())
      .append('|').append(t.disbursementDate()).append('|').append(t.accrualStartDate())
      .append('|').append(t.maturityDate()).append('|').append(normalized(t.principal()))
      .append('|').append(t.jurisdiction()).append('|').append(t.perspective())
      .append('|').append(t.repaymentMethod()).append('|').append(t.paymentFrequency())
      .append('|').append(t.dueDay()).append('|').append(t.interestCompounding())
      .append('|').append(t.dayCountConvention()).append('|').append(t.businessDayAdjustment())
      .append('|').append(t.roundingScale()).append('|').append(t.roundingMode())
      .append('|').append(t.finalResidualRule()).append('|').append(t.rateKind())
      .append('|').append(normalized(t.nominalAnnualRatePct()));
    for(Fee f:fees.stream().sorted(Comparator.comparing(Fee::feeCode)).toList())
      b.append("|F:").append(f.feeCode()).append('|').append(f.feeRuleType())
        .append('|').append(f.feeSettlement()).append('|').append(f.calculationBasis())
        .append('|').append(normalized(f.amountOrRate())).append('|').append(f.frequency())
        .append('|').append(f.eventDateRule()).append('|').append(f.ifrsEirClassification())
        .append('|').append(f.kzGesvClassification());
    return sha(b.toString());
  }

  private static String commandSha(Command command,String termsSha) {
    var d=command.draft();
    var row=d.rows().getFirst();
    return sha("FIN-A05-COMMAND-V1|"+termsSha+"|"+d.organizationId()+"|"+
      d.economicEntityId()+"|"+d.businessScopeId()+"|"+d.projectId()+"|"+
      d.instanceId()+"|"+d.expectedRevision()+"|"+d.formCode()+"|"+
      d.periodType()+"|"+d.periodCode()+"|"+d.currency()+"|"+d.coverage()+"|"+
      d.reportingBasis()+"|"+d.dataSpace()+"|"+d.createdBy()+"|"+
      d.changeReason()+"|"+row.rowId()+"|"+row.rowPosition()+"|"+
      new TreeMap<>(row.values()));
  }

  @Transactional(isolation=Isolation.READ_COMMITTED)
  public Receipt save(Command command,String idempotencyKey) {
    validate(command);
    if(idempotencyKey==null||idempotencyKey.isBlank()||idempotencyKey.length()>200)
      throw invalid("A05_IDEMPOTENCY_KEY_REQUIRED");
    var d=command.draft();
    var t=command.terms();
    String termsSha=termsSha256(t,command.fees());
    String requestSha=commandSha(command,termsSha);
    var previous=db.query("""
      SELECT r.contract_revision_id,r.source_revision_id,r.request_sha256
      FROM fin_intake.a05_loan_command_receipt r
      WHERE r.organization_id=? AND r.idempotency_key=?
      """,(rs,i)->new String[]{rs.getString(1),rs.getString(2),
        rs.getString(3).trim()},d.organizationId(),idempotencyKey);
    if(!previous.isEmpty()){
      var seen=previous.getFirst();
      if(!requestSha.equals(seen[2]))throw conflict("A05_IDEMPOTENCY_KEY_REUSED");
      var current=findHeader(d.organizationId(),d.projectId(),UUID.fromString(seen[1]));
      if(current==null||!current.contractId().toString().equals(seen[0])
          ||!termsSha.equals(current.termsSha()))
        throw new IllegalStateException("A05_IDEMPOTENT_RECEIPT_DRIFT");
      return receipt(current,"IDEMPOTENT_REPLAY");
    }

    var source=drafts.saveWithA05Companion(d,idempotencyKey,termsSha);
    if("IDEMPOTENT_REPLAY".equals(source.result()))
      throw conflict("A05_TYPED_RECEIPT_MISSING_FOR_REPLAY");
    var exact=drafts.read(source.instanceId(),d.organizationId());
    if(!source.revisionId().equals(exact.revisionId())
        ||!source.contentSha256().equals(exact.sha256())
        ||!"C05".equals(exact.formCode())
        ||!d.projectId().equals(exact.projectId())
        ||exact.rows().size()!=1
        ||!d.rows().getFirst().rowId().equals(exact.rows().getFirst().rowId()))
      throw new IllegalStateException("A05_C05_POST_SAVE_READBACK_MISMATCH");
    var identities=db.query("""
      SELECT id,source_row_id,loan_business_key,agreement_ref,creditor_ref
      FROM fin_intake.a05_loan_identity
      WHERE instance_id=? AND organization_id=? AND project_id=?
      """,(rs,i)->new Identity(rs.getObject(1,UUID.class),
        rs.getObject(2,UUID.class),rs.getString(3),rs.getString(4),rs.getString(5)),
      source.instanceId(),d.organizationId(),d.projectId());

    UUID identityId;
    if(identities.isEmpty()){
      require(d.expectedRevision()==0,"A05_EXISTING_C05_SLOT_CANNOT_BE_ADOPTED_IN_R10");
      identityId=UUID.randomUUID();
      db.update("""
        INSERT INTO fin_intake.a05_loan_identity
        (id,organization_id,economic_entity_id,business_scope_id,project_id,
         instance_id,source_row_id,loan_business_key,agreement_ref,creditor_ref)
        VALUES(?,?,?,?,?,?,?,?,?,?)
        """,identityId,d.organizationId(),d.economicEntityId(),
        d.businessScopeId(),d.projectId(),source.instanceId(),
        d.rows().getFirst().rowId(),t.loanBusinessKey(),
        t.agreementRef(),t.creditorRef());
    }else{
      if(identities.size()!=1)throw new IllegalStateException("A05_IDENTITY_NOT_UNIQUE");
      var id=identities.getFirst();
      if(!id.rowId().equals(d.rows().getFirst().rowId())
          ||!id.key().equals(t.loanBusinessKey())
          ||!id.agreement().equals(t.agreementRef())
          ||!id.creditor().equals(t.creditorRef()))
        throw conflict("A05_LOAN_IDENTITY_CHANGED");
      identityId=id.id();
      var old=db.query("""
        SELECT source_content_sha256,terms_sha256
        FROM fin_intake.a05_loan_contract_revision
        WHERE identity_id=? AND revision_number=?
        """,(rs,i)->new String[]{rs.getString(1).trim(),rs.getString(2).trim()},
          identityId,d.expectedRevision());
      if(old.size()!=1)throw new IllegalStateException("A05_PRIOR_TYPED_REVISION_MISSING");
      if(old.getFirst()[0].equals(source.contentSha256())
          &&old.getFirst()[1].equals(termsSha))
        throw invalid("A05_NO_C05_OR_TYPED_CHANGES");
    }

    UUID contractId=UUID.randomUUID(),rateId=UUID.randomUUID(),
      calendarId=UUID.randomUUID(),drawdownId=UUID.randomUUID();
    db.update("""
      INSERT INTO fin_intake.a05_loan_contract_revision
       (id,identity_id,source_revision_id,source_content_sha256,terms_sha256,
        revision_number,contract_date,disbursement_date,accrual_start_date,
        maturity_date,principal,currency_code,jurisdiction,perspective,
        repayment_method,payment_frequency,due_day,interest_compounding,
        day_count_convention,business_day_adjustment,rounding_scale,
        rounding_mode,final_residual_rule,rate_rule_revision_id,
        calendar_rule_revision_id,change_reason)
       VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
      """,contractId,identityId,source.revisionId(),source.contentSha256(),
      termsSha,source.revisionNo(),t.contractDate(),t.disbursementDate(),
      t.accrualStartDate(),t.maturityDate(),t.principal(),d.currency(),
      t.jurisdiction(),t.perspective(),t.repaymentMethod(),t.paymentFrequency(),
      t.dueDay(),t.interestCompounding(),t.dayCountConvention(),
      t.businessDayAdjustment(),t.roundingScale(),t.roundingMode(),
      t.finalResidualRule(),rateId,calendarId,d.changeReason());
    db.update("""
      INSERT INTO fin_intake.a05_loan_rate_rule_revision
      (id,contract_revision_id,rate_kind,nominal_annual_rate_pct,effective_from)
      VALUES(?,?,?,?,?)
      """,rateId,contractId,t.rateKind(),t.nominalAnnualRatePct(),t.accrualStartDate());
    db.update("""
      INSERT INTO fin_intake.a05_loan_calendar_rule_revision
      (id,contract_revision_id,payment_frequency,due_day,day_count_convention,
       business_day_adjustment,rounding_scale,rounding_mode,final_residual_rule)
      VALUES(?,?,?,?,?,?,?,?,?)
      """,calendarId,contractId,t.paymentFrequency(),t.dueDay(),
      t.dayCountConvention(),t.businessDayAdjustment(),t.roundingScale(),
      t.roundingMode(),t.finalResidualRule());
    db.update("""
      INSERT INTO fin_intake.a05_loan_drawdown_revision
      (id,contract_revision_id,disbursement_date,amount,event_state,cash_direction)
      VALUES(?,?,?,?,'PLANNED','INFLOW_BORROWER')
      """,drawdownId,contractId,t.disbursementDate(),t.principal());
    for(var fee:command.fees())db.update("""
      INSERT INTO fin_intake.a05_loan_fee_rule_revision
        (id,contract_revision_id,fee_code,fee_rule_type,fee_settlement,
         calculation_basis,amount_or_rate,frequency,event_date_rule,
         ifrs_eir_classification,kz_gesv_classification)
      VALUES(?,?,?,?,?,?,?,?,?,?,?)
      """,UUID.randomUUID(),contractId,fee.feeCode(),fee.feeRuleType(),
      fee.feeSettlement(),fee.calculationBasis(),fee.amountOrRate(),
      fee.frequency(),fee.eventDateRule(),fee.ifrsEirClassification(),
      fee.kzGesvClassification());
    db.update("""
      INSERT INTO fin_intake.a05_loan_command_receipt
      (id,organization_id,idempotency_key,request_sha256,
       contract_revision_id,source_revision_id)
      VALUES(?,?,?,?,?,?)
      """,UUID.randomUUID(),d.organizationId(),idempotencyKey,requestSha,
      contractId,source.revisionId());
    return new Receipt(source.instanceId(),source.revisionId(),source.revisionNo(),
      source.contentSha256(),contractId,termsSha,"UNQUALIFIED_DRAFT",false,false);
  }

  private Receipt receipt(Header h,String result) {
    var instance=db.queryForObject("""
      SELECT i.instance_id FROM fin_intake.a05_loan_identity i
      WHERE i.id=?
      """,UUID.class,h.identityId());
    return new Receipt(instance,h.sourceId(),h.revisionNo(),h.sourceSha(),
      h.contractId(),h.termsSha(),result,false,false);
  }

  private Header findHeader(UUID organizationId,UUID projectId,UUID revisionId) {
    var items=db.query("""
      SELECT c.id,c.identity_id,c.source_revision_id,c.revision_number,
        c.source_content_sha256,c.terms_sha256,
        c.rate_rule_revision_id,c.calendar_rule_revision_id,
        c.contract_date,c.disbursement_date,c.accrual_start_date,c.maturity_date,
        c.principal,c.jurisdiction,c.perspective,c.repayment_method,
        c.payment_frequency,c.due_day,c.interest_compounding,
        c.day_count_convention,c.business_day_adjustment,c.rounding_scale,
        c.rounding_mode,c.final_residual_rule,
        r.rate_kind,r.nominal_annual_rate_pct,
        i.loan_business_key,i.agreement_ref,i.creditor_ref
      FROM fin_intake.a05_loan_contract_revision c
      JOIN fin_intake.a05_loan_identity i ON i.id=c.identity_id
      JOIN fin_intake.a05_loan_rate_rule_revision r
        ON r.id=c.rate_rule_revision_id AND r.contract_revision_id=c.id
      JOIN fin_intake.a05_loan_calendar_rule_revision cal
        ON cal.id=c.calendar_rule_revision_id AND cal.contract_revision_id=c.id
      WHERE c.source_revision_id=? AND i.organization_id=? AND i.project_id=?
      """,(rs,n)->new Header(
        rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
        rs.getObject(3,UUID.class),rs.getInt(4),rs.getString(5).trim(),
        rs.getString(6).trim(),rs.getObject(7,UUID.class),rs.getObject(8,UUID.class),
        rs.getObject(9,LocalDate.class),rs.getObject(10,LocalDate.class),
        rs.getObject(11,LocalDate.class),rs.getObject(12,LocalDate.class),
        rs.getBigDecimal(13),rs.getString(14),rs.getString(15),rs.getString(16),
        rs.getString(17),rs.getInt(18),rs.getString(19),rs.getString(20),
        rs.getString(21),rs.getInt(22),rs.getString(23),rs.getString(24),
        rs.getString(25),rs.getBigDecimal(26),rs.getString(27),
        rs.getString(28),rs.getString(29)),
      revisionId,organizationId,projectId);
    if(items.size()>1)throw new IllegalStateException("A05_SOURCE_REVISION_NOT_UNIQUE");
    return items.isEmpty()?null:items.getFirst();
  }

  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
  public ContractView readExact(UUID organizationId,UUID projectId,
      UUID sourceRevisionId) {
    if(organizationId==null||projectId==null||sourceRevisionId==null)
      throw invalid("A05_EXACT_SOURCE_REQUIRED");
    var source=intake.readExact(organizationId,projectId,sourceRevisionId,"C05");
    var h=findHeader(organizationId,projectId,sourceRevisionId);
    if(h==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,
      "A05_TYPED_CONTRACT_NOT_FOUND");
    if(!h.sourceSha().equals(source.projection().sourceSha256Claim())
        ||source.sourceRowIds().size()!=1)
      throw new IllegalStateException("A05_SOURCE_REHASH_OR_CENSUS_DRIFT");
    var fees=db.query("""
      SELECT fee_code,fee_rule_type,fee_settlement,calculation_basis,
        amount_or_rate,frequency,event_date_rule,ifrs_eir_classification,
        kz_gesv_classification
      FROM fin_intake.a05_loan_fee_rule_revision
      WHERE contract_revision_id=? ORDER BY fee_code
      """,(rs,i)->new Fee(rs.getString(1),rs.getString(2),rs.getString(3),
        rs.getString(4),rs.getBigDecimal(5),rs.getString(6),rs.getString(7),
        rs.getString(8),rs.getString(9)),h.contractId());
    var t=new Terms(h.businessKey(),h.agreement(),h.creditor(),
      h.contractDate(),h.disbursement(),h.accrual(),h.maturity(),
      h.principal(),h.jurisdiction(),h.perspective(),h.repayment(),h.frequency(),
      h.dueDay(),h.compounding(),h.dayCount(),h.adjustment(),
      h.roundingScale(),h.roundingMode(),h.residualRule(),h.rateKind(),h.nominalRate());
    if(!termsSha256(t,fees).equals(h.termsSha()))
      throw new IllegalStateException("A05_TYPED_TERMS_SHA_MISMATCH");
    UUID drawdown=db.queryForObject("""
      SELECT id FROM fin_intake.a05_loan_drawdown_revision
      WHERE contract_revision_id=?
        AND disbursement_date=? AND amount=?
        AND event_state='PLANNED' AND cash_direction='INFLOW_BORROWER'
      """,UUID.class,h.contractId(),t.disbursementDate(),t.principal());
    if(drawdown==null)throw new IllegalStateException("A05_DRAWDOWN_MISSING");
    return new ContractView(receipt(h,"READ_ONLY"),t,fees,h.identityId(),
      h.rateId(),h.calendarId(),drawdown,source.integrityState());
  }
}
