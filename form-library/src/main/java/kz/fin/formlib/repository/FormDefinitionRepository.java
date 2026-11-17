package kz.fin.formlib.repository;
import kz.fin.formlib.domain.FormDefinition;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface FormDefinitionRepository extends JpaRepository<FormDefinition,UUID> {java.util.Optional<FormDefinition> findByFormCode(String formCode); java.util.List<FormDefinition> findAllByOrderByFormCodeAsc();}
