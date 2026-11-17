package kz.fin.formlib.repository;
import kz.fin.formlib.domain.LibCurrency;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface LibCurrencyRepository extends JpaRepository<LibCurrency,String> {java.util.List<LibCurrency> findAllByOrderByCodeAsc();}
