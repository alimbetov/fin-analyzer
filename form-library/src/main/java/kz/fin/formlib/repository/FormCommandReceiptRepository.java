package kz.fin.formlib.repository;
import kz.fin.formlib.domain.FormCommandReceipt;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface FormCommandReceiptRepository extends JpaRepository<FormCommandReceipt,UUID> {java.util.Optional<FormCommandReceipt> findByOrganizationIdAndIdempotencyKey(UUID organizationId,String idempotencyKey);}
