package kz.fin.backend.persistence;

import kz.fin.backend.analytics.AnalysisContext;
import kz.fin.backend.domain.EconomicKernel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class FactPublicationIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fin").withUsername("fin").withPassword("fin");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired FactAssertionJpaRepository repository;
    @Autowired FactSetPublicationService publicationService;
    @Autowired kz.fin.backend.execution.SingleExecutorScheduler scheduler;

    record Fixture(UUID org, UUID entity, UUID scope, UUID sourceId,
                   UUID factId, UUID revisionId, String factHash) {
        FactSetPublicationService.Request request(UUID expectedParent) {
            return new FactSetPublicationService.Request(org,entity,scope,
                EconomicKernel.ReportingBasis.MANAGEMENT,AnalysisContext.DataSpace.ACTUAL,
                revisionId,expectedParent);
        }
    }

    private Fixture fixture(UUID org,UUID entity,UUID scope,UUID parent,
                            boolean approvedSource,boolean validIdentity,boolean estimated) {
        UUID source=UUID.randomUUID(),fact=UUID.randomUUID(),revision=UUID.randomUUID();
        var time=EconomicKernel.TemporalReference.interval(
                LocalDate.of(2026,1,1),LocalDate.of(2026,2,1));
        var key=new EconomicKernel.FactKey(org,entity,scope,"REV_NET","1.0",time,
                EconomicKernel.ReportingBasis.MANAGEMENT,"MONEY","KZT",
                new TreeMap<>(Map.of("store","A")));
        var value=estimated?EconomicKernel.EconomicValue.estimated("100","ESTIMATE_V1")
                :EconomicKernel.EconomicValue.present("100");
        repository.saveAndFlush(FactAssertionJpaEntity.from(
                new EconomicKernel.FactAssertion(fact,key,value,source,
                Instant.parse("2026-11-15T00:00:00Z"))));
        if(approvedSource) jdbc.update("""
                INSERT INTO fin_fact.approved_source_revision(
                  organization_id,source_revision_id,form_code,form_version,approved_at)
                VALUES (?,?,?,?,now())
                """,org,source,"DF-SAL-01","1.0");
        if(parent==null) jdbc.update("""
                INSERT INTO fin_fact.fact_set_revision(id,organization_id,status,approved_at)
                VALUES (?,?,'APPROVED',now())
                """,revision,org);
        else jdbc.update("""
                INSERT INTO fin_fact.fact_set_revision(
                  id,organization_id,parent_revision_id,status,approved_at)
                VALUES (?,?,?,'APPROVED',now())
                """,revision,org,parent);
        String hash=jdbc.queryForObject("SELECT fin_fact.fact_identity_hash(?)",
                String.class,fact);
        assertNotNull(hash);
        jdbc.update("""
                INSERT INTO fin_fact.fact_selection(
                  organization_id,fact_set_revision_id,fact_key_hash,fact_assertion_id)
                VALUES (?,?,?,?)
                """,org,revision,validIdentity?hash:"wrong-key",fact);
        return new Fixture(org,entity,scope,source,fact,revision,hash);
    }

    private Fixture fixture() {
        return fixture(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
                null,true,true,false);
    }
    private void publish(Fixture f) { publicationService.publish(f.request(null)); }

    @Test void canonicalSourceAndScopeAreRequiredToPublish() {
        var f=fixture();
        var result=publicationService.publish(f.request(null));
        assertEquals(f.revisionId(),result.revisionId());
        assertEquals(64,result.snapshotHash().length());
        assertEquals(1,result.currentVersion());
        String actual=jdbc.queryForObject("""
            SELECT snapshot_hash FROM fin_fact.fact_set_publication
            WHERE fact_set_revision_id=? AND organization_id=?
            """,String.class,f.revisionId(),f.org());
        assertEquals(result.snapshotHash(),actual);
    }

    @Test void sourceAndPublicationAreImmutableAndSelectionsAreSealed() {
        var f=fixture();
        publish(f);
        assertThrows(DataAccessException.class,()->jdbc.update("""
            INSERT INTO fin_fact.fact_selection(
                organization_id,fact_set_revision_id,fact_key_hash,fact_assertion_id)
            VALUES (?,?,?,?)
            """,f.org(),f.revisionId(),"another-key",f.factId()));
        assertThrows(DataAccessException.class,()->jdbc.update("""
            DELETE FROM fin_fact.fact_set_publication WHERE fact_set_revision_id=?
            """,f.revisionId()));
        assertThrows(DataAccessException.class,()->jdbc.update("""
            UPDATE fin_fact.approved_source_revision SET form_version='2.0'
            WHERE organization_id=? AND source_revision_id=?
            """,f.org(),f.sourceId()));
    }

    @Test void rejectsMissingSourceAndWrongEconomicKeyHash() {
        UUID org=UUID.randomUUID(),entity=UUID.randomUUID(),scope=UUID.randomUUID();
        var noSource=fixture(org,entity,scope,null,false,true,false);
        assertThrows(DataAccessException.class,()->publish(noSource));
        var fakeKey=fixture(org,entity,scope,null,true,false,false);
        assertThrows(DataAccessException.class,()->publish(fakeKey));
        assertEquals(0L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_fact.fact_set_publication
            WHERE organization_id=?
            """,Long.class,org));
    }

    @Test void actualCannotPublishEstimatedFactsAndForeignScopeIsRejected() {
        var estimated=fixture(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
                null,true,true,true);
        assertThrows(DataAccessException.class,()->publish(estimated));
        var wrong=fixture();
        var request=new FactSetPublicationService.Request(wrong.org(),wrong.entity(),
                UUID.randomUUID(),EconomicKernel.ReportingBasis.MANAGEMENT,
                AnalysisContext.DataSpace.ACTUAL,wrong.revisionId(),null);
        assertThrows(DataAccessException.class,()->publicationService.publish(request));
    }

    @Test void onlyPublishedParentCanBeUsedAndCurrentPointerMustMatch() {
        UUID org=UUID.randomUUID(),entity=UUID.randomUUID(),scope=UUID.randomUUID();
        var first=fixture(org,entity,scope,null,true,true,false);
        var child=fixture(org,entity,scope,first.revisionId(),true,true,false);
        assertThrows(OptimisticLockingFailureException.class,
                ()->publicationService.publish(child.request(null)));
        assertThrows(DataAccessException.class,()->jdbc.update("""
            INSERT INTO fin_fact.fact_set_publication(
             fact_set_revision_id,organization_id,economic_entity_id,business_scope_id,
             reporting_basis,data_space,snapshot_hash) VALUES(?,?,?,?,?,?,?)
            """,child.revisionId(),org,entity,scope,"MANAGEMENT","ACTUAL","f".repeat(64)));
        assertEquals(1,publicationService.publish(first.request(null)).currentVersion());
        assertEquals(2,publicationService.publish(child.request(first.revisionId())).currentVersion());
        var stale=fixture(org,entity,scope,first.revisionId(),true,true,false);
        assertThrows(OptimisticLockingFailureException.class,
                ()->publicationService.publish(stale.request(first.revisionId())));
        assertEquals(child.revisionId(),jdbc.queryForObject("""
            SELECT fact_set_revision_id FROM fin_fact.current_fact_set
            WHERE organization_id=? AND economic_entity_id=? AND business_scope_id=?
            """,UUID.class,org,entity,scope));
    }

    @Test void forgedSnapshotDigestIsOverwrittenByDatabase() {
        var f=fixture();
        String fake="f".repeat(64);
        jdbc.update("""
            INSERT INTO fin_fact.fact_set_publication(
             fact_set_revision_id,organization_id,economic_entity_id,business_scope_id,
             reporting_basis,data_space,snapshot_hash) VALUES(?,?,?,?,?,?,?)
            """,f.revisionId(),f.org(),f.entity(),f.scope(),"MANAGEMENT","ACTUAL",fake);
        String calculated=jdbc.queryForObject("""
            SELECT snapshot_hash FROM fin_fact.fact_set_publication
            WHERE fact_set_revision_id=?""",String.class,f.revisionId());
        assertNotEquals(fake,calculated);
        assertEquals(64,calculated.length());
    }

    private UUID reportRun(Fixture f,int count) {
        UUID run=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO fin_calc.report_run(
              id,organization_id,fact_set_revision_id,report_code,report_version,
              method_version,input_fingerprint,expected_cell_count)
            VALUES(?,?,?,?,?,?,?,?)
            """,run,f.org(),f.revisionId(),"FIN_MONTHLY","v1","AUTO_INDICATORS_V1",
                "f".repeat(64),count);
        return run;
    }
    private void insertCell(Fixture f,UUID run,String code,int order,String state) {
        jdbc.update("""
            INSERT INTO fin_calc.report_cell(
              report_run_id,organization_id,column_code,display_order,translation_key,
              status,numeric_value,reason,unit_code,currency_code,method_version)
            VALUES(?,?,?,?,?,?,?,?,?,?,?)
            """,run,f.org(),code,order,"metric."+code,state,
                state.equals("CALCULATED")?new java.math.BigDecimal("100"):null,
                state.equals("CALCULATED")?null:"MISSING_INPUT","MONEY","KZT",
                "AUTO_INDICATORS_V1");
    }

    @Test void reportCanOnlyUsePublishedFactSetAndFingerprintIsDatabaseControlled() {
        var f=fixture();
        assertThrows(DataAccessException.class,()->reportRun(f,1));
        publish(f);
        UUID run=reportRun(f,1);
        String stored=jdbc.queryForObject("""
            SELECT input_fingerprint FROM fin_calc.report_run WHERE id=?
            """,String.class,run);
        assertNotEquals("f".repeat(64),stored);
        assertEquals(64,stored.length());
        assertThrows(DataAccessException.class,()->jdbc.update("""
            DELETE FROM fin_calc.report_run WHERE id=?""",run));
    }

    @Test void reportFinalizationRejectsIncompleteCellsAndPreventsLaterAppends() {
        var f=fixture();publish(f);
        UUID run=reportRun(f,2);
        insertCell(f,run,"REVENUE",100,"CALCULATED");
        assertThrows(DataAccessException.class,()->jdbc.update("""
            INSERT INTO fin_calc.report_publication(
                report_run_id,organization_id,cells_hash)
            VALUES(?,?,?)""",run,f.org(),"f".repeat(64)));
        insertCell(f,run,"COGS",200,"INCOMPLETE");
        jdbc.update("""
            INSERT INTO fin_calc.report_publication(report_run_id,organization_id,cells_hash)
            VALUES(?,?,?)""",run,f.org(),"f".repeat(64));
        String actual=jdbc.queryForObject("""
            SELECT cells_hash FROM fin_calc.report_publication
            WHERE report_run_id=?""",String.class,run);
        assertEquals(64,actual.length());
        assertNotEquals("f".repeat(64),actual);
        assertThrows(DataAccessException.class,()->insertCell(f,run,"EXTRA",300,"CALCULATED"));
        assertThrows(DataAccessException.class,()->jdbc.update("""
            UPDATE fin_calc.report_cell SET numeric_value=9
            WHERE report_run_id=? AND column_code='REVENUE'""",run));
    }

    @Test void duplicateCellOrderAndCrossTenantCellsAreRejected() {
        var f=fixture();publish(f);
        UUID run=reportRun(f,3);
        insertCell(f,run,"REVENUE",100,"CALCULATED");
        assertThrows(DataAccessException.class,()->insertCell(f,run,"COGS",100,"INCOMPLETE"));
        var stranger=new Fixture(UUID.randomUUID(),f.entity(),f.scope(),f.sourceId(),
                f.factId(),f.revisionId(),f.factHash());
        assertThrows(DataAccessException.class,()->insertCell(stranger,run,"COGS",200,"INCOMPLETE"));
        assertEquals(1L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_calc.report_cell WHERE report_run_id=?
            """,Long.class,run));
    }

    @Test void sameParentCannotWinTwoConcurrentPublications() throws Exception {
        UUID org=UUID.randomUUID(),entity=UUID.randomUUID(),scope=UUID.randomUUID();
        var base=fixture(org,entity,scope,null,true,true,false);
        publish(base);
        var a=fixture(org,entity,scope,base.revisionId(),true,true,false);
        var b=fixture(org,entity,scope,base.revisionId(),true,true,false);
        ExecutorService executor=Executors.newFixedThreadPool(2);
        CountDownLatch ready=new CountDownLatch(2), start=new CountDownLatch(1);
        try {
            var callA=executor.submit(()->attemptPublish(a,base.revisionId(),ready,start));
            var callB=executor.submit(()->attemptPublish(b,base.revisionId(),ready,start));
            assertTrue(ready.await(10,TimeUnit.SECONDS));
            start.countDown();
            boolean first=callA.get(20,TimeUnit.SECONDS);
            boolean second=callB.get(20,TimeUnit.SECONDS);
            assertNotEquals(first,second,"exactly one publish must win");
            assertEquals(1L,jdbc.queryForObject("""
                    SELECT count(*) FROM fin_fact.fact_set_publication
                    WHERE organization_id=? AND fact_set_revision_id IN (?,?)
                    """,Long.class,org,a.revisionId(),b.revisionId()));
        }finally{
            start.countDown();
            executor.shutdownNow();
        }
    }


    @Test void successfulPublicationCreatesSingleImmutableOutboxEvent() {
        var f=fixture();
        var published=publicationService.publish(f.request(null));
        var rows=jdbc.queryForList("""
            SELECT event_id,organization_id,fact_set_revision_id,event_type,
                   snapshot_hash,current_version
              FROM fin_exec.fact_publication_outbox
             WHERE organization_id=? AND fact_set_revision_id=?
            """, f.org(),f.revisionId());
        assertEquals(1,rows.size());
        var event=rows.getFirst();
        assertEquals(f.org(),event.get("organization_id"));
        assertEquals(f.revisionId(),event.get("fact_set_revision_id"));
        assertEquals("FACT_SET_PUBLISHED",event.get("event_type"));
        assertEquals(published.snapshotHash(),event.get("snapshot_hash"));
        assertEquals(1L,((Number)event.get("current_version")).longValue());
        assertThrows(DataAccessException.class,()->jdbc.update("""
                DELETE FROM fin_exec.fact_publication_outbox WHERE event_id=?
                """,event.get("event_id")));
    }

    @Test void rejectedPublicationDoesNotAppendOutboxEvent() {
        var f=fixture();
        assertThrows(OptimisticLockingFailureException.class,
            ()->publicationService.publish(f.request(UUID.randomUUID())));
        assertEquals(0L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_exec.fact_publication_outbox
             WHERE organization_id=? AND fact_set_revision_id=?
            """,Long.class,f.org(),f.revisionId()));
        assertEquals(0L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_fact.fact_set_publication
             WHERE organization_id=? AND fact_set_revision_id=?
            """,Long.class,f.org(),f.revisionId()));
    }

    @Test void outboxFailureRollsBackApprovedPublicationAndPointer() {
        var f=fixture();
        // A duplicate event prevents outbox append after the DB publication
        // and current pointer were already written within the same transaction.
        // The exception MUST roll back both earlier mutations.
        UUID eventId=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO fin_exec.fact_publication_outbox (
                event_id, organization_id, economic_entity_id, business_scope_id,
                fact_set_revision_id, event_type, reporting_basis, data_space,
                snapshot_hash, current_version)
            VALUES (?,?,?,?,?,'FACT_SET_PUBLISHED',?,?,?,?)
            """,eventId,f.org(),f.entity(),f.scope(),f.revisionId(),
               "MANAGEMENT","ACTUAL",f.factHash(),1L);
        assertThrows(DataAccessException.class,()->publicationService.publish(f.request(null)));
        assertEquals(0L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_fact.fact_set_publication
             WHERE organization_id=? AND fact_set_revision_id=?
            """,Long.class,f.org(),f.revisionId()));
        assertEquals(0L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_fact.current_fact_set
             WHERE organization_id=? AND economic_entity_id=? AND business_scope_id=?
            """,Long.class,f.org(),f.entity(),f.scope()));
        assertEquals(1L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_exec.fact_publication_outbox
             WHERE organization_id=? AND fact_set_revision_id=?
            """,Long.class,f.org(),f.revisionId()));
    }


    @Test void consumerSchedulesAndExecutesOneLogicalRunForPublishedRevision() {
        var f=fixture();
        publish(f);
        for(int i=0;i<150;i++) {
            scheduler.tick();
            Long complete=jdbc.queryForObject("""
                SELECT count(*) FROM fin_exec.analysis_run
                 WHERE organization_id=? AND fact_set_revision_id=?
                   AND run_status='COMPLETED'
                """,Long.class,f.org(),f.revisionId());
            if(complete==1L) break;
        }
        assertEquals(1L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_exec.analysis_run
             WHERE organization_id=? AND fact_set_revision_id=?
            """,Long.class,f.org(),f.revisionId()));
        assertEquals(1L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_exec.analysis_task t
            JOIN fin_exec.analysis_run r ON r.id=t.run_id
            WHERE r.organization_id=? AND r.fact_set_revision_id=?
              AND t.method_code='SYS/VERIFY_PUBLISHED_FACT_SET'
              AND t.task_status='COMPUTED'
            """,Long.class,f.org(),f.revisionId()));
        assertEquals(1L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_exec.outbox_delivery d
            JOIN fin_exec.fact_publication_outbox o ON o.event_id=d.event_id
            WHERE o.organization_id=? AND o.fact_set_revision_id=?
            """,Long.class,f.org(),f.revisionId()));
        // Redelivery/polling must not create a duplicate logical run.
        scheduler.tick();
        assertEquals(1L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_exec.analysis_run
             WHERE organization_id=? AND fact_set_revision_id=?
            """,Long.class,f.org(),f.revisionId()));
    }

    @Test void crashedWorkerLeaseIsRecoveredWithFreshFencingToken() {
        var f=fixture();
        publish(f);
        UUID event=jdbc.queryForObject("""
            SELECT event_id FROM fin_exec.fact_publication_outbox
             WHERE organization_id=? AND fact_set_revision_id=?
            """,UUID.class,f.org(),f.revisionId());
        String hash=jdbc.queryForObject("""
            SELECT snapshot_hash FROM fin_fact.fact_set_publication
             WHERE organization_id=? AND fact_set_revision_id=?
            """,String.class,f.org(),f.revisionId());
        UUID run=UUID.randomUUID(), task=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO fin_exec.analysis_run
              (id,event_id,organization_id,fact_set_revision_id,snapshot_hash,profile_code,run_status)
            VALUES (?,?,?,?,?,'AUTO_CORE_V1','RUNNING')
            """,run,event,f.org(),f.revisionId(),hash);
        jdbc.update("""
            INSERT INTO fin_exec.outbox_delivery(event_id,analysis_run_id) VALUES (?,?)
            """,event,run);
        jdbc.update("""
            INSERT INTO fin_exec.analysis_task
              (id,run_id,method_code,task_status,attempt,fencing_token,lease_until)
            VALUES (?,?,'SYS/VERIFY_PUBLISHED_FACT_SET','RUNNING',1,1,now()-interval '5 seconds')
            """,task,run);
        for(int i=0;i<150;i++) {
            scheduler.tick();
            String state=jdbc.queryForObject("""
                SELECT task_status FROM fin_exec.analysis_task WHERE id=?
                """,String.class,task);
            if("COMPUTED".equals(state)) break;
        }
        assertEquals("COMPUTED",jdbc.queryForObject("""
            SELECT task_status FROM fin_exec.analysis_task WHERE id=?
            """,String.class,task));
        assertEquals(2L,jdbc.queryForObject("""
            SELECT fencing_token FROM fin_exec.analysis_task WHERE id=?
            """,Long.class,task));
        assertEquals("COMPLETED",jdbc.queryForObject("""
            SELECT run_status FROM fin_exec.analysis_run WHERE id=?
            """,String.class,run));
        // A late/stale attempt cannot overwrite already committed output.
        assertEquals(0,jdbc.update("""
            UPDATE fin_exec.analysis_task SET result_json='{}'::jsonb
             WHERE id=? AND task_status='RUNNING' AND fencing_token=1
            """,task));
    }

    private boolean attemptPublish(Fixture candidate,UUID expected,
                                   CountDownLatch ready,CountDownLatch start) {
        ready.countDown();
        try {
            if(!start.await(15,TimeUnit.SECONDS)) return false;
            publicationService.publish(candidate.request(expected));
            return true;
        }catch(OptimisticLockingFailureException e){
            return false;
        }catch(InterruptedException e){
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
