package kz.fin.formlib.domain;
import jakarta.persistence.*;
import java.util.UUID;
import java.time.Instant;
import java.time.LocalDate;
@Entity
@Table(schema="fin_intake",name="lib_report_revision_cell")
public class LibReportRevisionCell {
@Id
@Column(name="id") private UUID id;
@Column(name="revision_id",nullable=false) private UUID revisionId;
@Column(name="metric_id",nullable=false) private UUID metricId;
@Column(name="period_code",nullable=false) private String periodCode;
@Column(name="value_state",nullable=false) private String valueState;
@Column(name="decimal_value") private String decimalValue;
public LibReportRevisionCell(){}
public UUID getId(){return id;}
public void setId(UUID v){id=v;}
public UUID getRevisionId(){return revisionId;}
public void setRevisionId(UUID v){revisionId=v;}
public UUID getMetricId(){return metricId;}
public void setMetricId(UUID v){metricId=v;}
public String getPeriodCode(){return periodCode;}
public void setPeriodCode(String v){periodCode=v;}
public String getValueState(){return valueState;}
public void setValueState(String v){valueState=v;}
public String getDecimalValue(){return decimalValue;}
public void setDecimalValue(String v){decimalValue=v;}
}
