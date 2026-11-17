package kz.fin.formlib.domain;
import jakarta.persistence.*;
import java.util.UUID;
@Entity @Table(schema="fin_lib",name="lib_period_type_translation")
public class LibPeriodTypeTranslation {
@Id @Column(name="id") private UUID id;
@Column(name="period_type",nullable=false) private String periodType;
@Column(name="locale",nullable=false) private String locale;
@Column(name="title",nullable=false) private String title;
@Column(name="description",nullable=false) private String description;
public UUID getId(){return id;}
public void setId(UUID v){id=v;}
public String getPeriodType(){return periodType;}
public void setPeriodType(String v){periodType=v;}
public String getLocale(){return locale;}
public void setLocale(String v){locale=v;}
public String getTitle(){return title;}
public void setTitle(String v){title=v;}
public String getDescription(){return description;}
public void setDescription(String v){description=v;}
}
