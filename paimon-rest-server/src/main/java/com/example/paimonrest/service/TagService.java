package com.example.paimonrest.service;

import com.example.paimonrest.domain.entity.TableEntity;
import com.example.paimonrest.domain.entity.TagEntity;
import com.example.paimonrest.domain.repo.TableSnapshotRepository;
import com.example.paimonrest.domain.repo.TagRepository;
import com.example.paimonrest.dto.TagDtos;
import com.example.paimonrest.support.ApiException;
import com.example.paimonrest.support.Paging;
import com.example.paimonrest.support.Values;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 标签管理。标签给快照起一个稳定名称，用于长期保留与按名回滚。
 */
@Service
@RequiredArgsConstructor
public class TagService {

    private final TagRepository tagRepository;
    private final TableSnapshotRepository snapshotRepository;
    private final TableLookup tableLookup;
    private final TableService tableService;

    @Transactional(readOnly = true)
    public TagDtos.ListTagsResponse list(String prefix, String database, String table,
                                         Integer maxResults, String pageToken, String tagNamePrefix) {
        TableEntity target = tableLookup.requireTable(prefix, database, table);
        List<String> tags = new ArrayList<>();
        for (TagEntity tag : tagRepository.findAllByTableIdOrderByTagNameAsc(target.getId())) {
            if (tagNamePrefix == null || tagNamePrefix.isBlank()
                    || tag.getTagName().startsWith(tagNamePrefix)) {
                tags.add(tag.getTagName());
            }
        }
        Paging.Slice<String> slice = Paging.slice(tags, Paging.offset(pageToken), Paging.pageSize(maxResults, 100, 1000));
        return new TagDtos.ListTagsResponse(slice.items(), slice.nextPageToken());
    }

    /**
     * 创建标签。未指定 {@code snapshotId} 时指向最新快照；未提供快照的表会返回 404。
     */
    @Transactional
    public void create(String prefix, String database, String table, TagDtos.CreateTagRequest request) {
        TableEntity target = tableLookup.requireTable(prefix, database, table);
        if (request == null || request.tagName() == null || request.tagName().isBlank()) {
            throw ApiException.badRequest("tagName must not be blank");
        }
        if (tagRepository.findByTableIdAndTagName(target.getId(), request.tagName()).isPresent()) {
            if (Values.bool(request.ignoreIfExists(), false)) {
                return;
            }
            throw ApiException.tagAlreadyExist(request.tagName());
        }
        Long snapshotId = request.snapshotId() != null
                ? request.snapshotId()
                : snapshotRepository.findFirstByTableIdOrderBySnapshotIdDesc(target.getId())
                        .map(snapshot -> snapshot.getSnapshotId())
                        .orElseThrow(() -> ApiException.snapshotNotExist(0));
        snapshotRepository.findByTableIdAndSnapshotId(target.getId(), snapshotId)
                .orElseThrow(() -> ApiException.snapshotNotExist(snapshotId));

        TagEntity tag = new TagEntity();
        tag.setId(Paging.newId());
        tag.setTableId(target.getId());
        tag.setTagName(request.tagName());
        tag.setSnapshotId(snapshotId);
        tag.setTagCreateTime(System.currentTimeMillis());
        tag.setTagTimeRetained(request.timeRetained());
        tagRepository.save(tag);
    }

    @Transactional(readOnly = true)
    public TagDtos.GetTagResponse get(String prefix, String database, String table, String tagName) {
        TableEntity target = tableLookup.requireTable(prefix, database, table);
        TagEntity tag = tagRepository.findByTableIdAndTagName(target.getId(), tagName)
                .orElseThrow(() -> ApiException.tagNotExist(tagName));
        return new TagDtos.GetTagResponse(
                tag.getTagName(),
                tableService.snapshot(target.getId(), tag.getSnapshotId()),
                tag.getTagCreateTime(),
                tag.getTagTimeRetained());
    }

    @Transactional
    public void delete(String prefix, String database, String table, String tagName) {
        TableEntity target = tableLookup.requireTable(prefix, database, table);
        TagEntity tag = tagRepository.findByTableIdAndTagName(target.getId(), tagName)
                .orElseThrow(() -> ApiException.tagNotExist(tagName));
        tagRepository.delete(tag);
    }
}
