package io.github.melin.paimonrest.web;

import io.github.melin.paimonrest.dto.ConsoleDtos;
import io.github.melin.paimonrest.service.ConsoleMetaService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 控制台的元数据端点。
 *
 * <p><b>非 Polaris 规格端点</b>，是本工程为 Web 控制台加的只读扩展，
 * 语义与取舍写在 {@link ConsoleDtos} 的类注释里。
 *
 * <p>刻意放在 {@code /api/console/} 而不是 {@code /api/management/} 下：
 * 后者是管理规格的镜像，路径与会话状态码都被规格约束，往里塞规格外端点
 * 会让「管理 API 与 spec/polaris-management-service.yml 一一对应」这个不变量
 * 变得需要额外解释。控制台自己的端点单列一个命名空间，边界更清楚。
 */
@RestController
@RequestMapping("/api/console/v1")
@RequiredArgsConstructor
public class ConsoleMetaController {

    private final ConsoleMetaService metaService;

    /** {@code GET /api/console/v1/meta}：枚举取值与服务端配置摘要。 */
    @GetMapping("/meta")
    public ConsoleDtos.ConsoleMeta meta() {
        return metaService.meta();
    }
}
