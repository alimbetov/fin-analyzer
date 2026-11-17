package kz.fin.backend.intake;

import kz.fin.backend.analytics.AnalysisContext;
import kz.fin.backend.domain.EconomicKernel;
import kz.fin.backend.persistence.FactSetPublicationService;
import kz.fin.backend.execution.HtapV12Fin01BlockedPlanService;
import kz.fin.backend.execution.HtapFin01AdmissionPreflight;
import kz.fin.backend.execution.HtapFin01EvidenceBindingService;
import kz.fin.backend.execution.HtapRuntimeClassDigest;
import kz.fin.backend.execution.MethodTaskAdapter;
import kz.fin.backend.methodology.Fin01RevenueMomSqlModule;
import java.math.RoundingMode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HTAP-V1.1 is a real published, source-population-verified, same-revision
 * two-month financial vertical. It does NOT calculate MoM or issue a permit.
 */
@SpringBootTest(properties="fin.execution.enabled=false")
@Testcontainers
class HtapV11TwoMonthFinancialSourcePostgresTest {
    @Container
    static final PostgreSQLContainer<?> db=new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fin").withUsername("fin").withPassword("fin");
    @DynamicPropertySource
    static void props(DynamicPropertyRegistry props) {
        props.add("spring.datasource.url",db::getJdbcUrl);
        props.add("spring.datasource.username",db::getUsername);
        props.add("spring.datasource.password",db::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired FactSetPublicationService publications;
    @Autowired HtapMappingGovernanceService governance;
    @Autowired HtapV11MonthlyRevenueSourceService sources;
    @Autowired HtapV12Fin01BlockedPlanService fin01Plans;
    @Autowired HtapFin01AdmissionPreflight v13Preflight;
    @Autowired HtapFin01EvidenceBindingService evidenceBindings;
    @Autowired Fin01RevenueMomSqlModule fin01Sql;

    private record Fixture(UUID org, UUID entity, UUID scope, UUID source,
                           UUID factSet, UUID mapping, String snapshotSha) {}

    private Jwt token(UUID org,String subject,String...permissions) {
        return Jwt.withTokenValue("trusted-test-jwt").header("alg","RS256")
                .subject(subject)
                .issuedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .expiresAt(Instant.parse("2030-01-01T00:00:00Z"))
                .claim("fin_access",List.of(Map.of("organizationId",org.toString(),
                        "permissions",List.of(permissions))))
                .build();
    }

    private Fixture publishedTwoMonths(String febCurrency,boolean missingFebruary,
                                       boolean wrongFebruaryEntity) {
        return publishedTwoMonths(febCurrency,missingFebruary,wrongFebruaryEntity,
                new BigDecimal("100.00"),new BigDecimal("120.00"));
    }
    private Fixture publishedTwoMonths(String febCurrency,boolean missingFebruary,
                                       boolean wrongFebruaryEntity,
                                       BigDecimal january,BigDecimal february) {
        UUID org=UUID.randomUUID(),entity=UUID.randomUUID(),scope=UUID.randomUUID(),
                source=UUID.randomUUID(),factSet=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO fin_calc.metric_contract(
                metric_code,metric_version,measure_kind,unit_code,qualification,method_ref)
            VALUES ('REV_NET','1.0.0','FLOW','MONEY','QUALIFIED','HTAP_V11_SYNTHETIC')
            ON CONFLICT (metric_code,metric_version) DO NOTHING
            """);
        jdbc.update("""
            INSERT INTO fin_fact.approved_source_revision(
                organization_id,source_revision_id,form_code,form_version,approved_at)
            VALUES (?,?,'F02','FIN-PILOT-V1',now())
            """,org,source);
        for(int month=1;month<=2;month++) {
            if(month==2 && missingFebruary)continue;
            LocalDate start=LocalDate.of(2026,month,1),end=start.plusMonths(1);
            UUID fact=UUID.randomUUID();
            BigDecimal amount=month==1?january:february;
            jdbc.update("""
                INSERT INTO fin_fact.fact_assertion(
                    id,organization_id,economic_entity_id,business_scope_id,
                    metric_code,metric_version,time_kind,start_inclusive,end_exclusive,
                    reporting_basis,unit_code,currency_code,dimension_hash,dimensions,
                    value_state,numeric_value,estimate_method_ref,source_revision_id,recorded_at)
                VALUES (?,?,?,?,'REV_NET','1.0.0','INTERVAL',?,?,
                    'MANAGEMENT','MONEY',?,
                    encode(sha256(convert_to('{}'::jsonb::text,'UTF8')),'hex'),
                    '{}'::jsonb,'PRESENT',?,NULL,?,now())
                """,fact,org,wrongFebruaryEntity && month==2?UUID.randomUUID():entity,
                    scope,start,end,month==1?"KZT":febCurrency,amount,source);
            jdbc.update("""
                INSERT INTO fin_fact.fact_origin(
                    fact_assertion_id,organization_id,source_revision_id,source_row_id,source_field)
                VALUES (?,?,?,?, 'netRevenue')
                """,fact,org,source,"net-revenue-"+month);
            jdbc.update("""
                INSERT INTO fin_fact.source_population_control(
                    organization_id,source_revision_id,metric_code,time_start,time_end,
                    expected_rows,expected_total,source_document_hash)
                VALUES (?,?,'REV_NET',?,?,1,?,?)
                """,org,source,start,end,amount,"c".repeat(64));
            if(month==1) {
                jdbc.update("""
                    INSERT INTO fin_fact.fact_set_revision(
                        id,organization_id,status,approved_at)
                    VALUES (?,?,'APPROVED',now())
                    """,factSet,org);
            }
            String identity=jdbc.queryForObject(
                    "SELECT fin_fact.fact_identity_hash(?)",String.class,fact);
            jdbc.update("""
                INSERT INTO fin_fact.fact_selection(
                    organization_id,fact_set_revision_id,fact_key_hash,fact_assertion_id)
                VALUES (?,?,?,?)
                """,org,factSet,identity,fact);
        }

        UUID mapping=governance.propose(org,"F02","FIN-PILOT-V1","HTAP-V11",
            "{\"sourceField\":\"netRevenue\",\"metricCode\":\"REV_NET\",\"metricVersion\":\"1.0.0\"}",
            token(org,"author","FIN_SUBMIT"));
        governance.review(org,mapping,true,"d".repeat(64),
                token(org,"reviewer","FIN_REVIEW"));
        governance.validatePilotSemantics(org,mapping,token(org,"reviewer","FIN_REVIEW"));
        assertEquals(1L,governance.activate(org,"F02","FIN-PILOT-V1",
                mapping,0,token(org,"publisher","FIN_PUBLISH")));
        var published=publications.publish(new FactSetPublicationService.Request(
                org,entity,scope,EconomicKernel.ReportingBasis.MANAGEMENT,
                AnalysisContext.DataSpace.SYNTHETIC,factSet,null));
        return new Fixture(org,entity,scope,source,factSet,mapping,published.snapshotHash());
    }

    private HtapV11MonthlyRevenueSourceService.VerifiedPair resolve(Fixture f,Jwt jwt) {
        return sources.verify(f.org(),f.entity(),f.scope(),f.factSet(),f.snapshotSha(),
                f.source(),f.mapping(),1,YearMonth.of(2026,1),YearMonth.of(2026,2),jwt);
    }

    @Test void exactPublishedSourceJanuaryFebruaryHasTwoImmutableOriginRows() {
        Fixture f=publishedTwoMonths("KZT",false,false);
        Jwt reader=token(f.org(),"reader","FIN_READ","FIN_ANALYZE");
        var result=resolve(f,reader);
        assertEquals(result,resolve(f,reader),"Read-only source selection must be deterministic");
        assertEquals(0,result.previous().netRevenue().compareTo(new BigDecimal("100.00")));
        assertEquals(0,result.current().netRevenue().compareTo(new BigDecimal("120.00")));
        assertEquals("KZT",result.previous().currency());
        assertNotEquals(result.previous().factId(),result.current().factId());
        assertEquals("ISO_MONTH_FLOW_PERIOD_ONLY_V1",result.periodPolicyCode());
        assertFalse(result.executionPermitted());
        assertEquals(2L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_fact.fact_origin
            WHERE organization_id=? AND source_revision_id=? AND source_field='netRevenue'
            """,Long.class,f.org(),f.source()));
        assertEquals(2L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_fact.source_population_control
            WHERE organization_id=? AND source_revision_id=? AND metric_code='REV_NET'
            """,Long.class,f.org(),f.source()));
        assertEquals(0L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_exec.analysis_run WHERE organization_id=?
            """,Long.class,f.org()));
        assertEquals(0L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_calc.report_run WHERE organization_id=?
            """,Long.class,f.org()));

        assertThrows(AccessDeniedException.class,
                ()->resolve(f,token(UUID.randomUUID(),"foreign","FIN_READ","FIN_ANALYZE")));
        assertThrows(IllegalArgumentException.class,
                ()->sources.verify(f.org(),f.entity(),f.scope(),f.factSet(),
                    "a".repeat(64),f.source(),f.mapping(),1,YearMonth.of(2026,1),
                    YearMonth.of(2026,2),reader));
        assertThrows(IllegalArgumentException.class,
                ()->sources.verify(f.org(),f.entity(),f.scope(),f.factSet(),
                    f.snapshotSha(),f.source(),f.mapping(),2,YearMonth.of(2026,1),
                    YearMonth.of(2026,2),reader));
        assertThrows(IllegalArgumentException.class,
                ()->sources.verify(f.org(),f.entity(),f.scope(),f.factSet(),
                    f.snapshotSha(),f.source(),f.mapping(),1,YearMonth.of(2026,1),
                    YearMonth.of(2026,3),reader));
    }

    @Test void missingMonthIsNeverTreatedAsPreviousAvailablePeriod() {
        Fixture f=publishedTwoMonths("KZT",true,false);
        assertThrows(IllegalArgumentException.class,
                ()->resolve(f,token(f.org(),"reader","FIN_READ","FIN_ANALYZE")));
    }

    @Test void mixedCurrencyCannotBecomeFinancialMoMSource() {
        Fixture f=publishedTwoMonths("USD",false,false);
        assertThrows(IllegalArgumentException.class,
                ()->resolve(f,token(f.org(),"reader","FIN_READ","FIN_ANALYZE")));
    }

    @Test void v12PinsTwoExactPublishedMonthsAndCommitsOnlyBlockedPlanIdempotently() {
        Fixture f=publishedTwoMonths("KZT",false,false);
        Jwt reader=token(f.org(),"planner","FIN_READ","FIN_ANALYZE");
        var req=new HtapV12Fin01BlockedPlanService.Request(f.org(),f.entity(),
                f.scope(),f.factSet(),f.snapshotSha(),f.source(),f.mapping(),1,
                YearMonth.of(2026,1),YearMonth.of(2026,2));
        var preview=fin01Plans.preview(req,reader);
        assertEquals(preview,fin01Plans.preview(req,reader));
        assertFalse(preview.executionPermitted());
        assertEquals("REV_NET",preview.previousSlice().metricCode());
        assertEquals(f.factSet(),preview.pair().factSetRevisionId());
        assertEquals(64,preview.algorithmClassSha256().length());
        assertTrue(preview.reasons().contains("FIN_HTAP_BUILD_ARTIFACT_NOT_ATTESTED"));
        assertTrue(preview.reasons().contains("FIN_HTAP_INDEPENDENT_METHOD_S2_PENDING"));

        var committed=fin01Plans.commitBlocked(req,reader);
        assertEquals(committed,fin01Plans.commitBlocked(req,reader));
        assertFalse(committed.executionPermitted());
        assertEquals("BLOCKED_PLAN_ONLY",committed.state());
        assertEquals(1L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_exec.htap_fin01_two_month_blocked_plan
            WHERE organization_id=? AND fact_set_revision_id=?
              AND previous_fact_id<>current_fact_id
              AND previous_month=DATE '2026-01-01'
              AND current_month=DATE '2026-02-01'
              AND catalog_epoch=1 AND currency_code='KZT'
              AND NOT execution_permitted
            """,Long.class,f.org(),f.factSet()));
        assertEquals(1L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_exec.htap_blocked_method_plan
            WHERE organization_id=? AND plan_state='BLOCKED_PLAN_ONLY'
            """,Long.class,f.org()));
        assertThrows(org.springframework.dao.DataAccessException.class,
                ()->jdbc.update("""
                    UPDATE fin_exec.htap_fin01_two_month_blocked_plan
                    SET execution_permitted=true WHERE id=?
                    """,committed.id()));
        assertThrows(org.springframework.dao.DataAccessException.class,
                ()->jdbc.update("""
                    DELETE FROM fin_exec.htap_fin01_two_month_blocked_plan WHERE id=?
                    """,committed.id()));
        assertEquals(0L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_exec.analysis_run WHERE organization_id=?
            """,Long.class,f.org()));
        assertEquals(0L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_calc.report_run WHERE organization_id=?
            """,Long.class,f.org()));

        assertThrows(IllegalArgumentException.class,()->fin01Plans.preview(
                new HtapV12Fin01BlockedPlanService.Request(f.org(),f.entity(),
                    f.scope(),f.factSet(),f.snapshotSha(),f.source(),f.mapping(),2,
                    YearMonth.of(2026,1),YearMonth.of(2026,2)),reader));
        assertThrows(AccessDeniedException.class,()->fin01Plans.preview(req,
                token(UUID.randomUUID(),"foreign","FIN_READ","FIN_ANALYZE")));
    }

    @Test void v13SyntheticResearchSqlMatchesIndependentJavaGoldenWithoutPublishing() {
        Fixture f=publishedTwoMonths("KZT",false,false);
        Jwt reader=token(f.org(),"researcher","FIN_READ","FIN_ANALYZE");
        var verified=resolve(f,reader);
        var planRequest=new HtapV12Fin01BlockedPlanService.Request(
                f.org(),f.entity(),f.scope(),f.factSet(),f.snapshotSha(),
                f.source(),f.mapping(),1,YearMonth.of(2026,1),YearMonth.of(2026,2));
        var committed=fin01Plans.commitBlocked(planRequest,reader);
        assertFalse(committed.executionPermitted());

        // Independent plain-Java golden. This is intentionally TEST-ONLY and
        // cannot issue a runtime ExecutionPermit, task or immutable result.
        BigDecimal expected=verified.current().netRevenue()
                .subtract(verified.previous().netRevenue())
                .multiply(new BigDecimal("100"))
                .divide(verified.previous().netRevenue(),12,RoundingMode.HALF_EVEN);
        assertEquals(0,expected.compareTo(new BigDecimal("20")));

        var actual=fin01Sql.calculate(new MethodTaskAdapter.TrustedRevision(
                f.org(),f.entity(),f.scope(),f.factSet(),f.snapshotSha(),"SYNTHETIC"));
        assertEquals("CALCULATED",actual.computationStatus());
        assertEquals("PCT",actual.unitCode());
        assertEquals(0,expected.compareTo(actual.value()));
        assertEquals(2,actual.provenance().size());
        assertEquals(
                java.util.Set.of(verified.previous().factId(),verified.current().factId()),
                actual.provenance().stream().map(p->p.assertionId())
                        .collect(java.util.stream.Collectors.toSet()));
        assertFalse(actual.executionQualified());
        assertEquals(0L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_exec.analysis_run WHERE organization_id=?
            """,Long.class,f.org()));
        assertEquals(0L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_calc.report_run WHERE organization_id=?
            """,Long.class,f.org()));
        assertEquals(1L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_exec.htap_fin01_two_month_blocked_plan
            WHERE organization_id=? AND NOT execution_permitted
            """,Long.class,f.org()));
        assertThrows(IllegalStateException.class,()->fin01Sql.calculate(
                new MethodTaskAdapter.TrustedRevision(f.org(),f.entity(),
                    f.scope(),f.factSet(),f.snapshotSha(),"ACTUAL")));
    }

    @Test void v13SyntheticResearchDoesNotMisclassifyMissingMonthOrCurrency() {
        Fixture missing=publishedTwoMonths("KZT",true,false);
        assertThrows(IllegalStateException.class,()->fin01Sql.calculate(
                new MethodTaskAdapter.TrustedRevision(missing.org(),missing.entity(),
                        missing.scope(),missing.factSet(),missing.snapshotSha(),"SYNTHETIC")));
        Fixture mixed=publishedTwoMonths("USD",false,false);
        assertThrows(IllegalStateException.class,()->fin01Sql.calculate(
                new MethodTaskAdapter.TrustedRevision(mixed.org(),mixed.entity(),
                        mixed.scope(),mixed.factSet(),mixed.snapshotSha(),"SYNTHETIC")));
    }

    @Test void v13GoldenZeroBaselineIsNotComputableAndNeverReportedAsZeroPercent() {
        Fixture f=publishedTwoMonths("KZT",false,false,
                BigDecimal.ZERO,new BigDecimal("120.00"));
        var result=fin01Sql.calculate(new MethodTaskAdapter.TrustedRevision(
                f.org(),f.entity(),f.scope(),f.factSet(),f.snapshotSha(),"SYNTHETIC"));
        assertEquals("NOT_COMPUTABLE",result.computationStatus());
        assertEquals("ZERO_BASELINE",result.reasonCode());
        assertNull(result.value());
        assertFalse(result.executionQualified());
        assertEquals(0L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_exec.analysis_run WHERE organization_id=?
            """,Long.class,f.org()));
    }

    @Test void v13GoldenNegativeBaselineDoesNotInventComparableGrowthRate() {
        Fixture f=publishedTwoMonths("KZT",false,false,
                new BigDecimal("-100.00"),new BigDecimal("120.00"));
        var result=fin01Sql.calculate(new MethodTaskAdapter.TrustedRevision(
                f.org(),f.entity(),f.scope(),f.factSet(),f.snapshotSha(),"SYNTHETIC"));
        assertEquals("NOT_COMPUTABLE",result.computationStatus());
        assertEquals("NEGATIVE_BASELINE_PERCENT_UNDEFINED",result.reasonCode());
        assertNull(result.value());
        assertFalse(result.executionQualified());
    }

    @Test void v13RepeatingDecimalMatchesIndependentHalfEvenJavaAtTwelvePlaces() {
        Fixture f=publishedTwoMonths("KZT",false,false,
                new BigDecimal("3.00"),new BigDecimal("4.00"));
        var result=fin01Sql.calculate(new MethodTaskAdapter.TrustedRevision(
                f.org(),f.entity(),f.scope(),f.factSet(),f.snapshotSha(),"SYNTHETIC"));
        BigDecimal expected=new BigDecimal("1.00").multiply(new BigDecimal("100"))
                .divide(new BigDecimal("3.00"),12,RoundingMode.HALF_EVEN);
        assertEquals("CALCULATED",result.computationStatus());
        assertEquals(0,expected.compareTo(result.value()));
        assertEquals(new BigDecimal("33.333333333333"),expected);
        assertFalse(result.executionQualified());
    }

    @Test void v13PreflightReadsExactBlockedPlanAndNeverIssuesExecutionPermit() {
        Fixture f=publishedTwoMonths("KZT",false,false);
        Jwt planner=token(f.org(),"planner","FIN_READ","FIN_ANALYZE");
        var request=new HtapV12Fin01BlockedPlanService.Request(
                f.org(),f.entity(),f.scope(),f.factSet(),f.snapshotSha(),
                f.source(),f.mapping(),1,YearMonth.of(2026,1),YearMonth.of(2026,2));
        var committed=fin01Plans.commitBlocked(request,planner);
        var preflight=v13Preflight.inspect(f.org(),committed.id(),planner);
        assertEquals(HtapFin01AdmissionPreflight.State.HOLD,preflight.state());
        assertEquals(preflight,v13Preflight.inspect(f.org(),committed.id(),planner));
        assertEquals(committed.sha256(),preflight.storedPlanSha256());
        assertFalse(preflight.executionPermitted());
        assertTrue(preflight.reasons().contains(
                "FIN_HTAP_V13_METHOD_CONFIGURATION_FINANCIAL_QUALIFICATION_PENDING"));
        assertTrue(preflight.reasons().contains(
                "FIN_HTAP_V13_DEPLOYED_ARTIFACT_ATTESTATION_MISSING"));
        assertTrue(preflight.reasons().contains(
                "FIN_HTAP_V13_DURABLE_EXECUTION_PERMIT_NOT_ISSUED"));
        assertTrue(preflight.reasons().contains("FIN_HTAP_V13_LEASE_FENCE_NOT_AUTHORIZED"));
        assertEquals(HtapFin01AdmissionPreflight.State.REJECTED,
                v13Preflight.inspect(f.org(),UUID.randomUUID(),planner).state());
        assertThrows(AccessDeniedException.class,()->v13Preflight.inspect(
                f.org(),committed.id(),token(UUID.randomUUID(),"foreign","FIN_READ","FIN_ANALYZE")));
        assertEquals(0L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_exec.analysis_run WHERE organization_id=?
            """,Long.class,f.org()));
        assertEquals(0L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_calc.report_run WHERE organization_id=?
            """,Long.class,f.org()));
    }

    @Test void v13PreflightInvalidatesPriorPlanAfterEditorialMappingEpochAdvances() {
        Fixture f=publishedTwoMonths("KZT",false,false);
        Jwt planner=token(f.org(),"planner","FIN_READ","FIN_ANALYZE");
        var request=new HtapV12Fin01BlockedPlanService.Request(
                f.org(),f.entity(),f.scope(),f.factSet(),f.snapshotSha(),
                f.source(),f.mapping(),1,YearMonth.of(2026,1),YearMonth.of(2026,2));
        var committed=fin01Plans.commitBlocked(request,planner);
        assertFalse(v13Preflight.inspect(f.org(),committed.id(),planner)
                .reasons().contains("FIN_HTAP_V13_MAPPING_EPOCH_OR_ACTIVE_VERSION_STALE"));
        UUID replacement=governance.propose(f.org(),"F02","FIN-PILOT-V1","HTAP-V13-REPLACEMENT",
                "{\"sourceField\":\"netRevenue\",\"metricCode\":\"REV_NET\",\"metricVersion\":\"1.0.0\"}",
                token(f.org(),"second-author","FIN_SUBMIT"));
        governance.review(f.org(),replacement,true,"e".repeat(64),
                token(f.org(),"second-reviewer","FIN_REVIEW"));
        assertEquals(2L,governance.activate(f.org(),"F02","FIN-PILOT-V1",
                replacement,1,token(f.org(),"second-publisher","FIN_PUBLISH")));
        var stale=v13Preflight.inspect(f.org(),committed.id(),planner);
        assertEquals(HtapFin01AdmissionPreflight.State.HOLD,stale.state());
        assertTrue(stale.reasons().contains(
                "FIN_HTAP_V13_MAPPING_EPOCH_OR_ACTIVE_VERSION_STALE"));
        assertFalse(stale.executionPermitted());
    }

    @Test void v132ImmutableEvidenceBindingRejectsTamperingStaleConfigAndRevokedS2() throws Exception {
        Fixture f=publishedTwoMonths("KZT",false,false);
        Jwt planner=token(f.org(),"binder","FIN_READ","FIN_ANALYZE");
        var planRequest=new HtapV12Fin01BlockedPlanService.Request(f.org(),f.entity(),
                f.scope(),f.factSet(),f.snapshotSha(),f.source(),f.mapping(),1,
                YearMonth.of(2026,1),YearMonth.of(2026,2));
        var plan=fin01Plans.commitBlocked(planRequest,planner);
        String classSha=HtapRuntimeClassDigest.inspect(Fin01RevenueMomSqlModule.class)
                .classSha256();
        UUID contract=UUID.randomUUID(), config=UUID.randomUUID(), review=UUID.randomUUID();
        String policy="""
            {"baselinePolicy":"POSITIVE_ONLY","roundingMode":"HALF_EVEN",
             "percentageScale":12,"periodPolicy":"ISO_MONTH_FLOW_PERIOD_ONLY_V1"}
            """;
        String contractVersion="FIN01-TEST-"+contract;
        jdbc.update("""
            INSERT INTO fin_calc.method_parameter_contract(
                contract_id,method_code,variant_code,contract_version,
                validation_profile,parameter_schema,schema_sha256,authored_by)
            VALUES (?,'FIN-01','REV_NET_MOM',?,'FIN01_MONTHLY_MOM_TYPED_V1',
                    fin_calc.fin01_monthly_mom_typed_schema_v1(),?,'config-author')
            """,contract,contractVersion,"0".repeat(64));
        jdbc.update("""
            INSERT INTO fin_calc.method_config_version(
                config_id,method_code,variant_code,version_no,contract_id,engine,
                algorithm_id,algorithm_version,algorithm_sha256,parameters,
                params_sha256,effective_sha256,created_by)
            VALUES (?,'FIN-01','REV_NET_MOM',1,?,'SQL',
                'FIN-01/REV_NET_MOM','R2_SQL_PILOT_1',?,?::jsonb,?,?,'config-author')
            """,config,contract,classSha,policy,"0".repeat(64),"0".repeat(64));
        jdbc.update("""
            INSERT INTO fin_calc.method_config_review(
                review_id,config_id,submitted_by,reviewed_by,decision,
                evidence_sha256,review_reason)
            VALUES (?,?,'config-author','config-reviewer','APPROVED',?,'test-only editorial approval')
            """,UUID.randomUUID(),config,"1".repeat(64));
        jdbc.update("""
            INSERT INTO fin_calc.method_active_config(
                method_code,variant_code,config_id,pointer_version,activated_by)
            VALUES ('FIN-01','REV_NET_MOM',?,1,'test-publisher')
            """,config);

        // These approvals are synthetic TEST FIXTURES, NOT production S2.
        jdbc.update("""
            INSERT INTO fin_calc.method_s2_review(
                id,method_code,method_version,form_code,form_version,reporting_basis,
                semantic_contract_sha,golden_cases_sha,review_evidence_sha,
                submitted_by,reviewed_by,review_decision,review_reason,
                effective_from,effective_until)
            VALUES (?,'FIN-01/REV_NET_MOM','R2_SQL_PILOT_1',
                'F02','FIN-PILOT-V1','MANAGEMENT',?,?,?,
                'test-submitter','test-independent-s2','APPROVED',
                'test-only synthetic S2 ledger',now()-interval '1 day',now()+interval '1 day')
            """,review,"2".repeat(64),"3".repeat(64),"4".repeat(64));

        String artifact="a".repeat(64),lock="b".repeat(64);
        String manifest="""
            {"methodCode":"FIN-01/REV_NET_MOM","algorithmVersion":"R2_SQL_PILOT_1",
              "artifactSha256":"%s","dependencyLockSha256":"%s",
              "implementationClassSha256":"%s",
              "dependencies":[{"component":"postgresql","sha256":"%s"}]}
            """.formatted(artifact,lock,classSha,"c".repeat(64));
        var evidence=new HtapFin01EvidenceBindingService.Evidence(
                plan.id(),config,review,artifact,lock,manifest,
                "test-untrusted-key","A".repeat(88));
        var bound=evidenceBindings.bindUnverified(f.org(),evidence,planner);
        assertEquals(bound,evidenceBindings.bindUnverified(f.org(),evidence,planner));
        assertEquals("EVIDENCE_BOUND_HOLD",bound.state());
        assertFalse(bound.executionPermitted());
        assertEquals(64,bound.bindingSha256().length());
        assertThrows(IllegalStateException.class,()->evidenceBindings.bindUnverified(
                f.org(),new HtapFin01EvidenceBindingService.Evidence(
                    plan.id(),config,review,"f".repeat(64),lock,manifest,
                    "test-untrusted-key","A".repeat(88)),planner));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("""
            UPDATE fin_exec.htap_fin01_evidence_binding
            SET execution_permitted=true WHERE id=?
            """,bound.id()));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("""
            DELETE FROM fin_exec.htap_fin01_evidence_binding WHERE id=?
            """,bound.id()));
        assertThrows(AccessDeniedException.class,()->evidenceBindings.inspect(
                f.org(),bound.id(),token(UUID.randomUUID(),"other","FIN_READ","FIN_ANALYZE")));
        var inspected=evidenceBindings.inspect(f.org(),bound.id(),planner);
        assertTrue(inspected.reasons().contains("FIN01_BINDING_ARTIFACT_SIGNATURE_AND_INSTALLED_BYTES_UNVERIFIED"));
        assertFalse(inspected.executionPermitted());

        jdbc.update("""
            INSERT INTO fin_calc.method_s2_revocation(
                id,review_id,revoked_by,reason,evidence_sha)
            VALUES (?,?,'test-independent-s2','test revocation',?)
            """,UUID.randomUUID(),review,"5".repeat(64));
        assertTrue(evidenceBindings.inspect(f.org(),bound.id(),planner)
                .reasons().contains("FIN01_BINDING_S2_REVOKED_OR_EXPIRED"));

        UUID newConfig=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO fin_calc.method_config_version(
                config_id,method_code,variant_code,version_no,parent_config_id,
                contract_id,engine,algorithm_id,algorithm_version,
                algorithm_sha256,parameters,params_sha256,effective_sha256,created_by)
            VALUES (?,'FIN-01','REV_NET_MOM',2,?,?,'SQL','FIN-01/REV_NET_MOM',
                'R2_SQL_PILOT_1',?,?::jsonb,?,?,'new-config-author')
            """,newConfig,config,contract,classSha,policy,"0".repeat(64),"0".repeat(64));
        jdbc.update("""
            INSERT INTO fin_calc.method_config_review(
                review_id,config_id,submitted_by,reviewed_by,decision,
                evidence_sha256,review_reason)
            VALUES (?,?,'new-config-author','another-reviewer','APPROVED',?,
                'test-only replacement')
            """,UUID.randomUUID(),newConfig,"6".repeat(64));
        jdbc.update("""
            UPDATE fin_calc.method_active_config
            SET config_id=?,pointer_version=2,activated_by='next-publisher'
            WHERE method_code='FIN-01' AND variant_code='REV_NET_MOM'
            """,newConfig);
        assertTrue(evidenceBindings.inspect(f.org(),bound.id(),planner)
                .reasons().contains("FIN01_BINDING_ACTIVE_CONFIG_CHANGED"));
        assertEquals(0L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_exec.analysis_run WHERE organization_id=?
            """,Long.class,f.org()));
        assertEquals(0L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_calc.report_run WHERE organization_id=?
            """,Long.class,f.org()));
    }

}
