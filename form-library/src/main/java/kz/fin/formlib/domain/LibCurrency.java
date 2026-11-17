package kz.fin.formlib.domain;
import jakarta.persistence.*;
@Entity @Table(schema="fin_lib",name="lib_currency")
public class LibCurrency {
  @Id @Column(name="code") private String code;
  @Column(name="iso_numeric",nullable=false) private String isoNumeric;
  @Column(name="minor_units",nullable=false) private Integer minorUnits;
  @Column(name="status",nullable=false) private String status;
  public String getCode(){return code;}
  public void setCode(String v){code=v;}
  public String getIsoNumeric(){return isoNumeric;}
  public void setIsoNumeric(String v){isoNumeric=v;}
  public Integer getMinorUnits(){return minorUnits;}
  public void setMinorUnits(Integer v){minorUnits=v;}
  public String getStatus(){return status;}
  public void setStatus(String v){status=v;}
}
