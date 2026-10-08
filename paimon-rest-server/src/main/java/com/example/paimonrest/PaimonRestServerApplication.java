package com.example.paimonrest;

import com.example.paimonrest.config.RestServerProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Paimon REST Catalog 服务入口。
 *
 * <p>服务实现 Apache Paimon 的 REST Catalog OpenAPI 规格（v1），
 * 元数据持久化在关系库中，通过 JPA 访问。
 */
@SpringBootApplication
@EnableConfigurationProperties(RestServerProperties.class)
public class PaimonRestServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaimonRestServerApplication.class, args);
    }
}
