package com.example.paimonrest.domain.repo;

import com.example.paimonrest.domain.entity.PrincipalEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 管理主体仓储。 */
public interface PrincipalRepository extends JpaRepository<PrincipalEntity, String> {

    Optional<PrincipalEntity> findByName(String name);

    boolean existsByName(String name);

    List<PrincipalEntity> findAllByOrderByNameAsc();
}
