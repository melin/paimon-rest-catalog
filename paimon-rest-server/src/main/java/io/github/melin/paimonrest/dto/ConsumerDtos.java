package io.github.melin.paimonrest.dto;

import java.util.List;

/**
 * 流式消费者（consumer）相关 DTO。
 */
public final class ConsumerDtos {

    private ConsumerDtos() {
    }

    /** nextSnapshot 为消费位点，即下一个待消费的快照 id。 */
    public record ConsumerInfo(String consumerId, Long nextSnapshot) {
    }

    public record ListConsumersResponse(List<ConsumerInfo> consumers, String nextPageToken) {
    }

    /** nextSnapshotId 为空表示删除该消费者记录。 */
    public record ResetConsumerRequest(String consumerId, Long nextSnapshotId) {
    }
}
