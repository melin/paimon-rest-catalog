package com.example.paimonrest.domain.repo;

import com.example.paimonrest.domain.entity.TableEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TableRepository extends JpaRepository<TableEntity, String> {

    Optional<TableEntity> findByCatalogIdAndDatabaseIdAndName(String catalogId, String databaseId, String name);

    boolean existsByDatabaseIdAndName(String databaseId, String name);

    List<TableEntity> findAllByDatabaseIdOrderByNameAsc(String databaseId);

    List<TableEntity> findAllByCatalogIdOrderByDatabaseIdAscNameAsc(String catalogId);
}
