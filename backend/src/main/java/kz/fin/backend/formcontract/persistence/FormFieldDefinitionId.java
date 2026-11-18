package kz.fin.backend.formcontract.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class FormFieldDefinitionId implements Serializable {
    @Column(name = "contract_version_id", nullable = false)
    private Long contractVersionId;

    @Column(name = "field_code", length = 128, nullable = false)
    private String fieldCode;

    protected FormFieldDefinitionId() { }

    public FormFieldDefinitionId(long contractVersionId, String fieldCode) {
        this.contractVersionId = contractVersionId;
        this.fieldCode = Objects.requireNonNull(fieldCode);
    }

    public Long getContractVersionId() { return contractVersionId; }
    public String getFieldCode() { return fieldCode; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof FormFieldDefinitionId that)) return false;
        return Objects.equals(contractVersionId, that.contractVersionId)
            && Objects.equals(fieldCode, that.fieldCode);
    }

    @Override public int hashCode() { return Objects.hash(contractVersionId, fieldCode); }
}
