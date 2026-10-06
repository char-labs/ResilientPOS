plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.spring") version "2.4.10"
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("plugin.jpa") version "2.4.10"
}

group = "com.resilientpos"
version = "0.0.1-SNAPSHOT"
description = "ResilientPOS"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

// Boot 4.1.1 BOM의 취약한 전이 의존성을 올린다. Boot는 버전 올림을 릴리스 직전에 하므로 그때까지 덮어쓴다
// (spring-projects/spring-boot#51476). 세 줄 모두 Boot 4.1.2 이상으로 올리는 PR에서 지운다.
// tomcat-embed-core 11.0.24: CVE-2026-65905·65182·68525(11.0.25에서 수정)
extra["tomcat.version"] = "11.0.26"
// jackson-core·jackson-databind 3.1.5: CVE-2026-89425·89407·91777·91776·68497·83557·19032(3.1.7에서 수정)
extra["jackson-bom.version"] = "3.1.7"
// kafka-clients 4.2.1이 쓰는 lz4-java 1.10.1: CVE-2026-59949(1.11.1에서 수정, kafka-clients 4.2.2는 1.11.2)
extra["kafka.version"] = "4.2.2"

dependencies {
    implementation(platform("org.springframework.modulith:spring-modulith-bom:2.1.1"))

    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("org.springframework.modulith:spring-modulith-starter-jpa")
    implementation("org.springframework.modulith:spring-modulith-events-kafka")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("com.github.f4b6a3:uuid-creator:6.1.1")
    implementation("io.github.oshai:kotlin-logging-jvm:8.0.4")
    implementation("com.linecorp.kotlin-jdsl:jpql-dsl:3.9.0")
    implementation("com.linecorp.kotlin-jdsl:jpql-render:3.9.0")
    implementation("org.springframework.boot:spring-boot-flyway")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-restdocs")
    testImplementation("org.springframework.restdocs:spring-restdocs-mockmvc")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("org.springframework.modulith:spring-modulith-events-core")
    testImplementation("org.awaitility:awaitility")
    testImplementation("com.tngtech.archunit:archunit:1.4.2")
    testImplementation("com.navercorp.fixturemonkey:fixture-monkey-starter-kotlin:1.2.3")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-kafka")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
