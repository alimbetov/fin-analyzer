package kz.fin.formlib.calc.ingest;

import kz.fin.formlib.calc.fact.FactDigest;
import kz.fin.formlib.calc.hybrid.CalcAsOfDebtKernel;
import kz.fin.formlib.calc.hybrid.CalcC01C02AsOfJdbcService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * DC-R2.4: exact, rehashed fin_intake ROWSET -> existing CalcAsOfDebtKernel.
 * This is a read-only stock preview, NOT payment-event allocation / ECL
 * valuation / independent proof of overdue amount / financial S2.
 */
@Service
@ConditionalOnProperty(name="fin.calc.intake-uat.enabled",havingValue="true")
public class C01C02IntakeUatPreviewService {
  private static final String OVERDUE_POLICY="DECLARED_OVERDUE_WITH_DUE_DATE_GUARD_V1";
  private static final String ASOF_POLICY=CalcC01C02AsOfJdbcService.POLICY;
  private final FinIntakeUatSourceAdapter intake;

  public C01C02IntakeUatPreviewService(FinIntakeUatSourceAdapter intake){
    this.intake=Objects.requireNonNull(intake);
  }

  public UatCalculationPreview preview(UUID organizationId,UUID projectId,
      UUID exactRevisionId,String formCode) {
    if(!Set.of("C01","C02").contains(formCode))
      throw new IllegalArgumentException("ASOF_INTAKE_FORM_REQUIRED");
    var pinned=intake.readExact(organizationId,projectId,exactRevisionId,formCode);
    var source=pinned.projection();
    var scope=source.scope();
    if(!"ROWSET".equals(source.contentMode()) || pinned.sourceRowIds().isEmpty()
        || pinned.sourceRowIds().size()>200
        || !"SYNTHETIC".equals(scope.dataSpace())
        || !"MANAGEMENT".equals(scope.reportingBasis())
        || !"PERIOD_ONLY".equals(scope.coverage())
        || !source.evidence().isEmpty()) {
      throw new IllegalStateException("ASOF_INTAKE_SCOPE_OR_ROWSET_UNSUPPORTED");
    }
    LocalDate asOf=scope.periodEndExclusive().minusDays(1);
    if(asOf.isBefore(scope.periodStart()))
      throw new IllegalStateException("ASOF_INTAKE_INVALID_CUTOFF");
    Set<String> numericFields="C01".equals(formCode)
        ?Set.of("gross","allowance","overdue")
        :Set.of("outstanding","overdue");
    Set<String> attributeFields="C01".equals(formCode)
        ?Set.of("counterparty","dueAt","relatedParty")
        :Set.of("counterparty","dueAt","debtCategory");
    String principal="C01".equals(formCode)?"gross":"outstanding";
    Set<UUID> rowIds=new HashSet<>(pinned.sourceRowIds());
    Map<UUID,Map<String,BigDecimal>> numericRows=new HashMap<>();
    Map<UUID,Map<String,String>> attributeRows=new HashMap<>();
    List<CalcAsOfDebtKernel.Cell> cells=new ArrayList<>();
    for(var numeric:source.numeric()){
      if(numeric==null||numeric.rowId()==null||!rowIds.contains(numeric.rowId())
          ||!numericFields.contains(numeric.sourceFieldCode())
          ||!"MONEY".equals(numeric.fieldType()))
        throw new IllegalStateException("ASOF_INTAKE_NUMERIC_BINDING_INVALID");
      var row=numericRows.computeIfAbsent(numeric.rowId(),ignored->new HashMap<>());
      if(row.containsKey(numeric.sourceFieldCode()))
        throw new IllegalStateException("ASOF_INTAKE_DUPLICATE_MONEY_FIELD");
      row.put(numeric.sourceFieldCode(),numeric.numericValue());
      if("PRESENT".equals(numeric.valueState())) {
        if(numeric.numericValue()==null)
          throw new IllegalStateException("ASOF_INTAKE_PRESENT_WITHOUT_VALUE");
        cells.add(new CalcAsOfDebtKernel.Cell(source.revisionId(),
            numeric.rowId().toString(),numeric.sourceFieldCode(),
            numeric.numericValue()));
      }else if(!"BLANK".equals(numeric.valueState())||numeric.numericValue()!=null){
        throw new IllegalStateException("ASOF_INTAKE_NUMERIC_STATE_INVALID");
      }
    }
    for(var attr:source.attributes()){
      if(attr==null || attr.rowId()==null||!rowIds.contains(attr.rowId())
          ||!attributeFields.contains(attr.sourceFieldCode())
          ||("dueAt".equals(attr.sourceFieldCode())
              && !"DATE".equals(attr.fieldType())))
        throw new IllegalStateException("ASOF_INTAKE_ATTRIBUTE_BINDING_INVALID");
      var row=attributeRows.computeIfAbsent(attr.rowId(),ignored->new HashMap<>());
      if(row.putIfAbsent(attr.sourceFieldCode(),attr.sourceValue())!=null)
        throw new IllegalStateException("ASOF_INTAKE_DUPLICATE_ATTRIBUTE_FIELD");
    }
    var diagnostics=new ArrayList<String>();
    boolean counterpartyMissing=false;
    boolean dateMissing=false;
    for(UUID rowId:pinned.sourceRowIds()){
      var row=numericRows.get(rowId);
      if(row==null || row.get(principal)==null
          || ("C01".equals(formCode) && row.get("allowance")==null))
        throw new IllegalStateException("ASOF_INTAKE_REQUIRED_ROW_MONEY_MISSING");
      var attrs=attributeRows.getOrDefault(rowId,Map.of());
      if(attrs.get("counterparty")==null||attrs.get("counterparty").isBlank())
        counterpartyMissing=true;
      String dueAt=attrs.get("dueAt");
      BigDecimal overdue=row.get("overdue");
      if(dueAt==null||dueAt.isBlank()){
        dateMissing=true;
        if(overdue!=null && overdue.signum()>0)
          throw new IllegalStateException("ASOF_DUE_AT_REQUIRED_FOR_DECLARED_OVERDUE");
      }else {
        LocalDate dueDate=LocalDate.parse(dueAt);
        // "Overdue" means unpaid amount past due at the snapshot cutoff,
        // not simply an amount due on the cutoff itself.
        if(overdue!=null&&overdue.signum()>0&&!dueDate.isBefore(asOf))
          throw new IllegalStateException("ASOF_FUTURE_OR_TODAY_DUE_CANNOT_BE_OVERDUE");
      }
    }
    var calculated=CalcAsOfDebtKernel.calculate(
        CalcAsOfDebtKernel.Form.valueOf(formCode),cells);
    if(calculated.sourceRowCount()!=rowIds.size())
      throw new IllegalStateException("ASOF_INTAKE_ROW_CENSUS_MISMATCH");

    if(dateMissing)diagnostics.add("SOME_DUE_DATES_MISSING_NO_INDEPENDENT_AGING");
    if(counterpartyMissing)diagnostics.add("COUNTERPARTY_BUSINESS_KEY_NOT_VERIFIED");
    diagnostics.add("DECLARED_OVERDUE_NOT_RECONSTRUCTED_FROM_PAYMENT_EVENTS");
    diagnostics.add("CALC_A01_ALLOCATION_NOT_YET_CONNECTED");
    diagnostics.add("ECL_ALLOWANCE_IS_USER_DECLARED_NOT_CALCULATED");

    var outputs=new LinkedHashMap<String,UatCalculationPreview.Node>();
    String currency=scope.currencyCode();
    if("C01".equals(formCode)){
      outputs.put("C01.gross",present(currency,calculated.fieldTotals().get("gross")));
      outputs.put("C01.allowance",present(currency,calculated.fieldTotals().get("allowance")));
    }
    outputs.put(calculated.primaryMetric(),present(currency,calculated.primaryAmount()));
    if(calculated.overdueAmount()==null){
      outputs.put(formCode+".declaredOverdue",
          missing(currency,"DECLARED_OVERDUE_INCOMPLETE"));
    }else{
      outputs.put(formCode+".declaredOverdue",
          present(currency,calculated.overdueAmount()));
    }
    outputs.put(formCode+".declaredOverdueShare",
        switch(calculated.overdueShareState()){
          case "PRESENT"->present("RATIO",calculated.overdueShare());
          case "MISSING_OPTIONAL_INPUT"->missing("RATIO","DECLARED_OVERDUE_INCOMPLETE");
          case "UNDEFINED_ZERO_DENOMINATOR"->new UatCalculationPreview.Node(
              "UNDEFINED","RATIO",null,"ZERO_DENOMINATOR");
          default->throw new IllegalStateException("ASOF_SHARE_STATE_UNKNOWN");
        });
    String methodSha=FactDigest.sha("FIN-FACT-DC-R2-ASOF-METHOD-V1",
        formCode,ASOF_POLICY,OVERDUE_POLICY,source.sourceSchemaSha256(),
        source.sourceMappingSha256(),"MANAGEMENT","SYNTHETIC");
    var parts=new ArrayList<String>();
    parts.add(formCode);
    parts.add(methodSha);
    for(var node:outputs.entrySet()){
      parts.add(node.getKey());
      parts.add(node.getValue().state());
      parts.add(node.getValue().unit());
      parts.add(node.getValue().value()==null?"":node.getValue().value());
      parts.add(node.getValue().errorCode()==null?"":node.getValue().errorCode());
    }
    String resultSha=FactDigest.sha("FIN-FACT-DC-R2-ASOF-VALUES-V1",
        parts.toArray(String[]::new));
    String previewSha=FactDigest.sha("FIN-FACT-DC-R2-ASOF-PREVIEW-V1",
        pinned.pinnedSourceSha256(),source.revisionId().toString(),
        asOf.toString(),methodSha,resultSha,OVERDUE_POLICY,
        String.join(";",diagnostics),Integer.toString(calculated.sourceRowCount()));
    return new UatCalculationPreview(formCode,source.instanceId(),
        source.revisionId(),source.formVersionId(),
        source.sourceSha256Claim(),source.rowOrderSha256(),
        pinned.pinnedSourceSha256(),source.sourceSchemaSha256(),
        methodSha,ASOF_POLICY,scope.periodType(),scope.periodCode(),asOf,
        currency,scope.reportingBasis(),scope.dataSpace(),
        calculated.sourceRowCount(),outputs,diagnostics,
        resultSha,previewSha,UatCalculationPreview.STATE,false,false);
  }

  private static UatCalculationPreview.Node present(String unit,BigDecimal value){
    if(value==null)throw new IllegalStateException("ASOF_REQUIRED_OUTPUT_ABSENT");
    return new UatCalculationPreview.Node("PRESENT",unit,
        value.stripTrailingZeros().toPlainString(),null);
  }
  private static UatCalculationPreview.Node missing(String unit,String reason){
    return new UatCalculationPreview.Node("MISSING",unit,null,reason);
  }
}
