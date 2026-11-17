package kz.fin.formlib.domain;
import jakarta.persistence.*;
import java.util.UUID;

/** An immutable per-report, per-metric input validation contract. */
@Entity
@Table(schema="fin_lib",name="lib_report_metric_validation")
public class LibReportMetricValidation {
  @Id @Column(name="id") private UUID id;
  @Column(name="report_id",nullable=false) private UUID reportId;
  @Column(name="metric_id",nullable=false) private UUID metricId;
  @Column(name="validation_version",nullable=false) private Integer validationVersion;
  @Column(name="allow_missing",nullable=false) private Boolean allowMissing;
  @Column(name="allow_not_applicable",nullable=false) private Boolean allowNotApplicable;
  @Column(name="sign_rule",nullable=false) private String signRule;
  @Column(name="min_value") private String minValue;
  @Column(name="max_value") private String maxValue;
  @Column(name="max_precision",nullable=false) private Integer maxPrecision;
  @Column(name="max_scale",nullable=false) private Integer maxScale;

  public UUID getId(){return id;} public void setId(UUID v){id=v;}
  public UUID getReportId(){return reportId;} public void setReportId(UUID v){reportId=v;}
  public UUID getMetricId(){return metricId;} public void setMetricId(UUID v){metricId=v;}
  public Integer getValidationVersion(){return validationVersion;}
  public void setValidationVersion(Integer v){validationVersion=v;}
  public Boolean getAllowMissing(){return allowMissing;}
  public void setAllowMissing(Boolean v){allowMissing=v;}
  public Boolean getAllowNotApplicable(){return allowNotApplicable;}
  public void setAllowNotApplicable(Boolean v){allowNotApplicable=v;}
  public String getSignRule(){return signRule;} public void setSignRule(String v){signRule=v;}
  public String getMinValue(){return minValue;} public void setMinValue(String v){minValue=v;}
  public String getMaxValue(){return maxValue;} public void setMaxValue(String v){maxValue=v;}
  public Integer getMaxPrecision(){return maxPrecision;}
  public void setMaxPrecision(Integer v){maxPrecision=v;}
  public Integer getMaxScale(){return maxScale;} public void setMaxScale(Integer v){maxScale=v;}
}
