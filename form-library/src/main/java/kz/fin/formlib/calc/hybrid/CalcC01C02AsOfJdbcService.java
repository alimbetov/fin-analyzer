package kz.fin.formlib.calc.hybrid;

import kz.fin.formlib.calc.plan.Calc4DExactFactSetInputResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * CALC-C01/C02: snapshot-pinned, read-back-verifiable SQL/JDBC + Java
 * BigDecimal calculation. Only test-qualified SYNTHETIC FactSets are accepted.
 * Results are append-only UAT receipts, never financial S2 or production CalcPlan.
 */
@Service
public class CalcC01C02AsOfJdbcService {
  public static final String POLICY = "ASOF_DEBT_POSITIVE_STOCK_UAT_V1";
  public record Preview(UUID resultId, UUID factsetId, String factsetSha256,
      String observationSha256, String formCode, UUID formVersionId,
      LocalDate asOf, String currency, String primaryMetric,
      BigDecimal primaryValue, BigDecimal overdueValue, BigDecimal overdueShare,
      String overdueShareStatus, String resultSha256, int sourceRows,
      boolean replayed, boolean sqlJavaParityPassed,
      boolean financiallyApproved, boolean productionAdmitted) {}
  private record SqlAggregate(String metric, long count, BigDecimal total) {}
  private record Saved(UUID id, String resultSha, BigDecimal primary,
      BigDecimal overdue, BigDecimal share, String shareState,
      int rows, boolean financialApproval, boolean productionAdmission) {}

  private final JdbcTemplate jdbc;
  private final Calc4DExactFactSetInputResolver resolver;
  public CalcC01C02AsOfJdbcService(JdbcTemplate jdbc,
      Calc4DExactFactSetInputResolver resolver) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.resolver = Objects.requireNonNull(resolver);
  }

  @Transactional(isolation=Isolation.REPEATABLE_READ, rollbackFor=Exception.class)
  public Preview executeUat(UUID organizationId, UUID exactFactsetId, String formCode) {
    if (organizationId == null || exactFactsetId == null
        || !Set.of("C01","C02").contains(formCode))
      throw new IllegalArgumentException("ASOF_EXACT_ORG_FACTSET_AND_FORM_REQUIRED");
    // Hold a shared lock to prevent concurrent source revocation during receipt write.
    var locked = jdbc.query("""
      SELECT c.id FROM fin_fact.factset_control c
      JOIN fin_fact.fact_set s ON s.id=c.active_factset_id
        AND s.organization_id=c.organization_id
        AND s.scope_key=c.stable_scope_key
        AND s.qualification_epoch=c.qualification_epoch
      WHERE s.id=? AND s.organization_id=? AND NOT c.revoked
        AND s.qualification_mode='TEST_QUALIFIED' AND s.data_space='SYNTHETIC'
      FOR SHARE OF c
      """,(r,i)->r.getObject(1,UUID.class),exactFactsetId,organizationId);
    if (locked.size() != 1)
      throw new IllegalStateException("ASOF_FACTSET_REVOKED_OR_NOT_UAT");
    var snapshot = resolver.resolve(organizationId,exactFactsetId);
    LocalDate asOf = snapshot.periodEnd().minusDays(1);
    if (asOf.isBefore(snapshot.periodStart()))
      throw new IllegalStateException("ASOF_FACTSET_DATE_INVALID");

    var matching = snapshot.observations().stream()
        .filter(o -> formCode.equals(o.formCode())).toList();
    if (matching.isEmpty())
      throw new IllegalStateException("ASOF_FORM_NOT_IN_PINNED_FACTSET");
    UUID formVersion = matching.getFirst().formVersionId();
    var expected = "C01".equals(formCode)
        ? Set.of("gross","allowance","overdue")
        : Set.of("outstanding","overdue");
    var fieldTypes = jdbc.query("""
      SELECT field_code,field_type FROM fin_lib.lib_form_field
      WHERE form_version_id=?
      """,(r,i)->Map.entry(r.getString(1),r.getString(2)),formVersion);
    var numericFields = new HashMap<String,String>();
    for (var f:fieldTypes) numericFields.put(f.getKey(),f.getValue());
    if (!expected.stream().allMatch(f -> "MONEY".equals(numericFields.get(f))))
      throw new IllegalStateException("ASOF_FORM_MONEY_CONTRACT_MISSING");

    var items = new ArrayList<CalcAsOfDebtKernel.Cell>();
    Map<String,Long> expectedCounts = new HashMap<>();
    for (var o:matching) {
      if (!formVersion.equals(o.formVersionId())
          || !o.metricCode().equals(formCode+"."+o.sourceFieldCode())
          || !"AS_OF".equals(o.periodSemantics())
          || !o.start().equals(snapshot.periodStart())
          || !o.end().equals(snapshot.periodEnd())
          || !snapshot.currency().equals(o.unitCode())
          || !expected.contains(o.sourceFieldCode()))
        throw new IllegalStateException("ASOF_SOURCE_PERIOD_UNIT_OR_FIELD_DRIFT");
      items.add(new CalcAsOfDebtKernel.Cell(o.sourceRevisionId(),
          o.rowKey(),o.sourceFieldCode(),o.value()));
      expectedCounts.merge(o.metricCode(),1L,Long::sum);
    }
    var form = CalcAsOfDebtKernel.Form.valueOf(formCode);
    var calculation = CalcAsOfDebtKernel.calculate(form,items);

    // Native PostgreSQL SUM_AS_OF, never SUM_FLOW and never multiple stock dates.
    var sql = jdbc.query("""
      SELECT metric_code,count(*),sum(numeric_value)
      FROM fin_fact.fact_observation
      WHERE factset_id=? AND source_form_code=? AND source_form_version_id=?
        AND period_semantics='AS_OF' AND period_start=? AND period_end_exclusive=?
        AND currency_code=? AND unit_code=?
      GROUP BY metric_code ORDER BY metric_code
      """,(r,i)->new SqlAggregate(r.getString(1),r.getLong(2),r.getBigDecimal(3)),
      exactFactsetId,formCode,formVersion,snapshot.periodStart(),
      snapshot.periodEnd(),snapshot.currency(),snapshot.currency());

    if (sql.size()!=expectedCounts.size())
      throw new IllegalStateException("ASOF_SQL_JAVA_SOURCE_SET_MISMATCH");
    for (var aggregate:sql) {
      String code = formCode+"."+aggregate.metric().substring(formCode.length()+1);
      if (!code.equals(aggregate.metric())
          || !expected.contains(code.substring(formCode.length()+1))
          || !Objects.equals(expectedCounts.get(code),aggregate.count()))
        throw new IllegalStateException("ASOF_SQL_JAVA_COUNT_OR_SOURCE_MISMATCH");
      BigDecimal javaSum = calculation.fieldTotals()
          .get(code.substring(formCode.length()+1));
      if (javaSum == null || aggregate.total() == null
          || aggregate.total().compareTo(javaSum)!=0)
        throw new IllegalStateException("ASOF_SQL_JAVA_SUM_MISMATCH");
    }
    if ("PRESENT".equals(calculation.overdueShareState())) {
      BigDecimal sqlRatio=jdbc.queryForObject(
          "SELECT round(?::numeric / NULLIF(?::numeric,0),12)",
          BigDecimal.class,calculation.overdueAmount(),
          calculation.fieldTotals().get("C01".equals(formCode)?"gross":"outstanding"));
      if (sqlRatio == null || sqlRatio.compareTo(calculation.overdueShare())!=0)
        throw new IllegalStateException("ASOF_SQL_JAVA_RATIO_MISMATCH");
    }
    String resultSha = sha("FIN-CALC154-ASOF-UAT-RESULT-V1|"+POLICY+"|"
        +organizationId+"|"+exactFactsetId+"|"+snapshot.factsetSha256()+"|"
        +snapshot.observationSha256()+"|"+formCode+"|"+formVersion+"|"
        +asOf+"|"+snapshot.currency()+"|"+calculation.primaryMetric()+"|"
        +calculation.primaryAmount().toPlainString()+"|"
        +String.valueOf(calculation.overdueAmount())+"|"
        +String.valueOf(calculation.overdueShare())+"|"
        +calculation.overdueShareState()+"|"+calculation.sourceRowCount());
    UUID newId=UUID.randomUUID();
    int inserted=jdbc.update("""
      INSERT INTO fin_calc.calc154_asof_uat_result(
        id,organization_id,factset_id,form_code,form_version_id,policy_version,
        as_of_date,currency_code,factset_sha256,observation_sha256,result_sha256,
        primary_metric_code,primary_value,overdue_value,overdue_share,
        overdue_share_state,source_row_count)
      VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
      ON CONFLICT(organization_id,factset_id,form_code,policy_version) DO NOTHING
      """,newId,organizationId,exactFactsetId,formCode,formVersion,POLICY,
      asOf,snapshot.currency(),snapshot.factsetSha256(),snapshot.observationSha256(),
      resultSha,calculation.primaryMetric(),calculation.primaryAmount(),
      calculation.overdueAmount(),calculation.overdueShare(),
      calculation.overdueShareState(),calculation.sourceRowCount());
    var stored=jdbc.query("""
      SELECT id,result_sha256,primary_value,overdue_value,overdue_share,
        overdue_share_state,source_row_count,financially_approved,production_admitted
      FROM fin_calc.calc154_asof_uat_result
      WHERE organization_id=? AND factset_id=? AND form_code=? AND policy_version=?
      """,(r,i)->new Saved(r.getObject(1,UUID.class),r.getString(2).trim(),
        r.getBigDecimal(3),r.getBigDecimal(4),r.getBigDecimal(5),
        r.getString(6),r.getInt(7),r.getBoolean(8),r.getBoolean(9)),
      organizationId,exactFactsetId,formCode,POLICY);
    if (stored.size()!=1) throw new IllegalStateException("ASOF_RESULT_NOT_PERSISTED");
    var saved=stored.getFirst();
    if (!saved.resultSha().equals(resultSha)
        || saved.primary().compareTo(calculation.primaryAmount())!=0
        || !same(saved.overdue(),calculation.overdueAmount())
        || !same(saved.share(),calculation.overdueShare())
        || !saved.shareState().equals(calculation.overdueShareState())
        || saved.rows()!=calculation.sourceRowCount()
        || saved.financialApproval() || saved.productionAdmission())
      throw new IllegalStateException("ASOF_RESULT_REPLAY_OR_APPROVAL_DRIFT");
    return new Preview(saved.id(),exactFactsetId,snapshot.factsetSha256(),
        snapshot.observationSha256(),formCode,formVersion,asOf,snapshot.currency(),
        calculation.primaryMetric(),calculation.primaryAmount(),
        calculation.overdueAmount(),calculation.overdueShare(),
        calculation.overdueShareState(),resultSha,calculation.sourceRowCount(),
        inserted==0,true,false,false);
  }
  private static boolean same(BigDecimal a, BigDecimal b) {
    return a==null ? b==null : b!=null && a.compareTo(b)==0;
  }
  private static String sha(String source) {
    try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(source.getBytes(StandardCharsets.UTF_8)));}
    catch (Exception ex) {throw new IllegalStateException("ASOF_SHA256_UNAVAILABLE",ex);}
  }
}
