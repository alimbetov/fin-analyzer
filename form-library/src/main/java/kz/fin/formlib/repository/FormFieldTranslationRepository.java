package kz.fin.formlib.repository;
import kz.fin.formlib.domain.FormFieldTranslation;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface FormFieldTranslationRepository extends JpaRepository<FormFieldTranslation,UUID> {}
