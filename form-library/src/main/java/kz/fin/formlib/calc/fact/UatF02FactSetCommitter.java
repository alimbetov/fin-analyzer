package kz.fin.formlib.calc.fact;

import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.formlib.calc.ingest.*;
import kz.fin.formlib.calc.partition.FactMonthlyPartitionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.SQLException;
import java.time.YearMonth;
import java.util.*;

/**
 * FACT-1.B: atomic TEST_ONLY F02 persistence, no REST entrypoint, no production S2.
 * Explicit UAT opt-in only. The default runtime is DENY.
 * Source SHA is reverified INSIDE the same REPEATABLE_READ TX-Q.
 */
@Service
public final class UatF02FactSetCommitter implements FactSetCommitter {
  static final String ISSUER="FIN_TEST_ONLY_ISSUER";
  static final String PROVIDER="FIN_TEST_ONLY";
  private static final String PRODUCER="FACT1B_F02_UAT";
  enum FaultPoint { NONE, AFTER_HEADER, AFTER_SECOND_OBSERVATION, BEFORE_OUTBOX }

  private final JdbcTemplate db;
  private final NamedParameterJdbcTemplate named;
  private final PlatformTransactionManager manager;
  private final FormFactSourceReader sourceReader;
  private final FormFactSourceIntegrityVerifier sourceVerifier;
  private final FormFactCandidateExtractor extractor;
  private final F02SourceFactAssembler assembler;
  private final FactMonthlyPartitionService partitions;
  private final ObjectMapper mapper;
  private final boolean enabled;

  public UatF02FactSetCommitter(JdbcTemplate db,PlatformTransactionManager manager,
      FormFactSourceReader sourceReader,FormFactSourceIntegrityVerifier sourceVerifier,
      FormFactCandidateExtractor extractor,F02SourceFactAssembler assembler,
      FactMonthlyPartitionService partitions,ObjectMapper mapper,
      @Value("$"+"{fin.fact.uat-committer.enabled:false}") boolean enabled){
    this.db=db;this.named=new NamedParameterJdbcTemplate(db);this.manager=manager;
    this.sourceReader=sourceReader;this.sourceVerifier=sourceVerifier;
    this.extractor=extractor;this.assembler=assembler;this.partitions=partitions;
    this.mapper=mapper;this.enabled=enabled;
  }

  @Override public FactSetCommitReceipt commitExact(CandidateFactSet candidate,
      F02MappingDescriptor descriptor,VerifiedQualification permission,
      TrustedRequestPrincipal principal,UUID userId) {
    return commitWithFault(candidate,descriptor,permission,principal,userId,FaultPoint.NONE);
  }

  FactSetCommitReceipt commitWithFault(CandidateFactSet candidate,
      F02MappingDescriptor descriptor,VerifiedQualification permission,
      TrustedRequestPrincipal principal,UUID userId,FaultPoint fault) {
    if(!enabled)throw new IllegalStateException("FACT1B_UAT_DISABLED");
    if(candidate==null||descriptor==null||permission==null||principal==null
        ||userId==null||fault==null)
      throw new IllegalArgumentException("FACT1B_ADMISSION_REQUIRED");
    F02CandidateIntegrity.verify(candidate);
    if(!ISSUER.equals(permission.issuerId())||!PROVIDER.equals(principal.identityProvider())
        ||!principal.permits(TrustedRequestPrincipal.QUALIFY_ACTION)
        ||!userId.toString().equals(principal.subjectId())
        ||!permission.matches(candidate,principal)
        ||!candidate.descriptorSha256().equals(descriptor.descriptorSha256())
        ||!candidate.mappingBundleSha256().equals(descriptor.mappingBundleSha256())
        ||!candidate.sources().getFirst().formVersionId().equals(descriptor.formVersionId()))
      throw new IllegalArgumentException("FACT1B_ADMISSION_MISMATCH");
    YearMonth month=YearMonth.from(candidate.scope().periodStart());
    if(!partitions.inspectRequiredCoverage(month,month).complete())
      throw new IllegalStateException("FACT1B_PARTITION_MISSING");
    for(int attempt=0;attempt<6;attempt++){
      try{
        TransactionTemplate tx=new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionTemplate.ISOLATION_REPEATABLE_READ);
        tx.setTimeout(30);
        return Objects.requireNonNull(tx.execute(s->
            commitTx(candidate,descriptor,permission,principal,userId,fault)));
      }catch(RuntimeException e){
        if(attempt==5||!retryable(e))throw e;
        try{Thread.sleep(35L*(attempt+1));}
        catch(InterruptedException interrupted){
          Thread.currentThread().interrupt();
          throw new IllegalStateException("FACT1B_INTERRUPTED",interrupted);
        }
      }
    }
    throw new IllegalStateException("FACT1B_RETRY_EXHAUSTED");
  }

  private FactSetCommitReceipt commitTx(CandidateFactSet requested,
      F02MappingDescriptor descriptor,VerifiedQualification permission,
      TrustedRequestPrincipal principal,UUID userId,FaultPoint fault){
    var scope=requested.scope();
    var member=requested.sources().getFirst();
    // DO NOT call INGEST-2 extractExact here: its REQUIRES_NEW would use
    // a different DB snapshot, breaking the TX-Q source binding.
    var snapshot=sourceReader.readExact(member.revisionId(),
        scope.organizationId(),scope.projectId());
    String rowSha=sourceVerifier.verify(snapshot);
    var p=extractor.extract(snapshot);
    var verifiedSource=new FormFactCandidateExtractor.Projection(
        p.revisionId(),p.instanceId(),p.formVersionId(),p.formCode(),
        p.contentMode(),rowSha,p.scope(),p.sourceSha256Claim(),
        p.sourceSchemaSha256(),p.sourceMappingSha256(),p.ingestionRegistrySha256(),
        "SOURCE_SHA_REVERIFIED","UNQUALIFIED_SOURCE_PROJECTION",
        p.numeric(),p.attributes(),p.evidence());
    var exact=assembler.assemble(verifiedSource,descriptor);
    if(!exact.equals(requested)||!permission.matches(exact,principal))
      throw new IllegalArgumentException("FACT1B_SOURCE_BINDING_MISMATCH");

    String scopeKey=scope.stableScopeKey();
    db.update("""
        INSERT INTO fin_fact.factset_control
          (id,stable_scope_key,organization_id,qualification_epoch,revoked,row_version)
        VALUES(?,?,?,0,true,0) ON CONFLICT (stable_scope_key) DO NOTHING
        """,UUID.randomUUID(),scopeKey,scope.organizationId());
    var ctl=db.queryForMap("""
        SELECT organization_id,qualification_epoch,active_factset_id,revoked
        FROM fin_fact.factset_control WHERE stable_scope_key=? FOR UPDATE
        """,scopeKey);
    if(!scope.organizationId().equals(ctl.get("organization_id")))
      throw new IllegalArgumentException("FACT1B_CROSS_TENANT_CONTROL");

    var existing=db.query("""
        SELECT factset_id,organization_id,project_id,initiated_by_user_id,
          scope_key,candidate_factset_sha256,source_set_sha256,binding_sha256,
          qualification_epoch FROM fin_fact.factset_qualification_receipt
        WHERE issuer_id=? AND decision_id=?
        """,(r,i)->new Existing(
          (UUID)r.getObject(1),(UUID)r.getObject(2),(UUID)r.getObject(3),
          (UUID)r.getObject(4),r.getString(5),r.getString(6),
          r.getString(7),r.getString(8),r.getLong(9)),
        permission.issuerId(),permission.decisionId());
    if(existing.size()>1)throw new IllegalStateException("FACT1B_RECEIPT_DUPLICATE");
    if(!existing.isEmpty()){
      var old=existing.getFirst();
      if(!old.org.equals(scope.organizationId())||!old.project.equals(scope.projectId())
          ||!old.user.equals(userId)||!old.scope.equals(scopeKey)
          ||!old.factSha.equals(exact.candidateFactsetSha256())
          ||!old.sourceSha.equals(exact.sourceSetSha256())
          ||!old.bindingSha.equals(permission.bindingSha256())
          ||old.epoch!=permission.issuerEpoch())
        throw new IllegalArgumentException("QUALIFICATION_REPLAY_CONFLICT");
      var count=db.queryForObject("""
          SELECT COUNT(*) FROM fin_fact.fact_observation WHERE factset_id=?
          """,Integer.class,old.factsetId);
      if(count==null||count!=3)throw new IllegalStateException("FACTSET_INTEGRITY_MISMATCH");
      return receipt(old.factsetId,exact,permission,userId,old.epoch,"IDEMPOTENT_REPLAY");
    }

    long previousEpoch=((Number)ctl.get("qualification_epoch")).longValue();
    if(permission.issuerEpoch()!=previousEpoch+1)
      throw new IllegalArgumentException("AUTHORITY_EPOCH_STALE");
    if(previousEpoch>0&&Boolean.TRUE.equals(ctl.get("revoked")))
      throw new IllegalArgumentException("FACT1B_SCOPE_REVOKED");
    if(ctl.get("active_factset_id")!=null)
      requireNewerRevision((UUID)ctl.get("active_factset_id"),member.revisionId(),scopeKey);
    UUID factId=UUID.randomUUID();
    MapSqlParameterSource h=new MapSqlParameterSource()
        .addValue("id",factId).addValue("org",scope.organizationId())
        .addValue("entity",scope.economicEntityId())
        .addValue("business",scope.businessScopeId())
        .addValue("project",scope.projectId()).addValue("user",userId)
        .addValue("dataset",scope.datasetCode()).addValue("scope",scopeKey)
        .addValue("space",scope.dataSpace()).addValue("coverage",scope.coverage())
        .addValue("basis",scope.reportingBasis()).addValue("currency",scope.currencyCode())
        .addValue("periodType",scope.periodType()).addValue("periodCode",scope.periodCode())
        .addValue("start",scope.periodStart()).addValue("end",scope.periodEndExclusive())
        .addValue("sources",jsonSources(member))
        .addValue("sourceSha",exact.sourceSetSha256())
        .addValue("mappingSha",exact.mappingBundleSha256())
        .addValue("version",exact.canonicalizationVersion())
        .addValue("count",exact.observations().size())
        .addValue("factSha",exact.candidateFactsetSha256())
        .addValue("binding",permission.bindingSha256())
        .addValue("epoch",permission.issuerEpoch())
        .addValue("issuer",permission.issuerId());
    named.update("""
        INSERT INTO fin_fact.fact_set (
          id,organization_id,economic_entity_id,business_scope_id,project_id,
          initiated_by_user_id,dataset_code,scope_key,data_space,coverage,
          reporting_basis,currency_code,target_period_type,target_period_code,
          target_period_start,target_period_end_exclusive,source_revisions_jsonb,
          source_set_sha256,mapping_bundle_sha256,canonicalization_version,
          observation_count,factset_sha256,qualification_binding_sha256,
          qualification_epoch,qualification_mode,issuer)
        VALUES(:id,:org,:entity,:business,:project,:user,:dataset,:scope,:space,
          :coverage,:basis,:currency,:periodType,:periodCode,:start,:end,
          CAST(:sources AS jsonb),:sourceSha,:mappingSha,:version,:count,
          :factSha,:binding,:epoch,'TEST_QUALIFIED',:issuer)
        """,h);
    fail(fault,FaultPoint.AFTER_HEADER);

    for(int i=0;i<exact.observations().size();i++){
      var o=exact.observations().get(i);
      var values=new MapSqlParameterSource()
          .addValue("start",o.periodStart()).addValue("end",o.periodEndExclusive())
          .addValue("id",UUID.randomUUID()).addValue("factset",factId)
          .addValue("revision",o.sourceRevisionId()).addValue("version",member.formVersionId())
          .addValue("field",o.sourceFieldCode()).addValue("sourceSha",member.canonicalContentSha256())
          .addValue("mapping",member.mappingSha256()).addValue("metric",o.metricCode())
          .addValue("contract",o.metricContractVersion()).addValue("period",scope.periodType())
          .addValue("dimensions",o.dimensionDigest())
          .addValue("key",o.observationKeySha256())
          .addValue("amount",o.value()).addValue("unit",o.unitCode())
          .addValue("currency",o.currencyCode()).addValue("valueSha",o.valueSha256());
      named.update("""
          INSERT INTO fin_fact.fact_observation (
            period_start,observation_id,factset_id,source_revision_id,
            source_form_code,source_form_version_id,source_row_key,source_field_code,
            source_content_sha256,mapping_sha256,metric_code,metric_contract_version,
            period_type,period_end_exclusive,period_semantics,dimension_digest,
            dimensions_jsonb,observation_key_sha256,value_kind,value_state,
            numeric_value,unit_code,currency_code,canonical_value_sha256)
          VALUES(:start,:id,:factset,:revision,'F02',:version,'SINGLE',:field,
            :sourceSha,:mapping,:metric,:contract,:period,:end,'INTERVAL',
            :dimensions,'{}'::jsonb,:key,'NUMERIC','PRESENT',:amount,:unit,
            :currency,:valueSha)
          """,values);
      if(i==1)fail(fault,FaultPoint.AFTER_SECOND_OBSERVATION);
    }
    db.update("""
        INSERT INTO fin_fact.factset_qualification_receipt (
          id,issuer_id,decision_id,factset_id,organization_id,project_id,
          initiated_by_user_id,scope_key,candidate_factset_sha256,
          source_set_sha256,binding_sha256,qualification_epoch,qualification_mode)
        VALUES(?,?,?,?,?,?,?,?,?,?,?,?,'TEST_QUALIFIED')
        """,UUID.randomUUID(),permission.issuerId(),permission.decisionId(),
        factId,scope.organizationId(),scope.projectId(),userId,scopeKey,
        exact.candidateFactsetSha256(),exact.sourceSetSha256(),
        permission.bindingSha256(),permission.issuerEpoch());
    db.update("""
        UPDATE fin_fact.factset_control
        SET active_factset_id=?,qualification_epoch=?,revoked=false,
            row_version=row_version+1,updated_at=now()
        WHERE stable_scope_key=? AND organization_id=?
        """,factId,permission.issuerEpoch(),scopeKey,scope.organizationId());
    fail(fault,FaultPoint.BEFORE_OUTBOX);
    db.update("""
        INSERT INTO fin_calc.calc_outbox_event (
          id,organization_id,scope_key,producer,producer_event_id,event_type,payload_jsonb)
        VALUES(?,?,?,?,?,'FACTSET_QUALIFIED',
          jsonb_build_object('factsetId',?::text,'factsetSha256',?::text,
            'epoch',?::bigint,'mode','TEST_QUALIFIED','projectId',?::text))
        """,UUID.randomUUID(),scope.organizationId(),scopeKey,PRODUCER,
        permission.decisionId().toString(),factId.toString(),
        exact.candidateFactsetSha256(),permission.issuerEpoch(),scope.projectId().toString());
    return receipt(factId,exact,permission,userId,permission.issuerEpoch(),"TEST_QUALIFIED");
  }

  private void requireNewerRevision(UUID active,UUID proposed,String scope){
    var old=db.query("""
        SELECT source_revisions_jsonb->0->>'revisionId'
        FROM fin_fact.fact_set WHERE id=? AND scope_key=?
        """,(r,i)->r.getString(1),active,scope);
    if(old.size()!=1||old.getFirst()==null)
      throw new IllegalStateException("FACT1B_ACTIVE_PROVENANCE_MISSING");
    var versions=db.query("""
        SELECT id,instance_id,revision_no FROM fin_intake.lib_form_revision
        WHERE id IN (?,?)
        """,(r,i)->new Revision((UUID)r.getObject(1),(UUID)r.getObject(2),r.getInt(3)),
        UUID.fromString(old.getFirst()),proposed);
    var earlier=versions.stream().filter(v->v.id.toString().equals(old.getFirst()))
        .findFirst().orElseThrow(()->new IllegalStateException("FACT1B_OLDER_SOURCE_MISSING"));
    var next=versions.stream().filter(v->v.id.equals(proposed))
        .findFirst().orElseThrow(()->new IllegalStateException("FACT1B_NEW_SOURCE_MISSING"));
    if(!earlier.instance.equals(next.instance)||next.no<=earlier.no)
      throw new IllegalArgumentException("FACT1B_OLDER_REVISION_BLOCKED");
  }

  private String jsonSources(PinnedSourceMember source){
    try{
      return mapper.writeValueAsString(List.of(Map.of(
          "revisionId",source.revisionId().toString(),
          "instanceId",source.instanceId().toString(),
          "formVersionId",source.formVersionId().toString(),
          "formCode",source.formCode(),
          "canonicalContentSha256",source.canonicalContentSha256(),
          "rowOrderSha256",source.rowOrderSha256(),
          "schemaSha256",source.schemaSha256(),
          "mappingSha256",source.mappingSha256(),
          "sourceRole",source.sourceRole())));
    }catch(Exception e){throw new IllegalStateException("FACT1B_SOURCE_JSON_FAILED",e);}
  }
  private static void fail(FaultPoint actual,FaultPoint target){
    if(actual==target)throw new IllegalStateException("FACT1B_FORCED_ROLLBACK");
  }
  private static FactSetCommitReceipt receipt(UUID id,CandidateFactSet candidate,
      VerifiedQualification grant,UUID user,long epoch,String result){
    return new FactSetCommitReceipt(id,candidate.sources().getFirst().revisionId(),
        candidate.scope().projectId(),user,candidate.candidateFactsetSha256(),
        grant.bindingSha256(),candidate.scope().stableScopeKey(),epoch,result);
  }
  private record Existing(UUID factsetId,UUID org,UUID project,UUID user,
      String scope,String factSha,String sourceSha,String bindingSha,long epoch){}
  private record Revision(UUID id,UUID instance,int no){}
  private static boolean retryable(Throwable error){
    for(Throwable t=error;t!=null;t=t.getCause())
      if(t instanceof SQLException sql
          && ("40001".equals(sql.getSQLState())||"40P01".equals(sql.getSQLState())))
        return true;
    return false;
  }
}
