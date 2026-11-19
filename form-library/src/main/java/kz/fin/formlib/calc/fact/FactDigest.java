package kz.fin.formlib.calc.fact;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** Versioned, typed, length-delimited binary SHA-256 protocol. NOT a signature. */
public final class FactDigest {
  private static final Pattern SHA = Pattern.compile("[0-9a-f]{64}");
  private FactDigest() {}

  public static String sha(String domain, String... parts) {
    if (domain == null || !domain.matches("FIN-FACT-[A-Z0-9-]+-V1")) {
      throw new IllegalArgumentException("FACT_DIGEST_DOMAIN_INVALID");
    }
    try {
      var bytes = new ByteArrayOutputStream();
      var out = new DataOutputStream(bytes);
      write(out, domain);
      out.writeInt(parts.length);
      for (String part : parts) write(out, part);
      out.flush();
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    } catch (IOException | NoSuchAlgorithmException e) {
      throw new IllegalStateException("FACT_SHA256_UNAVAILABLE", e);
    }
  }

  private static void write(DataOutputStream out, String value) throws IOException {
    if (value == null) throw new IllegalArgumentException("FACT_DIGEST_NULL_PART");
    byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
    out.writeInt(encoded.length);
    out.write(encoded);
  }

  public static String requireSha(String value, String code) {
    if (value == null || !SHA.matcher(value).matches()) {
      throw new IllegalArgumentException(code);
    }
    return value;
  }

  public static String required(String value, String code) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(code);
    return value;
  }
}
