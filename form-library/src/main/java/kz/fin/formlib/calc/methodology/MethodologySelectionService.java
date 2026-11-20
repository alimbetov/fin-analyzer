package kz.fin.formlib.calc.methodology;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * CALC-154: internal UAT-only versioned methodology selection.
 * Parameter JSON is validated in PostgreSQL 16 against immutable definitions.
 * No HTTP endpoint, job dispatch, CalcPlan promotion, financial S2 or production admission.
 * In particular FCFF+Ke and FCFE+WACC cannot be chosen.
 */
@Service
public class MethodologySelectionService {
  public static final String DCF_FAMILY = "DCF_VALUATION";
  public record Variant(UUID id,String code,String inputBasis,String rateBasis,
      String resultBasis,String contractSha256,boolean financiallyApproved) {}
  public record Parameter(String code,String type,boolean required,String optionsJson,
      String unitCode) {}
  public record Selection(UUID id,UUID organizationId,UUID projectId,UUID scenarioId,
      UUID variantRevisionId,String variantCode,String inputBasis,String rateBasis,
      String resultBasis,String paramsSha256,String selectionSha256,
      boolean replayed,boolean financiallyApproved,boolean productionAdmitted) {}

  private record Stored(UUID id,UUID organizationId,UUID projectId,UUID scenarioId,
      UUID variantRevisionId,String variantCode,String inputBasis,String rateBasis,
      String resultBasis,String variantSha,String paramsJson,String paramsSha,
      String selectionSha,boolean financiallyApproved,boolean productionAdmitted) {}
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public MethodologySelectionService(JdbcTemplate jdbc,ObjectMapper mapper) {
    this.jdbc=Objects.requireNonNull(jdbc);
    this.mapper=Objects.requireNonNull(mapper);
  }

  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
  public List<Variant> dcfVariants(){
    return jdbc.query("""
      SELECT id,variant_code,input_basis,rate_basis,result_basis,
        variant_contract_sha256,financial_s2_approved
      FROM fin_calc.methodology_variant_revision
      WHERE family_code=? AND registry_state='DESIGN_ONLY'
      ORDER BY variant_code,revision_number
      """,(r,i)->new Variant(r.getObject(1,UUID.class),r.getString(2),r.getString(3),
        r.getString(4),r.getString(5),r.getString(6).trim(),r.getBoolean(7)),
      DCF_FAMILY);
  }

  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
  public List<Parameter> dcfParameters(){
    return jdbc.query("""
      SELECT parameter_code,value_type,required,enum_options_jsonb::text,unit_code
      FROM fin_calc.methodology_parameter_definition
      WHERE family_code=? ORDER BY parameter_code
      """,(r,i)->new Parameter(r.getString(1),r.getString(2),r.getBoolean(3),
        r.getString(4),r.getString(5)),DCF_FAMILY);
  }

  @Transactional(isolation=Isolation.REPEATABLE_READ)
  public Selection selectDcfUat(UUID organizationId,UUID projectId,UUID scenarioId,
      String variantCode,Map<String,Object> parameters,String selectedBy) {
    if(organizationId==null||projectId==null||scenarioId==null
        ||variantCode==null||!variantCode.matches("[A-Z0-9_]{3,64}")
        ||parameters==null||selectedBy==null||selectedBy.isBlank()
        ||selectedBy.length()>128)
      throw new IllegalArgumentException("METHOD_SELECTION_SCOPE_OR_ACTOR_INVALID");

    String json;
    try { json=mapper.writeValueAsString(parameters); }
    catch(JsonProcessingException e) {
      throw new IllegalArgumentException("METHOD_SELECTION_JSON_INVALID",e);
    }
    var matching=jdbc.query("""
      SELECT id,variant_code,input_basis,rate_basis,result_basis,
        variant_contract_sha256,financial_s2_approved
      FROM fin_calc.methodology_variant_revision
      WHERE family_code=? AND variant_code=? AND revision_number=1 AND registry_state='DESIGN_ONLY'
      LIMIT 1
      """,(r,i)->new Variant(r.getObject(1,UUID.class),r.getString(2),r.getString(3),
        r.getString(4),r.getString(5),r.getString(6).trim(),r.getBoolean(7)),
      DCF_FAMILY,variantCode);
    if(matching.size()!=1||matching.getFirst().financiallyApproved())
      throw new IllegalStateException("METHOD_VARIANT_NOT_IN_UAT_CATALOG");
    var variant=matching.getFirst();
    String expectedVariantSha=sha("FIN-METHOD-VARIANT-V1|"+DCF_FAMILY+"|"+
      variant.code()+"|1|"+variant.inputBasis()+"|"+variant.rateBasis()+"|"+
      variant.resultBasis());
    // The pilot contains revision 1 only; later revisions require exact caller selection.
    if(!expectedVariantSha.equals(variant.contractSha256()))
      throw new IllegalStateException("METHOD_VARIANT_CONTRACT_SHA_DRIFT");

    String pgCanonical=jdbc.queryForObject(
      "SELECT ?::jsonb::text",String.class,json);
    if(pgCanonical==null || !pgCanonical.startsWith("{"))
      throw new IllegalArgumentException("METHOD_SELECTION_PARAMETERS_OBJECT_REQUIRED");
    String paramsSha=sha("FIN-METHOD-PARAMS-V1|"+pgCanonical);
    String selSha=sha("FIN-METHOD-SELECTION-V1|"+organizationId+"|"+projectId+
      "|"+scenarioId+"|"+DCF_FAMILY+"|"+variant.id()+"|"+
      variant.contractSha256()+"|"+paramsSha);
    UUID createdId=UUID.randomUUID();
    int inserted=jdbc.update("""
      INSERT INTO fin_calc.methodology_selection_revision(
        id,organization_id,project_id,scenario_id,family_code,variant_revision_id,
        parameter_values_jsonb,params_sha256,selection_sha256,selected_by)
      VALUES(?,?,?,?,?,?,?::jsonb,?,?,?)
      ON CONFLICT(organization_id,project_id,scenario_id,family_code,selection_sha256)
      DO NOTHING
      """,createdId,organizationId,projectId,scenarioId,DCF_FAMILY,variant.id(),
      pgCanonical,paramsSha,selSha,selectedBy);
    var saved=jdbc.query("""
      SELECT s.id,s.organization_id,s.project_id,s.scenario_id,
        s.variant_revision_id,v.variant_code,v.input_basis,v.rate_basis,
        v.result_basis,v.variant_contract_sha256,
        s.parameter_values_jsonb::text,s.params_sha256,s.selection_sha256,
        s.financial_s2_approved,s.production_admitted
      FROM fin_calc.methodology_selection_revision s
      JOIN fin_calc.methodology_variant_revision v ON v.id=s.variant_revision_id
        AND v.family_code=s.family_code
      WHERE s.organization_id=? AND s.project_id=? AND s.scenario_id=?
        AND s.family_code=? AND s.selection_sha256=?
      """,(r,i)->new Stored(r.getObject(1,UUID.class),
        r.getObject(2,UUID.class),r.getObject(3,UUID.class),
        r.getObject(4,UUID.class),r.getObject(5,UUID.class),
        r.getString(6),r.getString(7),r.getString(8),r.getString(9),
        r.getString(10).trim(),r.getString(11),r.getString(12).trim(),
        r.getString(13).trim(),r.getBoolean(14),r.getBoolean(15)),
      organizationId,projectId,scenarioId,DCF_FAMILY,selSha);
    if(saved.size()!=1)throw new IllegalStateException("METHOD_SELECTION_NOT_PERSISTED");
    var v=saved.getFirst();
    if(!v.variantRevisionId().equals(variant.id())
        ||!v.variantSha().equals(variant.contractSha256())
        ||!v.paramsJson().equals(pgCanonical)||!v.paramsSha().equals(paramsSha)
        ||!v.selectionSha().equals(selSha)
        ||v.financiallyApproved()||v.productionAdmitted())
      throw new IllegalStateException("METHOD_SELECTION_READBACK_DRIFT");
    return new Selection(v.id(),v.organizationId(),v.projectId(),v.scenarioId(),
      v.variantRevisionId(),v.variantCode(),v.inputBasis(),v.rateBasis(),
      v.resultBasis(),paramsSha,selSha,inserted==0,false,false);
  }

  private static String sha(String input) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(input.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("METHOD_SHA256_UNAVAILABLE",e);
    }
  }
}
