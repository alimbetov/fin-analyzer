package kz.fin.formlib;

import kz.fin.formlib.bootstrap.CatalogCategoryBootstrap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * R14.A direct DATABASE proof: 166 identities -> 47 navigation categories,
 * 188 four-language translations and 389 immutable category-to-form links.
 * The read API alone cannot satisfy these assertions.
 */
@SpringBootTest
class CatalogCategoryDatabaseIntegrityTest {
 @Autowired JdbcTemplate db;
 @Autowired CatalogCategoryBootstrap seed;
 private long number(String sql,Object... args){
   return Objects.requireNonNull(db.queryForObject(sql,Long.class,args));
 }
 @Test void exactCatalogIsPersistedInFinLibWithFourLanguagesAndForeignKeys() {
   assertEquals(166,number("select count(*) from fin_lib.lib_form_definition"));
   assertEquals(47,number("select count(*) from fin_lib.lib_catalog_category"));
   assertEquals(188,number("select count(*) from fin_lib.lib_catalog_category_translation"));
   assertEquals(389,number("select count(*) from fin_lib.lib_catalog_category_form"));
   assertEquals(1,number("select count(*) from fin_lib.lib_catalog_seed_manifest"));
   assertEquals(7,number("select count(*) from fin_lib.lib_catalog_category where category_type='ROOT'"));
   assertEquals(40,number("select count(*) from fin_lib.lib_catalog_category where category_type<>'ROOT'"));
   assertEquals(166,number("select count(*) from fin_lib.lib_catalog_category_form where association_role='PRIMARY'"));
   assertEquals(166,number("select count(distinct form_id) from fin_lib.lib_catalog_category_form where association_role='PRIMARY'"));
   assertEquals(0,number("""
     select count(*) from (
       select c.code from fin_lib.lib_catalog_category c
       left join fin_lib.lib_catalog_category_translation t on t.category_code=c.code
       group by c.code having count(t.id)<>4
     ) incomplete
     """));
 }
 @Test void selectedCodesAndVersionsAreActualForeignKeyLinkedForms() {
   assertEquals(8,number("""
     select count(*) from fin_lib.lib_catalog_category_form a
     where a.category_code='GROUP_FINANCE'
     """));
   assertEquals(27,number("""
     select count(*) from fin_lib.lib_catalog_category_form a
     where a.category_code='PURPOSE_BANK_FINANCING'
     """));
   assertEquals(3,number("""
     select count(*) from fin_lib.lib_catalog_category_form a
     where a.category_code='SECTOR_G'
     """));
   assertEquals(1,number("""
     select count(*) from fin_lib.lib_catalog_category_form l
     join fin_lib.lib_form_definition f on f.id=l.form_id
     where l.category_code='GROUP_FINANCE' and f.form_code='F02'
     """));
   for(String locale:List.of("ru","kk","en","zh-Hans")){
     assertEquals(47,number("""
       select count(*) from fin_lib.lib_catalog_category_translation
       where locale=?
       """,locale));
     assertEquals(166,number("""
       select count(*) from fin_lib.lib_form_translation t
       join fin_lib.lib_form_definition f on f.current_version_id=t.form_version_id
       where t.locale=?
       """,locale));
   }
   assertEquals(0,number("""
     select count(*) from fin_lib.lib_catalog_category_translation
     where title is null or trim(title)='' or purpose is null
       or trim(purpose)='' or description is null or trim(description)=''
     """));
   // The original 166 form identities are never rewritten by categorization.
   assertEquals(0,number("""
     select count(*) from fin_lib.lib_catalog_category_form a
     left join fin_lib.lib_form_definition f on f.id=a.form_id
     where f.id is null
     """));
 }
 @Test void seedCanBeReplayedWithoutDuplicatesAndUnknownLinksAreRejected() throws Exception{
   ApplicationArguments args=new DefaultApplicationArguments(new String[0]);
   seed.run(args);
   assertEquals(47,number("select count(*) from fin_lib.lib_catalog_category"));
   assertEquals(389,number("select count(*) from fin_lib.lib_catalog_category_form"));
   assertThrows(DataIntegrityViolationException.class,()->db.update("""
      insert into fin_lib.lib_catalog_category_form
      (category_code,form_id,association_role,sort_order)
      values(?,?,?,?)
     ""","GROUP_FINANCE",UUID.randomUUID(),"PRIMARY",1));
   assertThrows(DataIntegrityViolationException.class,()->db.update("""
      insert into fin_lib.lib_catalog_category_translation
      (id,category_code,locale,title,purpose,description,review_status)
      values(?,?,?,?,?,?,?)
      """,UUID.randomUUID(),"GROUP_FINANCE","de","Bad","Bad","Bad","DRAFT_UNREVIEWED"));
   assertEquals(389,number("select count(*) from fin_lib.lib_catalog_category_form"));
 }
}
