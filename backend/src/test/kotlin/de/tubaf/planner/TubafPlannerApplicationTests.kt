package de.tubaf.planner

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = ["spring.flyway.enabled=false"]) // Fallback falls Profil nicht greift
class TubafPlannerApplicationTests {

    @Test fun contextLoads() {}
}
