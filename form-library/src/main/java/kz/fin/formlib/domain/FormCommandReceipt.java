package kz.fin.formlib.domain;
import jakarta.persistence.*;
import java.util.UUID;
import java.time.Instant;
@Entity
@Table(schema="fin_intake", name="lib_form_command_receipt")
public class FormCommandReceipt {
@Id
@Column(name="id") private UUID id;
@Column(name="organization_id",nullable=false) private UUID organizationId;
@Column(name="idempotency_key",nullable=false) private String idempotencyKey;
@Column(name="request_sha256",nullable=false) private String requestSha256;
@Column(name="revision_id",nullable=false) private UUID revisionId;
@Column(name="result_code",nullable=false) private String resultCode;
public FormCommandReceipt(){}
public UUID getId(){return id;}
public void setId(UUID value){this.id=value;}
public UUID getOrganizationId(){return organizationId;}
public void setOrganizationId(UUID value){this.organizationId=value;}
public String getIdempotencyKey(){return idempotencyKey;}
public void setIdempotencyKey(String value){this.idempotencyKey=value;}
public String getRequestSha256(){return requestSha256;}
public void setRequestSha256(String value){this.requestSha256=value;}
public UUID getRevisionId(){return revisionId;}
public void setRevisionId(UUID value){this.revisionId=value;}
public String getResultCode(){return resultCode;}
public void setResultCode(String value){this.resultCode=value;}
}
