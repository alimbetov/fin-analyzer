package kz.fin.formlib.calc.hybrid;

import kz.fin.formlib.calc.fact.FactDigest;
import kz.fin.formlib.calc.plan.F02CalcPlanDescriptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Isolated synthetic PG16 method registration, never deployed in src/main. */
public final class HybridF02RegistryTestFixture {
  private HybridF02RegistryTestFixture(){}

  public static UUID install(JdbcTemplate jdbc,UUID planId,UUID formVersionId,
      String state,boolean wrongSourceSha){
    String version=("TEST_ONLY_REFERENCE".equals(state)&&!wrongSourceSha)
        ?"1":"TEST-"+UUID.randomUUID();
    if("1".equals(version)){
      var existing=jdbc.query("""
          SELECT id FROM fin_calc.method_revision
          WHERE method_code='F02_TEST_ONLY_V1' AND method_version='1'
          """,(r,i)->r.getObject(1,UUID.class));
      if(existing.size()==1)return existing.getFirst();
    }
    String dagSha=jdbc.queryForObject("""
        SELECT dag_jsonb->>'dagSha256' FROM fin_calc.calc_plan WHERE id=?
        """,String.class,planId);
    String schema=jdbc.queryForObject("""
        SELECT schema_sha256 FROM fin_lib.lib_form_version WHERE id=?
        """,String.class,formVersionId);
    var inputs=new ArrayList<HybridMethodRegistry.Input>();
    int ordinal=0;
    for(String field:List.of("cogs","netRevenue","operatingExpenses")){
      String metric="F02."+field;
      String unit=jdbc.queryForObject("""
          SELECT unit_policy FROM fin_lib.lib_field_binding_contract
          WHERE form_code='F02' AND field_code=?
          """,String.class,field);
      inputs.add(new HybridMethodRegistry.Input(ordinal++,formVersionId,
          field,metric,wrongSourceSha?"f".repeat(64):schema,unit,
          "INTERVAL","NONE","REQUIRE_PRESENT"));
    }
    var outputs=List.of(
        new HybridMethodRegistry.Output(0,"grossProfit","KZT",2,"PROPAGATE"),
        new HybridMethodRegistry.Output(1,"grossMargin","RATIO",8,"INVALID"),
        new HybridMethodRegistry.Output(2,"operatingResult","KZT",2,"PROPAGATE"));
    UUID id=UUID.randomUUID();
    jdbc.update("""
        INSERT INTO fin_calc.method_revision(
          id,method_code,method_version,registry_state,
          source_contract_sha256,output_contract_sha256,dag_sha256,
          algorithm_artifact_sha256,config_sha256,params_sha256,
          sql_artifact_sha256,artifact_attested)
        VALUES(?,?,?,?,?,?,?,?,?,?,?,false)
        """,id,F02CalcPlanDescriptor.METHOD,version,state,
        HybridMethodRegistry.sourceDigest(inputs),
        HybridMethodRegistry.outputDigest(outputs),
        dagSha,F02CalcPlanDescriptor.ARTIFACT_SHA,
        F02CalcPlanDescriptor.CONFIG_SHA,F02CalcPlanDescriptor.PARAMS_SHA,
        FactDigest.sha("FIN-FACT-HYB-SQL-MANIFEST-V1",
            "fin_calc.f02_native_calculate","TEST_ONLY_NOT_BUILD_ATTESTED"));
    for(var x:inputs){
      jdbc.update("""
          INSERT INTO fin_calc.method_input_binding(
            method_revision_id,ordinal,form_version_id,field_code,metric_code,
            source_schema_sha256,unit_policy,period_semantics,aggregation_policy,
            missing_policy)
          VALUES(?,?,?,?,?,?,?,?,?,?)
          """,id,x.ordinal(),x.formVersionId(),x.fieldCode(),x.metricCode(),
          x.schemaSha256(),x.unitPolicy(),x.periodSemantics(),
          x.aggregationPolicy(),x.missingPolicy());
    }
    for(var x:outputs){
      jdbc.update("""
          INSERT INTO fin_calc.method_output_binding(
            method_revision_id,ordinal,output_code,unit_code,
            numeric_scale,missing_policy) VALUES(?,?,?,?,?,?)
          """,id,x.ordinal(),x.outputCode(),x.unitCode(),x.numericScale(),
          x.missingPolicy());
    }
    return id;
  }
}
