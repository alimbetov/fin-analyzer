package kz.fin.formlib.domain;
import jakarta.persistence.*;
import java.util.UUID;
import java.time.Instant;
import java.time.LocalDate;
@Entity
@Table(schema="fin_lib",name="lib_report_definition")
public class LibReportDefinition {
@Id
@Column(name="id") private UUID id;
@Column(name="organization_id",nullable=false) private UUID organizationId;
@Column(name="report_code",nullable=false) private String reportCode;
@Column(name="title",nullable=false) private String title;
@Column(name="currency_code",nullable=false) private String currencyCode;
@Column(name="period_type",nullable=false) private String periodType;
@Column(name="time_axis",nullable=false) private String timeAxis;
@Column(name="layout_version",nullable=false) private Integer layoutVersion;
@Column(name="format_version",nullable=false) private Integer formatVersion;
@Column(name="validation_version",nullable=false) private Integer validationVersion;
@Column(name="latest_revision_no",nullable=false) private Integer latestRevisionNo;
@Column(name="current_revision_id") private UUID currentRevisionId;
@Version
@Column(name="row_version") private Long rowVersion;
@Column(name="created_at",nullable=false) private Instant createdAt;
public LibReportDefinition(){}
public UUID getId(){return id;}
public void setId(UUID v){id=v;}
public UUID getOrganizationId(){return organizationId;}
public void setOrganizationId(UUID v){organizationId=v;}
public String getReportCode(){return reportCode;}
public void setReportCode(String v){reportCode=v;}
public String getTitle(){return title;}
public void setTitle(String v){title=v;}
public String getCurrencyCode(){return currencyCode;}
public void setCurrencyCode(String v){currencyCode=v;}
public String getPeriodType(){return periodType;}
public void setPeriodType(String v){periodType=v;}
public String getTimeAxis(){return timeAxis;}
public void setTimeAxis(String v){timeAxis=v;}
public Integer getValidationVersion(){return validationVersion;}
public void setValidationVersion(Integer v){validationVersion=v;}
public Integer getFormatVersion(){return formatVersion;}
public void setFormatVersion(Integer v){formatVersion=v;}
public Integer getLayoutVersion(){return layoutVersion;}
public void setLayoutVersion(Integer v){layoutVersion=v;}
public Integer getLatestRevisionNo(){return latestRevisionNo;}
public void setLatestRevisionNo(Integer v){latestRevisionNo=v;}
public UUID getCurrentRevisionId(){return currentRevisionId;}
public void setCurrentRevisionId(UUID v){currentRevisionId=v;}
public Long getRowVersion(){return rowVersion;}
public void setRowVersion(Long v){rowVersion=v;}
public Instant getCreatedAt(){return createdAt;}
public void setCreatedAt(Instant v){createdAt=v;}
}
