package kz.fin.backend.formcontract.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

/**
 * Stable catalog identity, distinct from every immutable contract revision.
 * Catalog codes do not imply qualification or financial execution permission.
 */
@Entity
@Immutable
@Table(schema = "fin_form", name = "form_definition")
public class FormDefinitionEntity {
    @Id
    @Column(name = "form_code", length = 64, nullable = false)
    private String formCode;

    @Column(name = "catalog_code", length = 32, nullable = false)
    private String catalogCode;

    @Column(name = "form_kind", length = 48, nullable = false)
    private String formKind;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected FormDefinitionEntity() { }

    public String getFormCode() { return formCode; }
    public String getCatalogCode() { return catalogCode; }
    public String getFormKind() { return formKind; }
    public Instant getCreatedAt() { return createdAt; }
}
