package com.example.paimonrest.domain.repo;

import com.example.paimonrest.domain.entity.BranchEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BranchRepository extends JpaRepository<BranchEntity, String> {

    Optional<BranchEntity> findByTableIdAndName(String tableId, String name);

    boolean existsByTableIdAndName(String tableId, String name);

    List<BranchEntity> findAllByTableIdOrderByNameAsc(String tableId);
}
