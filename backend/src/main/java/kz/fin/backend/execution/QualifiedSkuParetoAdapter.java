package kz.fin.backend.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.backend.analytics.ConcentrationAnalysisEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

/** One CON-01 Pareto variant. Server-reviewed SKU evidence is mandatory.
 * Default OFF; registration does not qualify the CON-01 family.
 */
@Component
public final class QualifiedSkuParetoAdapter implements MethodTaskAdapter {
    public static final String CODE="CON-01/PARETO_SALES";
    private final ReviewedSkuPopulationLoader loader;
    private final ObjectMapper json;
    private final boolean enabled;

    public QualifiedSkuParetoAdapter(ReviewedSkuPopulationLoader loader,
            ObjectMapper json,
            @Value("${fin.methods.con.enabled:false}") boolean enabled) {
        this.loader=loader;this.json=json;this.enabled=enabled;
    }
    @Override public String methodCode(){return CODE;}
    @Override public boolean autoEligible(){return enabled;}
    @Override public List<String> dependsOn(){
        return List.of("SYS/VERIFY_PUBLISHED_FACT_SET");
    }
    @Override public boolean applicable(TrustedRevision revision){
        return loader.load(revision).isPresent();
    }

    @Override public String execute(TrustedRevision revision) throws Exception {
        var p=loader.load(revision).orElseThrow(
                ()->new IllegalStateException("CON01_REVIEWED_SKU_SOURCE_REQUIRED"));
        var analysis=ConcentrationAnalysisEngine.pareto(p.skus(),p.netSales());
        var rows=new ArrayList<Map<String,String>>();
        for(var row:analysis.rows()) {
            var data=new LinkedHashMap<String,String>();
            data.put("skuCode",row.skuCode());
            data.put("netSales",row.amount().toPlainString());
            data.put("sharePercent",row.sharePercent().toPlainString());
            data.put("cumulativePercent",row.cumulativePercent().toPlainString());
            rows.add(data);
        }
        var result=new LinkedHashMap<String,Object>();
        result.put("kind","SKU_PARETO_ANALYSIS");
        result.put("status","CALCULATED");
        result.put("methodCode",CODE);
        result.put("methodVersion",analysis.methodVersion());
        result.put("qualification","METHOD_FAMILY_NOT_YET_APPROVED");
        result.put("sourceRevisionId",revision.factSetRevisionId().toString());
        result.put("sourceFingerprint",revision.snapshotHash());
        result.put("skuEvidenceId",p.sourceId().toString());
        result.put("skuPopulationFingerprint",p.sourceFingerprint());
        result.put("skuSourceDocumentHash",p.sourceDocumentHash());
        result.put("periodStart",p.from().toString());
        result.put("periodEndExclusive",p.until().toString());
        result.put("currency",p.currency());
        result.put("accountingBasis",p.reportingBasis());
        result.put("coveragePolicy",p.coveragePolicy());
        result.put("reconciledNetRevenue",p.netSales().toPlainString());
        result.put("rows",rows);
        result.put("limitations",List.of("SKU_SOURCE_MANIFEST_REVIEWED_NOT_EXTERNAL_AUDIT",
                "METHOD_FAMILY_NOT_YET_APPROVED"));
        return json.writeValueAsString(result);
    }
}
