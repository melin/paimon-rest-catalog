package io.github.melin.paimonrest.domain.repo;

import io.github.melin.paimonrest.domain.entity.DatabaseEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DatabaseRepository extends JpaRepository<DatabaseEntity, String> {

    Optional<DatabaseEntity> findByCatalogIdAndName(String catalogId, String name);

    boolean existsByCatalogIdAndName(String catalogId, String name);

    List<DatabaseEntity> findAllByCatalogIdOrderByNameAsc(String catalogId);
}
