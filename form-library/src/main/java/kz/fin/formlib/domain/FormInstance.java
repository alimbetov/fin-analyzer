package kz.fin.formlib.domain;
import jakarta.persistence.*;
import java.util.UUID;
import java.time.Instant;
@Entity
@Table(schema="fin_intake", name="lib_form_instance")
public class FormInstance {
@Id
@Column(name="id") private UUID id;
@Column(name="organization_id",nullable=false) private UUID organizationId;
@Column(name="economic_entity_id",nullable=false) private UUID economicEntityId;
@Column(name="business_scope_id",nullable=false) private UUID businessScopeId;
@Column(name="project_id",nullable=false) private UUID projectId;
@Column(name="form_id",nullable=false) private UUID formId;
@Column(name="period_type",nullable=false) private String periodType;
@Column(name="period_code",nullable=false) private String periodCode;
@Column(name="coverage",nullable=false) private String coverage;
@Column(name="currency",nullable=false) private String currency;
@Column(name="reporting_basis",nullable=false) private String reportingBasis;
@Column(name="data_space",nullable=false) private String dataSpace;
@Column(name="current_revision_id") private UUID currentRevisionId;
@Column(name="latest_revision_no",nullable=false) private Integer latestRevisionNo;
@Version
@Column(name="row_version") private Long rowVersion;
@Column(name="workflow_status",nullable=false) private String workflowStatus="DRAFT";
@Column(name="status_version",nullable=false) private Long statusVersion=0L;
@Column(name="period_start") private java.time.LocalDate periodStart;
@Column(name="period_end_exclusive") private java.time.LocalDate periodEndExclusive;
public FormInstance(){}
public UUID getId(){return id;}
public void setId(UUID value){this.id=value;}
public UUID getOrganizationId(){return organizationId;}
public void setOrganizationId(UUID value){this.organizationId=value;}
public UUID getEconomicEntityId(){return economicEntityId;}
public void setEconomicEntityId(UUID value){this.economicEntityId=value;}
public UUID getBusinessScopeId(){return businessScopeId;}
public void setBusinessScopeId(UUID value){this.businessScopeId=value;}
public UUID getProjectId(){return projectId;}
public void setProjectId(UUID value){this.projectId=value;}
public UUID getFormId(){return formId;}
public void setFormId(UUID value){this.formId=value;}
public String getPeriodType(){return periodType;}
public void setPeriodType(String value){this.periodType=value;}
public String getPeriodCode(){return periodCode;}
public void setPeriodCode(String value){this.periodCode=value;}
public String getCoverage(){return coverage;}
public void setCoverage(String value){this.coverage=value;}
public String getCurrency(){return currency;}
public void setCurrency(String value){this.currency=value;}
public String getReportingBasis(){return reportingBasis;}
public void setReportingBasis(String value){this.reportingBasis=value;}
public String getDataSpace(){return dataSpace;}
public void setDataSpace(String value){this.dataSpace=value;}
public UUID getCurrentRevisionId(){return currentRevisionId;}
public void setCurrentRevisionId(UUID value){this.currentRevisionId=value;}
public Integer getLatestRevisionNo(){return latestRevisionNo;}
public void setLatestRevisionNo(Integer value){this.latestRevisionNo=value;}
public Long getRowVersion(){return rowVersion;}
public String getWorkflowStatus(){return workflowStatus;}
public void setWorkflowStatus(String value){this.workflowStatus=value;}
public Long getStatusVersion(){return statusVersion;}
public void setStatusVersion(Long value){this.statusVersion=value;}
public void setRowVersion(Long value){this.rowVersion=value;}
public java.time.LocalDate getPeriodStart(){return periodStart;}
public void setPeriodStart(java.time.LocalDate v){periodStart=v;}
public java.time.LocalDate getPeriodEndExclusive(){return periodEndExclusive;}
public void setPeriodEndExclusive(java.time.LocalDate v){periodEndExclusive=v;}
}
