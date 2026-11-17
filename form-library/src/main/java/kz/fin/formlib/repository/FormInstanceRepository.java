package kz.fin.formlib.repository;
import kz.fin.formlib.domain.FormInstance;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
public interface FormInstanceRepository extends JpaRepository<FormInstance,UUID> {java.util.Optional<FormInstance> findByIdAndOrganizationId(UUID id,UUID organizationId);
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select i from FormInstance i where i.id=:id and i.organizationId=:organizationId")
java.util.Optional<FormInstance> lockByIdAndOrganizationId(@Param("id") UUID id,
  @Param("organizationId") UUID organizationId);
}
