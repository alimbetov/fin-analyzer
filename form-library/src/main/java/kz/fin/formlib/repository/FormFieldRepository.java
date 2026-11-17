package kz.fin.formlib.repository;
import kz.fin.formlib.domain.FormField;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface FormFieldRepository extends JpaRepository<FormField,UUID> {java.util.List<FormField> findByFormVersionIdOrderByDisplayOrderAsc(UUID formVersionId);}
