package kz.fin.backend.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * Tenant-scoped raw JSON SKU-source intake and independent human review.
 * Authenticated actor and authorized tenant come only from the API boundary.
 *
 * Original bytes are stored immutably. Their SHA-256 proves integrity of the
 * submitted file, not its external invoice/ERP authenticity; that distinction
 * is presented explicitly to the reviewer.
 */
@Service
public final class ReviewedSkuIntakeService {
    private static final int MAX_BYTES=2_097_152;
    private static final int MAX_LINES=10_000;
    private static final Set<String> FILE_KEYS=Set.of("period","currency","events");
    private static final Set<String> EVENT_KEYS=Set.of(
            "sourceLineRef","skuCode","eventKind","originalSaleRef","saleDate","currency","grossAmount");
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public ReviewedSkuIntakeService(JdbcTemplate jdbc,ObjectMapper json){
        this.jdbc=jdbc;this.json=json;
    }

    public record Upload(UUID organizationId,UUID economicEntityId,UUID businessScopeId,
                         UUID factSetRevisionId,String sourceSystem,byte[] originalBytes) {}
    public record Receipt(UUID populationId,String documentHash,int lineCount,
                          String netSales,String period,String status) {}
    public record Review(UUID populationId,String decision,String populationFingerprint,
                         boolean supplementalAnalysisQueued) {}
    public record Info(UUID populationId,String state,String period,
                       String currency,String sourceSystem,String submittedBy,
                       String reviewer,String fingerprint,String sourceDocumentHash,
                       int sourceRows,String netSales) {}
    private record Snapshot(String basis,String space,String hash) {}
    private record Event(String ref,String sku,String kind,String original,
                         LocalDate date,String currency,BigDecimal amount) {}
    private record Document(YearMonth period,String currency,List<Event> events,
                            BigDecimal net) {}

    @Transactional
    public Receipt submit(String verifiedActor,Upload upload) {
        checkActor(verifiedActor);
        Objects.requireNonNull(upload,"upload");
        Objects.requireNonNull(upload.organizationId(),"organizationId");
        Objects.requireNonNull(upload.economicEntityId(),"economicEntityId");
        Objects.requireNonNull(upload.businessScopeId(),"businessScopeId");
        Objects.requireNonNull(upload.factSetRevisionId(),"factSetRevisionId");
        if(upload.sourceSystem()==null ||
                !upload.sourceSystem().matches("[A-Za-z0-9_-]{2,128}"))
            throw new IllegalArgumentException("SKU_SOURCE_SYSTEM_INVALID");
        byte[] original=upload.originalBytes();
        if(original==null || original.length<2 || original.length>MAX_BYTES)
            throw new IllegalArgumentException("SKU_SOURCE_FILE_SIZE_INVALID");
        var source=parse(original);
        var snapshots=jdbc.query("""
            SELECT reporting_basis,data_space,snapshot_hash
            FROM fin_fact.fact_set_publication
            WHERE organization_id=? AND economic_entity_id=?
              AND business_scope_id=? AND fact_set_revision_id=?
            """,(rs,i)->new Snapshot(rs.getString(1),rs.getString(2),rs.getString(3)),
                upload.organizationId(),upload.economicEntityId(),
                upload.businessScopeId(),upload.factSetRevisionId());
        if(snapshots.size()!=1 || !"ACTUAL".equals(snapshots.getFirst().space()))
            throw new IllegalArgumentException("SKU_TRUSTED_ACTUAL_REVISION_REQUIRED");
        var pub=snapshots.getFirst();
        String digest=sha256(original);
        UUID populationId=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO fin_con.sku_population_submission(
              id,organization_id,economic_entity_id,business_scope_id,
              fact_set_revision_id,published_snapshot_hash,reporting_basis,
              data_space,period_start,period_end,currency,source_document_hash,
              source_system,submitted_by,expected_line_count,expected_net_total)
            VALUES (?,?,?,?,?,?,?,'ACTUAL',?,?,?,?,?,?,?,?)
            """,populationId,upload.organizationId(),upload.economicEntityId(),
                upload.businessScopeId(),upload.factSetRevisionId(),pub.hash(),
                pub.basis(),source.period().atDay(1),source.period().plusMonths(1).atDay(1),
                source.currency(),digest,upload.sourceSystem(),verifiedActor,
                source.events().size(),source.net());
        jdbc.update("""
            INSERT INTO fin_con.sku_source_original(
              population_id,organization_id,original_bytes,sha256,original_length)
            VALUES (?,?,?,?,?)
            """,populationId,upload.organizationId(),original,digest,original.length);
        for(var event:source.events()) {
            jdbc.update("""
                INSERT INTO fin_con.sku_sales_line(
                  population_id,organization_id,source_line_ref,sku_code,event_kind,
                  original_sale_ref,sale_date,currency,gross_amount,source_document_hash)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """,populationId,upload.organizationId(),event.ref(),event.sku(),
                event.kind(),event.original(),event.date(),event.currency(),
                event.amount(),digest);
        }
        return new Receipt(populationId,digest,source.events().size(),
                source.net().toPlainString(),source.period().toString(),"PENDING");
    }

    @Transactional
    public Review review(String verifiedActor,UUID organizationId,UUID populationId,
                         boolean approved,String reason) {
        checkActor(verifiedActor);
        Objects.requireNonNull(organizationId);Objects.requireNonNull(populationId);
        if(reason==null || reason.isBlank() || reason.length()>1000)
            throw new IllegalArgumentException("SKU_REVIEW_REASON_REQUIRED");
        var rows=jdbc.query("""
            SELECT submitted_by FROM fin_con.sku_population_submission
            WHERE organization_id=? AND id=? FOR UPDATE
            """,(rs,i)->rs.getString(1),organizationId,populationId);
        if(rows.size()!=1)
            throw new IllegalArgumentException("SKU_POPULATION_NOT_FOUND_OR_FORBIDDEN");
        if(verifiedActor.equals(rows.getFirst()))
            throw new IllegalArgumentException("SKU_SELF_REVIEW_FORBIDDEN");
        jdbc.update("""
            INSERT INTO fin_con.sku_population_review(
              population_id,organization_id,decision,reviewed_by,review_reason)
            VALUES (?,?,?,?,?)
            """,populationId,organizationId,approved?"APPROVED":"REJECTED",
                verifiedActor,reason);
        var proof=jdbc.queryForObject("""
            SELECT population_fingerprint
            FROM fin_con.sku_population_review
            WHERE population_id=? AND organization_id=?
            """,String.class,populationId,organizationId);
        long supplemental=jdbc.queryForObject("""
            SELECT count(*) FROM fin_exec.fact_publication_outbox event
            JOIN fin_con.sku_population_submission source
              ON source.organization_id=event.organization_id
             AND source.fact_set_revision_id=event.fact_set_revision_id
            WHERE source.id=? AND source.organization_id=?
              AND event.event_type='SKU_POPULATION_APPROVED'
            """,Long.class,populationId,organizationId);
        return new Review(populationId,approved?"APPROVED":"REJECTED",
                proof,supplemental==1L);
    }

    @Transactional(readOnly=true)
    public Info read(UUID organizationId,UUID populationId) {
        Objects.requireNonNull(organizationId);Objects.requireNonNull(populationId);
        var rows=jdbc.query("""
            SELECT p.period_start,p.currency,p.source_system,p.submitted_by,
                   p.source_document_hash,p.expected_line_count,p.expected_net_total,
                   review.decision,review.reviewed_by,review.population_fingerprint
            FROM fin_con.sku_population_submission p
            LEFT JOIN fin_con.sku_population_review review
              ON review.population_id=p.id
             AND review.organization_id=p.organization_id
            WHERE p.organization_id=? AND p.id=?
            """,(rs,i)->new Info(populationId,
                Optional.ofNullable(rs.getString(8)).orElse("PENDING"),
                YearMonth.from(rs.getObject(1,LocalDate.class)).toString(),
                rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(9),
                rs.getString(10),rs.getString(5),rs.getInt(6),
                rs.getBigDecimal(7).toPlainString()),organizationId,populationId);
        if(rows.size()!=1)throw new IllegalArgumentException("SKU_POPULATION_NOT_FOUND_OR_FORBIDDEN");
        return rows.getFirst();
    }

    @Transactional(readOnly=true)
    public byte[] readOriginal(UUID organizationId,UUID populationId) {
        Objects.requireNonNull(organizationId);Objects.requireNonNull(populationId);
        var originals=jdbc.query("""
            SELECT original_bytes FROM fin_con.sku_source_original
            WHERE organization_id=? AND population_id=?
            """,(rs,i)->rs.getBytes(1),organizationId,populationId);
        if(originals.size()!=1)
            throw new IllegalArgumentException("SKU_ORIGINAL_NOT_FOUND_OR_FORBIDDEN");
        return originals.getFirst();
    }

    private Document parse(byte[] raw) {
        final JsonNode root;
        try { root=json.readTree(raw); }
        catch(Exception e){throw new IllegalArgumentException("SKU_INVALID_JSON_DOCUMENT",e);}
        if(root==null || !root.isObject() || !keys(root,FILE_KEYS))
            throw new IllegalArgumentException("SKU_INVALID_DOCUMENT_SHAPE");
        final YearMonth period;
        try { period=YearMonth.parse(requiredText(root,"period")); }
        catch(Exception e){throw new IllegalArgumentException("SKU_PERIOD_INVALID",e);}
        String currency=requiredText(root,"currency");
        if(!currency.matches("[A-Z]{3}"))
            throw new IllegalArgumentException("SKU_CURRENCY_INVALID");
        JsonNode entries=root.path("events");
        if(!entries.isArray() || entries.size()<2 || entries.size()>MAX_LINES)
            throw new IllegalArgumentException("SKU_EVENT_COUNT_INVALID");
        var events=new ArrayList<Event>();
        var uniqueRefs=new HashSet<String>();
        BigDecimal net=BigDecimal.ZERO;
        for(JsonNode entry:entries){
            if(!entry.isObject() || !keys(entry,EVENT_KEYS))
                throw new IllegalArgumentException("SKU_EVENT_SHAPE_INVALID");
            String ref=requiredText(entry,"sourceLineRef");
            String sku=requiredText(entry,"skuCode");
            String kind=requiredText(entry,"eventKind");
            String eventCurrency=requiredText(entry,"currency");
            String original=entry.hasNonNull("originalSaleRef")
                    ? requiredText(entry,"originalSaleRef"):null;
            if(!ref.matches("[A-Za-z0-9_-]{1,128}") ||
                    !sku.matches("[A-Za-z0-9_-]{1,128}") ||
                    !uniqueRefs.add(ref) || !Set.of("SALE","RETURN","CANCELLATION").contains(kind) ||
                    !currency.equals(eventCurrency) ||
                    (kind.equals("SALE") ? original!=null :
                            original==null || !original.matches("[A-Za-z0-9_-]{1,128}")))
                throw new IllegalArgumentException("SKU_EVENT_ID_CURRENCY_OR_KIND_INVALID");
            final LocalDate date;
            final BigDecimal amount;
            try {
                date=LocalDate.parse(requiredText(entry,"saleDate"));
                String rawAmount=requiredText(entry,"grossAmount");
                if(!rawAmount.matches("(?:0|[1-9][0-9]*)(?:\\.[0-9]{1,12})?"))
                    throw new IllegalArgumentException("SKU_AMOUNT_FORMAT_INVALID");
                amount=new BigDecimal(rawAmount);
            }catch(Exception e){
                throw new IllegalArgumentException("SKU_EVENT_DATE_OR_AMOUNT_INVALID",e);
            }
            if(!YearMonth.from(date).equals(period) ||
                    amount.signum()<=0 || amount.precision()>38)
                throw new IllegalArgumentException("SKU_EVENT_MONTH_OR_AMOUNT_INVALID");
            events.add(new Event(ref,sku,kind,original,date,currency,amount));
            net=net.add(kind.equals("SALE")?amount:amount.negate());
        }
        if(net.signum()<=0)
            throw new IllegalArgumentException("SKU_NET_REVENUE_NON_POSITIVE");
        return new Document(period,currency,List.copyOf(events),net);
    }

    private static boolean keys(JsonNode node,Set<String> allowed){
        var names=node.fieldNames();
        while(names.hasNext())if(!allowed.contains(names.next()))return false;
        return true;
    }
    private static String requiredText(JsonNode node,String key) {
        JsonNode value=node.get(key);
        if(value==null || !value.isTextual() || value.textValue().isBlank())
            throw new IllegalArgumentException("SKU_REQUIRED_TEXT_"+key);
        return value.textValue();
    }
    private static void checkActor(String actor) {
        if(actor==null || actor.isBlank() || actor.length()>200)
            throw new IllegalArgumentException("SKU_VERIFIED_ACTOR_REQUIRED");
    }
    private static String sha256(byte[] content){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException("SHA256_UNAVAILABLE",e);}
    }
}
