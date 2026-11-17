package kz.fin.formlib.domain;
import jakarta.persistence.*;
import java.util.UUID;
import java.time.Instant;
import java.time.LocalDate;
@Entity
@Table(schema="fin_lib",name="lib_report_metric")
public class LibReportMetric {
@Id
@Column(name="id") private UUID id;
@Column(name="report_id",nullable=false) private UUID reportId;
@Column(name="metric_id",nullable=false) private UUID metricId;
@Column(name="display_order",nullable=false) private Integer displayOrder;
public LibReportMetric(){}
public UUID getId(){return id;}
public void setId(UUID v){id=v;}
public UUID getReportId(){return reportId;}
public void setReportId(UUID v){reportId=v;}
public UUID getMetricId(){return metricId;}
public void setMetricId(UUID v){metricId=v;}
public Integer getDisplayOrder(){return displayOrder;}
public void setDisplayOrder(Integer v){displayOrder=v;}
}
