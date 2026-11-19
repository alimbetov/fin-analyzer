package kz.fin.formlib.bootstrap;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * CALC-3C.2. Seed exactly three KNOWN SYNTHETIC F02 UAT control examples.
 * Evidence must not be treated as S2-approved, or as coverage of 154 forms.
 * Later examples can be appended as separate immutable revisions without
 * changing this manifest or existing checksums.
 */
@Component
@Order(11)
public class Calc3C2ControlExampleBootstrap implements ApplicationRunner {
  private static final String SOURCE="catalog/calc3c2-f02-uat-control-v1.tsv";
  private static final String HEADER=String.join("\t","output_metric_code",
      "example_code","net_revenue","cogs","operating_expenses",
      "expected_value","expected_scale");
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  private record Expected(String metric,String code,String json,BigDecimal expected,
      int scale,String definitionSha) {}
  private record Formula(UUID id,UUID formVersion,String schema,String metric) {}
  private record Stored(String input,String expected,int scale,String definitionSha,String bindingSha) {}

  public Calc3C2ControlExampleBootstrap(JdbcTemplate jdbc,ObjectMapper mapper){
    this.jdbc=Objects.requireNonNull(jdbc);
    this.mapper=Objects.requireNonNull(mapper);
  }

  private List<Expected> readSource() throws Exception {
    var examples=new ArrayList<Expected>();
    var outputs=new HashSet<String>();
    try(var reader=new BufferedReader(new InputStreamReader(
        new ClassPathResource(SOURCE).getInputStream(),StandardCharsets.UTF_8))){
      if(!HEADER.equals(reader.readLine()))
        throw new IllegalStateException("CALC3C2_CONTROL_HEADER_DRIFT");
      String line;
      while((line=reader.readLine())!=null){
        if(line.isBlank())continue;
        var x=line.split("\t",-1);
        if(x.length!=7||!outputs.add(x[0])||!x[1].equals("F02_UAT_200_100_50"))
          throw new IllegalStateException("CALC3C2_CONTROL_SOURCE_IDENTITY");
        var revenue=new BigDecimal(x[2]);
        var cogs=new BigDecimal(x[3]);
        var expenses=new BigDecimal(x[4]);
        var expected=new BigDecimal(x[5]);
        var scale=Integer.parseInt(x[6]);
        if(revenue.signum()<0||cogs.signum()<0||expenses.signum()<0
            ||scale<0||scale>12||expected.scale()!=scale
            ||revenue.compareTo(new BigDecimal("200.00"))!=0
            ||cogs.compareTo(new BigDecimal("100.00"))!=0
            ||expenses.compareTo(new BigDecimal("50.00"))!=0)
          throw new IllegalStateException("CALC3C2_CONTROL_VALUES_DRIFT");
        var inputs=new LinkedHashMap<String,BigDecimal>();
        inputs.put("netRevenue",revenue);
        inputs.put("cogs",cogs);
        inputs.put("operatingExpenses",expenses);
        examples.add(new Expected(x[0],x[1],mapper.writeValueAsString(inputs),
            expected,scale,Calc3BRequirementBootstrap.sha(line)));
      }
    }
    if(examples.size()!=3||!outputs.equals(Set.of(
      "F02.grossProfit","F02.grossMargin","F02.operatingResult")))
      throw new IllegalStateException("CALC3C2_CONTROL_THREE_OUTPUTS_REQUIRED");
    return List.copyOf(examples);
  }

  @Override
  @Transactional
  public void run(ApplicationArguments arguments) throws Exception {
    String database=jdbc.execute((ConnectionCallback<String>) c->
        c.getMetaData().getDatabaseProductName());
    if(!"PostgreSQL".equals(database))return;
    var proposals=readSource();
    var available=jdbc.query("""
      SELECT c.id,c.form_version_id,c.source_schema_sha256,c.output_metric_code
      FROM fin_calc.formula_contract_revision c
      JOIN fin_lib.lib_form_definition f ON f.current_version_id=c.form_version_id
      WHERE c.form_code='F02' AND c.formula_version='F02_TEST_ONLY_V1'
        AND c.review_state='UAT_TEST_ONLY'
        AND c.financial_approval_state='NOT_S2_APPROVED'
      """,(r,i)->new Formula(r.getObject(1,UUID.class),r.getObject(2,UUID.class),
         r.getString(3),r.getString(4)));
    if(available.size()!=3)throw new IllegalStateException("CALC3C2_F02_CONTRACT_COUNT_DRIFT");
    var byMetric=new HashMap<String,Formula>();
    for(var f:available)
      if(byMetric.putIfAbsent(f.metric(),f)!=null)
        throw new IllegalStateException("CALC3C2_DUPLICATE_OUTPUT_CONTRACT");

    for(var x:proposals) {
      var f=byMetric.get(x.metric());
      if(f==null)throw new IllegalStateException("CALC3C2_CONTROL_UNPINNED:"+x.metric());
      String binding=Calc3BRequirementBootstrap.sha("CALC3C2-CONTROL-BIND-V1|"+
          f.id()+"|"+f.formVersion()+"|"+f.schema()+"|"+x.definitionSha());
      var saved=jdbc.query("""
        SELECT input_values_jsonb::text,expected_value::text,expected_scale,
          definition_sha256,binding_sha256
        FROM fin_calc.financial_control_example_revision
        WHERE formula_revision_id=? AND example_code=?
        """,(r,i)->new Stored(r.getString(1),r.getString(2),
          r.getInt(3),r.getString(4),r.getString(5)),f.id(),x.code());
      if(saved.isEmpty()) {
        jdbc.update("""
          INSERT INTO fin_calc.financial_control_example_revision(
            id,formula_revision_id,example_code,form_version_id,source_schema_sha256,
            input_values_jsonb,expected_value,expected_state,expected_scale,
            rounding_mode,reference_type,review_state,
            definition_sha256,binding_sha256)
          VALUES(?,?,?,?,?,?::jsonb,?,'PRESENT',?,'HALF_EVEN',
            'SYNTHETIC_UAT','UNREVIEWED',?,?)
          """,UUID.randomUUID(),f.id(),x.code(),f.formVersion(),f.schema(),
            x.json(),x.expected(),x.scale(),x.definitionSha(),binding);
        saved=jdbc.query("""
          SELECT input_values_jsonb::text,expected_value::text,expected_scale,
            definition_sha256,binding_sha256
          FROM fin_calc.financial_control_example_revision
          WHERE formula_revision_id=? AND example_code=?
          """,(r,i)->new Stored(r.getString(1),r.getString(2),
            r.getInt(3),r.getString(4),r.getString(5)),f.id(),x.code());
      }
      if(saved.size()!=1||saved.getFirst().scale()!=x.scale()
          ||new BigDecimal(saved.getFirst().expected()).compareTo(x.expected())!=0
          ||!saved.getFirst().definitionSha().equals(x.definitionSha())
          ||!saved.getFirst().bindingSha().equals(binding)
          ||!mapper.readTree(saved.getFirst().input()).equals(mapper.readTree(x.json())))
        throw new IllegalStateException("CALC3C2_CONTROL_EXAMPLE_MUTATION:"+x.metric());
    }
  }
}
