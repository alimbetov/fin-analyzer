package kz.fin.backend.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.backend.analytics.ConcentrationAnalysisEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * CON-02 runs only after CON-01 completed in the same SingleExecutor DAG.
 * Both variants use the same deterministic engine and a pinned immutable
 * source manifest. One family or variant is not automatically qualified.
 */
@Component
public final class QualifiedSkuAbcAdapter implements MethodTaskAdapter {
    public static final String CODE="CON-02/ABC_SALES";
    private final ReviewedSkuPopulationLoader loader;
    private final ObjectMapper json;
    private final boolean enabled;

    public QualifiedSkuAbcAdapter(ReviewedSkuPopulationLoader loader,
            ObjectMapper json,
            @Value("${fin.methods.con.enabled:false}") boolean enabled) {
        this.loader=loader;this.json=json;this.enabled=enabled;
    }
    @Override public String methodCode(){return CODE;}
    @Override public boolean autoEligible(){return enabled;}
    @Override public List<String> dependsOn(){
        return List.of(QualifiedSkuParetoAdapter.CODE);
    }
    @Override public boolean applicable(TrustedRevision revision){
        return loader.load(revision).isPresent();
    }

    @Override public String execute(TrustedRevision revision) throws Exception {
        var p=loader.load(revision).orElseThrow(
                ()->new IllegalStateException("CON02_REVIEWED_SKU_SOURCE_REQUIRED"));
        var pareto=ConcentrationAnalysisEngine.pareto(p.skus(),p.netSales());
        var policy=ConcentrationAnalysisEngine.DEFAULT_ABC_POLICY;
        var abc=ConcentrationAnalysisEngine.abc(pareto,policy);
        var classified=new ArrayList<Map<String,String>>();
        for(var row:abc.rows()){
            var data=new LinkedHashMap<String,String>();
            data.put("skuCode",row.skuCode());
            data.put("abcClass",row.classification());
            data.put("sharePercent",row.contributionPercent().toPlainString());
            data.put("cumulativePercent",row.cumulativePercent().toPlainString());
            classified.add(data);
        }
        var result=new LinkedHashMap<String,Object>();
        result.put("kind","SKU_ABC_CLASSIFICATION");
        result.put("status","CALCULATED");
        result.put("methodCode",CODE);
        result.put("methodVersion",abc.policyVersion());
        result.put("paretoMethodVersion",pareto.methodVersion());
        result.put("qualification","METHOD_FAMILY_NOT_YET_APPROVED");
        result.put("sourceRevisionId",revision.factSetRevisionId().toString());
        result.put("sourceFingerprint",revision.snapshotHash());
        result.put("skuEvidenceId",p.sourceId().toString());
        result.put("skuPopulationFingerprint",p.sourceFingerprint());
        result.put("skuSourceDocumentHash",p.sourceDocumentHash());
        result.put("periodStart",p.from().toString());
        result.put("periodEndExclusive",p.until().toString());
        result.put("currency",p.currency());
        result.put("coveragePolicy",p.coveragePolicy());
        result.put("policy",Map.of(
                "classAUpperPercent",policy.aLimitPercent().toPlainString(),
                "classBUpperPercent",policy.bLimitPercent().toPlainString(),
                "boundary","CROSSING_ITEM_PREVIOUS_CLASS_UNROUNDED"));
        result.put("rows",classified);
        result.put("limitations",List.of("SKU_SOURCE_MANIFEST_REVIEWED_NOT_EXTERNAL_AUDIT",
                "METHOD_FAMILY_NOT_YET_APPROVED"));
        return json.writeValueAsString(result);
    }
}
