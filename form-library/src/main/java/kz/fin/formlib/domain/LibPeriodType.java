package kz.fin.formlib.domain;
import jakarta.persistence.*;
@Entity @Table(schema="fin_lib",name="lib_period_type")
public class LibPeriodType {
  @Id @Column(name="code") private String code;
  @Column(name="sort_order",nullable=false) private Integer sortOrder;
  @Column(name="iso_calendar",nullable=false) private String isoCalendar;
  @Column(name="status",nullable=false) private String status;
  public String getCode(){return code;}
  public void setCode(String v){code=v;}
  public Integer getSortOrder(){return sortOrder;}
  public void setSortOrder(Integer v){sortOrder=v;}
  public String getIsoCalendar(){return isoCalendar;}
  public void setIsoCalendar(String v){isoCalendar=v;}
  public String getStatus(){return status;}
  public void setStatus(String v){status=v;}
}
