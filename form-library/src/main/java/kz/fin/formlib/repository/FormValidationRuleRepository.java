package kz.fin.formlib.repository;
import kz.fin.formlib.domain.FormValidationRule;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface FormValidationRuleRepository extends JpaRepository<FormValidationRule,UUID> {}
