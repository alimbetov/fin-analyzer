package kz.fin.formlib.repository;
import kz.fin.formlib.domain.LibMetricTranslation;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface LibMetricTranslationRepository extends JpaRepository<LibMetricTranslation,UUID> {java.util.List<LibMetricTranslation> findByLocale(String locale);}
