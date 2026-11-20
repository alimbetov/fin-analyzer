package kz.fin.formlib.calc.methodology;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * FIN CALC-APPS-R0: six input-contract templates bound to EXISTING form catalog.
 * Does not execute formula text or mint qualified CalcPlans. No write interface.
 */
@Service
public final class CalcApplicationContractRegistry {
  public record Contract(
      String code, String titleRu, String domain, int version,
      List<String> sourceForms, List<String> typedInputs,
      List<String> proposedOutputs, List<String> variants,
      String blockers, String contractSha256,
      boolean financiallyApproved, boolean productionAdmitted) {
    public Contract {
      sourceForms=List.copyOf(sourceForms);
      typedInputs=List.copyOf(typedInputs);
      proposedOutputs=List.copyOf(proposedOutputs);
      variants=List.copyOf(variants);
    }
  }
  private record Raw(String code,String title,String domain,int version,
      String sourcesJson,String inputsJson,String outputsJson,String variantsJson,
      String blockers,String sha,boolean financial,boolean production) {}

  private static final TypeReference<List<String>> LIST_TYPE=new TypeReference<>() {};
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  public CalcApplicationContractRegistry(JdbcTemplate jdbc,ObjectMapper mapper){
    this.jdbc=Objects.requireNonNull(jdbc);
    this.mapper=Objects.requireNonNull(mapper);
  }

  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
  public List<Contract> all(){
    var raw=jdbc.query("""
      SELECT a.app_code,a.title_ru,a.financial_domain,
        c.revision_number,c.source_form_codes_jsonb::text,
        c.candidate_input_fields_jsonb::text,c.proposed_outputs_jsonb::text,
        c.proposed_variants_jsonb::text,c.blocker_description,
        c.contract_sha256,c.financial_s2_approved,c.production_admitted
      FROM fin_calc.calc_application_definition a
      JOIN fin_calc.calc_application_contract_revision c
        ON c.app_code=a.app_code
      WHERE a.lifecycle_state='DESIGN_ONLY'
        AND c.contract_state='SCHEMA_CANDIDATE'
      ORDER BY a.app_code,c.revision_number
      """,(r,i)->new Raw(r.getString(1),r.getString(2),r.getString(3),
        r.getInt(4),r.getString(5),r.getString(6),r.getString(7),
        r.getString(8),r.getString(9),r.getString(10).trim(),
        r.getBoolean(11),r.getBoolean(12)));
    if(raw.size()!=6)
      throw new IllegalStateException("CALC_APPS_SIX_CONTRACTS_REQUIRED");
    var result=new ArrayList<Contract>();
    var observedCodes=new HashSet<String>();
    for(var x:raw){
      if(!x.code().matches("CALC-A0[1-6]") || !observedCodes.add(x.code())
          ||x.version()!=1 || x.financial() || x.production())
        throw new IllegalStateException("CALC_APPS_CONTRACT_SET_INVALID");
      String expected=sha("FIN-CALC-APPLICATION-CONTRACT-V1|"+x.code()+"|"
          +x.version()+"|"+x.sourcesJson()+"|"+x.inputsJson()+"|"
          +x.outputsJson()+"|"+x.variantsJson()+"|"+x.blockers());
      if(!expected.equals(x.sha()))
        throw new IllegalStateException("CALC_APPS_CONTRACT_DIGEST_MISMATCH");
      var sources=parse(x.sourcesJson());
      var typedInputs=parse(x.inputsJson());
      var outputs=parse(x.outputsJson());
      var variants=parse(x.variantsJson());
      if(sources.isEmpty()||typedInputs.isEmpty()||outputs.isEmpty()||variants.isEmpty())
        throw new IllegalStateException("CALC_APPS_INPUT_OR_OUTPUT_EMPTY");
      for(var input:typedInputs)
        if(!input.matches("[A-Za-z][A-Za-z0-9_]*:(TEXT|UUID|DATE|MONEY|DECIMAL|INTEGER|ENUM|CURRENCY)"))
          throw new IllegalStateException("CALC_APPS_INPUT_TYPE_UNSUPPORTED");
      result.add(new Contract(x.code(),x.title(),x.domain(),x.version(),
          sources,typedInputs,outputs,variants,x.blockers(),x.sha(),false,false));
    }
    return List.copyOf(result);
  }

  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
  public void requireSourceCatalogIntegrity(){
    all(); // also verify immutable SHA and exact contract registry.
    var missing=jdbc.query("""
      SELECT DISTINCT d.app_code, f.source_form
      FROM fin_calc.calc_application_contract_revision d
      CROSS JOIN LATERAL jsonb_array_elements_text(d.source_form_codes_jsonb)
        AS f(source_form)
      LEFT JOIN fin_lib.lib_form_definition r ON r.form_code=f.source_form
      WHERE r.form_code IS NULL
      ORDER BY d.app_code,f.source_form
      """,(r,i)->r.getString(1)+":"+r.getString(2));
    if(!missing.isEmpty())
      throw new IllegalStateException("CALC_APPS_UNRESOLVED_SOURCE_FORMS:"+missing);
  }

  private List<String> parse(String json){
    try{return mapper.readValue(json,LIST_TYPE);}
    catch(JsonProcessingException ex){
      throw new IllegalStateException("CALC_APPS_TYPED_JSON_UNREADABLE",ex);
    }
  }
  private static String sha(String value){
    try{
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(value.getBytes(StandardCharsets.UTF_8)));
    }catch(Exception ex){
      throw new IllegalStateException("CALC_APPS_SHA256_MISSING",ex);
    }
  }
}
