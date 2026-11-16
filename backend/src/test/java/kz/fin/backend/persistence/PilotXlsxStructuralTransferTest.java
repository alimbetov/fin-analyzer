package kz.fin.backend.persistence;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pre-save only: the production Apache POI issuer and parser must preserve
 * every source field, exact decimal and calendar month before a DB transaction.
 * This test deliberately does not submit, approve or publish any financial fact.
 */
final class PilotXlsxStructuralTransferTest {
    private final PilotXlsxTemplateService xlsx=new PilotXlsxTemplateService();

    private record Fixture(String form,String version,String[] fields,String[] values) {}

    private static List<Fixture> fixtures(){
        return List.of(
            new Fixture("F01","FIN-PILOT-V1",
                new String[]{"assets","liabilities","equity","cash","receivables"},
                new String[]{"100.25","40.12","60.13","10.01","5.02"}),
            new Fixture("F02","FIN-PILOT-V1",
                new String[]{"netRevenue","cogs","operatingExpenses"},
                new String[]{"400.125","260.112","0.001"}),
            new Fixture("F03","FIN-PILOT-V1",
                new String[]{"operatingCash","investingCash","financingCash"},
                new String[]{"35.125","-10.125","0"}),
            new Fixture("DF-CSH-01","FIN-PILOT-V1",
                new String[]{"receipt"},new String[]{"0"}),
            new Fixture("DF-CSH-02","FIN-PILOT-V1",
                new String[]{"payment"},new String[]{"280.000000000001"}),
            new Fixture("F01","FIN-LIQUIDITY-V1",
                new String[]{"assets","liabilities","equity","cash","receivables",
                             "currentAssets","currentLiabilities"},
                new String[]{"100.25","40.12","60.13","10.01","5.02","45.11","20.09"})
        );
    }
    private static PilotXlsxTemplateService.Context context(
            Fixture f,String currency,int scale) {
        return new PilotXlsxTemplateService.Context(
            UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),f.form(),
            "MANAGEMENT",currency,scale,f.version());
    }
    private static byte[] fill(PilotXlsxTemplateService service,
                               PilotXlsxTemplateService.Context context,
                               Fixture fixture,YearMonth month){
        byte[] raw=service.template(context);
        try(XSSFWorkbook book=new XSSFWorkbook(new ByteArrayInputStream(raw));
            ByteArrayOutputStream output=new ByteArrayOutputStream()){
            Sheet sheet=book.getSheet("FIN_Data");
            assertEquals(fixture.fields().length,sheet.getLastRowNum(),fixture.form());
            for(int i=0;i<fixture.fields().length;i++){
                Row row=sheet.getRow(i+1);
                assertEquals(fixture.fields()[i],row.getCell(1).getStringCellValue());
                row.getCell(2).setCellValue(month.toString());
                // High precision values remain literal strings; no Excel double rounding.
                row.getCell(3).setCellValue(fixture.values()[i]);
            }
            book.write(output);
            return output.toByteArray();
        }catch(IOException e){throw new IllegalStateException(e);}
    }

    @TestFactory
    Stream<DynamicTest> everyServerTemplateFieldAndContextRoundTripsBeforeSaving(){
        List<DynamicTest> tests=new ArrayList<>();
        for(Fixture f:fixtures())
            for(String currency:List.of("KZT","USD"))
                for(int scale:List.of(1,1000))
                    for(YearMonth period:List.of(YearMonth.of(2026,10),
                                                YearMonth.of(2026,12))){
                        final String title=f.form()+"/"+f.version()+"/"+currency+
                                "/x"+scale+"/"+period;
                        tests.add(DynamicTest.dynamicTest(title,()->{
                            var ctx=context(f,currency,scale);
                            byte[] raw=fill(xlsx,ctx,f,period);
                            var canonical=xlsx.parse(ctx,raw);
                            assertEquals(f.fields().length,canonical.size(),title);
                            Set<String> seen=new HashSet<>();
                            for(int i=0;i<f.fields().length;i++){
                                var cell=canonical.get(i);
                                assertEquals(f.fields()[i],cell.sourceField(),title);
                                assertEquals(period,cell.period(),title);
                                assertTrue(seen.add(cell.sourceRowId()+":"+
                                    cell.sourceField()+":"+cell.period()),title);
                                assertEquals(0,cell.enteredAmount().compareTo(
                                    new BigDecimal(f.values()[i])),title+":"+f.fields()[i]);
                            }
                            String fingerprint=TrustedFinancialFormMapper.fingerprint(canonical);
                            assertEquals(64,fingerprint.length(),title);
                            assertEquals(fingerprint,TrustedFinancialFormMapper.fingerprint(
                                xlsx.parse(ctx,raw)),title);
                            // The parser is pure: it does not perform DB writes or review.
                        }));
                    }
        assertEquals(48,tests.size());
        return tests.stream();
    }

    @Test
    void rejectsWrongCurrencyScaleFormVersionAndUnparseablePeriodBeforeSaving(){
        for(Fixture fixture:fixtures()){
            var ctx=context(fixture,"KZT",1);
            byte[] filled=fill(xlsx,ctx,fixture,YearMonth.of(2026,10));
            var wrongCurrency=new PilotXlsxTemplateService.Context(
                ctx.organizationId(),ctx.entityId(),ctx.businessScopeId(),ctx.formCode(),
                ctx.reportingBasis(),"USD",ctx.scale(),ctx.formVersion());
            var wrongScale=new PilotXlsxTemplateService.Context(
                ctx.organizationId(),ctx.entityId(),ctx.businessScopeId(),ctx.formCode(),
                ctx.reportingBasis(),ctx.currency(),1000,ctx.formVersion());
            assertThrows(IllegalArgumentException.class,()->xlsx.parse(wrongCurrency,filled));
            assertThrows(IllegalArgumentException.class,()->xlsx.parse(wrongScale,filled));
            assertThrows(IllegalArgumentException.class,()->
                xlsx.parse(ctx,modify(filled,book->book.getSheet("FIN_Data")
                  .getRow(1).getCell(2).setCellValue("2026-13"))));
            assertThrows(IllegalArgumentException.class,()->
                xlsx.parse(ctx,modify(filled,book->book.getSheet("_FIN_Metadata")
                  .getRow(2).getCell(1).setCellValue("FOREIGN_VERSION"))));
        }
    }

    private static byte[] modify(byte[] bytes,java.util.function.Consumer<XSSFWorkbook> edit){
        try(XSSFWorkbook book=new XSSFWorkbook(new ByteArrayInputStream(bytes));
            ByteArrayOutputStream output=new ByteArrayOutputStream()){
            edit.accept(book);
            book.write(output);
            return output.toByteArray();
        }catch(IOException e){throw new IllegalStateException(e);}
    }

    @Test
    void exactMonthIsPreservedButTheLegacyServerTemplateDoesNotBindAnExpectedMonth(){
        var f=fixtures().get(1); // F02
        var ctx=context(f,"KZT",1);
        byte[] october=fill(xlsx,ctx,f,YearMonth.of(2026,10));
        byte[] november=modify(october,book->{
            for(int i=1;i<=f.fields().length;i++)
                book.getSheet("FIN_Data").getRow(i).getCell(2).setCellValue("2026-11");
        });
        assertEquals(YearMonth.of(2026,11),xlsx.parse(ctx,november).getFirst().period());
        // P0 gap: server Context contains no expected period. A project-bound
        // expected-period policy is required to reject this before persistence.
    }
}
