package kz.fin.formlib.repository;
import kz.fin.formlib.domain.LibFormTranslation;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface LibFormTranslationRepository extends JpaRepository<LibFormTranslation,UUID> {java.util.List<LibFormTranslation> findByLocale(String locale);}
