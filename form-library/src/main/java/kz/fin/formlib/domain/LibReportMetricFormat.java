package kz.fin.formlib.domain;

import jakarta.persistence.*;
import java.util.UUID;

/** Immutable, report-scoped presentation profile for one selected metric. */
@Entity
@Table(schema="fin_lib",name="lib_report_metric_format")
public class LibReportMetricFormat {
  @Id @Column(name="id") private UUID id;
  @Column(name="report_id",nullable=false) private UUID reportId;
  @Column(name="metric_id",nullable=false) private UUID metricId;
  @Column(name="format_version",nullable=false) private Integer formatVersion;
  @Column(name="format_kind",nullable=false) private String formatKind;
  @Column(name="decimal_places",nullable=false) private Integer decimalPlaces;
  @Column(name="scale_divisor",nullable=false) private Integer scaleDivisor;
  @Column(name="rounding_mode",nullable=false) private String roundingMode;
  @Column(name="grouping_enabled",nullable=false) private Boolean groupingEnabled;
  @Column(name="negative_style",nullable=false) private String negativeStyle;
  @Column(name="currency_display",nullable=false) private String currencyDisplay;
  public UUID getId(){return id;} public void setId(UUID v){id=v;}
  public UUID getReportId(){return reportId;} public void setReportId(UUID v){reportId=v;}
  public UUID getMetricId(){return metricId;} public void setMetricId(UUID v){metricId=v;}
  public Integer getFormatVersion(){return formatVersion;} public void setFormatVersion(Integer v){formatVersion=v;}
  public String getFormatKind(){return formatKind;} public void setFormatKind(String v){formatKind=v;}
  public Integer getDecimalPlaces(){return decimalPlaces;} public void setDecimalPlaces(Integer v){decimalPlaces=v;}
  public Integer getScaleDivisor(){return scaleDivisor;} public void setScaleDivisor(Integer v){scaleDivisor=v;}
  public String getRoundingMode(){return roundingMode;} public void setRoundingMode(String v){roundingMode=v;}
  public Boolean getGroupingEnabled(){return groupingEnabled;} public void setGroupingEnabled(Boolean v){groupingEnabled=v;}
  public String getNegativeStyle(){return negativeStyle;} public void setNegativeStyle(String v){negativeStyle=v;}
  public String getCurrencyDisplay(){return currencyDisplay;} public void setCurrencyDisplay(String v){currencyDisplay=v;}
}
