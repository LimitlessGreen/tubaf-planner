package de.tubaf.planner.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@ConfigurationProperties(prefix = "tubaf.scraper")
class ScrapingConfiguration {
    var baseUrl: String = "https://evlvz.hrz.tu-freiberg.de/~vover/"
    var timeout: Long = 30000
    var userAgent: String =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    var maxRetries: Int = 3
    var retryDelay: Long = 1000
    var respectfulDelay: Long = 500
    var headless: Boolean = true

    // Study program mappings for TUBAF
    var studyProgramMappings: Map<String, StudyProgramMapping> =
        mapOf(
            "ANBI" to StudyProgramMapping("Angewandte Informatik", "BACHELOR"),
            "ANMA" to StudyProgramMapping("Angewandte Mathematik", "BACHELOR"),
            "BAET" to StudyProgramMapping("Elektrotechnik", "BACHELOR"),
            "BAIN" to StudyProgramMapping("Informatik", "BACHELOR"),
            "BAUM" to StudyProgramMapping("Umwelt-Engineering", "BACHELOR"),
            "BAWI" to StudyProgramMapping("Wirtschaftsingenieurwesen", "BACHELOR"),
            "MABA" to StudyProgramMapping("Mathematik", "MASTER"),
            "MAET" to StudyProgramMapping("Elektrotechnik", "MASTER"),
            "MAIN" to StudyProgramMapping("Informatik", "MASTER"),
            "MAUM" to StudyProgramMapping("Umwelt-Engineering", "MASTER")
        )

    // Room building mappings
    var buildingMappings: Map<String, String> =
        mapOf(
            "HSZ" to "Hauptschulgebäude",
            "FEZ" to "Forschungs- und Entwicklungszentrum",
            "GEO" to "Geowissenschaften",
            "IUZ" to "Informatik- und Umweltzentrum",
            "CHE" to "Chemie",
            "PHY" to "Physik",
            "MAS" to "Maschinenbau",
            "BAU" to "Bauingenieurwesen"
        )

    // Course type mappings
    var courseTypeMappings: Map<String, CourseTypeMapping> =
        mapOf(
            "V" to CourseTypeMapping("Vorlesung", "Lecture"),
            "Ü" to CourseTypeMapping("Übung", "Exercise"),
            "S" to CourseTypeMapping("Seminar", "Seminar"),
            "P" to CourseTypeMapping("Praktikum", "Lab/Practical")
        )
}

data class StudyProgramMapping(val fullName: String, val degreeType: String)

data class CourseTypeMapping(val germanName: String, val englishName: String)

data class BuildingMapping(val fullName: String, val shortName: String)
