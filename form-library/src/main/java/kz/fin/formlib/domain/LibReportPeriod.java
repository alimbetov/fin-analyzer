package kz.fin.formlib.domain;
import jakarta.persistence.*;
import java.util.UUID;
import java.time.Instant;
import java.time.LocalDate;
@Entity
@Table(schema="fin_lib",name="lib_report_period")
public class LibReportPeriod {
@Id
@Column(name="id") private UUID id;
@Column(name="report_id",nullable=false) private UUID reportId;
@Column(name="period_code",nullable=false) private String periodCode;
@Column(name="start_inclusive",nullable=false) private LocalDate startInclusive;
@Column(name="end_exclusive",nullable=false) private LocalDate endExclusive;
@Column(name="display_order",nullable=false) private Integer displayOrder;
public LibReportPeriod(){}
public UUID getId(){return id;}
public void setId(UUID v){id=v;}
public UUID getReportId(){return reportId;}
public void setReportId(UUID v){reportId=v;}
public String getPeriodCode(){return periodCode;}
public void setPeriodCode(String v){periodCode=v;}
public LocalDate getStartInclusive(){return startInclusive;}
public void setStartInclusive(LocalDate v){startInclusive=v;}
public LocalDate getEndExclusive(){return endExclusive;}
public void setEndExclusive(LocalDate v){endExclusive=v;}
public Integer getDisplayOrder(){return displayOrder;}
public void setDisplayOrder(Integer v){displayOrder=v;}
}
