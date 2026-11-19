package kz.fin.formlib.calc.plan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.formlib.calc.fact.CandidateFactSet;
import kz.fin.formlib.calc.fact.FactDigest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CALC-R0.1: deterministic F02/UAT graph and exact execution identity.
 *
 * The artifact SHA is a VERSIONED SYNTHETIC DESCRIPTOR, not a verified JAR
 * build digest or a financially approved algorithm. CALC-R0.2 must pin this
 * same descriptor and reject unknown versions rather than using latest.
 */
public final class F02CalcPlanDescriptor {
  public static final String METHOD = "F02_TEST_ONLY_V1";
  public static final String CONTRACT = "FIN-CALC-R01-F02-V1";
  public static final String ARTIFACT_KIND = "SYNTHETIC_DESCRIPTOR_NOT_BUILD_ATTESTED";

  private static final String ARTIFACT_DESCRIPTION =
      "F02:v1|sources=netRevenue,cogs,operatingExpenses:KZT:INTERVAL|"
      +"grossProfit=SUBTRACT(netRevenue,cogs):v1|"
      +"grossMargin=DIVIDE(grossProfit,netRevenue):v1|"
      +"operatingResult=SUBTRACT(grossProfit,operatingExpenses):v1";
  private static final String CONFIG_DESCRIPTION =
      "F02:UAT:v1|currency=KZT|moneyScale=2|ratioScale=8|rounding=HALF_EVEN|"
      +"divisionByZero=INVALID|zeroIsPresent=true";
  private static final String PARAMETERS_DESCRIPTION =
      "F02:UAT:v1|signConvention=positiveCostMagnitudes|period=M|basis=MANAGEMENT|"
      +"coverage=PERIOD_ONLY|dataSpace=SYNTHETIC";

  public static final String ARTIFACT_SHA=FactDigest.sha(
      "FIN-FACT-CALC-ARTIFACT-V1",ARTIFACT_DESCRIPTION);
  public static final String CONFIG_SHA=FactDigest.sha(
      "FIN-FACT-CALC-CONFIG-V1",CONFIG_DESCRIPTION);
  public static final String PARAMS_SHA=FactDigest.sha(
      "FIN-FACT-CALC-PARAMS-V1",PARAMETERS_DESCRIPTION);

  private final ObjectMapper mapper;

  public F02CalcPlanDescriptor(ObjectMapper mapper) {
    this.mapper=java.util.Objects.requireNonNull(mapper);
  }

  public record PlanSpec(UUID factSetId,String executionFingerprint,
      String algorithmArtifactSha256,String configSha256,String paramsSha256,
      String dagSha256,String dagJson) {
    public PlanSpec {
      if(factSetId==null)throw new IllegalArgumentException("CALC_PLAN_FACTSET_REQUIRED");
      FactDigest.requireSha(executionFingerprint,"CALC_EXECUTION_SHA_INVALID");
      FactDigest.requireSha(algorithmArtifactSha256,"CALC_ARTIFACT_SHA_INVALID");
      FactDigest.requireSha(configSha256,"CALC_CONFIG_SHA_INVALID");
      FactDigest.requireSha(paramsSha256,"CALC_PARAMS_SHA_INVALID");
      FactDigest.requireSha(dagSha256,"CALC_DAG_SHA_INVALID");
      FactDigest.required(dagJson,"CALC_DAG_REQUIRED");
    }
  }

  public PlanSpec build(UUID factSetId,CandidateFactSet candidate,
      String qualificationBindingSha256,long qualificationEpoch) {
    if(factSetId==null||candidate==null||qualificationEpoch<1)
      throw new IllegalArgumentException("CALC_PLAN_BINDING_INVALID");
    FactDigest.requireSha(qualificationBindingSha256,"CALC_BINDING_SHA_INVALID");
    var scope=candidate.scope();
    if(!"F02_TEST_ONLY".equals(scope.datasetCode())
        ||!"M".equals(scope.periodType())||!"KZT".equals(scope.currencyCode())
        ||candidate.sources().size()!=1||candidate.observations().size()!=3)
      throw new IllegalArgumentException("CALC_F02_PILOT_ONLY");
    var source=candidate.sources().getFirst();
    // Stable list order is the TOPOLGICAL order, not map iteration order.
    var nodes=List.of(
      node("grossProfit","SUBTRACT","1",List.of("F02.netRevenue","F02.cogs"),"KZT",2),
      node("grossMargin","DIVIDE","1",List.of("grossProfit","F02.netRevenue"),"RATIO",8),
      node("operatingResult","SUBTRACT","1",List.of("grossProfit","F02.operatingExpenses"),"KZT",2));
    Map<String,Object> dag=new LinkedHashMap<>();
    dag.put("contract",CONTRACT);
    dag.put("method",METHOD);
    dag.put("artifactKind",ARTIFACT_KIND);
    dag.put("artifactSha256",ARTIFACT_SHA);
    dag.put("configSha256",CONFIG_SHA);
    dag.put("paramsSha256",PARAMS_SHA);
    dag.put("sources",List.of("F02.cogs","F02.netRevenue","F02.operatingExpenses"));
    dag.put("nodes",nodes);
    String dagSha=FactDigest.sha("FIN-FACT-CALC-DAG-V1",
        ARTIFACT_SHA,CONFIG_SHA,PARAMS_SHA,
        "grossProfit:SUBTRACT:1:F02.netRevenue,F02.cogs:KZT:2",
        "grossMargin:DIVIDE:1:grossProfit,F02.netRevenue:RATIO:8",
        "operatingResult:SUBTRACT:1:grossProfit,F02.operatingExpenses:KZT:2");
    dag.put("dagSha256",dagSha);
    String json;
    try {json=mapper.writeValueAsString(dag);}
    catch(JsonProcessingException ex){throw new IllegalStateException("CALC_PLAN_JSON_UNAVAILABLE",ex);}
    String execution=FactDigest.sha("FIN-FACT-CALC-EXECUTION-V1",
        CONTRACT,METHOD,scope.stableScopeKey(),scope.organizationId().toString(),
        scope.projectId().toString(),factSetId.toString(),
        candidate.candidateFactsetSha256(),candidate.sourceSetSha256(),
        candidate.mappingBundleSha256(),candidate.descriptorSha256(),
        qualificationBindingSha256,Long.toString(qualificationEpoch),
        source.revisionId().toString(),source.formVersionId().toString(),
        source.canonicalContentSha256(),source.rowOrderSha256(),
        source.schemaSha256(),source.mappingSha256(),
        ARTIFACT_SHA,CONFIG_SHA,PARAMS_SHA,dagSha);
    return new PlanSpec(factSetId,execution,ARTIFACT_SHA,CONFIG_SHA,PARAMS_SHA,dagSha,json);
  }

  private static Map<String,Object> node(String code,String operator,String version,
      List<String> inputs,String unit,int scale){
    Map<String,Object> n=new LinkedHashMap<>();
    n.put("code",code);
    n.put("operator",operator);
    n.put("operatorVersion",version);
    n.put("inputs",List.copyOf(inputs));
    n.put("unit",unit);
    n.put("scale",scale);
    n.put("rounding","HALF_EVEN");
    return n;
  }
}
