package kz.fin.backend.persistence;

import kz.fin.backend.domain.EconomicKernel;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.Sheet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class PilotXlsxIntakeIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fin").withUsername("fin").withPassword("fin");
    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",postgres::getJdbcUrl);
        r.add("spring.datasource.username",postgres::getUsername);
        r.add("spring.datasource.password",postgres::getPassword);
    }
    @Autowired PilotXlsxTemplateService xlsx;
    @Autowired ReviewedFinancialIntakeService intake;
    @Autowired JdbcTemplate jdbc;
    private static PilotXlsxTemplateService.Context context(String form) {
        return new PilotXlsxTemplateService.Context(
                UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
                form,"MANAGEMENT","KZT",1);
    }
    private static byte[] edit(byte[] input,java.util.function.Consumer<XSSFWorkbook> change){
        try(XSSFWorkbook workbook=new XSSFWorkbook(new ByteArrayInputStream(input));
            ByteArrayOutputStream output=new ByteArrayOutputStream()){
            change.accept(workbook);
            workbook.write(output);
            return output.toByteArray();
        }catch(IOException e){throw new IllegalStateException(e);}
    }
    private static byte[] completeF02(byte[] blank){
        return edit(blank,workbook->{
            Sheet data=workbook.getSheet("FIN_Data");
            double[] amounts={400,260,90};
            for(int i=1;i<=3;i++){
                data.getRow(i).getCell(2).setCellValue("2026-10");
                data.getRow(i).getCell(3).setCellValue(amounts[i-1]);
            }
        });
    }
    @Test void allFiveLegacyPilotFormsExportImportInSchemaOrderWithoutPublishing(){
        Map<String,double[]> examples=new LinkedHashMap<>();
        examples.put("F01",new double[]{100,40,60,10,5});
        examples.put("F02",new double[]{400,260,90});
        examples.put("F03",new double[]{35,-10,-5});
        examples.put("DF-CSH-01",new double[]{160});
        examples.put("DF-CSH-02",new double[]{280});
        for(var entry:examples.entrySet()){
            var c=context(entry.getKey());
            byte[] original=xlsx.template(c);
            byte[] uploaded=edit(original,book->{
                Sheet data=book.getSheet("FIN_Data");
                for(int index=0;index<entry.getValue().length;index++){
                    data.getRow(index+1).getCell(2).setCellValue("2026-10");
                    data.getRow(index+1).getCell(3).setCellValue(entry.getValue()[index]);
                }
            });
            var parsed=xlsx.parse(c,uploaded);
            assertEquals(entry.getValue().length,parsed.size(),entry.getKey());
            assertEquals("2026-10",parsed.getFirst().period().toString(),entry.getKey());
            assertEquals(0L,jdbc.queryForObject("""
              SELECT count(*) FROM fin_intake.submission WHERE organization_id=?
              """,Long.class,c.organizationId()),"Parsing must not approve or persist source");
        }
    }
    @Test void legacyServerXlsxRejectsMalformedEmptyAndPrecisionUnsafeUploads(){
        var c=context("F02");
        assertThrows(IllegalArgumentException.class,()->xlsx.parse(c,new byte[]{1,2,3}));
        assertThrows(IllegalArgumentException.class,()->xlsx.parse(c,null));
        assertThrows(IllegalArgumentException.class,()->xlsx.parse(c,xlsx.template(c)));
        byte[] populated=completeF02(xlsx.template(c));
        byte[] moved=edit(populated,book->book.getSheet("FIN_Data")
            .createRow(900).createCell(0).setCellValue("forged"));
        assertThrows(IllegalArgumentException.class,()->xlsx.parse(c,moved));
        byte[] unsafe=edit(populated,book->book.getSheet("FIN_Data")
            .getRow(1).getCell(3).setCellValue(1234567890123456d));
        assertThrows(IllegalArgumentException.class,()->xlsx.parse(c,unsafe));
        byte[] invalid=edit(populated,book->book.getSheet("FIN_Data")
            .getRow(1).getCell(3).setCellValue("1,000.00"));
        assertThrows(IllegalArgumentException.class,()->xlsx.parse(c,invalid));
    }
    @Test void exactOriginalXlsxSurvivesSeparateReviewAndActualPublication(){
        var c=context("F02");
        byte[] original=completeF02(xlsx.template(c));
        var cells=xlsx.parse(c,original);
        assertEquals(3,cells.size());
        var submitted=intake.submitXlsx("author",c,original);
        assertEquals(64,submitted.sourceDocumentHash().length());
        var stored=intake.read(c.organizationId(),submitted.id());
        assertEquals("XLSX",stored.sourceKind());
        assertArrayEquals(original,intake.readRawXlsx(c.organizationId(),submitted.id()));
        assertEquals("APPROVED",intake.review("finance-reviewer",c.organizationId(),
                submitted.id(),true,"Verified against source and support").decision());
        var published=intake.publishReviewedActual(new ReviewedFinancialIntakeService.PublishRequest(
                c.organizationId(),c.entityId(),c.businessScopeId(),
                EconomicKernel.ReportingBasis.MANAGEMENT,List.of(submitted.id()),null));
        assertNotNull(published.revisionId());
        assertEquals(3L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_fact.fact_selection
            WHERE organization_id=? AND fact_set_revision_id=?
            """,Long.class,c.organizationId(),published.revisionId()));
        assertThrows(DataAccessException.class,()->jdbc.update("""
            UPDATE fin_intake.raw_xlsx SET raw_bytes=?
            WHERE organization_id=? AND submission_id=?
            """,new byte[]{1},c.organizationId(),submitted.id()));
    }
    @Test void xlsxFormulasTamperedContextAndAdditionalColumnsRejectBeforeIntake(){
        var c=context("F02");byte[] valid=completeF02(xlsx.template(c));
        var formula=edit(valid,w->w.getSheet("FIN_Data")
                .getRow(1).getCell(3).setCellFormula("1+2"));
        assertThrows(IllegalArgumentException.class,()->xlsx.parse(c,formula));
        var tampered=edit(valid,w->w.getSheet("_FIN_Metadata")
                .getRow(3).getCell(1).setCellValue(UUID.randomUUID().toString()));
        assertThrows(IllegalArgumentException.class,()->xlsx.parse(c,tampered));
        var extra=edit(valid,w->w.getSheet("FIN_Data")
                .getRow(1).createCell(5).setCellValue("UNREGISTERED"));
        assertThrows(IllegalArgumentException.class,()->xlsx.parse(c,extra));
        var wrong=context("F01");
        assertThrows(IllegalArgumentException.class,()->xlsx.parse(wrong,valid));
        assertThrows(IllegalArgumentException.class,
                ()->intake.submitXlsx("author",c,formula));
    }
    @Test void incompleteValuesCannotBecomeAnApprovedFinancialSubmission(){
        var c=context("F01");
        byte[] partial=edit(xlsx.template(c),w->{
            Sheet sheet=w.getSheet("FIN_Data");
            sheet.getRow(1).getCell(2).setCellValue("2026-10");
            sheet.getRow(1).getCell(3).setCellValue(100);
        });
        assertEquals(1,xlsx.parse(c,partial).size());
        assertThrows(IllegalArgumentException.class,
                ()->intake.submitXlsx("author",c,partial));
        assertEquals(0L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_intake.submission WHERE organization_id=?
            """,Long.class,c.organizationId()));
    }
}
