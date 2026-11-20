package kz.fin.formlib.calc.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * CALC-4D.3 immutable, UAT-only persisted preview. No calc_plan/calc_job write
 * and no route to production qualification. Source revocation obtains a write
 * lock on factset_control; FOR SHARE here blocks concurrent revocation until
 * the current staging transaction completes.
 */
@Service
public class Calc4DUatPlanStore {
  public record Stored(UUID id,UUID organizationId,UUID projectId,
      String fingerprint,String dagSha256,String manifestSha256,
      List<UUID> exactFormulas,List<UUID> exactFactsets,String currency,
      String periodType,LocalDate start,LocalDate end,boolean replayed,
      boolean admittedToProduction) {
    public Stored {
      exactFormulas=List.copyOf(exactFormulas);
      exactFactsets=List.copyOf(exactFactsets);
    }
  }
  private record Row(UUID id,UUID org,UUID project,String fingerprint,
      String dag,String manifest,String inputSha,String formulaJson,
      String sourceJson,String currency,String periodType,LocalDate start,LocalDate end,
      String admission) {}
  private final JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate named;
  private final Calc4DImmutablePlanCompiler compiler;
  private final ObjectMapper json;
  private final boolean enabled;

  public Calc4DUatPlanStore(JdbcTemplate jdbc,Calc4DImmutablePlanCompiler compiler,
      ObjectMapper json,@Value("${fin.calc4d.uat-execution-enabled:false}") boolean enabled) {
    this.jdbc=Objects.requireNonNull(jdbc);
    this.named=new NamedParameterJdbcTemplate(jdbc);
    this.compiler=Objects.requireNonNull(compiler);
    this.json=Objects.requireNonNull(json);
    this.enabled=enabled;
  }

  @Transactional(isolation=Isolation.REPEATABLE_READ)
  public Stored stage(UUID org,List<UUID> exactFormulaIds,List<UUID> exactFactsetIds)
      throws Exception {
    requireEnabled();
    var formulas=sortedUnique(exactFormulaIds,1024,"CALC4D_UAT_FORMULAS");
    var factsets=sortedUnique(exactFactsetIds,32,"CALC4D_UAT_FACTSETS");
    lockActiveFactsets(org,factsets);
    var preview=compiler.preview(org,formulas,factsets);
    if(preview.executable()||!"UAT_PREVIEW_ONLY".equals(preview.state())
        ||preview.sources().size()!=factsets.size())
      throw new IllegalStateException("CALC4D_UAT_UNQUALIFIED_PREVIEW_REQUIRED");
    String formulaJson=json.writeValueAsString(formulas.stream()
      .map(UUID::toString).toList());
    String sourceJson=json.writeValueAsString(preview.sources());
    String manifestSha=Calc4DTypedDagCompiler.sha(
      "FIN-CALC4D-UAT-SOURCES-V1|"+sourceJson);
    UUID newId=UUID.randomUUID();
    jdbc.update("""
      INSERT INTO fin_calc.calc4d_uat_plan(
        id,organization_id,project_id,execution_fingerprint,dag_sha256,
        source_manifest_sha256,input_plan_sha256,formula_revision_ids_jsonb,
        source_factsets_jsonb,currency_code,period_type,period_start,
        period_end_exclusive)
      VALUES(?,?,?,?,?,?,?,?::jsonb,?::jsonb,?,?,?,?)
      ON CONFLICT (organization_id,execution_fingerprint) DO NOTHING
      """,newId,org,preview.projectId(),preview.planFingerprint(),
      preview.dagSha256(),manifestSha,preview.planFingerprint(),
      formulaJson,sourceJson,preview.currency(),preview.periodType(),
      LocalDate.parse(preview.periodStart()),LocalDate.parse(preview.periodEnd()));
    var row=loadByFingerprint(org,preview.planFingerprint());
    if(!row.project().equals(preview.projectId())
        ||!row.dag().equals(preview.dagSha256())
        ||!row.manifest().equals(manifestSha)
        ||!row.inputSha().equals(preview.planFingerprint())
        ||!row.currency().equals(preview.currency())
        ||!row.periodType().equals(preview.periodType())
        ||!row.start().equals(LocalDate.parse(preview.periodStart()))
        ||!row.end().equals(LocalDate.parse(preview.periodEnd()))
        ||!json.readTree(formulaJson).equals(json.readTree(row.formulaJson()))
        ||!json.readTree(sourceJson).equals(json.readTree(row.sourceJson()))
        ||!"UAT_ONLY_NOT_S2_APPROVED".equals(row.admission()))
      throw new IllegalStateException("CALC4D_UAT_PERSISTED_PLAN_CONFLICT");
    return toStored(row,formulas,factsets,!newId.equals(row.id()));
  }

  /**
   * Used by the executor within the SAME write transaction; it acquires the
   * source control locks before compiling/re-hashing all pinned inputs.
   */
  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
  public Stored requireCurrent(UUID organizationId,UUID exactPlanId) throws Exception {
    requireEnabled();
    if(organizationId==null||exactPlanId==null)
      throw new IllegalArgumentException("CALC4D_UAT_PLAN_AND_ORG_REQUIRED");
    var rows=jdbc.query("""
      SELECT id,organization_id,project_id,execution_fingerprint,dag_sha256,
        source_manifest_sha256,input_plan_sha256,formula_revision_ids_jsonb::text,
        source_factsets_jsonb::text,currency_code,period_type,period_start,
        period_end_exclusive,admission_state
      FROM fin_calc.calc4d_uat_plan WHERE id=? AND organization_id=?
      """,(r,i)->map(r),exactPlanId,organizationId);
    if(rows.size()!=1)throw new IllegalStateException("CALC4D_UAT_PLAN_NOT_FOUND");
    Row row=rows.getFirst();
    List<UUID> formulas=decodeUuids(row.formulaJson());
    var sources=json.readTree(row.sourceJson());
    if(!sources.isArray()||sources.isEmpty()||sources.size()>32)
      throw new IllegalStateException("CALC4D_UAT_SOURCE_LIST_INVALID");
    var factsets=new java.util.ArrayList<UUID>();
    for(var source:sources){
      if(!source.isObject())throw new IllegalStateException("CALC4D_UAT_SOURCE_PIN_INVALID");
      factsets.add(UUID.fromString(source.path("factsetId").asText()));
    }
    factsets= new java.util.ArrayList<>(sortedUnique(factsets,32,"CALC4D_UAT_FACTSETS"));
    lockActiveFactsets(organizationId,factsets);
    var preview=compiler.preview(organizationId,formulas,factsets);
    String canonicalSources=json.writeValueAsString(preview.sources());
    String manifestSha=Calc4DTypedDagCompiler.sha(
      "FIN-CALC4D-UAT-SOURCES-V1|"+canonicalSources);
    if(!"UAT_ONLY_NOT_S2_APPROVED".equals(row.admission())
        ||!row.project().equals(preview.projectId())
        ||!row.fingerprint().equals(preview.planFingerprint())
        ||!row.dag().equals(preview.dagSha256())
        ||!row.inputSha().equals(preview.planFingerprint())
        ||!row.manifest().equals(manifestSha)
        ||!json.readTree(canonicalSources).equals(json.readTree(row.sourceJson()))
        ||!row.currency().equals(preview.currency())
        ||!row.periodType().equals(preview.periodType())
        ||!row.start().equals(LocalDate.parse(preview.periodStart()))
        ||!row.end().equals(LocalDate.parse(preview.periodEnd())))
      throw new IllegalStateException("CALC4D_UAT_STALE_OR_TAMPERED_PLAN");
    return toStored(row,formulas,factsets,true);
  }

  private Row loadByFingerprint(UUID org,String fingerprint){
    var rows=jdbc.query("""
      SELECT id,organization_id,project_id,execution_fingerprint,dag_sha256,
        source_manifest_sha256,input_plan_sha256,formula_revision_ids_jsonb::text,
        source_factsets_jsonb::text,currency_code,period_type,period_start,
        period_end_exclusive,admission_state
      FROM fin_calc.calc4d_uat_plan
      WHERE organization_id=? AND execution_fingerprint=?
      """,(r,i)->map(r),org,fingerprint);
    if(rows.size()!=1)throw new IllegalStateException("CALC4D_UAT_STAGED_PLAN_MISSING");
    return rows.getFirst();
  }

  private static Row map(java.sql.ResultSet r)throws java.sql.SQLException{
    return new Row(r.getObject(1,UUID.class),r.getObject(2,UUID.class),
      r.getObject(3,UUID.class),r.getString(4).trim(),r.getString(5).trim(),
      r.getString(6).trim(),r.getString(7).trim(),r.getString(8),
      r.getString(9),r.getString(10),r.getString(11),
      r.getDate(12).toLocalDate(),r.getDate(13).toLocalDate(),r.getString(14));
  }
  private Stored toStored(Row r,List<UUID> formulas,List<UUID> factsets,boolean replayed){
    return new Stored(r.id(),r.org(),r.project(),r.fingerprint(),r.dag(),
      r.manifest(),formulas,factsets,r.currency(),r.periodType(),r.start(),
      r.end(),replayed,false);
  }
  private List<UUID> decodeUuids(String raw)throws Exception{
    var array=json.readValue(raw,String[].class);
    var found=new java.util.ArrayList<UUID>();
    for(String value:array)found.add(UUID.fromString(value));
    return sortedUnique(found,1024,"CALC4D_UAT_FORMULAS");
  }

  private void lockActiveFactsets(UUID org,List<UUID> factsets){
    if(org==null)throw new IllegalArgumentException("CALC4D_UAT_ORGANIZATION_REQUIRED");
    var locked=named.query("""
      SELECT c.active_factset_id
      FROM fin_fact.factset_control c
      JOIN fin_fact.fact_set f ON f.id=c.active_factset_id
        AND f.organization_id=c.organization_id
        AND f.scope_key=c.stable_scope_key
      WHERE c.organization_id=:org
        AND c.active_factset_id IN (:ids)
        AND c.revoked=false AND c.qualification_epoch=f.qualification_epoch
        AND f.qualification_mode='TEST_QUALIFIED' AND f.data_space='SYNTHETIC'
      ORDER BY c.stable_scope_key
      FOR SHARE OF c
      """,new MapSqlParameterSource("org",org).addValue("ids",factsets),
      (r,i)->r.getObject(1,UUID.class));
    if(locked.size()!=factsets.size()
        ||!new HashSet<>(locked).equals(new HashSet<>(factsets)))
      throw new IllegalStateException("CALC4D_UAT_FACTSET_REVOKED_OR_STALE");
  }

  private static List<UUID> sortedUnique(List<UUID> ids,int max,String code){
    if(ids==null||ids.isEmpty()||ids.size()>max
        ||ids.stream().anyMatch(Objects::isNull)
        ||new HashSet<>(ids).size()!=ids.size())
      throw new IllegalArgumentException(code+"_INVALID_OR_DUPLICATE");
    return ids.stream().sorted(Comparator.comparing(UUID::toString)).toList();
  }
  private void requireEnabled(){
    if(!enabled)throw new IllegalStateException("CALC4D_UAT_EXECUTION_DISABLED");
  }
}
