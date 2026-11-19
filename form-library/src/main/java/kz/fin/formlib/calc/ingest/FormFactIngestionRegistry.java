package kz.fin.formlib.calc.ingest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;

/**
 * Exact source-mode classification of the 166 R0 form profiles.
 * It is NOT a financial field-to-metric mapping and never grants qualification.
 */
public final class FormFactIngestionRegistry {
  public enum Mode { SINGLE_RECORD, ROWSET, EVIDENCE }
  public record Profile(String formCode, Mode mode, String reviewState) {}

  private final Map<String, Profile> profiles;
  private final String manifestSha256;

  private FormFactIngestionRegistry(Map<String, Profile> profiles, String sha) {
    this.profiles = Map.copyOf(profiles);
    this.manifestSha256 = sha;
  }

  public static FormFactIngestionRegistry loadDefault() {
    String name = "/catalog/fin-fact-ingestion-strategy-r0.tsv";
    try (var input = FormFactIngestionRegistry.class.getResourceAsStream(name)) {
      if (input == null) throw new IllegalStateException("INGEST_REGISTRY_MISSING");
      byte[] bytes = input.readAllBytes();
      String source = new String(bytes, StandardCharsets.UTF_8);
      var lines = source.split("\\R");
      if (lines.length < 2 || !lines[0].equals("form_code\tcontent_mode\treview_state")) {
        throw new IllegalStateException("INGEST_REGISTRY_HEADER_INVALID");
      }
      Map<String, Profile> parsed = new HashMap<>();
      for (int lineNo = 1; lineNo < lines.length; lineNo++) {
        if (lines[lineNo].isBlank()) continue;
        String[] c = lines[lineNo].split("\t", -1);
        if (c.length != 3 || !c[0].matches("[A-Z0-9-]{2,32}")) {
          throw new IllegalStateException("INGEST_REGISTRY_ROW_INVALID:" + (lineNo + 1));
        }
        Mode mode;
        try { mode = Mode.valueOf(c[1]); }
        catch (IllegalArgumentException ex) {
          throw new IllegalStateException("INGEST_REGISTRY_MODE_INVALID:" + c[0], ex);
        }
        if (!c[2].equals("UNREVIEWED") && !c[2].equals("TEST_ONLY_PILOT_PENDING")) {
          throw new IllegalStateException("INGEST_REGISTRY_REVIEW_STATE_INVALID:" + c[0]);
        }
        if (c[2].equals("TEST_ONLY_PILOT_PENDING") && !c[0].equals("F02")) {
          throw new IllegalStateException("INGEST_REGISTRY_PILOT_FORM_INVALID");
        }
        if (parsed.putIfAbsent(c[0], new Profile(c[0], mode, c[2])) != null) {
          throw new IllegalStateException("INGEST_REGISTRY_DUPLICATE:" + c[0]);
        }
      }
      if (parsed.size() != 166
          || parsed.values().stream().filter(v -> v.mode() == Mode.ROWSET).count() != 151
          || parsed.values().stream().filter(v -> v.mode() == Mode.SINGLE_RECORD).count() != 3
          || parsed.values().stream().filter(v -> v.mode() == Mode.EVIDENCE).count() != 12
          || parsed.get("F02") == null
          || !Objects.equals(parsed.get("F02").reviewState(), "TEST_ONLY_PILOT_PENDING")) {
        throw new IllegalStateException("INGEST_REGISTRY_BASELINE_MISMATCH");
      }
      return new FormFactIngestionRegistry(parsed, sha256(bytes));
    } catch (IOException ex) {
      throw new IllegalStateException("INGEST_REGISTRY_UNREADABLE", ex);
    }
  }

  public Profile require(String formCode, String persistedContentMode) {
    Profile profile = profiles.get(formCode);
    if (profile == null) throw new IllegalArgumentException("FORM_PROFILE_NOT_REGISTERED");
    if (!profile.mode().name().equals(persistedContentMode)) {
      throw new IllegalArgumentException("SOURCE_MODE_CONTRACT_MISMATCH");
    }
    return profile;
  }

  public int size() { return profiles.size(); }
  public Map<String, Profile> profiles() { return profiles; }
  public String manifestSha256() { return manifestSha256; }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
  }
}
