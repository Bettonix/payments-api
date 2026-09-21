// payments-api/build.gradle.kts

plugins {
    java
    id("org.springframework.boot") version "3.3.5"
    id("io.spring.dependency-management") version "1.1.6"
}

group = "com.portfolio"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

extra["testcontainersVersion"] = "1.20.4"
extra["resilience4jVersion"] = "2.2.0"

dependencyManagement {
    imports {
        mavenBom("org.testcontainers:testcontainers-bom:${property("testcontainersVersion")}")
        mavenBom("io.github.resilience4j:resilience4j-bom:${property("resilience4jVersion")}")
    }
}

dependencies {
    // core
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-aop")

    // observability
    implementation("io.micrometer:micrometer-registry-prometheus")
    // opentelemetry starter requer collector rodando; adicionado em V2 com profile opcional
    // implementation("io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter:2.9.0")

    // resilience
    implementation("io.github.resilience4j:resilience4j-spring-boot3")
    implementation("io.github.resilience4j:resilience4j-reactor")

    // migration
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // postgres + cache
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    // kafka
    implementation("org.springframework.kafka:spring-kafka")

    // test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:kafka")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
}

tasks.withType<Test>().configureEach {
    // fast feedback locally
    systemProperty("spring.profiles.active", System.getProperty("spring.profiles.active", "test"))
}

// jacoco for coverage — uses plugin defaults (tool version 0.8.x)