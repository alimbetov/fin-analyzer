package kz.fin.formlib.domain;
import jakarta.persistence.*;
import java.util.UUID;
import java.time.Instant;
import java.time.LocalDate;
@Entity
@Table(schema="fin_intake",name="lib_report_command_receipt")
public class LibReportCommandReceipt {
@Id
@Column(name="id") private UUID id;
@Column(name="report_id",nullable=false) private UUID reportId;
@Column(name="idempotency_key",nullable=false) private String idempotencyKey;
@Column(name="request_sha256",nullable=false) private String requestSha256;
@Column(name="revision_id",nullable=false) private UUID revisionId;
public LibReportCommandReceipt(){}
public UUID getId(){return id;}
public void setId(UUID v){id=v;}
public UUID getReportId(){return reportId;}
public void setReportId(UUID v){reportId=v;}
public String getIdempotencyKey(){return idempotencyKey;}
public void setIdempotencyKey(String v){idempotencyKey=v;}
public String getRequestSha256(){return requestSha256;}
public void setRequestSha256(String v){requestSha256=v;}
public UUID getRevisionId(){return revisionId;}
public void setRevisionId(UUID v){revisionId=v;}
}
