package io.github.melin.paimonrest.domain.repo;

import io.github.melin.paimonrest.domain.entity.FunctionEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FunctionRepository extends JpaRepository<FunctionEntity, String> {

    Optional<FunctionEntity> findByCatalogIdAndDatabaseIdAndName(String catalogId, String databaseId, String name);

    boolean existsByDatabaseIdAndName(String databaseId, String name);

    List<FunctionEntity> findAllByDatabaseIdOrderByNameAsc(String databaseId);

    List<FunctionEntity> findAllByCatalogIdOrderByDatabaseIdAscNameAsc(String catalogId);
}
