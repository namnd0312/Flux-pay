package com.fluxpay.gateway;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base for gateway integration tests: the full application boots against a real
 * PostgreSQL 16 started by Testcontainers and is driven through MockMvc.
 *
 * <p>{@code @ServiceConnection} hands the container's JDBC url, user, and
 * password to Spring Boot automatically, so no datasource config is needed in
 * the test profile. Flyway then applies the real migrations, which is what makes
 * {@code ddl-auto=validate} meaningful in tests. Subclasses add behaviour;
 * Docker must be running or the container fails fast with a clear message.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MutableClock.Config.class)
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

    static {
        POSTGRES.start();
    }
}
