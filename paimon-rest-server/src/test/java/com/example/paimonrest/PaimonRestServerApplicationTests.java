package com.example.paimonrest;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** {@code test} profile 把数据源换成内存 H2（见 {@code src/test/resources/application-test.yml}）。 */
@SpringBootTest
@ActiveProfiles("test")
class PaimonRestServerApplicationTests {

	@Test
	void contextLoads() {
	}

}
