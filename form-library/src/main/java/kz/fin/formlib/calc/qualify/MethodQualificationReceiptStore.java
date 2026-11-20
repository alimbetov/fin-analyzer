package kz.fin.formlib.calc.qualify;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * AUTOQUAL-1.A: internal-only append-only UAT receipt writer.
 * Does NOT accept caller-supplied approval state, and is NOT an HTTP endpoint.
 */
@Service
public class MethodQualificationReceiptStore {
  public record Receipt(UUID id, String requestSha256, String status,
      boolean replayed, boolean financialS2Approved, boolean productionAdmitted) {}
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final MethodQualificationEvaluator evaluator;
  public MethodQualificationReceiptStore(JdbcTemplate jdbc, ObjectMapper mapper,
      MethodQualificationEvaluator evaluator) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.mapper = Objects.requireNonNull(mapper);
    this.evaluator = Objects.requireNonNull(evaluator);
  }

  @Transactional
  public Receipt evaluateAndRecord(MethodQualificationEvaluator.Request request) throws Exception {
    Objects.requireNonNull(request, "request");
    var decision = evaluator.evaluate(request);
    // Canonicalization version is part of the digest domain.
    String serialized=mapper.writeValueAsString(request);
    var md=MessageDigest.getInstance("SHA-256");
    String digest=HexFormat.of().formatHex(md.digest(
        ("FIN-AUTOQUAL-REQUEST-V1|"+serialized).getBytes(StandardCharsets.UTF_8)));
    UUID id=UUID.randomUUID();
    int added=jdbc.update("""
      INSERT INTO fin_calc.method_qualification_receipt(
        id,form_code,output_metric_code,qualification_class,
        qualification_status,formula_sha256,artifact_sha256,factset_sha256,
        request_sha256,policy_sha256,test_evidence_sha256,standard_reference,
        standard_edition,blockers_jsonb,financial_s2_approved,production_admitted)
      VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,false,false)
      ON CONFLICT(request_sha256) DO NOTHING
      """,id,request.formCode(),request.outputCode(),
      request.classification()==null ? "E" : request.classification().name(),
      decision.status().name(),safeSha(request.formulaSha256()),
      safeSha(request.artifactSha256()),safeSha(request.factSetSha256()),
      digest,request.policySha256(),request.testEvidenceSha256(),
      request.standardReference(),request.standardEdition(),
      mapper.writeValueAsString(decision.blockers()));
    return jdbc.queryForObject("""
      SELECT id,request_sha256,qualification_status,financial_s2_approved,production_admitted
      FROM fin_calc.method_qualification_receipt WHERE request_sha256=?
      """,(rs,n)->new Receipt(rs.getObject(1,UUID.class),rs.getString(2),
        rs.getString(3),added==0,rs.getBoolean(4),rs.getBoolean(5)),digest);
  }

  private static String safeSha(String sha) {
    // Required SHA columns are strict. Invalid evidence never persists as valid evidence.
    if (sha==null || !sha.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("AUTOQUAL_RECEIPT_REQUIRED_SHA_INVALID");
    return sha;
  }
}
