package kz.fin.backend.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * R4.2 deterministic adapter for reviewed FIN-PILOT-V1 source cells.
 * No public API: source approval, raw-file parsing, reviewer identity and
 * OIDC/ABAC authorizations MUST originate from independent trusted services.
 *
 * Raw source fields are NOT statutory aliases. Records are selected and
 * published by FactSetPublicationService only after source reconciliation.
 */
@Service
public class TrustedFinancialFormMapper {
    private final JdbcTemplate jdbc;
    public TrustedFinancialFormMapper(JdbcTemplate jdbc){this.jdbc=jdbc;}

    public record Cell(String sourceRowId,String sourceField,YearMonth period,BigDecimal enteredAmount) {
        public Cell {
            if(sourceRowId==null||!sourceRowId.matches("[A-Za-z0-9_-]{1,80}")||
                    sourceField==null||!sourceField.matches("[A-Za-z][A-Za-z0-9_]{0,127}"))
                throw new IllegalArgumentException("INVALID_PILOT_SOURCE_COORDINATE");
            Objects.requireNonNull(period);
            Objects.requireNonNull(enteredAmount);
            if(enteredAmount.precision()>38||enteredAmount.scale()>12)
                throw new IllegalArgumentException("INVALID_PILOT_SOURCE_DECIMAL");
        }
    }
    private record Approved(String form,String version,String currency,int scale,int count,
                            String fingerprint,String documentHash) {}
    private record Mapping(String metric,String metricVersion,String timeKind) {}
    private record Group(String metric,LocalDate start,LocalDate end) {}
    public record Mapped(UUID factId,String metricCode,LocalDate start,LocalDate end,
                         BigDecimal canonicalAmount,String sourceRowId,String sourceField) {}

    /** Fingerprint is deterministic but NOT an electronic signature. */
    public static String fingerprint(List<Cell> cells){
        Objects.requireNonNull(cells);
        List<Cell> ordered=new ArrayList<>(cells);
        ordered.sort(Comparator.comparing(Cell::sourceRowId)
                .thenComparing(Cell::sourceField).thenComparing(Cell::period));
        try{
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            Set<String> coordinates=new HashSet<>();
            for(Cell cell:ordered){
                Objects.requireNonNull(cell);
                String key=cell.sourceRowId()+":"+cell.sourceField()+":"+cell.period();
                if(!coordinates.add(key))
                    throw new IllegalArgumentException("DUPLICATE_SOURCE_CELL");
                feed(digest,cell.sourceRowId());feed(digest,cell.sourceField());
                feed(digest,cell.period().toString());
                feed(digest,cell.enteredAmount().stripTrailingZeros().toPlainString());
            }
            return HexFormat.of().formatHex(digest.digest());
        }catch(NoSuchAlgorithmException e){throw new IllegalStateException("SHA256_UNAVAILABLE",e);}
    }
    private static void feed(MessageDigest digest,String value){
        byte[] bytes=value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
    }

    @Transactional
    public List<Mapped> mapReviewedSource(UUID authorizedOrg,UUID sourceRevision,
                                          UUID entityId,UUID businessScopeId,
                                          String reportingBasis,List<Cell> input) {
        Objects.requireNonNull(authorizedOrg);Objects.requireNonNull(sourceRevision);
        Objects.requireNonNull(entityId);Objects.requireNonNull(businessScopeId);
        if(!Set.of("MANAGEMENT","SEPARATE","CONSOLIDATED").contains(reportingBasis))
            throw new IllegalArgumentException("INVALID_PILOT_REPORTING_BASIS");
        List<Cell> cells=List.copyOf(Objects.requireNonNull(input));
        if(cells.isEmpty())throw new IllegalArgumentException("EMPTY_SOURCE_INPUT");
        String actualFingerprint=fingerprint(cells);
        List<Approved> approvals=jdbc.query("""
            SELECT sr.form_code,sr.form_version,i.input_currency,i.amount_scale,
                   i.expected_cell_count,i.normalized_cell_fingerprint,i.source_document_hash
            FROM fin_fact.approved_source_revision sr
            JOIN fin_fact.approved_source_intake i
              ON i.organization_id=sr.organization_id
             AND i.source_revision_id=sr.source_revision_id
            WHERE sr.organization_id=? AND sr.source_revision_id=?
            FOR UPDATE OF sr
            """,(rs,n)->new Approved(rs.getString(1),rs.getString(2),rs.getString(3),
                    rs.getInt(4),rs.getInt(5),rs.getString(6),rs.getString(7)),
                authorizedOrg,sourceRevision);
        if(approvals.size()!=1)
            throw new IllegalArgumentException("REVIEWED_PILOT_SOURCE_NOT_FOUND_OR_FORBIDDEN");
        Approved approved=approvals.getFirst();
        if(!("FIN-PILOT-V1".equals(approved.version()) ||
                ("F01".equals(approved.form()) &&
                 "FIN-LIQUIDITY-V1".equals(approved.version()))))
            throw new IllegalArgumentException("UNQUALIFIED_PILOT_FORM_VERSION");
        if(cells.size()!=approved.count() || !actualFingerprint.equals(approved.fingerprint()))
            throw new IllegalArgumentException("SOURCE_INTAKE_FINGERPRINT_OR_COUNT_MISMATCH");
        Map<String,Mapping> mappings=new HashMap<>();
        jdbc.query("""
            SELECT source_field,metric_code,metric_version,required_time_kind
            FROM fin_fact.pilot_field_mapping
            WHERE form_code=? AND form_version=?
            """,(rs,n)->Map.entry(rs.getString(1),
                    new Mapping(rs.getString(2),rs.getString(3),rs.getString(4))),
                approved.form(),approved.version())
                .forEach(entry->mappings.put(entry.getKey(),entry.getValue()));
        if(mappings.isEmpty())throw new IllegalArgumentException("UNKNOWN_PILOT_FORM");
        // Verify every field before writing. JDBC insert/lineage/control are one transaction.
        for(Cell cell:cells)
            if(!mappings.containsKey(cell.sourceField()))
                throw new IllegalArgumentException("SOURCE_FIELD_NOT_MAPPED");
        Map<Group,List<Mapped>> groups=new LinkedHashMap<>();
        for(Cell cell:cells){
            Mapping mapping=mappings.get(cell.sourceField());
            LocalDate start=mapping.timeKind().equals("AS_OF")
                    ?cell.period().atEndOfMonth():cell.period().atDay(1);
            LocalDate end=mapping.timeKind().equals("AS_OF")
                    ?start.plusDays(1):cell.period().plusMonths(1).atDay(1);
            BigDecimal amount=cell.enteredAmount().multiply(BigDecimal.valueOf(approved.scale()));
            if(amount.precision()>38 || amount.scale()>12)
                throw new IllegalArgumentException("SCALED_AMOUNT_OUT_OF_RANGE");
            boolean detail=approved.form().startsWith("DF-CSH-");
            String dimensions=detail?"{\"sourceRow\":\""+cell.sourceRowId()+"\"}":"{}";
            UUID factId=UUID.randomUUID();
            jdbc.update("""
                INSERT INTO fin_fact.fact_assertion(
                    id,organization_id,economic_entity_id,business_scope_id,metric_code,metric_version,
                    time_kind,start_inclusive,end_exclusive,reporting_basis,unit_code,currency_code,
                    dimension_hash,dimensions,value_state,numeric_value,estimate_method_ref,
                    source_revision_id,recorded_at
                ) VALUES (?,?,?,?,?,?,?,?,?,?,'MONEY',?,
                    encode(sha256(convert_to(?::jsonb::text,'UTF8')),'hex'),
                    ?::jsonb,?,?,NULL,?,now())
                """,factId,authorizedOrg,entityId,businessScopeId,mapping.metric(),mapping.metricVersion(),
                mapping.timeKind(),start,end,reportingBasis,approved.currency(),dimensions,dimensions,
                amount.signum()==0?"EXPLICIT_ZERO":"PRESENT",amount,sourceRevision);
            jdbc.update("""
                INSERT INTO fin_fact.fact_origin(
                    fact_assertion_id,organization_id,source_revision_id,source_row_id,source_field)
                VALUES (?,?,?,?,?)
                """,factId,authorizedOrg,sourceRevision,cell.sourceRowId(),cell.sourceField());
            groups.computeIfAbsent(new Group(mapping.metric(),start,end),unused->new ArrayList<>())
                    .add(new Mapped(factId,mapping.metric(),start,end,amount,
                            cell.sourceRowId(),cell.sourceField()));
        }
        for(var entry:groups.entrySet()){
            Group group=entry.getKey();
            BigDecimal total=entry.getValue().stream().map(Mapped::canonicalAmount)
                    .reduce(BigDecimal.ZERO,BigDecimal::add);
            jdbc.update("""
                INSERT INTO fin_fact.source_population_control(
                    organization_id,source_revision_id,metric_code,time_start,time_end,
                    expected_rows,expected_total,source_document_hash)
                VALUES (?,?,?,?,?,?,?,?)
                """,authorizedOrg,sourceRevision,group.metric(),group.start(),group.end(),
                entry.getValue().size(),total,approved.documentHash());
        }
        return groups.values().stream().flatMap(Collection::stream).toList();
    }
}
