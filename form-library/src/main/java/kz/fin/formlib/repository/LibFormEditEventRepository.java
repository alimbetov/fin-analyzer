package kz.fin.formlib.repository;
import kz.fin.formlib.domain.LibFormEditEvent;
import java.util.UUID;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
public interface LibFormEditEventRepository extends JpaRepository<LibFormEditEvent,UUID>{
  List<LibFormEditEvent> findByEditSessionIdOrderByEditRevisionAsc(UUID editSessionId);
}
