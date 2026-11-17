package kz.fin.formlib.repository;
import kz.fin.formlib.domain.LibFormEditSession;
import java.util.UUID;
import java.util.List;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
public interface LibFormEditSessionRepository extends JpaRepository<LibFormEditSession,UUID>{
  List<LibFormEditSession> findByFormIdAndStatus(UUID formId,String status);
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select x from LibFormEditSession x where x.id=:id")
  java.util.Optional<LibFormEditSession> lockById(@Param("id")UUID id);
}
