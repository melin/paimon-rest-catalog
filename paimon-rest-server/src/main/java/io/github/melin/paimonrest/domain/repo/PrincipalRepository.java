package io.github.melin.paimonrest.domain.repo;

import io.github.melin.paimonrest.domain.entity.PrincipalEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 管理主体仓储。 */
public interface PrincipalRepository extends JpaRepository<PrincipalEntity, String> {

    Optional<PrincipalEntity> findByName(String name);

    /**
     * 按 clientId 查主体。
     *
     * <p>OAuth 2.0 客户端凭据流程的入口：请求里带的是 clientId 而不是主体名，
     * 而授权判定需要主体名，因此这一步是必须的翻译。
     * 返回值可能是空——调用方不能据此短路掉密钥比较，
     * 否则响应时间会泄漏「这个 clientId 存不存在」。
     */
    Optional<PrincipalEntity> findByClientId(String clientId);

    boolean existsByName(String name);

    List<PrincipalEntity> findAllByOrderByNameAsc();
}
