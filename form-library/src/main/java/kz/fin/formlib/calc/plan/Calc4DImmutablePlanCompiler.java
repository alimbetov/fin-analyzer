package kz.fin.formlib.calc.plan;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * CALC-4D.2 read-only compiler of a deterministic immutable UAT plan PREVIEW.
 * Binds exact formula DAG to exact, rehashed TEST_QUALIFIED SYNTHETIC FactSets.
 *
 * Intentionally DOES NOT INSERT INTO fin_calc.calc_plan/calc_job/calc_result:
 * HYB-2B has a BLOCKED worker, and independent financial S2 does not exist.
 */
@Service
public class Calc4DImmutablePlanCompiler {
  public record PinnedSource(UUID factsetId,String factsetSha256,
      String observationSha256,String scopeKey,long epoch) {}
  public record CompiledPreview(UUID organizationId,UUID projectId,
      String currency,String periodStart,String periodEnd,String periodType,
      String dagSha256,String planFingerprint,List<PinnedSource> sources,
      int nodeCount,int numericSourceCount,List<String> blockers,
      String state,boolean executable) {
    public CompiledPreview{
      sources=List.copyOf(sources);
      blockers=List.copyOf(blockers);
    }
  }

  private final Calc4DTypedDagCompiler dagCompiler;
  private final Calc4DExactFactSetInputResolver inputResolver;
  public Calc4DImmutablePlanCompiler(Calc4DTypedDagCompiler dagCompiler,
      Calc4DExactFactSetInputResolver inputResolver){
    this.dagCompiler=Objects.requireNonNull(dagCompiler);
    this.inputResolver=Objects.requireNonNull(inputResolver);
  }

  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
  public CompiledPreview preview(UUID organizationId,List<UUID> formulaRevisionIds,
      List<UUID> exactFactsetIds){
    if(organizationId==null||exactFactsetIds==null||exactFactsetIds.isEmpty()
        ||exactFactsetIds.size()>32
        ||exactFactsetIds.stream().anyMatch(Objects::isNull)
        ||new HashSet<>(exactFactsetIds).size()!=exactFactsetIds.size())
      throw new IllegalArgumentException("CALC4D_EXACT_FACTSET_LIST_REQUIRED");
    var dag=dagCompiler.compile(formulaRevisionIds);
    var snapshots=new ArrayList<Calc4DExactFactSetInputResolver.Snapshot>();
    for(var id:exactFactsetIds) snapshots.add(inputResolver.resolve(organizationId,id));
    snapshots.sort(java.util.Comparator.comparing(s->s.factsetId().toString()));
    var initial=snapshots.getFirst();
    for(var s:snapshots){
      if(!initial.projectId().equals(s.projectId())
          ||!initial.economicEntityId().equals(s.economicEntityId())
          ||!initial.businessScopeId().equals(s.businessScopeId())
          ||!initial.currency().equals(s.currency())
          ||!initial.periodStart().equals(s.periodStart())
          ||!initial.periodEnd().equals(s.periodEnd())
          ||!initial.periodType().equals(s.periodType()))
        throw new IllegalStateException("CALC4D_INTERFORM_SCOPE_PERIOD_CURRENCY_MISMATCH");
    }
    var indexed=new HashMap<String,List<Calc4DExactFactSetInputResolver.Observation>>();
    for(var s:snapshots)for(var o:s.observations()){
      String key=o.formVersionId()+"|"+o.metricCode();
      indexed.computeIfAbsent(key,k->new ArrayList<>()).add(o);
    }
    var missing=new ArrayList<String>();
    for(var input:dag.physicalInputs()){
      String key=input.formVersionId()+"|"+input.metricCode();
      var observations=indexed.get(key);
      if(observations==null||observations.isEmpty()){
        missing.add(input.metricCode());
        continue;
      }
      for(var o:observations){
        if(!input.formVersionId().equals(o.formVersionId())
            ||!input.fieldCode().equals(o.sourceFieldCode())
            ||!input.metricCode().equals(o.metricCode()))
          throw new IllegalStateException("CALC4D_EXACT_INPUT_VERSION_OR_FIELD_DRIFT");
      }
    }
    if(!missing.isEmpty())
      throw new IllegalStateException("CALC4D_REQUIRED_FACTSET_METRICS_MISSING:"+missing);

    // Verify declared temporal semantics; never coerce AS_OF stocks to
    // summable INTERVAL flows, and never synthesize FX conversion.
    for(var node:dag.orderedNodes()){
      for(var in:node.inputs()){
        if(!"PHYSICAL_NUMERIC_FIELD".equals(in.sourceKind()))continue;
        String key=in.formVersionId()+"|"+in.metricCode();
        String expected=switch(node.periodSemantics()){
          case "AS_OF_CANDIDATE" -> "AS_OF";
          case "INTERVAL_REQUIRED","INTERVAL_CANDIDATE" -> "INTERVAL";
          default -> throw new IllegalStateException("CALC4D_PERIOD_POLICY_NOT_SUPPORTED");
        };
        for(var o:indexed.get(key)){
          if(!expected.equals(o.periodSemantics()))
            throw new IllegalStateException("CALC4D_OBSERVATION_PERIOD_OR_CURRENCY_MISMATCH");
        }
      }
    }
    // Derived dependencies are validated by the DAG compiler. A preview
    // does not assume rowset aggregations, grouping, or rounding policies.
    var sources=new ArrayList<PinnedSource>();
    StringBuilder canonical=new StringBuilder("FIN-CALC4D-IMMUTABLE-PREVIEW-V1|")
      .append(organizationId).append('|').append(initial.projectId())
      .append('|').append(initial.economicEntityId())
      .append('|').append(initial.businessScopeId())
      .append('|').append(initial.currency())
      .append('|').append(initial.periodType())
      .append('|').append(initial.periodStart())
      .append('|').append(initial.periodEnd())
      .append('|').append(dag.dagSha256());
    for(var s:snapshots){
      sources.add(new PinnedSource(s.factsetId(),s.factsetSha256(),
        s.observationSha256(),s.scopeKey(),s.qualificationEpoch()));
      canonical.append("|F:").append(s.factsetId()).append(':')
        .append(s.factsetSha256()).append(':').append(s.observationSha256())
        .append(':').append(s.scopeKey()).append(':').append(s.qualificationEpoch());
    }
    var blockers=new ArrayList<>(dag.blockers());
    blockers.add("EXECUTION_NOT_ADMITTED");
    blockers.add("METHODOLOGY_OUTPUT_REQUIREDNESS_UNREVIEWED");
    blockers.add("CURRENCY_DIMENSION_ROUNDING_POLICIES_UNREVIEWED");
    return new CompiledPreview(organizationId,initial.projectId(),
      initial.currency(),initial.periodStart().toString(),
      initial.periodEnd().toString(),initial.periodType(),dag.dagSha256(),
      Calc4DTypedDagCompiler.sha(canonical.toString()),sources,
      dag.orderedNodes().size(),dag.physicalInputs().size(),
      blockers.stream().distinct().sorted().toList(),"UAT_PREVIEW_ONLY",false);
  }
}
