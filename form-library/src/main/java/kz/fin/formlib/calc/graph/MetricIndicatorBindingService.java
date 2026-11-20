package kz.fin.formlib.calc.graph;

import kz.fin.formlib.service.IndicatorNavigatorCatalog;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * MG-R0.2: explicit immutable editorial goal/indicator to physical source OR
 * exact candidate formula binding. No heuristic aliases and no financial math.
 *
 * Formula bindings are version-pinned; source bindings resolve via live
 * fin_lib form/field contracts. This class never writes or qualifies finance.
 */
@Service
public class MetricIndicatorBindingService {
  public record Binding(UUID id,String goalCode,String indicatorCode,
      int revisionNumber,String targetMetricCode,String bindingKind,
      UUID formulaRevisionId,String evidenceRef,String bindingSha256,
      String reviewState,boolean financiallyApproved,boolean productionAdmitted) {}
  public record LinkedGraph(Binding binding,
      MetricGraphResolver.Resolution metricGraph,String state,
      boolean financiallyQualified) {}

  private final JdbcTemplate db;
  private final IndicatorNavigatorCatalog navigator;
  private final MetricGraphResolver graph;

  public MetricIndicatorBindingService(JdbcTemplate db,
      IndicatorNavigatorCatalog navigator,MetricGraphResolver graph) {
    this.db=Objects.requireNonNull(db);
    this.navigator=Objects.requireNonNull(navigator);
    this.graph=Objects.requireNonNull(graph);
  }

  private static String sha(String value){
    try{
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(value.getBytes(StandardCharsets.UTF_8)));
    }catch(Exception ex){throw new IllegalStateException("MG_R02_SHA_UNAVAILABLE",ex);}
  }
  private static void verify(Binding item){
    String expected=sha("FIN-MG-R02-INDICATOR-LINK-V1|"+
      item.goalCode()+"|"+item.indicatorCode()+"|"+item.revisionNumber()+"|"+
      item.targetMetricCode()+"|"+item.bindingKind()+"|"+
      (item.formulaRevisionId()==null?"NONE":item.formulaRevisionId())+"|"+
      item.evidenceRef());
    if(!expected.equals(item.bindingSha256())
        ||!"EDITORIAL_CANDIDATE_NOT_S2".equals(item.reviewState())
        ||item.financiallyApproved()||item.productionAdmitted())
      throw new IllegalStateException("MG_R02_CANDIDATE_BINDING_INTEGRITY_DRIFT");
  }
  private static Binding row(java.sql.ResultSet r,int i)throws java.sql.SQLException {
    return new Binding(r.getObject(1,UUID.class),r.getString(2),r.getString(3),
      r.getInt(4),r.getString(5),r.getString(6),r.getObject(7,UUID.class),
      r.getString(8),r.getString(9).trim(),r.getString(10),
      r.getBoolean(11),r.getBoolean(12));
  }

  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
  public List<Binding> variants(String goalCode,String indicatorCode) {
    if(goalCode==null||!goalCode.matches("[A-Z][A-Z0-9_]{2,63}")
        ||indicatorCode==null||!indicatorCode.matches("[A-Z][A-Z0-9_]{2,159}"))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
        "MG_R02_GOAL_OR_INDICATOR_CODE_INVALID");
    if(navigator.goal(goalCode,"ru").isEmpty()||
        navigator.indicator(indicatorCode).isEmpty())
      throw new ResponseStatusException(HttpStatus.NOT_FOUND,
        "MG_R02_GOAL_OR_INDICATOR_NOT_FOUND");
    if(!navigator.indicator(indicatorCode).orElseThrow().goalCodes().contains(goalCode))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
        "MG_R02_GOAL_INDICATOR_LINK_NOT_AUTHORED");
    var found=db.query("""
      SELECT id,goal_code,indicator_code,revision_number,target_metric_code,
        binding_kind,formula_revision_id,evidence_ref,binding_sha256,
        review_state,financially_approved,production_admitted
      FROM fin_calc.metric_indicator_binding_revision
      WHERE goal_code=? AND indicator_code=?
      ORDER BY revision_number
      """,MetricIndicatorBindingService::row,goalCode,indicatorCode);
    for(var item:found)verify(item);
    return List.copyOf(found);
  }

  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
  public LinkedGraph requireExact(UUID bindingRevisionId) {
    if(bindingRevisionId==null)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
        "MG_R02_EXACT_BINDING_ID_REQUIRED");
    var found=db.query("""
      SELECT id,goal_code,indicator_code,revision_number,target_metric_code,
        binding_kind,formula_revision_id,evidence_ref,binding_sha256,
        review_state,financially_approved,production_admitted
      FROM fin_calc.metric_indicator_binding_revision WHERE id=?
      """,MetricIndicatorBindingService::row,bindingRevisionId);
    if(found.size()!=1)
      throw new ResponseStatusException(HttpStatus.NOT_FOUND,
        "MG_R02_BINDING_REVISION_NOT_FOUND");
    var link=found.getFirst();
    verify(link);
    var variants=variants(link.goalCode(),link.indicatorCode());
    if(variants.stream().noneMatch(item->item.id().equals(link.id())))
      throw new IllegalStateException("MG_R02_BINDING_NOT_IN_ACTIVE_EDITORIAL_CATALOG");
    var target=graph.resolve(link.targetMetricCode(),null,link.formulaRevisionId());
    if(!navigator.indicator(link.indicatorCode()).orElseThrow()
        .inputFormCodes().containsAll(
          target.requiredPhysicalInputs().stream()
            .map(MetricGraphResolver.PhysicalRequirement::formCode).toList()))
      throw new IllegalStateException("MG_R02_SOURCE_OUTSIDE_CANDIDATE_MODEL");
    String state;
    if("SOURCE_FIELD".equals(link.bindingKind())) {
      if(link.formulaRevisionId()!=null||
          !"PHYSICAL_SOURCE_ONLY".equals(target.state())||
          target.requiredPhysicalInputs().size()!=1||
          !target.requiredPhysicalInputs().getFirst().metricCode()
            .equals(link.targetMetricCode()))
        throw new IllegalStateException("MG_R02_DIRECT_SOURCE_CONTRACT_DRIFT");
      state="DIRECT_SOURCE_CANDIDATE_NOT_CALCULATED";
    }else if("DERIVED_CANDIDATE".equals(link.bindingKind())){
      // R16 already marks F02.netProfit as FORM_DERIVED_CANDIDATE:
      // its historical formula text is NOT a qualified executable method.
      int candidates=db.queryForObject("""
        SELECT count(*) FROM fin_lib.lib_metric_binding_exception e
        WHERE e.metric_code=? AND e.semantic_role='FORM_DERIVED_CANDIDATE'
          AND e.review_state='CANDIDATE_UNQUALIFIED'
        """,Integer.class,link.targetMetricCode());
      if(link.formulaRevisionId()!=null||candidates!=1
          ||!"CATALOG_ONLY".equals(target.state())
          ||!target.requiredPhysicalInputs().isEmpty()
          ||!target.orderedNodes().isEmpty())
        throw new IllegalStateException("MG_R02_DERIVED_CANDIDATE_DRIFT");
      state="DERIVED_METHOD_NOT_AUTHORED";
    }else if("FORMULA_REVISION".equals(link.bindingKind())){
      if(link.formulaRevisionId()==null
          ||!link.formulaRevisionId().equals(target.selectedMethodRevisionId())
          ||!"DAG_COMPILED_PREVIEW_ONLY".equals(target.state()))
        throw new IllegalStateException("MG_R02_FORMULA_BINDING_NOT_COMPILED");
      state="EXACT_DAG_CANDIDATE_NOT_EXECUTABLE";
    }else throw new IllegalStateException("MG_R02_UNKNOWN_BINDING_KIND");
    return new LinkedGraph(link,target,state,false);
  }
}
