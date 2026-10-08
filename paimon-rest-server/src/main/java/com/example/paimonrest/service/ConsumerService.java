package com.example.paimonrest.service;

import com.example.paimonrest.config.RestServerProperties;
import com.example.paimonrest.domain.entity.ConsumerEntity;
import com.example.paimonrest.domain.entity.TableEntity;
import com.example.paimonrest.domain.repo.ConsumerRepository;
import com.example.paimonrest.dto.ConsumerDtos;
import com.example.paimonrest.support.ApiException;
import com.example.paimonrest.support.Paging;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 流式消费者进度管理。
 *
 * <p>{@code nextSnapshot} 表示下一次待消费的快照 id；重置为 {@code null} 等价于删除该消费者。
 */
@Service
@RequiredArgsConstructor
public class ConsumerService {

    private final ConsumerRepository consumerRepository;
    private final TableLookup tableLookup;
    private final RestServerProperties properties;

    @Transactional(readOnly = true)
    public ConsumerDtos.ListConsumersResponse list(String prefix, String database, String table,
                                                   Integer maxResults, String pageToken) {
        TableEntity target = tableLookup.requireTable(prefix, database, table);
        List<ConsumerDtos.ConsumerInfo> consumers = new ArrayList<>();
        for (ConsumerEntity consumer : consumerRepository.findAllByTableIdOrderByConsumerIdAsc(target.getId())) {
            consumers.add(new ConsumerDtos.ConsumerInfo(consumer.getConsumerId(), consumer.getNextSnapshot()));
        }
        Paging.Slice<ConsumerDtos.ConsumerInfo> slice = Paging.slice(consumers,
                Paging.offset(pageToken),
                Paging.pageSize(maxResults, properties.getDefaultPageSize(), properties.getMaxPageSize()));
        return new ConsumerDtos.ListConsumersResponse(slice.items(), slice.nextPageToken());
    }

    @Transactional
    public void reset(String prefix, String database, String table, ConsumerDtos.ResetConsumerRequest request) {
        TableEntity target = tableLookup.requireTable(prefix, database, table);
        if (request == null || request.consumerId() == null || request.consumerId().isBlank()) {
            throw ApiException.badRequest("consumerId must not be blank");
        }
        ConsumerEntity existing = consumerRepository
                .findByTableIdAndConsumerId(target.getId(), request.consumerId())
                .orElse(null);

        if (request.nextSnapshotId() == null) {
            if (existing != null) {
                consumerRepository.delete(existing);
            }
            return;
        }
        ConsumerEntity consumer = existing != null ? existing : new ConsumerEntity();
        if (consumer.getId() == null) {
            consumer.setId(Paging.newId());
            consumer.setTableId(target.getId());
            consumer.setConsumerId(request.consumerId());
        }
        consumer.setNextSnapshot(request.nextSnapshotId());
        consumer.setUpdatedAt(System.currentTimeMillis());
        consumerRepository.save(consumer);
    }
}
