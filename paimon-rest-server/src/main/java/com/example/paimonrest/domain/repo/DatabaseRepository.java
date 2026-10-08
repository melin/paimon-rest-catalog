package com.example.paimonrest.domain.repo;

import com.example.paimonrest.domain.entity.DatabaseEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DatabaseRepository extends JpaRepository<DatabaseEntity, String> {

    Optional<DatabaseEntity> findByCatalogIdAndName(String catalogId, String name);

    boolean existsByCatalogIdAndName(String catalogId, String name);

    List<DatabaseEntity> findAllByCatalogIdOrderByNameAsc(String catalogId);
}
