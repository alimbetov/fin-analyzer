package kz.fin.backend.methodology;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Read-only backend choice catalog. Only frozen editorial editions are shown;
 * no selection, approval, activation, calculation or ACTUAL ExecutionPermit.
 * HTTP/role-aware write APIs are deliberately not introduced here.
 */
@Service
public class MethodVariantPresetReadService {
    public record Choice(int optionNo, String parametersJson,
                         String parametersSha256, String optionSha256) {}

    public record Edition(UUID editionId, String methodCode, String variantCode,
                          int editionNo, UUID contractId, String contractVersion,
                          String schemaSha256, String algorithmVersion,
                          String generationPolicyJson, String optionsSha256,
                          List<Choice> choices) {
        public Edition {
            choices = List.copyOf(choices);
        }
        public boolean executionPermitted() { return false; }
        public boolean methodS2Qualified() { return false; }
    }

    private record DbChoice(UUID editionId, String methodCode, String variantCode,
                            int editionNo, UUID contractId, String contractVersion,
                            String schemaSha256, String algorithmVersion,
                            String generationPolicy, String optionsSha256,
                            int expectedCount, Choice choice) {}

    private final JdbcTemplate jdbc;

    public MethodVariantPresetReadService(JdbcTemplate jdbc) {
        this.jdbc=Objects.requireNonNull(jdbc);
    }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public List<Edition> choices(String methodCode,String variantCode) {
        Objects.requireNonNull(methodCode);
        Objects.requireNonNull(variantCode);
        var rows=jdbc.query("""
            SELECT edition_id,method_code,variant_code,edition_no,
                   contract_id,contract_version,schema_sha256,
                   algorithm_version,generation_policy::text,options_sha256,
                   option_count,option_no,parameters::text,
                   params_sha256,option_sha256
              FROM fin_calc.method_variant_preset_choices
             WHERE method_code=? AND variant_code=?
             ORDER BY edition_no,option_no
            """,(rs,i)->new DbChoice(rs.getObject(1,UUID.class),
                    rs.getString(2),rs.getString(3),rs.getInt(4),
                    rs.getObject(5,UUID.class),rs.getString(6),
                    rs.getString(7),rs.getString(8),rs.getString(9),
                    rs.getString(10),rs.getInt(11),
                    new Choice(rs.getInt(12),rs.getString(13),
                               rs.getString(14),rs.getString(15))),
                methodCode,variantCode);

        var result=new ArrayList<Edition>();
        int current=0;
        while(current<rows.size()) {
            var first=rows.get(current);
            var options=new ArrayList<Choice>();
            do {
                var next=rows.get(current);
                if (!next.editionId().equals(first.editionId())) break;
                if(options.size()+1!=next.choice().optionNo()
                        || next.expectedCount()!=first.expectedCount()
                        || !next.optionsSha256().equals(first.optionsSha256()))
                    throw new IllegalStateException("FIN_METHOD_PRESET_READ_MODEL_CONFLICT");
                options.add(next.choice());
                current++;
            } while(current<rows.size());
            if(options.size()!=first.expectedCount() || options.size()>10)
                throw new IllegalStateException("FIN_METHOD_PRESET_READ_MODEL_CONFLICT");
            result.add(new Edition(first.editionId(),first.methodCode(),
                    first.variantCode(),first.editionNo(),first.contractId(),
                    first.contractVersion(),first.schemaSha256(),
                    first.algorithmVersion(),first.generationPolicy(),
                    first.optionsSha256(),options));
        }
        return List.copyOf(result);
    }
}
