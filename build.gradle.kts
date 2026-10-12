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
    implementation(platform("org.springframework.modulith:spring-modulith-bom:2.1.2"))

    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-session-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("org.springframework.modulith:spring-modulith-starter-jpa")
    implementation("org.springframework.modulith:spring-modulith-events-kafka")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    implementation("com.github.f4b6a3:uuid-creator:6.1.1")
    implementation("io.github.oshai:kotlin-logging-jvm:8.0.4")
    implementation("com.linecorp.kotlin-jdsl:jpql-dsl:3.9.0")
    implementation("com.linecorp.kotlin-jdsl:jpql-render:3.9.0")
    implementation("org.springframework.boot:spring-boot-flyway")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    runtimeOnly("org.springframework.modulith:spring-modulith-observability-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-opentelemetry-test")
    testImplementation("io.opentelemetry:opentelemetry-sdk-testing")
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
    // Fixture Monkey는 jqwik의 API만 쓴다. jqwik 엔진은 `@Property` 테스트가 없는데도 실행 기록 파일을 만든다.
    useJUnitPlatform { excludeEngines("jqwik") }
    // fork마다 PostgreSQL·Redis·Kafka 컨테이너를 따로 띄워 DB와 consumer group을 같이 쓰지 않는다. CI(4 vCPU)에서 2와 3을 재서 고른 고정값이다.
    // CPU 수에서 정하지 않는다. 로컬과 CI가 같은 fork 수로 돈다.
    maxParallelForks = 2
    // AlertRulesTest·ObservabilityConfigurationTest가 알림 규칙과 runbook을 읽는다. 입력으로 두지 않으면 규칙만 고쳤을 때 테스트가 up-to-date로 건너뛰어진다.
    inputs.dir("docker/prometheus").withPropertyName("alertRules").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file("docs/wiki/runbook.md").withPropertyName("runbook").withPathSensitivity(PathSensitivity.RELATIVE)
    // REST Docs 스니펫. output으로 두지 않으면 test가 build cache에서 올 때 스니펫이 되살아나지 않는다.
    outputs.dir(layout.buildDirectory.dir("generated-snippets")).withPropertyName("restDocsSnippets")
    // CI가 test를 job 여러 개로 나눌 때 쓴다(-PtestShard=1/2). 바깥 클래스 이름의 해시로 나눠 모든 클래스가 정확히 한 job에서 돈다. 없으면 전부 돈다.
    providers.gradleProperty("testShard").orNull?.let { shard ->
        val (index, count) = Regex("""(\d+)/(\d+)""").matchEntire(shard)?.destructured?.let { (i, c) -> i.toInt() to c.toInt() }
            ?: throw GradleException("testShard는 번호/개수 형식이어야 한다: $shard")
        if (index !in 1..count) throw GradleException("testShard의 번호는 1부터 개수까지다: $shard")
        exclude { !it.isDirectory && it.relativePath.pathString.removeSuffix(".class").substringBefore('$').hashCode().mod(count) != index - 1 }
    }
}
