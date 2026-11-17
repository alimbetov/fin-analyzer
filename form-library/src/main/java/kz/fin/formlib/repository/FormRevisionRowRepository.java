package kz.fin.formlib.repository;
import kz.fin.formlib.domain.FormRevisionRow;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface FormRevisionRowRepository extends JpaRepository<FormRevisionRow,UUID> {java.util.List<FormRevisionRow> findByRevisionIdOrderByRowPositionAscRowIdAsc(UUID revisionId);}
