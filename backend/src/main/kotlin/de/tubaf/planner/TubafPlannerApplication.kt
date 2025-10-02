package de.tubaf.planner

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.config.EnableJpaAuditing

@SpringBootApplication
@EnableJpaAuditing
class TubafPlannerApplication

fun main(args: Array<String>) {
    runApplication<TubafPlannerApplication>(*args)
}
