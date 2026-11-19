package kz.fin.formlib.calc.fact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.formlib.calc.ingest.FormFactCandidateExtractor;
import kz.fin.formlib.calc.ingest.FormFactSourceReader;
import kz.fin.formlib.calc.ingest.FormFactSourceIntegrityVerifier;
import kz.fin.formlib.calc.plan.F02CalcPlanDescriptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import java.time.OffsetDateTime;

/**
 * CALC-R0.1 / TX-B — one PostgreSQL transaction for a locked outbox event,
 * pinned source/fact rehash, immutable CalcPlan, READY job and event ACK.
 * Restricts admission to already persisted FACT-1.B synthetic TEST_QUALIFIED
 * receipts, NOT client events or DRAFT_REVISION_SAVED. Defaults to OFF.
 * No live S2, production IAM, worker, or CURRENT publication.
 */
@Service
public final class UatCalcR01OutboxDispatcher {
  private static final String PRODUCER="FACT1B_F02_UAT";
  private static final String EVENT="FACTSET_QUALIFIED";
  enum FaultPoint { NONE, AFTER_PLAN, AFTER_JOB, BEFORE_ACK }
  public record BatchResult(int planned,int reused,int stale,int deferred,int quarantined) {
    public BatchResult {
      if(planned<0||reused<0||stale<0||deferred<0||quarantined<0)
        throw new IllegalArgumentException("CALC_BATCH_INVALID");
    }
    public int processed(){return planned+reused+stale;}
    public int rejected(){return deferred+quarantined;}
  }
  private enum State { PLANNED, REUSED, STALE, DEFERRED, QUARANTINED }
  private static final class RejectedEvent extends IllegalArgumentException {
    private final UUID eventId;
    private RejectedEvent(UUID id,RuntimeException cause){
      super(cause.getMessage(),cause);
      eventId=id;
    }
  }
  private record PendingFailure(int failures,OffsetDateTime processedAt,
      OffsetDateTime quarantinedAt) {}
  private record EventRow(UUID id,UUID org,String scope,String decisionId,String payload) {}
  private record Stored(
      UUID id,UUID org,UUID entity,UUID business,UUID project,UUID user,
      String scope,String dataset,String dataSpace,String coverage,String basis,
      String currency,String periodType,String periodCode,LocalDate start,
      LocalDate end,String sources,String sourceSetSha,String mappingBundleSha,
      int canonicalVersion,int observationCount,String factSha,String bindingSha,
      long epoch,String mode,String issuer,UUID decisionId,UUID receiptOrg,
      UUID receiptProject,UUID receiptUser,String receiptScope,String receiptFactSha,
      String receiptSourceSha,String receiptBindingSha,long receiptEpoch,String receiptMode) {}

  private final JdbcTemplate sql;
  private final PlatformTransactionManager manager;
  private final ObjectMapper mapper;
  private final FormFactSourceReader reader;
  private final FormFactSourceIntegrityVerifier sourceVerifier;
  private final FormFactCandidateExtractor extractor;
  private final F02SourceFactAssembler assembler;
  private final F02CalcPlanDescriptor descriptor;
  private final boolean enabled;

  public UatCalcR01OutboxDispatcher(JdbcTemplate sql,PlatformTransactionManager manager,
      ObjectMapper mapper,FormFactSourceReader reader,
      FormFactSourceIntegrityVerifier sourceVerifier,
      FormFactCandidateExtractor extractor,F02SourceFactAssembler assembler,
      @Value("$"+"{fin.calc.uat.dispatch.enabled:false}") boolean enabled){
    this.sql=sql;this.manager=manager;this.mapper=mapper;this.reader=reader;
    this.sourceVerifier=sourceVerifier;this.extractor=extractor;
    this.assembler=assembler;this.descriptor=new F02CalcPlanDescriptor(mapper);
    this.enabled=enabled;
  }

  /** Explicit bounded dispatch. Schedule only via separately enabled UAT runner. */
  public BatchResult dispatchBatch(int max){
    return dispatchBatchWithFault(max,FaultPoint.NONE);
  }

  /** Package-private test failpoint: no HTTP or production mutation path. */
  BatchResult dispatchBatchWithFault(int max,FaultPoint fault){
    if(!enabled)throw new IllegalStateException("CALC_R01_DISPATCH_DISABLED");
    if(max<1||max>32||fault==null)throw new IllegalArgumentException("CALC_R01_BATCH_INVALID");
    int planned=0,reused=0,stale=0,deferred=0,quarantined=0;
    for(int i=0;i<max;i++){
      State outcome;
      try{
        outcome=dispatchInTxWithBoundedRetry(fault);
      }catch(RejectedEvent rejected){
        // The business TX-B has already rolled back. Do NOT ACK invalid events.
        // A short independent TX records a bounded retry/quarantine, allowing
        // healthy events behind a poison event to progress in this same batch.
        outcome=recordFailure(rejected);
      }
      if(outcome==null)break;
      switch(outcome){
        case PLANNED -> planned++;
        case REUSED -> reused++;
        case STALE -> stale++;
        case DEFERRED -> deferred++;
        case QUARANTINED -> quarantined++;
      }
    }
    return new BatchResult(planned,reused,stale,deferred,quarantined);
  }

  private State dispatchInTxWithBoundedRetry(FaultPoint fault){
    for(int attempt=0;attempt<5;attempt++){
      try{
        var tx=new TransactionTemplate(manager);
        // SKIP LOCKED consumers use READ COMMITTED. Financial sources/observations
        // are immutable; authority pointer is explicitly locked FOR SHARE.
        // REPEATABLE READ can raise 40001 when concurrent consumers ACK the
        // same outbox row while another snapshot is already held.
        tx.setIsolationLevel(TransactionTemplate.ISOLATION_READ_COMMITTED);
        tx.setTimeout(25);
        return tx.execute(status->dispatchOne(fault));
      }catch(RuntimeException ex){
        if(attempt==4||!retryable(ex))throw ex;
        try{Thread.sleep(25L*(attempt+1));}
        catch(InterruptedException interrupted){
          Thread.currentThread().interrupt();
          throw new IllegalStateException("CALC_R01_RETRY_INTERRUPTED",interrupted);
        }
      }
    }
    throw new IllegalStateException("CALC_R01_RETRY_EXHAUSTED");
  }

  private State recordFailure(RejectedEvent rejected){
    var tx=new TransactionTemplate(manager);
    tx.setIsolationLevel(TransactionTemplate.ISOLATION_READ_COMMITTED);
    tx.setTimeout(10);
    return tx.execute(status -> {
      var failures=sql.query("""
          SELECT failure_count,processed_at,quarantined_at
          FROM fin_calc.calc_outbox_event WHERE id=? FOR UPDATE
          """,(r,i)->new PendingFailure(r.getInt(1),
              r.getObject(2,OffsetDateTime.class),
              r.getObject(3,OffsetDateTime.class)),rejected.eventId);
      if(failures.size()!=1)
        throw new IllegalStateException("CALC_R01_FAILED_EVENT_LOST");
      var previous=failures.getFirst();
      // Another consumer might have successfully processed this event after
      // our rollback. Never requeue or quarantine an already acknowledged row.
      if(previous.processedAt!=null||previous.quarantinedAt!=null)
        return State.DEFERRED;
      int next=previous.failures+1;
      boolean dead=next>=5;
      int waitSeconds=switch(Math.min(next,4)){
        case 1 -> 60;
        case 2 -> 300;
        case 3 -> 900;
        default -> 3600;
      };
      String raw=rejected.getMessage();
      String reason=raw!=null&&raw.matches("[A-Z0-9_]{1,75}")
          ?raw:"CALC_R01_REJECTED";
      int updated=sql.update("""
          UPDATE fin_calc.calc_outbox_event
          SET failure_count=failure_count+1,attempts=attempts+1,
              next_attempt_at=now()+(? * interval '1 second'),
              quarantined_at=CASE WHEN ? THEN now() ELSE NULL END,
              last_error_code=?
          WHERE id=? AND processed_at IS NULL AND quarantined_at IS NULL
          """,waitSeconds,dead,reason,rejected.eventId);
      if(updated!=1)throw new IllegalStateException("CALC_R01_FAILURE_RECEIPT_CONFLICT");
      org.slf4j.LoggerFactory.getLogger(getClass()).warn(
          "CALC_R01_EVENT_REJECTED id={} code={} failure_count={} quarantined={}",
          rejected.eventId,reason,next,dead);
      return dead?State.QUARANTINED:State.DEFERRED;
    });
  }

  private static boolean retryable(Throwable error){
    for(Throwable t=error;t!=null;t=t.getCause()){
      if(t instanceof SQLException sql
          && ("40001".equals(sql.getSQLState())
              ||"40P01".equals(sql.getSQLState())
              ||"55P03".equals(sql.getSQLState())))
        return true;
    }
    return false;
  }

  private State dispatchOne(FaultPoint fault){
    // FOR UPDATE SKIP LOCKED distributes events across independent PG
    // connections. ACK is included in this exact transaction.
    var events=sql.query("""
        SELECT id,organization_id,scope_key,producer_event_id,
               payload_jsonb::text payload
        FROM fin_calc.calc_outbox_event
        WHERE producer=? AND event_type=? AND processed_at IS NULL
          AND next_attempt_at<=now() AND quarantined_at IS NULL
        ORDER BY created_at,id
        FOR UPDATE SKIP LOCKED LIMIT 1
        """,(r,i)->new EventRow(r.getObject("id",UUID.class),
          r.getObject("organization_id",UUID.class),r.getString("scope_key"),
          r.getString("producer_event_id"),r.getString("payload")),PRODUCER,EVENT);
    if(events.isEmpty())return null;
    var event=events.getFirst();
    try {
    JsonNode payload=json(event.payload);
    if(!payload.isObject()||payload.size()!=5
        ||!"TEST_QUALIFIED".equals(text(payload,"mode")))
      throw new IllegalArgumentException("CALC_R01_INVALID_EVENT_PAYLOAD");
    UUID factSetId=uuid(text(payload,"factsetId"));
    UUID project=uuid(text(payload,"projectId"));
    UUID decision=uuid(event.decisionId);
    String payloadSha=text(payload,"factsetSha256");
    long payloadEpoch=positiveLong(payload,"epoch");
    FactDigest.requireSha(payloadSha,"CALC_R01_INVALID_EVENT_SHA");

    var records=sql.query("""
        SELECT f.id,f.organization_id,f.economic_entity_id,f.business_scope_id,
          f.project_id,f.initiated_by_user_id,f.scope_key,f.dataset_code,
          f.data_space,f.coverage,f.reporting_basis,f.currency_code,
          f.target_period_type,f.target_period_code,f.target_period_start,
          f.target_period_end_exclusive,f.source_revisions_jsonb::text sources,
          f.source_set_sha256,f.mapping_bundle_sha256,f.canonicalization_version,
          f.observation_count,f.factset_sha256,f.qualification_binding_sha256,
          f.qualification_epoch,f.qualification_mode,f.issuer,
          r.decision_id AS receipt_decision_id,
          r.organization_id AS receipt_org,r.project_id AS receipt_project,
          r.initiated_by_user_id AS receipt_user,r.scope_key AS receipt_scope,
          r.candidate_factset_sha256 AS receipt_fact_sha,
          r.source_set_sha256 AS receipt_source_sha,
          r.binding_sha256 AS receipt_binding_sha,
          r.qualification_epoch AS receipt_epoch,
          r.qualification_mode AS receipt_mode
        FROM fin_fact.fact_set f
        JOIN fin_fact.factset_qualification_receipt r ON r.factset_id=f.id
        WHERE f.id=?
        """,(r,i)->stored(r),factSetId);
    if(records.size()!=1)
      throw new IllegalArgumentException("CALC_R01_QUALIFIED_RECEIPT_MISSING");
    Stored f=records.getFirst();
    if(!f.id.equals(factSetId)||!f.org.equals(event.org)
        ||!f.scope.equals(event.scope)||!f.project.equals(project)
        ||!f.decisionId.equals(decision)||!f.org.equals(f.receiptOrg)
        ||!f.project.equals(f.receiptProject)||f.user==null
        ||!f.user.equals(f.receiptUser)||!f.scope.equals(f.receiptScope)
        ||!f.factSha.equals(f.receiptFactSha)
        ||!f.sourceSetSha.equals(f.receiptSourceSha)
        ||!f.bindingSha.equals(f.receiptBindingSha)
        ||f.epoch!=f.receiptEpoch||f.epoch!=payloadEpoch
        ||!f.factSha.equals(payloadSha)||!"TEST_QUALIFIED".equals(f.mode)
        ||!"TEST_QUALIFIED".equals(f.receiptMode)
        ||!"FIN_TEST_ONLY_ISSUER".equals(f.issuer)
        ||f.epoch<1)
      throw new IllegalArgumentException("CALC_R01_EVENT_BINDING_MISMATCH");

    // Source and persisted observation validation is independent of the
    // event JSON. Recheck the EXACT historical revision inside TX-B.
    CandidateFactSet candidate=reconstruct(f);

    // Lock authority row so a local revoke cannot race TX-B publication
    // of a new queue entry. Worker and CURRENT must revalidate later too.
    var guards=sql.query("""
        SELECT organization_id,active_factset_id,qualification_epoch,revoked
        FROM fin_fact.factset_control
        WHERE stable_scope_key=? FOR SHARE
        """,(r,i)->new Object[]{
          r.getObject(1,UUID.class),r.getObject(2,UUID.class),
          r.getLong(3),r.getBoolean(4)},f.scope);
    if(guards.size()!=1||!f.org.equals(guards.getFirst()[0]))
      throw new IllegalArgumentException("CALC_R01_SCOPE_CONTROL_MISSING");
    Object[] control=guards.getFirst();
    if((boolean)control[3]||!f.id.equals(control[1])||(long)control[2]!=f.epoch){
      acknowledge(event.id,"CALC_R01_STALE_OR_REVOKED");
      return State.STALE;
    }

    var plan=descriptor.build(f.id,candidate,f.bindingSha,f.epoch);
    UUID planId=UUID.randomUUID();
    int inserted=sql.update("""
        INSERT INTO fin_calc.calc_plan (
          id,organization_id,scope_key,factset_id,execution_fingerprint,
          qualified_binding_sha256,qualification_epoch,
          algorithm_artifact_sha256,config_sha256,params_sha256,
          dag_jsonb,qualification_mode)
        VALUES (?,?,?,?,?,?,?,?,?,?,?::jsonb,'TEST_QUALIFIED')
        ON CONFLICT (organization_id,scope_key,execution_fingerprint) DO NOTHING
        """,planId,f.org,f.scope,f.id,plan.executionFingerprint(),
        f.bindingSha,f.epoch,plan.algorithmArtifactSha256(),
        plan.configSha256(),plan.paramsSha256(),plan.dagJson());
    if(inserted!=1&&inserted!=0)
      throw new IllegalStateException("CALC_R01_PLAN_INSERT_INCONSISTENT");
    var found=sql.query("""
        SELECT id,factset_id,qualified_binding_sha256,qualification_epoch,
          algorithm_artifact_sha256,config_sha256,params_sha256,
          dag_jsonb::text AS dag,qualification_mode
        FROM fin_calc.calc_plan
        WHERE organization_id=? AND scope_key=? AND execution_fingerprint=?
        """,(r,i)->new Object[]{r.getObject(1,UUID.class),r.getObject(2,UUID.class),
          r.getString(3),r.getLong(4),r.getString(5),r.getString(6),
          r.getString(7),r.getString(8),r.getString(9)},
        f.org,f.scope,plan.executionFingerprint());
    if(found.size()!=1)
      throw new IllegalStateException("CALC_R01_PLAN_MISSING");
    Object[] saved=found.getFirst();
    if(!f.id.equals(saved[1])||!f.bindingSha.equals(saved[2])
        ||f.epoch!=(long)saved[3]
        ||!plan.algorithmArtifactSha256().equals(saved[4])
        ||!plan.configSha256().equals(saved[5])
        ||!plan.paramsSha256().equals(saved[6])
        ||!json(saved[7].toString()).equals(json(plan.dagJson()))
        ||!"TEST_QUALIFIED".equals(saved[8]))
      throw new IllegalArgumentException("CALC_R01_PLAN_CONFLICT");
    UUID exactPlanId=(UUID)saved[0];
    if(fault==FaultPoint.AFTER_PLAN)throw new IllegalStateException("CALC_R01_FORCED_ROLLBACK");

    // One durable job per exact immutable plan. No worker runs in R0.1.
    sql.update("""
        INSERT INTO fin_calc.calc_job (id,plan_id,organization_id,scope_key)
        VALUES (?,?,?,?) ON CONFLICT (plan_id) DO NOTHING
        """,UUID.randomUUID(),exactPlanId,f.org,f.scope);
    var jobs=sql.query("""
        SELECT organization_id,scope_key FROM fin_calc.calc_job WHERE plan_id=?
        """,(r,i)->new Object[]{r.getObject(1,UUID.class),r.getString(2)},exactPlanId);
    if(jobs.size()!=1||!f.org.equals(jobs.getFirst()[0])
        ||!f.scope.equals(jobs.getFirst()[1]))
      throw new IllegalStateException("CALC_R01_JOB_CONFLICT");
    if(fault==FaultPoint.AFTER_JOB||fault==FaultPoint.BEFORE_ACK)
      throw new IllegalStateException("CALC_R01_FORCED_ROLLBACK");
    acknowledge(event.id,null);
    return inserted==1?State.PLANNED:State.REUSED;
    }catch(RuntimeException ex){
      if(ex instanceof DataAccessException
          ||"CALC_R01_FORCED_ROLLBACK".equals(ex.getMessage()))
        throw ex;
      throw new RejectedEvent(event.id,ex);
    }
  }

  private CandidateFactSet reconstruct(Stored f){
    if(!"F02_TEST_ONLY".equals(f.dataset)||f.observationCount!=3
        ||f.canonicalVersion!=1)
      throw new IllegalArgumentException("CALC_R01_FACTSET_SHAPE_MISMATCH");
    JsonNode sources=json(f.sources);
    if(!sources.isArray()||sources.size()!=1||sources.get(0).size()!=9)
      throw new IllegalArgumentException("CALC_R01_SOURCE_SET_INVALID");
    JsonNode member=sources.get(0);
    UUID revision=uuid(text(member,"revisionId"));
    var snapshot=reader.readExact(revision,f.org,f.project);
    String rowOrder=sourceVerifier.verify(snapshot);
    var p=extractor.extract(snapshot);
    var q=new FormFactCandidateExtractor.Projection(
        p.revisionId(),p.instanceId(),p.formVersionId(),p.formCode(),
        p.contentMode(),rowOrder,p.scope(),p.sourceSha256Claim(),
        p.sourceSchemaSha256(),p.sourceMappingSha256(),p.ingestionRegistrySha256(),
        "SOURCE_SHA_REVERIFIED","UNQUALIFIED_SOURCE_PROJECTION",
        p.numeric(),p.attributes(),p.evidence());
    var mapping=F02MappingDescriptor.testOnlyPilot(p.formVersionId(),
        p.sourceSchemaSha256(),p.sourceMappingSha256());
    var candidate=assembler.assemble(q,mapping);
    var source=candidate.sources().getFirst();
    Map<String,String> expectedSources=Map.of(
        "revisionId",source.revisionId().toString(),
        "instanceId",source.instanceId().toString(),
        "formVersionId",source.formVersionId().toString(),
        "formCode",source.formCode(),
        "canonicalContentSha256",source.canonicalContentSha256(),
        "rowOrderSha256",source.rowOrderSha256(),
        "schemaSha256",source.schemaSha256(),
        "mappingSha256",source.mappingSha256(),
        "sourceRole",source.sourceRole());
    for(var entry:expectedSources.entrySet())
      if(!entry.getValue().equals(text(member,entry.getKey())))
        throw new IllegalArgumentException("CALC_R01_PINNED_SOURCE_MISMATCH");
    var s=candidate.scope();
    if(!f.org.equals(s.organizationId())||!f.entity.equals(s.economicEntityId())
        ||!f.business.equals(s.businessScopeId())||!f.project.equals(s.projectId())
        ||!f.scope.equals(s.stableScopeKey())||!f.dataSpace.equals(s.dataSpace())
        ||!f.coverage.equals(s.coverage())||!f.basis.equals(s.reportingBasis())
        ||!f.currency.equals(s.currencyCode())||!f.periodType.equals(s.periodType())
        ||!f.periodCode.equals(s.periodCode())||!f.start.equals(s.periodStart())
        ||!f.end.equals(s.periodEndExclusive())
        ||!f.factSha.equals(candidate.candidateFactsetSha256())
        ||!f.sourceSetSha.equals(candidate.sourceSetSha256())
        ||!f.mappingBundleSha.equals(candidate.mappingBundleSha256()))
      throw new IllegalArgumentException("CALC_R01_FACTSET_HASH_MISMATCH");
    F02CandidateIntegrity.verify(candidate);
    Integer count=sql.queryForObject("""
        SELECT count(*) FROM fin_fact.fact_observation WHERE factset_id=?
        """,Integer.class,f.id);
    if(count==null||count!=3)
      throw new IllegalArgumentException("CALC_R01_OBSERVATION_COUNT_MISMATCH");
    var observed=sql.query("""
        SELECT source_revision_id,source_form_code,source_form_version_id,
          source_row_key,source_field_code,source_content_sha256,mapping_sha256,
          metric_code,metric_contract_version,period_type,period_start,
          period_end_exclusive,period_semantics,dimension_digest,
          dimensions_jsonb::text,value_kind,value_state,numeric_value,
          unit_code,currency_code,observation_key_sha256,canonical_value_sha256
        FROM fin_fact.fact_observation
        WHERE factset_id=? AND period_start>=? AND period_start<?
        ORDER BY metric_code
        """,(r,i)->new Observation(
            r.getObject(1,UUID.class),r.getString(2),r.getObject(3,UUID.class),
            r.getString(4),r.getString(5),r.getString(6),r.getString(7),
            r.getString(8),r.getString(9),r.getString(10),r.getDate(11).toLocalDate(),
            r.getDate(12).toLocalDate(),r.getString(13),r.getString(14),
            r.getString(15),r.getString(16),r.getString(17),r.getBigDecimal(18),
            r.getString(19),r.getString(20),r.getString(21),r.getString(22)),
        f.id,f.start,f.end);
    if(observed.size()!=3)
      throw new IllegalArgumentException("CALC_R01_OBSERVATION_MISMATCH");
    for(int i=0;i<3;i++){
      var expected=candidate.observations().get(i);
      var a=observed.get(i);
      if(!source.revisionId().equals(a.revision)||!"F02".equals(a.formCode)
          ||!source.formVersionId().equals(a.version)
          ||!expected.sourceRowKey().equals(a.rowKey)
          ||!expected.sourceFieldCode().equals(a.fieldCode)
          ||!source.canonicalContentSha256().equals(a.contentSha)
          ||!source.mappingSha256().equals(a.mappingSha)
          ||!expected.metricCode().equals(a.metric)
          ||!expected.metricContractVersion().equals(a.metricVersion)
          ||!s.periodType().equals(a.periodType)
          ||!expected.periodStart().equals(a.start)
          ||!expected.periodEndExclusive().equals(a.end)
          ||!"INTERVAL".equals(a.semantics)
          ||!expected.dimensionDigest().equals(a.dimensions)
          ||!json(a.dimensionsJson).equals(mapper.createObjectNode())
          ||!"NUMERIC".equals(a.kind)||!"PRESENT".equals(a.state)
          ||a.value==null||expected.value().compareTo(a.value)!=0
          ||!expected.unitCode().equals(a.unit)
          ||!expected.currencyCode().equals(a.currency)
          ||!expected.observationKeySha256().equals(a.keySha)
          ||!expected.valueSha256().equals(a.valueSha))
        throw new IllegalArgumentException("CALC_R01_OBSERVATION_SHA_MISMATCH");
    }
    return candidate;
  }
  private record Observation(
      UUID revision,String formCode,UUID version,String rowKey,String fieldCode,
      String contentSha,String mappingSha,String metric,String metricVersion,
      String periodType,LocalDate start,LocalDate end,String semantics,
      String dimensions,String dimensionsJson,String kind,String state,
      BigDecimal value,String unit,String currency,String keySha,String valueSha){}

  private static Stored stored(ResultSet r)throws SQLException {
    return new Stored(
        r.getObject("id",UUID.class),r.getObject("organization_id",UUID.class),
        r.getObject("economic_entity_id",UUID.class),r.getObject("business_scope_id",UUID.class),
        r.getObject("project_id",UUID.class),r.getObject("initiated_by_user_id",UUID.class),
        r.getString("scope_key"),r.getString("dataset_code"),
        r.getString("data_space"),r.getString("coverage"),r.getString("reporting_basis"),
        r.getString("currency_code"),r.getString("target_period_type"),
        r.getString("target_period_code"),r.getDate("target_period_start").toLocalDate(),
        r.getDate("target_period_end_exclusive").toLocalDate(),
        r.getString("sources"),r.getString("source_set_sha256"),
        r.getString("mapping_bundle_sha256"),r.getInt("canonicalization_version"),
        r.getInt("observation_count"),r.getString("factset_sha256"),
        r.getString("qualification_binding_sha256"),r.getLong("qualification_epoch"),
        r.getString("qualification_mode"),r.getString("issuer"),
        r.getObject("receipt_decision_id",UUID.class),
        r.getObject("receipt_org",UUID.class),r.getObject("receipt_project",UUID.class),
        r.getObject("receipt_user",UUID.class),r.getString("receipt_scope"),
        r.getString("receipt_fact_sha"),r.getString("receipt_source_sha"),
        r.getString("receipt_binding_sha"),r.getLong("receipt_epoch"),
        r.getString("receipt_mode"));
  }

  private JsonNode json(String raw){
    try{
      if(raw==null)throw new IllegalArgumentException("CALC_R01_JSON_REQUIRED");
      var parser=mapper.getFactory().createParser(raw);
      parser.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
      try(parser){
        JsonNode value=mapper.readTree(parser);
        if(value==null||parser.nextToken()!=null)
          throw new IllegalArgumentException("CALC_R01_JSON_INVALID");
        return value;
      }
    }catch(java.io.IOException ex){
      throw new IllegalArgumentException("CALC_R01_JSON_INVALID",ex);
    }
  }
  private static String text(JsonNode obj,String key){
    JsonNode value=obj.get(key);
    if(value==null||!value.isTextual()||value.textValue().isBlank())
      throw new IllegalArgumentException("CALC_R01_CLAIM_MISSING_"+key);
    return value.textValue();
  }
  private static UUID uuid(String text){
    try{return UUID.fromString(text);}
    catch(RuntimeException ex){
      throw new IllegalArgumentException("CALC_R01_CLAIM_UUID_INVALID",ex);
    }
  }
  private static long positiveLong(JsonNode json,String name){
    JsonNode value=json.get(name);
    if(value==null||!value.isIntegralNumber()||!value.canConvertToLong()
        ||value.longValue()<1)
      throw new IllegalArgumentException("CALC_R01_EPOCH_INVALID");
    return value.longValue();
  }
  private void acknowledge(UUID eventId,String reason){
    int count=sql.update("""
        UPDATE fin_calc.calc_outbox_event
        SET processed_at=now(),attempts=attempts+1,last_error_code=?
        WHERE id=? AND processed_at IS NULL
        """,reason,eventId);
    if(count!=1)throw new IllegalStateException("CALC_R01_ACK_CONFLICT");
  }
}
