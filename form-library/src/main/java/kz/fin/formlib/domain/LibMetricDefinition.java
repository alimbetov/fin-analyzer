package kz.fin.formlib.domain;
import jakarta.persistence.*;
import java.util.UUID;
@Entity @Table(schema="fin_lib",name="lib_metric_definition")
public class LibMetricDefinition {
@Id @Column(name="id") private UUID id;
@Column(name="metric_code",nullable=false) private String metricCode;
@Column(name="metric_kind",nullable=false) private String metricKind;
@Column(name="form_code") private String formCode;
@Column(name="source_field_code") private String sourceFieldCode;
@Column(name="unit_policy",nullable=false) private String unitPolicy;
@Column(name="qualification_state",nullable=false) private String qualificationState;
public UUID getId(){return id;}
public void setId(UUID v){id=v;}
public String getMetricCode(){return metricCode;}
public void setMetricCode(String v){metricCode=v;}
public String getMetricKind(){return metricKind;}
public void setMetricKind(String v){metricKind=v;}
public String getFormCode(){return formCode;}
public void setFormCode(String v){formCode=v;}
public String getSourceFieldCode(){return sourceFieldCode;}
public void setSourceFieldCode(String v){sourceFieldCode=v;}
public String getUnitPolicy(){return unitPolicy;}
public void setUnitPolicy(String v){unitPolicy=v;}
public String getQualificationState(){return qualificationState;}
public void setQualificationState(String v){qualificationState=v;}
}
