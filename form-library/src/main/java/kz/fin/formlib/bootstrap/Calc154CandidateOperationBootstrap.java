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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * CALC-154 post-catalog candidate operation seeding.
 *
 * The PostgreSQL Liquibase migration creates ONLY the schema. Catalog rows,
 * including lib_form_definition, form fields and metric definitions, are
 * populated by ApplicationRunner later. This runner has order 6, after
 * CatalogBootstrap (1) and FieldMetricContractBootstrap (5).
 *
 * Only five UNQUALIFIED proposals are persisted; no method_revision,
 * CalcPlan, job, fact or result can be produced by this class.
 */
@Component
@Order(6)
public class Calc154CandidateOperationBootstrap implements ApplicationRunner {
  private static final String RESOURCE = "catalog/calc154-operation-candidates-r0.tsv";
  private final JdbcTemplate jdbc;

  public Calc154CandidateOperationBootstrap(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc);
  }

  private record Proposal(String form, String operation, String output,
                          String numerator, String denominator) {}

  private List<Proposal> readCandidates() throws Exception {
    var entries = new ArrayList<Proposal>();
    var identities = new HashSet<String>();
    try (var reader = new BufferedReader(new InputStreamReader(
        new ClassPathResource(RESOURCE).getInputStream(), StandardCharsets.UTF_8))) {
      String header = reader.readLine();
      if (!"form_code\toperation_code\toutput_code\tnumerator_metric_code\tdenominator_metric_code"
          .equals(header))
        throw new IllegalStateException("CALC154_CANDIDATE_HEADER_DRIFT");
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank()) continue;
        String[] a = line.split("\t", -1);
        if (a.length != 5) throw new IllegalStateException("CALC154_CANDIDATE_COLUMNS");
        String denominator = a[4].isBlank() ? null : a[4];
        Proposal p = new Proposal(a[0], a[1], a[2], a[3], denominator);
        if (!identities.add(p.form() + "|" + p.operation() + "|" + p.output()))
          throw new IllegalStateException("CALC154_DUPLICATE_CANDIDATE");
        if (!Set.of("GROUP_BY", "RATIO_OF_SUMS").contains(p.operation())
            || (p.operation().equals("GROUP_BY") != (p.denominator() == null))
            || p.numerator().equals(p.denominator()))
          throw new IllegalStateException("CALC154_CANDIDATE_OPERATION_INVALID");
        entries.add(p);
      }
    }
    if (entries.size() != 5) throw new IllegalStateException("CALC154_CANDIDATES_NOT_FIVE");
    return List.copyOf(entries);
  }

  private void requirePhysicalMetric(String form, String metric) {
    Integer count = jdbc.queryForObject("""
      SELECT count(*) FROM fin_lib.lib_metric_definition m
      JOIN fin_lib.lib_form_definition f
        ON f.form_code=m.form_code AND f.form_code=?
      JOIN fin_lib.lib_form_version v ON v.id=f.current_version_id
      JOIN fin_lib.lib_form_field ff
        ON ff.form_version_id=v.id AND ff.field_code=m.source_field_code
      JOIN fin_lib.lib_field_binding_contract c
        ON c.form_code=f.form_code AND c.field_code=ff.field_code
           AND c.metric_code=m.metric_code
           AND c.review_state='CANDIDATE_UNQUALIFIED'
      WHERE m.metric_code=? AND m.metric_kind='SOURCE_FIELD'
        AND ff.field_type IN ('MONEY','DECIMAL','INTEGER')
      """, Integer.class, form, metric);
    if (count == null || count != 1)
      throw new IllegalStateException("CALC154_CANDIDATE_SOURCE_NOT_PHYSICAL:" + metric);
  }

  @Override
  @Transactional
  public void run(ApplicationArguments ignored) throws Exception {
    String database = jdbc.execute((ConnectionCallback<String>) c ->
        c.getMetaData().getDatabaseProductName());
    // H2 tests do not apply PostgreSQL-only migration 034 and must not fake it.
    if (!"PostgreSQL".equals(database)) return;

    List<Proposal> proposals = readCandidates();
    for (var p : proposals) {
      requirePhysicalMetric(p.form(), p.numerator());
      if (p.denominator() != null) requirePhysicalMetric(p.form(), p.denominator());
    }
    Integer existing = jdbc.queryForObject(
        "SELECT count(*) FROM fin_calc.candidate_metric_operation_binding", Integer.class);
    if (existing == null || (existing != 0 && existing != proposals.size()))
      throw new IllegalStateException("CALC154_CANDIDATE_PARTIAL_SEED");

    if (existing == 0) {
      // Startup bootstrap, not a job or financial calculation. Transactional:
      // validation and all inserts roll back together on any failure.
      for (var p : proposals) {
        jdbc.update("""
          INSERT INTO fin_calc.candidate_metric_operation_binding
            (form_code,operation_code,output_code,numerator_metric_code,
             denominator_metric_code,review_state)
          VALUES (?,?,?,?,?,'CANDIDATE_UNQUALIFIED')
          """, p.form(), p.operation(), p.output(), p.numerator(), p.denominator());
      }
    }

    // Idempotent restarts fail on any manual drift instead of overwriting it.
    for (var p : proposals) {
      var records = jdbc.query("""
          SELECT numerator_metric_code,denominator_metric_code,review_state
          FROM fin_calc.candidate_metric_operation_binding
          WHERE form_code=? AND operation_code=? AND output_code=?
          """, (r, i) -> new String[]{
            r.getString(1), r.getString(2), r.getString(3)
          }, p.form(), p.operation(), p.output());
      if (records.size() != 1 || !p.numerator().equals(records.getFirst()[0])
          || !Objects.equals(p.denominator(), records.getFirst()[1])
          || !"CANDIDATE_UNQUALIFIED".equals(records.getFirst()[2]))
        throw new IllegalStateException("CALC154_CANDIDATE_SEED_DRIFT");
    }
  }
}
