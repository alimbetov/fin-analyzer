package kz.fin.formlib.repository;
import kz.fin.formlib.domain.LibCurrencyTranslation;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface LibCurrencyTranslationRepository extends JpaRepository<LibCurrencyTranslation,UUID> {java.util.List<LibCurrencyTranslation> findByLocale(String locale);}
