package kz.fin.backend.diagnostics;

import kz.fin.backend.compass.CompassCatalog;
import kz.fin.backend.analytics.AnalysisContext;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * FIN-DIAG-R2: scenario-driven economic invariant checks over OLD source forms
 * and NEW extension-sheet contracts. SYNTHETIC ONLY, pure JVM evaluation.
 *
 * This is an executable prototype of the arithmetic and diagnostic contract;
 * it MUST NOT be used as a trusted source resolver, a statutory report,
 * a financial health rating, or a client authorization boundary.
 */
public final class ScenarioEconomicsEngine {
    private ScenarioEconomicsEngine() {}
    public static final String METHOD_VERSION="SCENARIO_ECONOMICS_V2";
    private static final MathContext MC=new MathContext(34,RoundingMode.HALF_EVEN);
    private static final int SCALE=12;

    public enum EvidenceState { APPROVED, PENDING, ESTIMATED, INVALID, MISSING }
    public enum Verdict { OBSERVATION, REVIEW, MISSING_EVIDENCE, NOT_COMPUTABLE }
    public enum Method {
        OWNER_CASH_MOVEMENT, BALANCE_RECONCILIATION, CASH_FLOW_RECONCILIATION,
        ANNUAL_REVENUE_TREND, BUDGET_ACTUAL_VARIANCE, WIP_ROLLFORWARD,
        UNIT_PRODUCTION_COST, STANDARD_ACTUAL_VARIANCE, BOQ_RESOURCE_COST,
        CONSTRUCTION_EVM, DATED_PROJECT_NPV, TRADE_GROSS_MARGIN, STOCK_TURNOVER,
        DSCR_PILOT, WEEKLY_CASH_FORECAST_ROLLFORWARD, OVERHEAD_ABSORPTION_VARIANCE,
        SECTOR_METHOD_QUALIFICATION
    }

    public record Scope(UUID organizationId,UUID entityId,UUID businessScopeId,
                        UUID factSetRevisionId,AnalysisContext.DataSpace dataSpace,
                        String reportingBasis,String fiscalCalendarVersion,String currency) {
        public Scope {
            Objects.requireNonNull(organizationId);
            Objects.requireNonNull(entityId);
            Objects.requireNonNull(businessScopeId);
            Objects.requireNonNull(factSetRevisionId);
            Objects.requireNonNull(dataSpace);
            if(reportingBasis==null || reportingBasis.isBlank() || fiscalCalendarVersion==null
                    || fiscalCalendarVersion.isBlank() || currency==null || !currency.matches("[A-Z]{3}"))
                throw new IllegalArgumentException("INVALID_DIAGNOSTIC_SCOPE");
        }
    }

    /**
     * sheetCode is empty for a base/old form, otherwise a real EX-... code
     * whose registered parent must be formCode.
     * itemKey is the finest economic granularity (cost centre, order, WBS etc).
     * period identifies the calendar/fiscal month containing a fact or row.
     * eventDate applies to irregular dated project cash flows.
     */
    public record InputRow(String formCode,String sheetCode,String itemKey,String rowId,YearMonth period,
                           LocalDate eventDate,String currency,String sourceRevision,
                           EvidenceState state,Map<String,BigDecimal> values) {
        public InputRow {
            if(formCode==null || formCode.isBlank() || sheetCode==null
                    || itemKey==null || itemKey.isBlank() || rowId==null || rowId.isBlank() || period==null
                    || currency==null || !currency.matches("[A-Z]{3}"))
                throw new IllegalArgumentException("INVALID_DIAGNOSTIC_ROW");
            Objects.requireNonNull(state);
            values=Map.copyOf(Objects.requireNonNull(values));
            if(state==EvidenceState.APPROVED && (sourceRevision==null || sourceRevision.isBlank()))
                throw new IllegalArgumentException("APPROVED_REVISION_REQUIRED");
            if(state!=EvidenceState.APPROVED && !values.isEmpty())
                throw new IllegalArgumentException("UNTRUSTED_VALUES_NOT_ALLOWED");
            if(eventDate!=null && !YearMonth.from(eventDate).equals(period))
                throw new IllegalArgumentException("EVENT_DATE_OUTSIDE_PERIOD");
            for(var e:values.entrySet()) {
                if(!e.getKey().matches("[A-Za-z][A-Za-z0-9_]*") || e.getValue()==null)
                    throw new IllegalArgumentException("INVALID_DIAGNOSTIC_FIELD");
                if(e.getValue().precision()>38 || e.getValue().scale()>12)
                    throw new IllegalArgumentException("DECIMAL_PRECISION_EXCEEDED");
            }
        }
        public String code() {return sheetCode.isEmpty()?formCode:sheetCode;}
    }

    public record Input(Scope scope,String scenarioCode,List<InputRow> rows) {
        public Input {
            Objects.requireNonNull(scope);
            Objects.requireNonNull(scenarioCode);
            rows=List.copyOf(Objects.requireNonNull(rows));
        }
    }
    public record SourceRef(String formCode,String sheetCode,String sourceRevision,
                            String itemKey,String rowId,YearMonth period,LocalDate eventDate) {}
    public record Finding(Method method,Verdict verdict,String reason,
                          String subject,Map<String,String> computedValues,
                          List<String> neededSources,List<SourceRef> sources) {
        public Finding {
            computedValues=Collections.unmodifiableMap(new TreeMap<>(computedValues));
            neededSources=List.copyOf(neededSources);
            sources=List.copyOf(sources);
            if(verdict==Verdict.MISSING_EVIDENCE && neededSources.isEmpty())
                throw new IllegalArgumentException("MISSING_EVIDENCE_NEEDS_SOURCES");
        }
    }
    public record Report(String scenarioCode,String methodVersion,
                         String qualification,List<Finding> findings) {
        public Report {findings=List.copyOf(findings);}
    }

    private record Sheet(String parent,Set<String> numericFields,String status) {}
    private static final Map<String,Sheet> SHEETS=loadSheets();
    private static final CompassCatalog FORMS=CompassCatalog.bundled();

    private static Map<String,Sheet> loadSheets(){
        try(var input=ScenarioEconomicsEngine.class.getResourceAsStream(
                "/fin/form-extension-sheets-v2.tsv")){
            if(input==null)throw new IllegalStateException("EXTENSION_REGISTRY_MISSING");
            try(var reader=new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8))){
                String header=reader.readLine();
                if(header==null || !header.startsWith("sheetCode\tdomain\tparentFormCode"))
                    throw new IllegalStateException("INVALID_EXTENSION_REGISTRY_HEADER");
                Map<String,Sheet> map=new TreeMap<>();
                String line;
                while((line=reader.readLine())!=null){
                    if(line.isBlank())continue;
                    String[] f=line.split("\\t",-1);
                    if(f.length!=11)throw new IllegalStateException("INVALID_EXTENSION_ROW");
                    Set<String> numeric=new HashSet<>();
                    for(String field:f[6].split(";")){
                        String[] pair=field.split(":");
                        if(pair.length!=2)throw new IllegalStateException("INVALID_EXTENSION_FIELD");
                        if(Set.of("MONEY","DECIMAL","INTEGER").contains(pair[1]))numeric.add(pair[0]);
                    }
                    if(map.putIfAbsent(f[0],new Sheet(f[2],Set.copyOf(numeric),f[10]))!=null)
                        throw new IllegalStateException("DUPLICATE_EXTENSION");
                }
                if(map.size()!=30)throw new IllegalStateException("EXTENSION_REGISTRY_INCOMPLETE");
                return Map.copyOf(map);
            }
        }catch(IOException e){throw new IllegalStateException("EXTENSION_REGISTRY_UNAVAILABLE",e);}
    }

    public static Report evaluate(Input input) {
        Objects.requireNonNull(input);
        if(input.scope().dataSpace()!=AnalysisContext.DataSpace.SYNTHETIC)
            throw new IllegalArgumentException("UNQUALIFIED_SCENARIO_DIAGNOSTICS_FOR_ACTUAL");
        if(!FORMS.scenarios().containsKey(input.scenarioCode()))
            throw new IllegalArgumentException("UNKNOWN_DIAGNOSTIC_SCENARIO");
        Set<String> unique=new HashSet<>();
        for(InputRow row:input.rows()){
            if(!row.currency().equals(input.scope().currency()))
                throw new IllegalArgumentException("DIAGNOSTIC_CROSS_CURRENCY");
            if(!FORMS.forms().containsKey(row.formCode()))
                throw new IllegalArgumentException("UNKNOWN_DIAGNOSTIC_FORM");
            if(!row.sheetCode().isEmpty()){
                Sheet sheet=SHEETS.get(row.sheetCode());
                if(sheet==null || !sheet.parent().equals(row.formCode()))
                    throw new IllegalArgumentException("INVALID_EXTENSION_PARENT");
                if(!sheet.numericFields().containsAll(row.values().keySet()))
                    throw new IllegalArgumentException("UNREGISTERED_EXTENSION_FIELD");
                if(row.sheetCode().equals("EX-CST-06")
                        && row.state()==EvidenceState.APPROVED
                        && !row.sourceRevision().startsWith("calc:"))
                    throw new IllegalArgumentException("DERIVED_SHEET_REQUIRES_CALCULATION_LINEAGE");
            }
            String key=row.code()+"|"+row.rowId()+"|"+row.period()+"|"+row.eventDate();
            if(!unique.add(key))
                throw new IllegalArgumentException("DUPLICATE_DIAGNOSTIC_GRAIN");
        }
        List<Finding> findings=new ArrayList<>();
        List<InputRow> rows=input.rows();
        switch(input.scenarioCode()){
            case "SC-IP-HEALTH" -> cashMovement(rows,findings);
            case "SC-RETAIL-ECONOMY" -> retail(rows,findings);
            case "SC-CREDIT-DOSSIER" -> dscr(rows,findings);
            case "SC-SECTOR-ROUTED" -> findings.add(new Finding(
                    Method.SECTOR_METHOD_QUALIFICATION,Verdict.NOT_COMPUTABLE,
                    "SECTOR_APPLICABILITY_NEEDS_INDEPENDENT_METHODOLOGY", "BUSINESS",
                    Map.of(),List.of(),List.of()));
            case "SC-LLC-FINANCE" -> {balance(rows,findings); cashFlow(rows,findings);}
            case "SC-HISTORY-3Y5Y" -> annualHistory(rows,findings);
            case "SC-BUDGET-FPA" -> {budget(rows,findings); weeklyCashForecast(rows,findings);}
            case "SC-MFG-ECONOMY" -> {wip(rows,findings); productionCost(rows,findings); standards(rows,findings);
                overheadAbsorption(rows,findings);}
            case "SC-CONSTRUCTION-ESTIMATE" -> {boq(rows,findings); earnedValue(rows,findings);}
            case "SC-INVESTMENT-APPRAISAL" -> datedProjectNpv(rows,findings);
            default -> throw new IllegalArgumentException("SCENARIO_METHOD_NOT_IMPLEMENTED");
        }
        return new Report(input.scenarioCode(),METHOD_VERSION,"SYNTHETIC_PILOT_UNQUALIFIED",findings);
    }
    private static List<InputRow> approved(List<InputRow> input,String code) {
        return input.stream().filter(r->r.code().equals(code)
                && r.state()==EvidenceState.APPROVED).sorted(
                Comparator.comparing(InputRow::itemKey).thenComparing(InputRow::period)
                        .thenComparing(r->r.eventDate()==null?LocalDate.MIN:r.eventDate()))
                .toList();
    }
    private static BigDecimal v(InputRow row,String field) {return row.values().get(field);}
    private static boolean has(InputRow row,String... fields) {
        return Arrays.stream(fields).allMatch(row.values()::containsKey);
    }
    private static SourceRef ref(InputRow r) {
        return new SourceRef(r.formCode(),r.sheetCode(),r.sourceRevision(),
                r.itemKey(),r.rowId(),r.period(),r.eventDate());
    }
    private static void missing(List<Finding> findings,Method method,String source,String subject){
        findings.add(new Finding(method,Verdict.MISSING_EVIDENCE,"INPUT_NOT_APPROVED_OR_INCOMPLETE",
                subject,Map.of(),List.of(source),List.of()));
    }
    private static void add(List<Finding> out,Method method,Verdict verdict,String reason,
                            String subject,Map<String,BigDecimal> metrics,InputRow... input) {
        Map<String,String> computed=new TreeMap<>();
        metrics.forEach((k,value)->computed.put(k,value.stripTrailingZeros().toPlainString()));
        out.add(new Finding(method,verdict,reason,subject,computed, List.of(),
                Arrays.stream(input).map(ScenarioEconomicsEngine::ref).toList()));
    }
    private static BigDecimal ratio(BigDecimal numerator,BigDecimal denominator){
        if(denominator.signum()==0)throw new IllegalArgumentException("RATIO_DIVIDE_BY_ZERO");
        return numerator.divide(denominator,SCALE,RoundingMode.HALF_EVEN);
    }
    private static void cashMovement(List<InputRow> rows,List<Finding> out){
        var in=approved(rows,"DF-CSH-01");var paid=approved(rows,"DF-CSH-02");
        if(in.isEmpty()||paid.isEmpty()){missing(out,Method.OWNER_CASH_MOVEMENT,
                in.isEmpty()?"DF-CSH-01":"DF-CSH-02","BUSINESS");return;}
        // Cash movement is NOT accrual profit. Preserve the source lineage
        // of every included row and never silently drop unpaired months.
        Map<YearMonth,BigDecimal> incoming=sumByPeriod(in,"amount");
        Map<YearMonth,BigDecimal> outgoing=sumByPeriod(paid,"amount");
        Set<YearMonth> periods=new TreeSet<>(incoming.keySet());
        periods.addAll(outgoing.keySet());
        for(YearMonth month:periods){
            if(!incoming.containsKey(month) || !outgoing.containsKey(month)){
                missing(out,Method.OWNER_CASH_MOVEMENT,
                        (incoming.containsKey(month)?"DF-CSH-02":"DF-CSH-01")+":"+month,
                        month.toString());
                continue;
            }
            BigDecimal amount=incoming.get(month).subtract(outgoing.get(month));
            List<SourceRef> provenance=new ArrayList<>();
            in.stream().filter(row->row.period().equals(month)).forEach(row->provenance.add(ref(row)));
            paid.stream().filter(row->row.period().equals(month)).forEach(row->provenance.add(ref(row)));
            out.add(new Finding(Method.OWNER_CASH_MOVEMENT,Verdict.REVIEW,
                    "CASH_MOVEMENT_NOT_NET_PROFIT",month.toString(),
                    Map.of("cashMovement",amount.stripTrailingZeros().toPlainString()),
                    List.of(),provenance));
        }
    }
    private static Map<YearMonth,BigDecimal> sumByPeriod(List<InputRow> list,String field){
        Map<YearMonth,BigDecimal> totals=new TreeMap<>();
        for(InputRow row:list){
            if(!has(row,field))throw new IllegalArgumentException("MISSING_APPROVED_CASH_AMOUNT");
            totals.merge(row.period(),v(row,field),BigDecimal::add);
        }
        return totals;
    }
    private static void balance(List<InputRow> rows,List<Finding> out){
        var list=approved(rows,"F01");
        if(list.isEmpty()){missing(out,Method.BALANCE_RECONCILIATION,"F01","BUSINESS");return;}
        for(InputRow r:list){
            if(!has(r,"assets","liabilities","equity")) {
                missing(out,Method.BALANCE_RECONCILIATION,"F01:assets,liabilities,equity",r.itemKey());continue;
            }
            BigDecimal delta=v(r,"assets").subtract(v(r,"liabilities")).subtract(v(r,"equity"));
            add(out,Method.BALANCE_RECONCILIATION,delta.signum()==0?Verdict.REVIEW:Verdict.OBSERVATION,
                    delta.signum()==0?"BALANCE_RECONCILED":"BALANCE_NOT_RECONCILED",
                    r.itemKey(),Map.of("balanceDifference",delta),r);
        }
    }
    private static void cashFlow(List<InputRow> rows,List<Finding> out){
        var list=approved(rows,"F03");
        if(list.isEmpty()){missing(out,Method.CASH_FLOW_RECONCILIATION,"F03","BUSINESS");return;}
        for(InputRow r:list){
            if(!has(r,"openingCash","cfo","cfi","cff","closingCash")){
                missing(out,Method.CASH_FLOW_RECONCILIATION,"F03:cashFlowBridge",r.itemKey());continue;
            }
            BigDecimal expected=v(r,"openingCash").add(v(r,"cfo")).add(v(r,"cfi")).add(v(r,"cff"));
            BigDecimal delta=v(r,"closingCash").subtract(expected);
            add(out,Method.CASH_FLOW_RECONCILIATION,delta.signum()==0?Verdict.REVIEW:Verdict.OBSERVATION,
                    delta.signum()==0?"CASH_BRIDGE_RECONCILED":"CASH_BRIDGE_NOT_RECONCILED",
                    r.itemKey(),Map.of("cashDifference",delta),r);
        }
    }
    private static void annualHistory(List<InputRow> rows,List<Finding> out){
        var all=approved(rows,"F02");
        var annual=all.stream().filter(r->r.itemKey().equals("ANNUAL_REVENUE")).toList();
        if(annual.size()<3){
            missing(out,Method.ANNUAL_REVENUE_TREND,"F02:3_CONSECUTIVE_FISCAL_YEARS","BUSINESS");
            return;
        }
        Map<Integer,InputRow> years=new TreeMap<>();
        for(InputRow row:annual){
            if(!has(row,"netRevenue")){
                missing(out,Method.ANNUAL_REVENUE_TREND,"F02:netRevenue",row.period().toString());return;
            }
            if(years.putIfAbsent(row.period().getYear(),row)!=null)
                throw new IllegalArgumentException("DUPLICATE_FISCAL_YEAR");
        }
        List<Integer> seq=years.keySet().stream().sorted().toList();
        int latest=seq.getLast();
        for(int year=latest-2;year<=latest;year++)
            if(!years.containsKey(year) || years.get(year).period().getMonthValue()
                    !=years.get(latest).period().getMonthValue()){
                missing(out,Method.ANNUAL_REVENUE_TREND,"F02:COMPARABLE_3_YEARS","BUSINESS");return;
            }
        InputRow recent=years.get(latest),prior=years.get(latest-1),old=years.get(latest-2);
        BigDecimal last=v(recent,"netRevenue"),before=v(prior,"netRevenue");
        if(before.signum()<=0 || v(old,"netRevenue").signum()<=0) {
            out.add(new Finding(Method.ANNUAL_REVENUE_TREND,Verdict.NOT_COMPUTABLE,
                    "NONPOSITIVE_GROWTH_BASE",recent.itemKey(),Map.of(),
                    List.of(),List.of(ref(recent),ref(prior),ref(old))));
            return;
        }
        BigDecimal change=last.subtract(before);
        add(out,Method.ANNUAL_REVENUE_TREND,Verdict.REVIEW,
                change.signum()<0?"YOY_DECLINE":"YOY_NONDECLINE",String.valueOf(latest),
                Map.of("yoyAbsolute",change,"yoyFraction",ratio(change,before),
                        "yearAgoRevenue",before,"currentRevenue",last),old,prior,recent);
    }
    private static void budget(List<InputRow> rows,List<Finding> out){
        List<InputRow> budget=approved(rows,"EX-BUD-02"), actual=approved(rows,"C09");
        if(budget.isEmpty()||actual.isEmpty()){
            missing(out,Method.BUDGET_ACTUAL_VARIANCE,
                    budget.isEmpty()?"EX-BUD-02":"C09","BUSINESS");return;
        }
        Map<String,InputRow> current=new TreeMap<>();
        for(InputRow r:actual)
            if(current.putIfAbsent(r.itemKey()+"/"+r.period(),r)!=null)
                throw new IllegalArgumentException("DUPLICATE_ACTUAL_ACCOUNT_PERIOD");
        Set<String> seenBudget=new HashSet<>();
        for(InputRow b:budget){
            if(!seenBudget.add(b.itemKey()+"/"+b.period()))
                throw new IllegalArgumentException("DUPLICATE_BUDGET_ACCOUNT_PERIOD");
            InputRow a=current.get(b.itemKey()+"/"+b.period());
            if(a==null || !has(a,"amount") || !has(b,"plannedAmount")){
                missing(out,Method.BUDGET_ACTUAL_VARIANCE,
                        "EX-BUD-02+C09:ALIGNED_ACCOUNT_AND_PERIOD",b.itemKey());continue;
            }
            BigDecimal plan=v(b,"plannedAmount"),fact=v(a,"amount");
            BigDecimal variance=fact.subtract(plan);
            if(plan.signum()==0){
                add(out,Method.BUDGET_ACTUAL_VARIANCE,Verdict.NOT_COMPUTABLE,
                        "ZERO_BUDGET_DENOMINATOR",b.itemKey(),
                        Map.of("absoluteVariance",variance),b,a);
            }else{
                add(out,Method.BUDGET_ACTUAL_VARIANCE,Verdict.REVIEW,
                        "VARIANCE_DIRECTION_REQUIRES_ACCOUNT_SEMANTICS",b.itemKey(),
                        Map.of("absoluteVariance",variance,
                                "relativeVariance",ratio(variance,plan)),b,a);
            }
        }
    }
    private static void wip(List<InputRow> rows,List<Finding> out){
        var wip=approved(rows,"EX-MFG-02");
        if(wip.isEmpty()){missing(out,Method.WIP_ROLLFORWARD,"EX-MFG-02","WORK_ORDER");return;}
        for(InputRow r:wip){
            if(!has(r,"openingWip","addedCosts","transferredOut","closingWip")){
                missing(out,Method.WIP_ROLLFORWARD,"EX-MFG-02:WIP_VALUES",r.itemKey());continue;
            }
            BigDecimal expected=v(r,"openingWip").add(v(r,"addedCosts"))
                    .subtract(v(r,"transferredOut"));
            BigDecimal discrepancy=v(r,"closingWip").subtract(expected);
            add(out,Method.WIP_ROLLFORWARD,discrepancy.signum()==0?Verdict.REVIEW:Verdict.OBSERVATION,
                    discrepancy.signum()==0?"WIP_RECONCILED":"WIP_UNRECONCILED",
                    r.itemKey(),Map.of("wipDifference",discrepancy),r);
        }
    }
    private static void productionCost(List<InputRow> rows,List<Finding> out){
        var list=approved(rows,"SF-C02");
        if(list.isEmpty()){missing(out,Method.UNIT_PRODUCTION_COST,"SF-C02","PRODUCTION");return;}
        for(InputRow r:list){
            if(!has(r,"materialUsed","directLabor","overhead","goodUnits")){
                missing(out,Method.UNIT_PRODUCTION_COST,"SF-C02:UNIT_COST_FIELDS",r.itemKey());continue;
            }
            if(v(r,"goodUnits").signum()<=0){
                add(out,Method.UNIT_PRODUCTION_COST,Verdict.NOT_COMPUTABLE,
                        "NONPOSITIVE_GOOD_OUTPUT",r.itemKey(),Map.of(),r);continue;
            }
            BigDecimal cost=v(r,"materialUsed").add(v(r,"directLabor")).add(v(r,"overhead"));
            add(out,Method.UNIT_PRODUCTION_COST,Verdict.REVIEW,
                    "COST_ALLOCATION_REQUIRES_VALIDATION",r.itemKey(),
                    Map.of("productionCost",cost,"unitCost",ratio(cost,v(r,"goodUnits"))),r);
        }
    }
    private static void standards(List<InputRow> rows,List<Finding> out){
        var list=approved(rows,"EX-MFG-04");
        if(list.isEmpty()){missing(out,Method.STANDARD_ACTUAL_VARIANCE,"EX-MFG-04","BATCH");return;}
        for(InputRow r:list){
            if(!has(r,"standardQty","actualQty","standardRate","actualRate")){
                missing(out,Method.STANDARD_ACTUAL_VARIANCE,"EX-MFG-04:QUANTITY_RATE",r.itemKey());continue;
            }
            BigDecimal standardQuantity=v(r,"standardQty"),actualQuantity=v(r,"actualQty"),
                    standardRate=v(r,"standardRate"),actualRate=v(r,"actualRate");
            if(standardQuantity.signum()<0 || actualQuantity.signum()<0
                    || standardRate.signum()<0 || actualRate.signum()<0)
                throw new IllegalArgumentException("NEGATIVE_PRODUCTION_RESOURCE");
            BigDecimal price=actualRate.subtract(standardRate).multiply(actualQuantity);
            BigDecimal usage=actualQuantity.subtract(standardQuantity).multiply(standardRate);
            BigDecimal total=price.add(usage);
            BigDecimal control=actualRate.multiply(actualQuantity)
                    .subtract(standardRate.multiply(standardQuantity));
            if(total.compareTo(control)!=0)
                throw new IllegalStateException("RESOURCE_VARIANCE_NOT_RECONCILED");
            add(out,Method.STANDARD_ACTUAL_VARIANCE,Verdict.REVIEW,
                    "PRICE_USAGE_VARIANCE_DECOMPOSITION",r.itemKey(),
                    Map.of("priceVariance",price,"usageVariance",usage,
                            "totalVariance",total),r);
        }
    }
    private static void boq(List<InputRow> rows,List<Finding> out){
        List<InputRow> quantities=approved(rows,"EX-CST-01"), rates=approved(rows,"EX-CST-02");
        if(quantities.isEmpty() || rates.isEmpty()){
            missing(out,Method.BOQ_RESOURCE_COST,
                    quantities.isEmpty()?"EX-CST-01":"EX-CST-02","BOQ");return;
        }
        Map<String,List<InputRow>> resources=new TreeMap<>();
        for(InputRow r:rates)resources.computeIfAbsent(r.itemKey(),x->new ArrayList<>()).add(r);
        for(InputRow q:quantities){
            var matched=resources.get(q.itemKey());
            if(matched==null || !has(q,"measuredQuantity")){
                missing(out,Method.BOQ_RESOURCE_COST,"EX-CST-01+EX-CST-02:BOQ_RESOURCE_MATCH",
                        q.itemKey());continue;
            }
            if(v(q,"measuredQuantity").signum()<0)
                throw new IllegalArgumentException("NEGATIVE_BOQ_MEASURED_QUANTITY");
            BigDecimal direct=BigDecimal.ZERO;
            boolean incomplete=false;
            for(InputRow rate:matched){
                if(!rate.period().equals(q.period()) || !has(rate,"quantityNorm","unitRate")){
                    incomplete=true;break;
                }
                if(v(rate,"quantityNorm").signum()<0 || v(rate,"unitRate").signum()<0)
                    throw new IllegalArgumentException("NEGATIVE_BOQ_RATE_OR_NORM");
                direct=direct.add(v(q,"measuredQuantity")
                        .multiply(v(rate,"quantityNorm")).multiply(v(rate,"unitRate")));
            }
            if(incomplete){
                missing(out,Method.BOQ_RESOURCE_COST,"EX-CST-02:QUALIFIED_RATE_AND_PERIOD",
                        q.itemKey());continue;
            }
            List<SourceRef> sources=new ArrayList<>();sources.add(ref(q));
            matched.forEach(r->sources.add(ref(r)));
            out.add(new Finding(Method.BOQ_RESOURCE_COST,Verdict.REVIEW,
                    "RESOURCE_BOQ_ESTIMATE_NOT_OFFICIAL",q.itemKey(),
                    Map.of("directCost",direct.stripTrailingZeros().toPlainString()),
                    List.of(),sources));
        }
    }
    private static void earnedValue(List<InputRow> rows,List<Finding> out){
        var list=approved(rows,"EX-CST-06");
        if(list.isEmpty()){missing(out,Method.CONSTRUCTION_EVM,"EX-CST-06","PROJECT");return;}
        for(InputRow r:list){
            if(!has(r,"pv","ev","ac","bac","etc","eac")){
                missing(out,Method.CONSTRUCTION_EVM,"EX-CST-06:PV_EV_AC_BAC_ETC_EAC",
                        r.itemKey());continue;
            }
            BigDecimal ac=v(r,"ac"),pv=v(r,"pv"),eac=v(r,"eac"),bac=v(r,"bac"),
                    etc=v(r,"etc"),ev=v(r,"ev");
            if(ac.signum()<0 || pv.signum()<0 || bac.signum()<0 || etc.signum()<0)
                throw new IllegalArgumentException("INVALID_EVM_NONNEGATIVE_METRIC");
            if(eac.compareTo(ac.add(etc))!=0){
                add(out,Method.CONSTRUCTION_EVM,Verdict.OBSERVATION,
                        "EAC_MUST_MATCH_SELECTED_AC_PLUS_ETC_POLICY",r.itemKey(),
                        Map.of("eacDifference",eac.subtract(ac.add(etc))),r);continue;
            }
            if(ac.signum()==0 || pv.signum()==0){
                add(out,Method.CONSTRUCTION_EVM,Verdict.NOT_COMPUTABLE,
                        "EVM_ZERO_DENOMINATOR",r.itemKey(),
                        Map.of("eac",eac,"vac",bac.subtract(eac)),r);continue;
            }
            add(out,Method.CONSTRUCTION_EVM,eac.compareTo(bac)>0?Verdict.OBSERVATION:Verdict.REVIEW,
                    eac.compareTo(bac)>0?"PROJECT_COST_OVERRUN":"EVM_REVIEW",
                    r.itemKey(),Map.of("cpi",ratio(ev,ac),"spi",ratio(ev,pv),
                            "eac",eac,"vac",bac.subtract(eac)),r);
        }
    }
    private static void retail(List<InputRow> rows,List<Finding> out) {
        var sold=approved(rows,"SF-G01");
        if(sold.isEmpty()){missing(out,Method.TRADE_GROSS_MARGIN,"SF-G01","SKU");}
        for(InputRow r:sold){
            if(!has(r,"netRevenue","cogs")){
                missing(out,Method.TRADE_GROSS_MARGIN,"SF-G01:NET_REVENUE_AND_COGS",r.itemKey());continue;
            }
            BigDecimal revenue=v(r,"netRevenue"),cost=v(r,"cogs");
            BigDecimal gross=revenue.subtract(cost);
            if(revenue.signum()<=0){
                add(out,Method.TRADE_GROSS_MARGIN,Verdict.NOT_COMPUTABLE,
                        "NONPOSITIVE_RETAIL_REVENUE",r.itemKey(),
                        Map.of("grossProfit",gross),r);
            }else{
                add(out,Method.TRADE_GROSS_MARGIN,gross.signum()<0?Verdict.OBSERVATION:Verdict.REVIEW,
                        gross.signum()<0?"NEGATIVE_SKU_GROSS_PROFIT":"SKU_MARGIN_REVIEW",
                        r.itemKey(),Map.of("grossProfit",gross,
                                "grossMarginFraction",ratio(gross,revenue)),r);
            }
        }
        var inventory=approved(rows,"SF-G02");
        if(inventory.isEmpty()){missing(out,Method.STOCK_TURNOVER,"SF-G02","SKU");return;}
        for(InputRow r:inventory){
            if(!has(r,"avgStockValue","periodCogs")){
                missing(out,Method.STOCK_TURNOVER,"SF-G02:AVERAGE_STOCK_COGS",r.itemKey());continue;
            }
            if(v(r,"avgStockValue").signum()<=0){
                add(out,Method.STOCK_TURNOVER,Verdict.NOT_COMPUTABLE,
                        "NONPOSITIVE_AVERAGE_STOCK",r.itemKey(),Map.of(),r);
            }else{
                add(out,Method.STOCK_TURNOVER,Verdict.REVIEW,
                        "STOCK_PERIOD_COMPARABILITY_REQUIRED",r.itemKey(),
                        Map.of("turnoverForPeriod",ratio(v(r,"periodCogs"),
                                v(r,"avgStockValue"))),r);
            }
        }
    }

    private static void dscr(List<InputRow> rows,List<Finding> out){
        var list=approved(rows,"P09");
        if(list.isEmpty()){missing(out,Method.DSCR_PILOT,"P09","BORROWER");return;}
        for(InputRow r:list){
            if(!has(r,"cfads","debtService")){
                missing(out,Method.DSCR_PILOT,"P09:CFADS_AND_DEBT_SERVICE",r.itemKey());continue;
            }
            BigDecimal service=v(r,"debtService");
            if(service.signum()<=0){
                add(out,Method.DSCR_PILOT,Verdict.NOT_COMPUTABLE,
                        "DEBT_SERVICE_MUST_BE_POSITIVE",r.itemKey(),Map.of(),r);continue;
            }
            BigDecimal result=ratio(v(r,"cfads"),service);
            add(out,Method.DSCR_PILOT,Verdict.REVIEW,
                    "DSCR_DEFINITION_REQUIRES_LENDER_POLICY",r.itemKey(),
                    Map.of("dscr",result),r);
        }
    }


    /** 13 consecutive Monday-based forecast weeks, exactly one synthetic scenario. */
    private static void weeklyCashForecast(List<InputRow> rows,List<Finding> out) {
        var weeks=approved(rows,"EX-BUD-06");
        if(weeks.size()!=13) {
            missing(out,Method.WEEKLY_CASH_FORECAST_ROLLFORWARD,
                    "EX-BUD-06:13_APPROVED_CONSECUTIVE_WEEKS","FORECAST");return;
        }
        List<InputRow> ordered=weeks.stream()
                .sorted(Comparator.comparing(r->r.eventDate()==null?LocalDate.MIN:r.eventDate())).toList();
        String scope=ordered.getFirst().itemKey();
        for(int i=0;i<ordered.size();i++) {
            InputRow row=ordered.get(i);
            if(row.eventDate()==null || row.eventDate().getDayOfWeek()!=java.time.DayOfWeek.MONDAY
                    || !row.itemKey().equals(scope)
                    || !has(row,"openingCash","inflow","outflow","restrictedCash","closingCash")
                    || (i>0 && !row.eventDate().equals(ordered.get(i-1).eventDate().plusWeeks(1)))) {
                missing(out,Method.WEEKLY_CASH_FORECAST_ROLLFORWARD,
                        "EX-BUD-06:ALIGNED_MONDAY_WEEK_STARTS_AND_CASH_FIELDS",scope);return;
            }
            if(v(row,"inflow").signum()<0 || v(row,"outflow").signum()<0
                    || v(row,"restrictedCash").signum()<0)
                throw new IllegalArgumentException("INVALID_13W_NONNEGATIVE_FLOW_OR_RESTRICTED");
        }
        BigDecimal totalMismatch=BigDecimal.ZERO;
        BigDecimal lowestAvailable=null;
        for(int i=0;i<ordered.size();i++) {
            InputRow row=ordered.get(i);
            BigDecimal expected=v(row,"openingCash").add(v(row,"inflow"))
                    .subtract(v(row,"outflow"));
            totalMismatch=totalMismatch.add(v(row,"closingCash").subtract(expected).abs());
            if(i>0) totalMismatch=totalMismatch.add(
                    v(row,"openingCash").subtract(v(ordered.get(i-1),"closingCash")).abs());
            BigDecimal available=v(row,"closingCash").subtract(v(row,"restrictedCash"));
            lowestAvailable=lowestAvailable==null?available:lowestAvailable.min(available);
        }
        List<SourceRef> evidence=ordered.stream().map(ScenarioEconomicsEngine::ref).toList();
        if(totalMismatch.signum()!=0) {
            out.add(new Finding(Method.WEEKLY_CASH_FORECAST_ROLLFORWARD,Verdict.OBSERVATION,
                    "FORECAST_ROLLFORWARD_OR_CONTINUITY_MISMATCH",scope,
                    Map.of("reconciliationAbsoluteDifference",
                            totalMismatch.stripTrailingZeros().toPlainString()),
                    List.of(),evidence));
        }else {
            BigDecimal initial=v(ordered.getFirst(),"openingCash");
            BigDecimal closing=v(ordered.getLast(),"closingCash");
            out.add(new Finding(Method.WEEKLY_CASH_FORECAST_ROLLFORWARD,Verdict.REVIEW,
                    "SYNTHETIC_FORECAST_NOT_REALIZED_CASH",scope,
                    Map.of("closingCashForecast",closing.stripTrailingZeros().toPlainString(),
                            "forecastNetMovement",closing.subtract(initial).stripTrailingZeros().toPlainString(),
                            "minimumUnrestrictedCash",lowestAvailable.stripTrailingZeros().toPlainString()),
                    List.of(),evidence));
        }
    }

    /** Explicit allocation difference, without inferring accounting treatment. */
    private static void overheadAbsorption(List<InputRow> rows,List<Finding> out) {
        var entries=approved(rows,"EX-MFG-06");
        if(entries.isEmpty()) {
            missing(out,Method.OVERHEAD_ABSORPTION_VARIANCE,"EX-MFG-06","COST_POOL");return;
        }
        for(InputRow row:entries) {
            if(!has(row,"actualOverhead","absorbedOverhead","capacityBase")) {
                missing(out,Method.OVERHEAD_ABSORPTION_VARIANCE,
                        "EX-MFG-06:ACTUAL_ABSORBED_CAPACITY",row.itemKey());continue;
            }
            BigDecimal actual=v(row,"actualOverhead"),absorbed=v(row,"absorbedOverhead"),
                    capacity=v(row,"capacityBase");
            if(actual.signum()<0 || absorbed.signum()<0 || capacity.signum()<0)
                throw new IllegalArgumentException("NEGATIVE_OVERHEAD_OR_CAPACITY");
            BigDecimal delta=actual.subtract(absorbed);
            Map<String,BigDecimal> result=new TreeMap<>();
            result.put("overheadAbsorptionDifference",delta);
            if(capacity.signum()>0)
                result.put("absorbedOverheadPerCapacityUnit",ratio(absorbed,capacity));
            add(out,Method.OVERHEAD_ABSORPTION_VARIANCE,
                    delta.signum()!=0?Verdict.OBSERVATION:Verdict.REVIEW,
                    delta.signum()>0?"OVERHEAD_UNDERABSORBED"
                            :delta.signum()<0?"OVERHEAD_OVERABSORBED":"OVERHEAD_RECONCILED",
                    row.itemKey(),result,row);
        }
    }

    private static void datedProjectNpv(List<InputRow> rows,List<Finding> out){
        List<InputRow> flows=approved(rows,"EX-INV-01"), policies=approved(rows,"EX-INV-02");
        if(flows.isEmpty() || policies.size()!=1){
            missing(out,Method.DATED_PROJECT_NPV,
                    flows.isEmpty()?"EX-INV-01":"EX-INV-02:ONE_DISCOUNT_POLICY","PROJECT");return;
        }
        InputRow rate=policies.getFirst();
        if(!has(rate,"wacc") || rate.eventDate()==null){
            missing(out,Method.DATED_PROJECT_NPV,"EX-INV-02:WACC_VALUATION_DATE","PROJECT");return;
        }
        BigDecimal annual=v(rate,"wacc");
        if(annual.compareTo(BigDecimal.ONE.negate())<=0)
            throw new IllegalArgumentException("INVALID_PROJECT_DISCOUNT_RATE");
        LocalDate valuationDate=rate.eventDate();
        BigDecimal npv=BigDecimal.ZERO;
        for(InputRow flow:flows) {
            if(!has(flow,"fcff") || flow.eventDate()==null){
                missing(out,Method.DATED_PROJECT_NPV,"EX-INV-01:DATED_FCFF","PROJECT");return;
            }
            long days=ChronoUnit.DAYS.between(valuationDate,flow.eventDate());
            if(days<0)throw new IllegalArgumentException("CASH_FLOW_BEFORE_VALUATION_DATE");
            double power=StrictMath.pow(1.0+annual.doubleValue(),days/365.0);
            if(!Double.isFinite(power) || power==0)
                throw new IllegalArgumentException("NUMERIC_XNPV_OVERFLOW");
            npv=npv.add(v(flow,"fcff").divide(BigDecimal.valueOf(power),MC));
        }
        Map<String,String> result=Map.of("datedNpvApproximate",npv.setScale(SCALE,RoundingMode.HALF_EVEN)
                .stripTrailingZeros().toPlainString(),
                "annualWacc",annual.stripTrailingZeros().toPlainString());
        List<SourceRef> provenance=new ArrayList<>();provenance.add(ref(rate));
        flows.forEach(r->provenance.add(ref(r)));
        out.add(new Finding(Method.DATED_PROJECT_NPV,Verdict.REVIEW,
                "ACT_365_APPROXIMATE_NO_TERMINAL_OR_FCFE",rate.itemKey(),result,
                List.of(),provenance));
    }
}
