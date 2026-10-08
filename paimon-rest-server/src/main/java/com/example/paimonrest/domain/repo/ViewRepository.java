package com.example.paimonrest.domain.repo;

import com.example.paimonrest.domain.entity.ViewEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ViewRepository extends JpaRepository<ViewEntity, String> {

    Optional<ViewEntity> findByCatalogIdAndDatabaseIdAndName(String catalogId, String databaseId, String name);

    boolean existsByDatabaseIdAndName(String databaseId, String name);

    List<ViewEntity> findAllByDatabaseIdOrderByNameAsc(String databaseId);

    List<ViewEntity> findAllByCatalogIdOrderByDatabaseIdAscNameAsc(String catalogId);
}
