package kz.fin.formlib.repository;
import kz.fin.formlib.domain.FormDefinition;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
public interface FormDefinitionRepository extends JpaRepository<FormDefinition,UUID> {java.util.Optional<FormDefinition> findByFormCode(String formCode); java.util.List<FormDefinition> findAllByOrderByFormCodeAsc();
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select f from FormDefinition f where f.id=:id")
java.util.Optional<FormDefinition> lockById(@Param("id") UUID id);
}
