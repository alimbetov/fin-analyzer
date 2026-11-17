package kz.fin.formlib.domain;
import jakarta.persistence.*;
import java.util.UUID;
@Entity
@Table(schema="fin_lib",name="lib_report_derived_rule")
public class LibReportDerivedRule {
  @Id @Column(name="id") private UUID id;
  @Column(name="report_id",nullable=false) private UUID reportId;
  @Column(name="metric_id",nullable=false) private UUID metricId;
  @Column(name="derived_version",nullable=false) private Integer derivedVersion;
  @Column(name="operator",nullable=false) private String operator;
  @Column(name="visibility",nullable=false) private String visibility;
  @Column(name="completeness_policy",nullable=false) private String completenessPolicy;
  @Column(name="review_state",nullable=false) private String reviewState;
  public UUID getId(){return id;} public void setId(UUID v){id=v;}
  public UUID getReportId(){return reportId;} public void setReportId(UUID v){reportId=v;}
  public UUID getMetricId(){return metricId;} public void setMetricId(UUID v){metricId=v;}
  public Integer getDerivedVersion(){return derivedVersion;}
  public void setDerivedVersion(Integer v){derivedVersion=v;}
  public String getOperator(){return operator;} public void setOperator(String v){operator=v;}
  public String getVisibility(){return visibility;} public void setVisibility(String v){visibility=v;}
  public String getCompletenessPolicy(){return completenessPolicy;}
  public void setCompletenessPolicy(String v){completenessPolicy=v;}
  public String getReviewState(){return reviewState;} public void setReviewState(String v){reviewState=v;}
}
