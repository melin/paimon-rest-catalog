package com.example.paimonrest.domain.repo;

import com.example.paimonrest.domain.entity.SemanticViewEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SemanticViewRepository extends JpaRepository<SemanticViewEntity, String> {

    Optional<SemanticViewEntity> findByCatalogIdAndDatabaseIdAndName(String catalogId, String databaseId, String name);

    List<SemanticViewEntity> findAllByDatabaseIdOrderByNameAsc(String databaseId);
}
