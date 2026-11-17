package kz.fin.backend.methodology.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/**
 * Read-only access to persisted method entities and all draft parameter names.
 * No projection here grants method S2 or financial computation permissions.
 */
public interface MethodologyRepository extends JpaRepository<MethodologyEntity, String> {
    @Query("""
            select distinct m from MethodologyEntity m
            left join fetch m.parameters
            order by m.methodCode
            """)
    List<MethodologyEntity> findAllWithParameterCandidates();
}
