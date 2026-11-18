package kz.fin.formlib.domain;
import jakarta.persistence.*;
import java.util.UUID;
import java.time.Instant;
@Entity
@Table(schema="fin_intake", name="lib_form_revision_row")
public class FormRevisionRow {
@Id
@Column(name="id") private UUID id;
@Column(name="revision_id",nullable=false) private UUID revisionId;
@Column(name="row_id",nullable=false) private UUID rowId;
@Column(name="row_position",nullable=false) private Integer rowPosition;
@Column(name="values_json",columnDefinition="text") private String valuesJson;
public FormRevisionRow(){}
public UUID getId(){return id;}
public void setId(UUID value){this.id=value;}
public UUID getRevisionId(){return revisionId;}
public void setRevisionId(UUID value){this.revisionId=value;}
public UUID getRowId(){return rowId;}
public void setRowId(UUID value){this.rowId=value;}
public Integer getRowPosition(){return rowPosition;}
public void setRowPosition(Integer value){this.rowPosition=value;}
public String getValuesJson(){return valuesJson;}
public void setValuesJson(String value){this.valuesJson=value;}
}
