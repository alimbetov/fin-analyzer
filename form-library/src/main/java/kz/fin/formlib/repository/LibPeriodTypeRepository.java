package kz.fin.formlib.repository;
import kz.fin.formlib.domain.LibPeriodType;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface LibPeriodTypeRepository extends JpaRepository<LibPeriodType,String> {java.util.List<LibPeriodType> findAllByOrderBySortOrderAsc();}
