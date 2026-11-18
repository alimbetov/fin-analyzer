package kz.fin.backend.formcontract.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/**
 * Read-only typed field contract; historical definitions cannot be updated.
 * No field in this entity grants a metric binding or computation permit.
 */
@Entity
@Immutable
@Table(schema = "fin_form", name = "form_field_definition")
public class FormFieldDefinitionEntity {
    @EmbeddedId
    private FormFieldDefinitionId id;

    @Column(name = "ordinal", nullable = false)
    private int ordinal;

    @Column(name = "value_type", nullable = false, length = 24)
    private String valueType;

    @Column(name = "is_required", nullable = false)
    private boolean required;

    @Column(name = "min_occurs", nullable = false)
    private int minOccurs;

    @Column(name = "max_occurs")
    private Integer maxOccurs;

    @Column(name = "numeric_precision")
    private Integer numericPrecision;

    @Column(name = "numeric_scale")
    private Integer numericScale;

    @Column(name = "unit_code", length = 32)
    private String unitCode;

    @Column(name = "rounding_mode", length = 32)
    private String roundingMode;

    @Column(name = "missing_value_policy", nullable = false, length = 32)
    private String missingValuePolicy;

    @Column(name = "input_class", nullable = false, length = 16)
    private String inputClass;

    @Column(name = "aggregation_policy", nullable = false, length = 32)
    private String aggregationPolicy;

    @Column(name = "semantic_definition", nullable = false, columnDefinition = "text")
    private String semanticDefinition;

    protected FormFieldDefinitionEntity() { }

    public FormFieldDefinitionId getId() { return id; }
    public int getOrdinal() { return ordinal; }
    public String getValueType() { return valueType; }
    public boolean isRequired() { return required; }
    public int getMinOccurs() { return minOccurs; }
    public Integer getMaxOccurs() { return maxOccurs; }
    public Integer getNumericPrecision() { return numericPrecision; }
    public Integer getNumericScale() { return numericScale; }
    public String getUnitCode() { return unitCode; }
    public String getRoundingMode() { return roundingMode; }
    public String getMissingValuePolicy() { return missingValuePolicy; }
    public String getInputClass() { return inputClass; }
    public String getAggregationPolicy() { return aggregationPolicy; }
    public String getSemanticDefinition() { return semanticDefinition; }
}
