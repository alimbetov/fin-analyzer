package kz.fin.backend.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * CAT-A3 read-only server gate. The methodology catalog is the SERVER resource,
 * not the browser's "approved" field. No CAT-A2 method has independent S2
 * authorization yet, so the gate cannot issue a production execution permit.
 *
 * This is a boundary for new CAT-A3 adapters, NOT a retroactive qualification
 * of existing FIN-01/FIN-02 adapters or of the entire method family.
 */
@Service
public final class CatA3MethodFactAdmission {
    private static final String RESOURCE="/fin/methodology/methodology-catalog-r1.json";
    private static final Set<String> PILOT_FORMS=Set.of(
            "F01","F02","F03",
            "DF-CSH-01","DF-CSH-02","DF-CSH-03",
            "DF-CSH-04","DF-CSH-05","DF-CSH-06");
    public enum Status { METHOD_S2_HOLD, SOURCE_NOT_PUBLISHED_OR_CHANGED,
        SOURCE_SCOPE_MISMATCH, INPUT_INVALID, NOT_IN_PILOT, METHOD_UNKNOWN }
    public record Decision(Status status,String methodCode,String formCode,String factRevision) {
        public Decision { Objects.requireNonNull(status); }
        /** The pilot has no independent S2 approval and cannot grant production execution. */
        public boolean allowsActualExecution(){ return false; }
    }
    public record Publication(String snapshotHash,String dataSpace,String reportingBasis) {}
    private final JdbcTemplate jdbc;
    private final JsonNode methods;

    public CatA3MethodFactAdmission(JdbcTemplate jdbc,ObjectMapper mapper) {
        this.jdbc=Objects.requireNonNull(jdbc);
        Objects.requireNonNull(mapper);
        try(var source=getClass().getResourceAsStream(RESOURCE)){
            if(source==null)throw new IllegalStateException("CAT_A3_METHODOLOGY_CATALOG_MISSING");
            JsonNode root=mapper.readTree(source);
            if(!"HOLD".equals(root.path("releaseGate").asText()) ||
                    !root.path("methods").isArray() || root.path("methods").size()!=60)
                throw new IllegalStateException("CAT_A3_CATALOG_UNEXPECTED_RELEASE_STATE");
            for(JsonNode method:root.path("methods")){
                if(method.path("releaseQualified").asBoolean(true) ||
                        method.path("autoRunAllowed").asBoolean(true))
                    throw new IllegalStateException("CAT_A3_UNREVIEWED_METHOD_PROMOTION");
            }
            this.methods=root.path("methods");
        }catch(IOException ex){throw new IllegalStateException("CAT_A3_CATALOG_READ_FAILED",ex);}
    }

    /**
     * Strictly server-side. revision is supplied by the trusted outbox and
     * not from a client request; it does not by itself prove human review.
     */
    public Decision inspect(String methodCode,String formCode,MethodTaskAdapter.TrustedRevision revision){
        if(methodCode==null || formCode==null || revision==null)
            return new Decision(Status.INPUT_INVALID,methodCode,formCode,null);
        if(!PILOT_FORMS.contains(formCode))
            return new Decision(Status.NOT_IN_PILOT,methodCode,formCode,
                    Objects.toString(revision.factSetRevisionId(),null));
        String family=methodCode.contains("/")?methodCode.substring(0,methodCode.indexOf('/')):methodCode;
        boolean present=false;
        for(JsonNode method:methods){
            if(family.equals(method.path("code").asText())){present=true;break;}
        }
        if(!present)return new Decision(Status.METHOD_UNKNOWN,methodCode,formCode,
                Objects.toString(revision.factSetRevisionId(),null));
        if(revision.organizationId()==null || revision.economicEntityId()==null ||
                revision.businessScopeId()==null || revision.factSetRevisionId()==null ||
                revision.snapshotHash()==null || revision.snapshotHash().isBlank() ||
                (revision.dataSpace()==null || !Set.of("ACTUAL","SYNTHETIC").contains(revision.dataSpace())))
            return new Decision(Status.INPUT_INVALID,methodCode,formCode,null);
        List<Publication> publications=jdbc.query("""
                SELECT snapshot_hash,data_space,reporting_basis
                  FROM fin_fact.fact_set_publication
                 WHERE organization_id=? AND economic_entity_id=?
                   AND business_scope_id=? AND fact_set_revision_id=?
                """,(rs,i)->new Publication(rs.getString(1),rs.getString(2),rs.getString(3)),
                revision.organizationId(),revision.economicEntityId(),
                revision.businessScopeId(),revision.factSetRevisionId());
        if(publications.size()!=1)
            return new Decision(Status.SOURCE_NOT_PUBLISHED_OR_CHANGED,methodCode,
                    formCode,revision.factSetRevisionId().toString());
        Publication p=publications.getFirst();
        if(!Objects.equals(p.snapshotHash(),revision.snapshotHash()))
            return new Decision(Status.SOURCE_NOT_PUBLISHED_OR_CHANGED,methodCode,
                    formCode,revision.factSetRevisionId().toString());
        if(!Objects.equals(p.dataSpace(),revision.dataSpace()) ||
                !Set.of("MANAGEMENT","SEPARATE","CONSOLIDATED").contains(p.reportingBasis()))
            return new Decision(Status.SOURCE_SCOPE_MISMATCH,methodCode,
                    formCode,revision.factSetRevisionId().toString());
        // Published != complete source population. This is an intermediate
        // inspection only. A future allow verdict MUST also require reviewed
        // source-row population, signed method version, and trusted S2 decision.
        return new Decision(Status.METHOD_S2_HOLD,methodCode,formCode,
                revision.factSetRevisionId().toString());
    }
}
