package kz.fin.formlib.api;

import kz.fin.formlib.service.CatalogReadCache;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Set;

/** Read-only R14 navigation categories and 166 form associations. */
@RestController
@RequestMapping("/api/v1/catalog/category-tree")
public class CatalogCategoryReadController {
  private final JdbcTemplate jdbc;
  private final CatalogReadCache cache;
  public CatalogCategoryReadController(JdbcTemplate jdbc,CatalogReadCache cache){
    this.jdbc=jdbc;this.cache=cache;
  }

  public record Category(String code,String parentCode,String type,int sortOrder,
                         String title,String translationStatus) {}
  public record Link(String categoryCode,String formCode,String role,int sortOrder) {}
  public record Tree(List<Category> categories,List<Link> links) {}

  @GetMapping
  public Tree get(@RequestParam(defaultValue="ru") String locale){
    if(!Set.of("ru","kk","en","zh-Hans").contains(locale))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"UNSUPPORTED_LOCALE");
    return cache.get("category-tree",locale,()->readTree(locale));
  }

  private Tree readTree(String locale){
    var categories=jdbc.query("""
      SELECT c.code,c.parent_code,c.category_type,c.sort_order,t.title,t.review_status
      FROM fin_lib.lib_catalog_category c
      JOIN fin_lib.lib_catalog_category_translation t
        ON t.category_code=c.code AND t.locale=?
      WHERE c.lifecycle_status<>'ARCHIVED'
      ORDER BY c.sort_order,c.code
      """,(rs,n)->new Category(rs.getString(1),rs.getString(2),rs.getString(3),
        rs.getInt(4),rs.getString(5),rs.getString(6)),locale);
    var links=jdbc.query("""
      SELECT a.category_code,f.form_code,a.association_role,a.sort_order
      FROM fin_lib.lib_catalog_category_form a
      JOIN fin_lib.lib_form_definition f ON f.id=a.form_id
      ORDER BY a.category_code,a.sort_order,f.form_code
      """,(rs,n)->new Link(rs.getString(1),rs.getString(2),
        rs.getString(3),rs.getInt(4)));
    return new Tree(categories,links);
  }
}
