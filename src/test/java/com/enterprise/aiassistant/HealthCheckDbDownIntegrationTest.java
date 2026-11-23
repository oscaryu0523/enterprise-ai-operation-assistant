package com.enterprise.aiassistant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

// BDD 3.3: health returns DOWN when DB is unavailable
// database-platform bypasses dialect auto-detection (which needs a live connection).
// initialization-fail-timeout=-1 lets HikariCP start without a live pool.
// minimum-idle=0 prevents eager connection acquisition at startup.
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {
      "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
      "spring.jpa.properties.jakarta.persistence.schema-generation.database.action=none",
      "spring.datasource.url=jdbc:postgresql://localhost:1/nonexistent",
      "spring.datasource.hikari.connection-timeout=250",
      "spring.datasource.hikari.initialization-fail-timeout=-1",
      "spring.datasource.hikari.minimum-idle=0"
    })
@AutoConfigureMockMvc
class HealthCheckDbDownIntegrationTest {

  @Autowired MockMvc mockMvc;

  @Test
  void health_returnsDown_whenDbUnavailable() throws Exception {
    mockMvc
        .perform(get("/actuator/health"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.status").value("DOWN"))
        .andExpect(jsonPath("$.components.db.status").value("DOWN"));
  }
}
