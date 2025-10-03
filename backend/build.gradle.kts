plugins {
        kotlin("jvm") version "2.0.21"
        kotlin("plugin.spring") version "2.0.21"
        id("org.springframework.boot") version "3.4.0"
        id("io.spring.dependency-management") version "1.1.6"
        kotlin("plugin.jpa") version "2.0.21"
        // id("io.gitlab.arturbosch.detekt") version "1.23.4" // Deaktiviert für jetzt
        id("com.ncorti.ktfmt.gradle") version "0.18.0"
}

group = "de.tubaf"
version = "0.0.1-SNAPSHOT"
description = "TUBAF Schedule Planning and Management System with Kotlin"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

configurations {
	compileOnly {
		extendsFrom(configurations.annotationProcessor.get())
	}
}

repositories {
	mavenCentral()
}

dependencies {
	// Spring Boot Starters
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-cache")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.springframework.boot:spring-boot-starter-webflux")
	implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
	
	    // WebJars for Frontend Libraries
    implementation("org.webjars:bootstrap:5.3.2")
    implementation("org.webjars:jquery:3.7.1")
    implementation("org.webjars:font-awesome:6.4.2")
    implementation("org.webjars.npm:chart.js:4.4.0")
	
	// Kotlin & Coroutines
	implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
	implementation("io.projectreactor.kotlin:reactor-kotlin-extensions")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
	
	// Database & Migrations
	implementation("org.flywaydb:flyway-core")
	implementation("org.flywaydb:flyway-database-postgresql")
	runtimeOnly("org.postgresql:postgresql")
	runtimeOnly("com.h2database:h2") // For development/testing
	
	// Web Scraping & HTTP
	implementation("org.jsoup:jsoup:1.18.1")
	implementation("com.squareup.okhttp3:okhttp:5.1.0")
	implementation("com.squareup.okhttp3:okhttp-urlconnection:5.1.0")
	implementation("io.ktor:ktor-client-core:2.3.12")
	implementation("io.ktor:ktor-client-cio:2.3.12")
	
	// API Documentation
	implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0")
	
	// Logging & Monitoring
	implementation("io.micrometer:micrometer-registry-prometheus")
	implementation("net.logstash.logback:logstash-logback-encoder:8.0")
	
	// Development Tools
	developmentOnly("org.springframework.boot:spring-boot-devtools")
	annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
	
	// Testing
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("io.projectreactor:reactor-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
	testImplementation("org.testcontainers:junit-jupiter")
	testImplementation("org.testcontainers:postgresql")
	testImplementation("com.ninja-squad:springmockk:4.0.2")
	testImplementation("io.mockk:mockk:1.13.12")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict")
	}
}

allOpen {
	annotation("jakarta.persistence.Entity")
	annotation("jakarta.persistence.MappedSuperclass")
	annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
        useJUnitPlatform()

        testLogging {
                showStandardStreams = true
        }
}

// Code Quality & Formatting
// detekt {
//	toolVersion = "1.23.7"
//	config.setFrom("$projectDir/config/detekt/detekt.yml")
//	buildUponDefaultConfig = true
// }

ktfmt {
	kotlinLangStyle()
}
