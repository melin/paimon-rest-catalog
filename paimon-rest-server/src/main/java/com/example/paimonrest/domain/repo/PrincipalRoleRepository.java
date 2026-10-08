package com.example.paimonrest.domain.repo;

import com.example.paimonrest.domain.entity.PrincipalRoleEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** principal role 仓储。 */
public interface PrincipalRoleRepository extends JpaRepository<PrincipalRoleEntity, String> {

    Optional<PrincipalRoleEntity> findByName(String name);

    boolean existsByName(String name);

    List<PrincipalRoleEntity> findAllByOrderByNameAsc();

    List<PrincipalRoleEntity> findAllByIdIn(Iterable<String> ids);
}
