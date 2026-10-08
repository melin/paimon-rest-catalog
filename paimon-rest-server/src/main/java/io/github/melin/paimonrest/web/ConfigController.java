package io.github.melin.paimonrest.web;

import io.github.melin.paimonrest.dto.CommonDtos;
import io.github.melin.paimonrest.service.CatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /v1/config}：目录发现入口。
 */
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class ConfigController {

    private final CatalogService catalogService;

    @GetMapping("/config")
    public CommonDtos.ConfigResponse config(@RequestParam(required = false) String warehouse) {
        return catalogService.config(warehouse);
    }
}
