package kz.fin.backend.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.backend.analytics.AnalysisContext;
import kz.fin.backend.domain.EconomicKernel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.YearMonth;
import java.util.*;

/**
 * FIN P0-A: manual financial-source intake with immutable four-eyes approval.
 *
 * Identity/permissions are mandatory at the API boundary; arguments here are
 * trusted principals, not user-editable JSON. This creates ACTUAL source facts,
 * NOT qualified ACTUAL diagnoses. Raw XLSX remains a separate gate.
 */
@Service
public class ReviewedFinancialIntakeService {
    private final JdbcTemplate jdbc;
    private final TrustedFinancialFormMapper mapper;
    private final FactSetPublicationService publisher;
    private final ObjectMapper json;

    public ReviewedFinancialIntakeService(JdbcTemplate jdbc,TrustedFinancialFormMapper mapper,
                                          FactSetPublicationService publisher,ObjectMapper json) {
        this.jdbc=jdbc;this.mapper=mapper;this.publisher=publisher;this.json=json;
    }
    public record IntakeRequest(UUID organizationId,UUID economicEntityId,UUID businessScopeId,
                                String formCode,String reportingBasis,String currencyCode,int amountScale,
                                List<TrustedFinancialFormMapper.Cell> cells) {}
    public record Submitted(UUID id,String fingerprint,String sourceDocumentHash,int cellCount,
                            String state) {}
    public record Reviewed(UUID sourceRevisionId,String decision,String fingerprint,int mappedFacts) {}
    public record PublishRequest(UUID organizationId,UUID economicEntityId,UUID businessScopeId,
                                 EconomicKernel.ReportingBasis reportingBasis,
                                 List<UUID> approvedSourceIds,UUID expectedCurrentRevisionId) {}
    private record Submission(UUID id,UUID organizationId,UUID entityId,UUID scopeId,
                              String form,String basis,String currency,int scale,String submitter,
                              String fingerprint,String sourceDigest,String cellsJson,int count) {}
    public record SubmissionInfo(UUID id,String formCode,String formVersion,String reportingBasis,
                                 String currencyCode,int amountScale,String submittedBy,
                                 String reviewStatus,String reviewBy,String reviewReason,
                                 String sourceKind,String sourceDocumentHash,
                                 List<TrustedFinancialFormMapper.Cell> cells) {
        public SubmissionInfo { cells=List.copyOf(cells); }
    }

    @Transactional(readOnly=true)
    public SubmissionInfo read(UUID organizationId,UUID submissionId){
        Objects.requireNonNull(organizationId);Objects.requireNonNull(submissionId);
        List<SubmissionInfo> info=jdbc.query("""
            SELECT s.id,s.form_code,s.form_version,s.reporting_basis,
                   s.currency_code,s.amount_scale,s.submitted_by,
                   coalesce(r.decision,'PENDING'),r.reviewed_by,r.reason,s.intake_kind,
                   s.source_document_sha,s.cells::text
            FROM fin_intake.submission s
            LEFT JOIN fin_intake.review r
              ON r.organization_id=s.organization_id AND r.submission_id=s.id
            WHERE s.organization_id=? AND s.id=?
            """,(rs,n)->new SubmissionInfo(rs.getObject(1,UUID.class),rs.getString(2),
                rs.getString(3),rs.getString(4),rs.getString(5),rs.getInt(6),
                rs.getString(7),rs.getString(8),rs.getString(9),rs.getString(10),
                rs.getString(11),rs.getString(12),deserialize(rs.getString(13))),
                organizationId,submissionId);
        if(info.size()!=1)throw new IllegalArgumentException("INTAKE_NOT_FOUND_OR_FORBIDDEN");
        return info.getFirst();
    }

    private static final Set<String> FORMS=Set.of("F01","F02","F03","DF-CSH-01","DF-CSH-02");
    private static final Map<String,Set<String>> REQUIRED=Map.of(
            "F01",Set.of("assets","liabilities","equity"),
            "F02",Set.of("netRevenue","cogs","operatingExpenses"),
            "F03",Set.of("operatingCash","investingCash","financingCash")
    );

    @Transactional
    public Submitted submitManual(String verifiedSubject,IntakeRequest request){
        checkSubject(verifiedSubject);
        Objects.requireNonNull(request);
        Objects.requireNonNull(request.organizationId());
        Objects.requireNonNull(request.economicEntityId());
        Objects.requireNonNull(request.businessScopeId());
        if(!FORMS.contains(request.formCode()))
            throw new IllegalArgumentException("INTAKE_UNSUPPORTED_PILOT_FORM");
        if(!Set.of("MANAGEMENT","SEPARATE","CONSOLIDATED").contains(request.reportingBasis())
                || request.currencyCode()==null || !request.currencyCode().matches("[A-Z]{3}")
                || request.amountScale()!=1 && request.amountScale()!=1000)
            throw new IllegalArgumentException("INTAKE_INVALID_REPORTING_COORDINATES");
        List<TrustedFinancialFormMapper.Cell> cells=List.copyOf(Objects.requireNonNull(request.cells()));
        if(cells.isEmpty() || cells.size()>512)
            throw new IllegalArgumentException("INTAKE_CELL_COUNT_OUT_OF_BOUNDS");
        validateForm(request.formCode(),cells);
        String fingerprint=TrustedFinancialFormMapper.fingerprint(cells);
        String cellsJson=serialize(cells);
        if(cellsJson.length()>128_000)throw new IllegalArgumentException("INTAKE_JSON_TOO_LARGE");
        // Manual entry has NO XLSX raw-file digest. This is the server-owned
        // digest of its versioned input manifest, clearly distinguishable from
        // a cryptographically authenticated bank/accounting statement.
        String documentHash=sha256("FIN-MANUAL-V1|"+request.organizationId()+"|"+
                request.economicEntityId()+"|"+request.businessScopeId()+"|"+
                request.formCode()+"|FIN-PILOT-V1|"+request.reportingBasis()+"|"+
                request.currencyCode()+"|"+request.amountScale()+"|"+fingerprint);
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO fin_intake.submission(
                id,organization_id,economic_entity_id,business_scope_id,form_code,
                form_version,reporting_basis,currency_code,amount_scale,intake_kind,
                source_document_sha,normalized_fingerprint,cells,expected_cell_count,submitted_by)
            VALUES (?,?,?,?,?,'FIN-PILOT-V1',?, ?,?,'MANUAL',?,?,?::jsonb,?,?)
            """,id,request.organizationId(),request.economicEntityId(),request.businessScopeId(),
                request.formCode(),request.reportingBasis(),request.currencyCode(),
                request.amountScale(),documentHash,fingerprint,cellsJson,cells.size(),verifiedSubject);
        return new Submitted(id,fingerprint,documentHash,cells.size(),"PENDING_INDEPENDENT_REVIEW");
    }

    private static void checkSubject(String subject) {
        if(subject==null || subject.isBlank() || subject.length()>200)
            throw new IllegalArgumentException("VERIFIED_ACTOR_REQUIRED");
    }
    private static void validateForm(String form,List<TrustedFinancialFormMapper.Cell> cells) {
        Map<YearMonth,List<TrustedFinancialFormMapper.Cell>> byMonth=new TreeMap<>();
        for(var c:cells)byMonth.computeIfAbsent(c.period(),ignore->new ArrayList<>()).add(c);
        if(byMonth.size()>24)throw new IllegalArgumentException("INTAKE_TOO_MANY_MONTHS");
        for(var entry:byMonth.entrySet()){
            var rows=entry.getValue();
            Set<String> fields=new HashSet<>();
            for(var cell:rows){
                if(!form.startsWith("DF-CSH-") && !"balance".equals(cell.sourceRowId())
                        && form.equals("F01"))
                    throw new IllegalArgumentException("INTAKE_F01_CANONICAL_ROW_REQUIRED");
                if(!form.startsWith("DF-CSH-") && form.equals("F02")
                        && !"pnl".equals(cell.sourceRowId()))
                    throw new IllegalArgumentException("INTAKE_F02_CANONICAL_ROW_REQUIRED");
                if(!form.startsWith("DF-CSH-") && form.equals("F03")
                        && !"cf".equals(cell.sourceRowId()))
                    throw new IllegalArgumentException("INTAKE_F03_CANONICAL_ROW_REQUIRED");
                if(!fields.add(cell.sourceField()) && !form.startsWith("DF-CSH-"))
                    throw new IllegalArgumentException("INTAKE_DUPLICATE_FORM_FIELD");
            }
            Set<String> mandatory=REQUIRED.get(form);
            if(mandatory!=null && !fields.containsAll(mandatory))
                throw new IllegalArgumentException("INTAKE_REQUIRED_FORM_FIELDS_MISSING");
            if(form.equals("F01")){
                Map<String,BigDecimal> amounts=new HashMap<>();
                rows.forEach(c->amounts.put(c.sourceField(),c.enteredAmount()));
                if(amounts.get("assets").subtract(amounts.get("liabilities"))
                        .subtract(amounts.get("equity")).signum()!=0)
                    throw new IllegalArgumentException("INTAKE_BALANCE_IDENTITY_FAILED");
            }
        }
    }
    private String serialize(List<TrustedFinancialFormMapper.Cell> cells) {
        try{return json.writeValueAsString(cells);}
        catch(JsonProcessingException e){throw new IllegalArgumentException("INTAKE_INVALID_CELLS",e);}
    }
    private static String sha256(String input) {
        try{
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        }catch(NoSuchAlgorithmException e){throw new IllegalStateException("SHA256_UNAVAILABLE",e);}
    }

    @Transactional
    public Reviewed review(String verifiedReviewer,UUID organizationId,UUID sourceRevision,
                           boolean approved,String reason){
        checkSubject(verifiedReviewer);
        Objects.requireNonNull(organizationId);
        Objects.requireNonNull(sourceRevision);
        if(reason==null || reason.isBlank() || reason.length()>1000)
            throw new IllegalArgumentException("INTAKE_REVIEW_REASON_REQUIRED");
        Submission s=sourceForUpdate(organizationId,sourceRevision);
        if(s.submitter().equals(verifiedReviewer))
            throw new IllegalArgumentException("INTAKE_SELF_REVIEW_FORBIDDEN");
        if(jdbc.queryForObject("""
            SELECT count(*) FROM fin_intake.review
            WHERE organization_id=? AND submission_id=?
            """,Integer.class,organizationId,sourceRevision)!=0)
            throw new IllegalArgumentException("INTAKE_ALREADY_REVIEWED");
        List<TrustedFinancialFormMapper.Cell> cells=deserialize(s.cellsJson());
        if(cells.size()!=s.count() || !TrustedFinancialFormMapper.fingerprint(cells).equals(s.fingerprint()))
            throw new IllegalArgumentException("INTAKE_STORED_FINGERPRINT_MISMATCH");
        jdbc.update("""
            INSERT INTO fin_intake.review(submission_id,organization_id,decision,reviewed_by,reason)
            VALUES (?,?,?,?,?)
            """,sourceRevision,organizationId,approved?"APPROVED":"REJECTED",verifiedReviewer,reason);
        if(!approved)return new Reviewed(sourceRevision,"REJECTED",s.fingerprint(),0);
        jdbc.update("""
            INSERT INTO fin_fact.approved_source_revision(
                organization_id,source_revision_id,form_code,form_version,approved_at)
            VALUES (?,?,?,'FIN-PILOT-V1',now())
            """,organizationId,sourceRevision,s.form());
        jdbc.update("""
            INSERT INTO fin_fact.approved_source_intake(
                organization_id,source_revision_id,normalized_cell_fingerprint,source_document_hash,
                input_currency,amount_scale,expected_cell_count,reviewed_by,reviewed_at)
            VALUES (?,?,?,?,?,?,?,?,now())
            """,organizationId,sourceRevision,s.fingerprint(),s.sourceDigest(),
                s.currency(),s.scale(),s.count(),verifiedReviewer);
        var facts=mapper.mapReviewedSource(organizationId,sourceRevision,s.entityId(),s.scopeId(),
                s.basis(),cells);
        if(facts.size()!=s.count())throw new IllegalStateException("INTAKE_MAPPED_COUNT_MISMATCH");
        return new Reviewed(sourceRevision,"APPROVED",s.fingerprint(),facts.size());
    }

    private Submission sourceForUpdate(UUID org,UUID source) {
        List<Submission> list=jdbc.query("""
            SELECT id,organization_id,economic_entity_id,business_scope_id,
                   form_code,reporting_basis,currency_code,amount_scale,
                   submitted_by,normalized_fingerprint,source_document_sha,
                   cells::text,expected_cell_count
            FROM fin_intake.submission
            WHERE organization_id=? AND id=? FOR UPDATE
            """,(rs,n)->new Submission(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                rs.getObject(3,UUID.class),rs.getObject(4,UUID.class),rs.getString(5),
                rs.getString(6),rs.getString(7),rs.getInt(8),rs.getString(9),
                rs.getString(10),rs.getString(11),rs.getString(12),rs.getInt(13)),org,source);
        if(list.size()!=1)throw new IllegalArgumentException("INTAKE_NOT_FOUND_OR_FORBIDDEN");
        return list.getFirst();
    }
    private List<TrustedFinancialFormMapper.Cell> deserialize(String content) {
        try {return json.readValue(content,new TypeReference<List<TrustedFinancialFormMapper.Cell>>(){});}
        catch(JsonProcessingException e){throw new IllegalArgumentException("INTAKE_CORRUPT_SOURCE_MANIFEST",e);}
    }

    @Transactional
    public FactSetPublicationService.Publication publishReviewedActual(PublishRequest request) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(request.organizationId());
        Objects.requireNonNull(request.economicEntityId());
        Objects.requireNonNull(request.businessScopeId());
        Objects.requireNonNull(request.reportingBasis());
        List<UUID> sources=List.copyOf(Objects.requireNonNull(request.approvedSourceIds()));
        if(sources.isEmpty()||sources.size()>50||new HashSet<>(sources).size()!=sources.size())
            throw new IllegalArgumentException("INTAKE_INVALID_SOURCE_SET");
        Set<String> currencies=new HashSet<>();
        List<UUID> allFactIds=new ArrayList<>();
        for(UUID id:sources) {
            Submission s=sourceForUpdate(request.organizationId(),id);
            if(!s.entityId().equals(request.economicEntityId()) ||
                    !s.scopeId().equals(request.businessScopeId()) ||
                    !s.basis().equals(request.reportingBasis().name()))
                throw new IllegalArgumentException("INTAKE_FACTSET_SCOPE_MISMATCH");
            currencies.add(s.currency());
            List<String> decisions=jdbc.query("""
                SELECT decision FROM fin_intake.review WHERE submission_id=? AND organization_id=?
                """,(rs,n)->rs.getString(1),id,request.organizationId());
            if(decisions.size()!=1 || !decisions.getFirst().equals("APPROVED"))
                throw new IllegalArgumentException("INTAKE_SOURCE_NOT_INDEPENDENTLY_APPROVED");
            Integer controls=jdbc.queryForObject("""
                SELECT count(*) FROM fin_fact.source_population_control
                WHERE organization_id=? AND source_revision_id=?
                """,Integer.class,request.organizationId(),id);
            if(controls==null || controls==0)
                throw new IllegalArgumentException("INTAKE_SOURCE_NOT_MAPPED");
            List<UUID> ids=jdbc.query("""
                SELECT a.id FROM fin_fact.fact_assertion a
                JOIN fin_fact.fact_origin o
                  ON o.fact_assertion_id=a.id AND o.organization_id=a.organization_id
                WHERE a.organization_id=? AND a.source_revision_id=?
                ORDER BY a.id
                """,(rs,n)->rs.getObject(1,UUID.class),request.organizationId(),id);
            if(ids.size()!=s.count())
                throw new IllegalArgumentException("INTAKE_FACT_POPULATION_MISMATCH");
            allFactIds.addAll(ids);
        }
        if(currencies.size()!=1)throw new IllegalArgumentException("INTAKE_MULTI_CURRENCY_FACTSET");
        UUID revision=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO fin_fact.fact_set_revision(id,organization_id,parent_revision_id,status,approved_at)
            VALUES (?,?,?,'APPROVED',now())
            """,revision,request.organizationId(),request.expectedCurrentRevisionId());
        Set<String> factKeys=new HashSet<>();
        for(UUID fact:allFactIds){
            String identity=jdbc.queryForObject("SELECT fin_fact.fact_identity_hash(?)",
                    String.class,fact);
            if(identity==null || !factKeys.add(identity))
                throw new IllegalArgumentException("INTAKE_CONFLICTING_FACT_IDENTITIES");
            jdbc.update("""
                INSERT INTO fin_fact.fact_selection(
                    organization_id,fact_set_revision_id,fact_key_hash,fact_assertion_id)
                VALUES (?,?,?,?)
                """,request.organizationId(),revision,identity,fact);
        }
        return publisher.publish(new FactSetPublicationService.Request(
                request.organizationId(),request.economicEntityId(),request.businessScopeId(),
                request.reportingBasis(),AnalysisContext.DataSpace.ACTUAL,revision,
                request.expectedCurrentRevisionId()));
    }
}
