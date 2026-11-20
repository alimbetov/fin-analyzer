package kz.fin.formlib.calc.graph;

import kz.fin.formlib.calc.plan.Calc4DTypedDagCompiler;
import kz.fin.formlib.service.IndicatorNavigatorCatalog;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * FIN MG-R0.1: catalog metric -> exact candidate formula versions ->
 * existing typed DAG -> exact physical source-field requirements.
 *
 * Read-only. No new registry, auto-created methodology, financial approval,
 * FactSet, CalcPlan, execution or financial value.
 *
 * The editorial KPI catalog (NET_PROFIT etc.) and fin_lib/fin_calc metric
 * identities are deliberately NOT guessed to be equivalent.
 */
@Service
public class MetricGraphResolver {
  public record MethodRevision(UUID id,String formCode,UUID formVersionId,
      String schemaSha256,String formulaVersion,String reviewState,
      String definitionSha256,String bindingSha256,boolean currentSchema) {}
  public record PhysicalRequirement(String formCode,UUID formVersionId,
      String schemaSha256,String metricCode,String fieldCode,String fieldType,
      String inputMode,String requirednessState,String inputBindingSha256) {}
  public record Resolution(String metricCode,String metricKind,
      String metricQualificationState,List<String> goalCodes,
      List<String> candidateInputForms,List<MethodRevision> methodRevisions,
      UUID selectedMethodRevisionId,String graphSha256,
      List<Calc4DTypedDagCompiler.Node> orderedNodes,
      List<PhysicalRequirement> requiredPhysicalInputs,
      String state,List<String> blockers,boolean graphExecutable,
      boolean financiallyQualified) {
    public Resolution {
      goalCodes=List.copyOf(goalCodes);
      candidateInputForms=List.copyOf(candidateInputForms);
      methodRevisions=List.copyOf(methodRevisions);
      orderedNodes=List.copyOf(orderedNodes);
      requiredPhysicalInputs=List.copyOf(requiredPhysicalInputs);
      blockers=List.copyOf(blockers);
    }
  }
  private record Metric(String code,String kind,String qualification) {}
  private record SourceField(String formCode,UUID versionId,String schema,
      String fieldCode,String type,String inputMode,String contractSha) {}

  private final JdbcTemplate jdbc;
  private final Calc4DTypedDagCompiler compiler;
  private final IndicatorNavigatorCatalog catalog;

  public MetricGraphResolver(JdbcTemplate jdbc,Calc4DTypedDagCompiler compiler,
      IndicatorNavigatorCatalog catalog) {
    this.jdbc=Objects.requireNonNull(jdbc);
    this.compiler=Objects.requireNonNull(compiler);
    this.catalog=Objects.requireNonNull(catalog);
  }

  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
  public Resolution resolve(String metricCode,String goalCode,UUID exactMethodRevisionId) {
    if(metricCode==null||!metricCode.matches("[A-Za-z][A-Za-z0-9_.-]{1,159}"))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"MG_METRIC_CODE_INVALID");
    if(goalCode!=null&&!goalCode.matches("[A-Z][A-Z0-9_]{2,63}"))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"MG_GOAL_CODE_INVALID");

    var editorial=catalog.indicator(metricCode);
    if(goalCode!=null) {
      if(catalog.goal(goalCode,"ru").isEmpty())
        throw new ResponseStatusException(HttpStatus.NOT_FOUND,"MG_GOAL_NOT_FOUND");
      if(editorial.isEmpty()||!editorial.get().goalCodes().contains(goalCode))
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "MG_GOAL_INDICATOR_MAPPING_NOT_AUTHORED");
    }
    List<String> goalCodes=editorial.map(IndicatorNavigatorCatalog.Indicator::goalCodes)
      .orElse(List.of());
    List<String> candidateForms=editorial.map(
      IndicatorNavigatorCatalog.Indicator::inputFormCodes).orElse(List.of());

    List<Metric> existing=jdbc.query("""
      SELECT metric_code,metric_kind,qualification_state
      FROM fin_lib.lib_metric_definition WHERE metric_code=?
      """,(r,i)->new Metric(r.getString(1),r.getString(2),r.getString(3)),metricCode);
    if(existing.size()>1)
      throw new IllegalStateException("MG_METRIC_IDENTITY_NOT_UNIQUE");
    Metric metric=existing.isEmpty()?null:existing.getFirst();

    List<MethodRevision> methods=jdbc.query("""
      SELECT c.id,c.form_code,c.form_version_id,c.source_schema_sha256,
        c.formula_version,c.review_state,c.definition_sha256,c.binding_sha256,
        (c.form_version_id=f.current_version_id
         AND c.source_schema_sha256=v.schema_sha256) AS current_schema
      FROM fin_calc.formula_contract_revision c
      LEFT JOIN fin_lib.lib_form_definition f ON f.form_code=c.form_code
      LEFT JOIN fin_lib.lib_form_version v ON v.id=f.current_version_id
      WHERE c.output_metric_code=?
      ORDER BY c.formula_version,c.id
      """,(r,i)->new MethodRevision(r.getObject(1,UUID.class),r.getString(2),
          r.getObject(3,UUID.class),r.getString(4).trim(),
          r.getString(5),r.getString(6),r.getString(7).trim(),
          r.getString(8).trim(),r.getBoolean(9)),metricCode);

    if(metric==null && editorial.isEmpty() && methods.isEmpty())
      throw new ResponseStatusException(HttpStatus.NOT_FOUND,"MG_METRIC_NOT_FOUND");

    var blockers=new LinkedHashSet<String>();
    var inputForms=new LinkedHashSet<>(candidateForms);
    List<Calc4DTypedDagCompiler.Node> nodes=List.of();
    List<PhysicalRequirement> inputs=List.of();
    String graphSha=null;
    String state;
    UUID chosen=null;

    if(methods.isEmpty()) {
      if(exactMethodRevisionId!=null)
        throw new ResponseStatusException(HttpStatus.CONFLICT,"MG_METHOD_REVISION_NOT_FOR_METRIC");
      inputs=metric!=null&&"SOURCE_FIELD".equals(metric.kind())
        ?physicalMetric(metricCode):List.of();
      for(var input:inputs)inputForms.add(input.formCode());
      state=inputs.isEmpty()?"CATALOG_ONLY":"PHYSICAL_SOURCE_ONLY";
      blockers.add(editorial.isPresent()
        ?"MG_EDITORIAL_INDICATOR_FORMULA_LINK_NOT_AUTHORED"
        :"MG_METHOD_REVISION_NOT_AUTHORED");
    } else {
      if(exactMethodRevisionId==null) {
        if(methods.size()>1) {
          state="METHOD_SELECTION_REQUIRED";
          blockers.add("MG_EXACT_METHOD_REVISION_REQUIRED");
        } else {
          chosen=methods.getFirst().id();
          state="METHOD_SELECTED";
        }
      } else {
        if(methods.stream().noneMatch(m->m.id().equals(exactMethodRevisionId)))
          throw new ResponseStatusException(HttpStatus.CONFLICT,
            "MG_METHOD_REVISION_NOT_FOR_METRIC");
        chosen=exactMethodRevisionId;
        state="METHOD_SELECTED";
      }
      if(chosen!=null) {
        var selected=chosen;
        if(methods.stream().noneMatch(m->m.id().equals(selected)&&m.currentSchema())) {
          state="STALE_METHOD_REVISION";
          blockers.add("MG_FORM_SCHEMA_CHANGED");
        } else {
          try {
            List<UUID> closure=dependencyClosure(chosen);
            var graph=compiler.compile(closure);
            if(graph.orderedNodes().stream().noneMatch(
                n->n.formulaRevisionId().equals(selected)
                  &&n.outputMetricCode().equals(metricCode)))
              throw new IllegalStateException("MG_SELECTED_OUTPUT_NOT_IN_DAG");
            nodes=graph.orderedNodes();
            graphSha=graph.dagSha256();
            var requirements=new ArrayList<PhysicalRequirement>();
            for(var in:graph.physicalInputs()) {
              SourceField source=sourceField(in);
              requirements.add(new PhysicalRequirement(source.formCode(),
                in.formVersionId(),in.schemaSha256(),in.metricCode(),
                in.fieldCode(),in.fieldType(),source.inputMode(),
                "REVIEW_REQUIRED",in.bindingSha256()));
              inputForms.add(source.formCode());
            }
            inputs=List.copyOf(requirements);
            blockers.addAll(graph.blockers());
            state="DAG_COMPILED_PREVIEW_ONLY";
          } catch(IllegalStateException ex) {
            state="BLOCKED_DAG_CONTRACT";
            blockers.add("MG_DAG_STALE_OR_INVALID");
          }
        }
      }
    }

    if(metric==null)blockers.add("MG_METRIC_DEFINITION_NOT_REGISTERED");
    if(editorial.isPresent() && !methods.isEmpty())
      blockers.add("MG_EDITORIAL_TO_FORMULA_LINK_REQUIRES_REVIEW");
    blockers.add("MG_FINANCIAL_S2_NOT_APPROVED");

    return new Resolution(metricCode,metric==null
        ?editorial.isPresent()?"EDITORIAL_INDICATOR":"FORMULA_CANDIDATE_ONLY"
        :metric.kind(),
      metric==null?"CATALOG_UNQUALIFIED":metric.qualification(),
      goalCodes,List.copyOf(inputForms),methods,chosen,graphSha,nodes,inputs,
      state,List.copyOf(blockers),false,false);
  }

  private List<UUID> dependencyClosure(UUID target) {
    var all=new LinkedHashSet<UUID>();
    var pending=new ArrayDeque<UUID>();
    pending.add(target);
    while(!pending.isEmpty()){
      var id=pending.removeFirst();
      if(!all.add(id))continue;
      if(all.size()>1024)throw new IllegalStateException("MG_DAG_TOO_LARGE");
      var dependencies=jdbc.query("""
        SELECT source_formula_revision_id
        FROM fin_calc.formula_typed_input_binding
        WHERE formula_revision_id=? AND source_kind='FORMULA_CANDIDATE'
        ORDER BY ordinal
        """,(r,i)->r.getObject(1,UUID.class),id);
      for(UUID dependency:dependencies) {
        if(dependency==null)throw new IllegalStateException("MG_DAG_DEPENDENCY_NULL");
        pending.addLast(dependency);
      }
    }
    return List.copyOf(all);
  }

  private List<PhysicalRequirement> physicalMetric(String code) {
    return jdbc.query("""
      SELECT f.form_code,v.id,v.schema_sha256,ff.field_code,
         ff.field_type,ff.input_mode,c.contract_sha256
      FROM fin_lib.lib_metric_definition m
      JOIN fin_lib.lib_form_definition f ON f.form_code=m.form_code
      JOIN fin_lib.lib_form_version v ON v.id=f.current_version_id
      JOIN fin_lib.lib_form_field ff ON ff.form_version_id=v.id
        AND ff.field_code=m.source_field_code
      JOIN fin_lib.lib_field_binding_contract c
        ON c.form_code=f.form_code AND c.field_code=ff.field_code
        AND c.metric_code=m.metric_code AND c.source_schema_sha256=v.schema_sha256
      WHERE m.metric_code=? AND m.metric_kind='SOURCE_FIELD'
      """,(r,i)->new PhysicalRequirement(r.getString(1),
        r.getObject(2,UUID.class),r.getString(3).trim(),code,
        r.getString(4),r.getString(5),r.getString(6),
        "CANDIDATE_UNQUALIFIED",r.getString(7).trim()),code);
  }

  private SourceField sourceField(Calc4DTypedDagCompiler.Input input) {
    var sources=jdbc.query("""
      SELECT f.form_code,v.id,v.schema_sha256,ff.field_code,
        ff.field_type,ff.input_mode,c.contract_sha256
      FROM fin_lib.lib_form_version v
      JOIN fin_lib.lib_form_definition f ON f.id=v.form_id
      JOIN fin_lib.lib_form_field ff ON ff.form_version_id=v.id
      JOIN fin_lib.lib_field_binding_contract c
        ON c.form_code=f.form_code AND c.field_code=ff.field_code
        AND c.metric_code=? AND c.source_schema_sha256=v.schema_sha256
      WHERE v.id=? AND ff.field_code=? AND ff.field_type=?
      """,(r,i)->new SourceField(r.getString(1),r.getObject(2,UUID.class),
        r.getString(3).trim(),r.getString(4),r.getString(5),
        r.getString(6),r.getString(7).trim()),input.metricCode(),
        input.formVersionId(),input.fieldCode(),input.fieldType());
    if(sources.size()!=1)
      throw new IllegalStateException("MG_REQUIRED_SOURCE_FIELD_NOT_PINNED");
    return sources.getFirst();
  }
}
