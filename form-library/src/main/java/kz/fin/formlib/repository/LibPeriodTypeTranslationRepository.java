package kz.fin.formlib.repository;
import kz.fin.formlib.domain.LibPeriodTypeTranslation;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface LibPeriodTypeTranslationRepository extends JpaRepository<LibPeriodTypeTranslation,UUID> {java.util.List<LibPeriodTypeTranslation> findByLocale(String locale);}
