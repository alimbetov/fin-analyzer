package kz.fin.formlib.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FormContentFingerprintTest {
  private final ObjectMapper mapper=new ObjectMapper();
  private final FormContentFingerprint fingerprint=new FormContentFingerprint(mapper);
  private final UUID org=UUID.fromString("00000000-0000-0000-0000-000000000001");
  private final UUID entity=UUID.fromString("00000000-0000-0000-0000-000000000002");
  private final UUID scope=UUID.fromString("00000000-0000-0000-0000-000000000003");
  private final UUID project=UUID.fromString("00000000-0000-0000-0000-000000000004");
  private final FormContentFingerprint.Context ctx=new FormContentFingerprint.Context(
      org,entity,scope,project,"DF-CSH-01","CATALOG-V1","M","2026-11",
      "PERIOD_ONLY","KZT","MANAGEMENT","SYNTHETIC");

  private static String sha(String raw) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(raw.getBytes(StandardCharsets.UTF_8)));
  }

  @Test void byteCompatibleWithFormerInlineWriterAlgorithm() throws Exception {
    UUID a=UUID.fromString("00000000-0000-0000-0000-000000000010");
    UUID b=UUID.fromString("00000000-0000-0000-0000-000000000011");
    var rows=List.of(
        new FormContentFingerprint.Row(a,20,Map.of("amount","120.5","invoiceRef","A")),
        new FormContentFingerprint.Row(b,10,Map.of("amount","0","invoiceRef","B")));
    var values=Map.<String,String>of();
    var actual=fingerprint.compute(ctx,values,rows,null);

    // INDEPENDENT test reproduction of the previous FormDraftService inline
    // JSON shape: map keys ordered, rows sorted by UUID, row-order List.toString.
    String oldJson=mapper.writeValueAsString(List.of(org.toString(),entity.toString(),
        scope.toString(),project.toString(),"DF-CSH-01","CATALOG-V1",
        "M","2026-11","PERIOD_ONLY","KZT","MANAGEMENT","SYNTHETIC",
        new TreeMap<>(values),
        rows.stream().sorted(Comparator.comparing(FormContentFingerprint.Row::rowId))
            .map(r->List.of(r.rowId().toString(),new TreeMap<>(r.values()))).toList()));
    String legacyContent=sha("FIN-LIB-CONTENT-V1|"+oldJson);
    String orderList=rows.stream().sorted(Comparator.comparingInt(
        FormContentFingerprint.Row::rowPosition).thenComparing(FormContentFingerprint.Row::rowId))
        .map(r->r.rowPosition()+":"+r.rowId()).toList().toString();
    assertEquals(legacyContent,actual.contentSha256());
    assertEquals(sha("FIN-LIB-ROW-ORDER-V1|"+orderList),actual.rowOrderSha256());
  }

  @Test void pureRowReorderChangesOnlyOrderSha() {
    UUID a=UUID.randomUUID(), b=UUID.randomUUID();
    var first=List.of(new FormContentFingerprint.Row(a,1,Map.of("amount","100")),
        new FormContentFingerprint.Row(b,2,Map.of("amount","200")));
    var second=List.of(new FormContentFingerprint.Row(a,2,Map.of("amount","100")),
        new FormContentFingerprint.Row(b,1,Map.of("amount","200")));
    var one=fingerprint.compute(ctx,Map.of(),first,null);
    var two=fingerprint.compute(ctx,Map.of(),second,null);
    assertEquals(one.contentSha256(),two.contentSha256());
    assertNotEquals(one.rowOrderSha256(),two.rowOrderSha256());
  }

  @Test void differentFinancialScopeAndContentChangeContentSha() {
    var amount=Map.of("netRevenue","100");
    var a=fingerprint.compute(ctx,amount,List.of(),null);
    var changed=fingerprint.compute(ctx,Map.of("netRevenue","101"),List.of(),null);
    var otherOrg=new FormContentFingerprint.Context(UUID.randomUUID(),entity,scope,project,
        "DF-CSH-01","CATALOG-V1","M","2026-11","PERIOD_ONLY","KZT",
        "MANAGEMENT","SYNTHETIC");
    assertNotEquals(a.contentSha256(),changed.contentSha256());
    assertNotEquals(a.contentSha256(),fingerprint.compute(
        otherOrg,amount,List.of(),null).contentSha256());
    assertEquals(a.rowOrderSha256(),changed.rowOrderSha256());
  }

  @Test void attachmentNestedJsonFormatIsBackwardCompatible() throws Exception {
    var att=new FormContentFingerprint.Attachment("invoice.pdf","application/pdf","c".repeat(64));
    var actual=fingerprint.compute(ctx,Map.of("documentRef","D-12"),List.of(),att);
    String baseline=mapper.writeValueAsString(List.of(
        org.toString(),entity.toString(),scope.toString(),project.toString(),
        "DF-CSH-01","CATALOG-V1","M","2026-11",
        "PERIOD_ONLY","KZT","MANAGEMENT","SYNTHETIC",
        new TreeMap<>(Map.of("documentRef","D-12")),List.of()));
    String legacy=mapper.writeValueAsString(List.of(baseline,att.fileName(),
        att.mediaType(),att.sha256()));
    assertEquals(sha("FIN-LIB-CONTENT-V1|"+legacy),actual.contentSha256());
    assertNotEquals(actual.contentSha256(),fingerprint.compute(ctx,
        Map.of("documentRef","D-12"),List.of(),
        new FormContentFingerprint.Attachment("other.pdf",att.mediaType(),att.sha256())).contentSha256());
    assertNotEquals(actual.contentSha256(),fingerprint.compute(ctx,
        Map.of("documentRef","D-12"),List.of(),null).contentSha256());
  }
}
