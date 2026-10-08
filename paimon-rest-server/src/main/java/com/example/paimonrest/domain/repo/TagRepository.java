package com.example.paimonrest.domain.repo;

import com.example.paimonrest.domain.entity.TagEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TagRepository extends JpaRepository<TagEntity, String> {

    Optional<TagEntity> findByTableIdAndTagName(String tableId, String tagName);

    List<TagEntity> findAllByTableIdOrderByTagNameAsc(String tableId);
}
