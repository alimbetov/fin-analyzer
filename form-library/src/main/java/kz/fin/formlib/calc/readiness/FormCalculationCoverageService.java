package kz.fin.formlib.calc.readiness;

import kz.fin.formlib.calc.ingest.FormFactIngestionRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * CALC-166.A / R16.A: live PostgreSQL physical field/metric coverage for EVERY
 * current form version. This is a read model, never financial authorization.
 *
 * A field/metric candidate or a legacy CALC_PILOT catalog entry must never
 * imply qualification. Only the existing, test-only F02 pipeline has an
 * executable UAT proof. No new fin_fact/calculation writes are allowed here.
 */
@Service
public class FormCalculationCoverageService {
  public enum Readiness {
    UAT_F02_TEST_ONLY,
    BLOCKED_CONTRACT_INTEGRITY,
    BLOCKED_FINANCIAL_REVIEW,
    EVIDENCE_SUPPORT_ONLY
  }
  public enum FieldState {
    MISSING_BINDING,
    STALE_SCHEMA,
    FIELD_CONTRACT_MISMATCH,
    METRIC_IDENTITY_MISMATCH,
    VERSIONED_MAPPING_MISMATCH,
    CANDIDATE_UNQUALIFIED
  }
  public record FieldCoverage(String formCode,UUID formVersionId,
      String fieldCode,String fieldType,String metricCode,
      String semanticRole,String reviewState,boolean versionedBinding,
      FieldState state) {
    public FieldCoverage {
      Objects.requireNonNull(formVersionId);
      Objects.requireNonNull(fieldCode);
      Objects.requireNonNull(state);
    }
  }
  public record FormCoverage(String formCode,UUID formVersionId,
      String contentMode,String schemaSha256,String versionQualification,
      int physicalFields,int sourceFieldCandidates,int numericFieldCandidates,
      int evidenceFields,int missingBindings,int driftedBindings,
      int versionedMetricBindings,Readiness readiness,String blocker,
      List<FieldCoverage> fields) {
    public FormCoverage {
      fields=List.copyOf(fields);
      Objects.requireNonNull(formVersionId);
      Objects.requireNonNull(readiness);
      Objects.requireNonNull(blocker);
    }
    /** UAT pilot proof does NOT constitute an approved financial model. */
    public boolean financialQualified(){return false;}
    public boolean testOnlyExecutable(){return readiness==Readiness.UAT_F02_TEST_ONLY;}
  }
  public record Snapshot(List<FormCoverage> forms,int physicalFields,
      int sourceFieldCandidates,int numericFieldCandidates,int evidenceFields,
      int missingBindings,int driftedBindings,int testOnlyExecutableForms,
      int financiallyQualifiedForms,String ingestionManifestSha256) {
    public Snapshot {
      forms=List.copyOf(forms);
      if(forms.size()!=166)throw new IllegalStateException("CALC_166_FORM_COVERAGE_INCOMPLETE");
      if(financiallyQualifiedForms!=0)
        throw new IllegalStateException("CALC_166_IMPLICIT_FINANCIAL_APPROVAL");
    }
    public FormCoverage require(String code){
      return forms.stream().filter(f->f.formCode().equals(code)).findFirst()
          .orElseThrow(()->new IllegalArgumentException("CALC_166_FORM_UNREGISTERED"));
    }
  }

  private record Physical(String form,String mode,UUID version,String schema,
      String qualification,String field,String type,String inputMode,boolean required,
      String contractField,String contractSchema,String contractType,
      String contractInputMode,Boolean contractRequired,String role,
      String metric,String review,String unitPolicy,String metricFound,
      String metricKind,String metricForm,String metricField,
      String metricUnit,String versionedMetric) {}

  private final JdbcTemplate sql;
  private final FormFactIngestionRegistry registry;

  @Autowired
  public FormCalculationCoverageService(JdbcTemplate sql) {
    this(sql,FormFactIngestionRegistry.loadDefault());
  }
  FormCalculationCoverageService(JdbcTemplate sql,FormFactIngestionRegistry registry){
    this.sql=Objects.requireNonNull(sql);
    this.registry=Objects.requireNonNull(registry);
  }

  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
  public Snapshot snapshot(){
    // Every displayed value below is read from the real current PG catalog,
    // not copied from the 794 R6 TSV candidate list. Versioned mapping is
    // deliberately separate from the legacy, candidate-only source mapping.
    List<Physical> rows=sql.query("""
        SELECT f.form_code, v.id version_id,v.content_mode,
          v.schema_sha256,v.qualification_state,
          ff.field_code,ff.field_type,ff.input_mode,ff.required,
          c.field_code cfield,c.source_schema_sha256 csha,
          c.field_type ctype,c.input_mode cmode,c.required crequired,
          c.semantic_role,c.metric_code,c.review_state,c.unit_policy,
          m.metric_code mcode,m.metric_kind,m.form_code mform,
          m.source_field_code mfield,m.unit_policy munit,
          vm.metric_code versioned_metric
        FROM fin_lib.lib_form_definition f
        JOIN fin_lib.lib_form_version v ON v.id=f.current_version_id
          AND v.form_id=f.id
        LEFT JOIN fin_lib.lib_form_field ff ON ff.form_version_id=v.id
        LEFT JOIN fin_lib.lib_field_binding_contract c
          ON c.form_code=f.form_code AND c.field_code=ff.field_code
        LEFT JOIN fin_lib.lib_metric_definition m ON m.metric_code=c.metric_code
        LEFT JOIN fin_lib.lib_form_contract_metric_binding vm
          ON vm.form_version_id=v.id AND vm.field_code=ff.field_code
        ORDER BY f.form_code,ff.field_code
        """,(r,i)->new Physical(
          r.getString("form_code"),r.getString("content_mode"),
          r.getObject("version_id",UUID.class),r.getString("schema_sha256"),
          r.getString("qualification_state"),r.getString("field_code"),
          r.getString("field_type"),r.getString("input_mode"),
          r.getBoolean("required"),r.getString("cfield"),r.getString("csha"),
          r.getString("ctype"),r.getString("cmode"),
          (Boolean)r.getObject("crequired"),r.getString("semantic_role"),
          r.getString("metric_code"),r.getString("review_state"),
          r.getString("unit_policy"),r.getString("mcode"),
          r.getString("metric_kind"),r.getString("mform"),
          r.getString("mfield"),r.getString("munit"),
          r.getString("versioned_metric")));
    Map<String,List<Physical>> groups=new LinkedHashMap<>();
    for(Physical row:rows)groups.computeIfAbsent(row.form,k->new ArrayList<>()).add(row);
    if(groups.size()!=166||!groups.keySet().equals(registry.profiles().keySet()))
      throw new IllegalStateException("CALC_166_FORM_REGISTRY_DATABASE_DRIFT");

    List<FormCoverage> forms=new ArrayList<>();
    int total=0,source=0,numeric=0,evidence=0,missing=0,drift=0,pilots=0;
    for(var entry:groups.entrySet()){
      String code=entry.getKey();
      List<Physical> group=entry.getValue();
      Physical first=group.getFirst();
      var profile=registry.require(code,first.mode);
      List<FieldCoverage> fields=new ArrayList<>();
      int src=0,num=0,ev=0,miss=0,bad=0,versioned=0;
      for(Physical r:group){
        if(r.field==null)continue;
        FieldState state=classify(r);
        if(state==FieldState.MISSING_BINDING)miss++;
        else if(state!=FieldState.CANDIDATE_UNQUALIFIED)bad++;
        if("SOURCE_FIELD".equals(r.role)){
          src++;
          if(Set.of("MONEY","DECIMAL","INTEGER").contains(r.type))num++;
        }
        if("EVIDENCE_METADATA".equals(r.role))ev++;
        if(r.versionedMetric!=null)versioned++;
        fields.add(new FieldCoverage(code,r.version,r.field,r.type,
            r.metric,r.role,r.review,r.versionedMetric!=null,state));
      }
      if(fields.isEmpty())
        throw new IllegalStateException("CALC_166_FORM_WITHOUT_PHYSICAL_FIELDS:"+code);
      boolean structurallyClean=miss==0&&bad==0;
      boolean f02Pilot=code.equals("F02")
          &&profile.mode()==FormFactIngestionRegistry.Mode.SINGLE_RECORD
          &&"TEST_ONLY_PILOT_PENDING".equals(profile.reviewState())
          &&structurallyClean && fields.size()==3 && src==3 && num==3
          &&fields.stream().map(FieldCoverage::fieldCode).collect(
              java.util.stream.Collectors.toSet())
              .equals(Set.of("netRevenue","cogs","operatingExpenses"));
      Readiness status;
      String blocker;
      if(!structurallyClean){
        status=Readiness.BLOCKED_CONTRACT_INTEGRITY;
        blocker="MISSING_OR_STALE_FIELD_METRIC_BINDING";
      }else if(f02Pilot){
        status=Readiness.UAT_F02_TEST_ONLY;
        blocker="PRODUCTION_S2_NOT_APPROVED";
        pilots++;
      }else if(profile.mode()==FormFactIngestionRegistry.Mode.EVIDENCE){
        status=Readiness.EVIDENCE_SUPPORT_ONLY;
        blocker="DOCUMENT_EVIDENCE_NO_NUMERIC_METHOD";
      }else{
        status=Readiness.BLOCKED_FINANCIAL_REVIEW;
        blocker="NO_REVIEWED_VERSIONED_MAPPING_AND_CALC_KERNEL";
      }
      fields.sort(Comparator.comparing(FieldCoverage::fieldCode));
      forms.add(new FormCoverage(code,first.version,first.mode,first.schema,
          first.qualification,fields.size(),src,num,ev,miss,bad,versioned,
          status,blocker,fields));
      total+=fields.size();source+=src;numeric+=num;evidence+=ev;
      missing+=miss;drift+=bad;
    }
    forms.sort(Comparator.comparing(FormCoverage::formCode));
    return new Snapshot(forms,total,source,numeric,evidence,
        missing,drift,pilots,0,registry.manifestSha256());
  }

  /** For future dispatch adapters. Deliberately refuses any unapproved form. */
  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
  public FormCoverage requireExistingUatPilot(String code,UUID exactVersion){
    if(exactVersion==null)
      throw new IllegalArgumentException("CALC_166_EXACT_VERSION_REQUIRED");
    var found=snapshot().require(code);
    if(!found.testOnlyExecutable()||!found.formVersionId().equals(exactVersion))
      throw new IllegalStateException("CALC_166_NOT_ADMITTED_TEST_ONLY");
    return found;
  }

  private static FieldState classify(Physical r){
    if(r.contractField==null)return FieldState.MISSING_BINDING;
    if(!Objects.equals(r.contractSchema,r.schema))return FieldState.STALE_SCHEMA;
    if(!Objects.equals(r.contractType,r.type)
        ||!Objects.equals(r.contractInputMode,r.inputMode)
        ||!Objects.equals(r.contractRequired,r.required))
      return FieldState.FIELD_CONTRACT_MISMATCH;
    if("SOURCE_FIELD".equals(r.role)){
      if(r.metric==null||r.metricFound==null
          ||!"SOURCE_FIELD".equals(r.metricKind)
          ||!Objects.equals(r.metricForm,r.form)
          ||!Objects.equals(r.metricField,r.field)
          ||!Objects.equals(r.metricUnit,r.unitPolicy))
        return FieldState.METRIC_IDENTITY_MISMATCH;
    }else if("EVIDENCE_METADATA".equals(r.role)){
      if(r.metric!=null)return FieldState.METRIC_IDENTITY_MISMATCH;
    }else return FieldState.METRIC_IDENTITY_MISMATCH;
    if(r.versionedMetric!=null && !Objects.equals(r.versionedMetric,r.metric))
      return FieldState.VERSIONED_MAPPING_MISMATCH;
    // Draft review statuses remain unqualified even if structurally valid.
    return FieldState.CANDIDATE_UNQUALIFIED;
  }
}
