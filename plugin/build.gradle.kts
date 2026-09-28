plugins {
    kotlin("jvm") version "2.3.20"
    id("com.gradleup.shadow") version "9.2.2"
}

group = "com.randomwars"
version = "0.1.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
}

kotlin {
    jvmToolchain(21)
}

// 저장소 루트의 resourcepack/ 을 zip으로 묶어 플러그인 jar 안에 pack.zip 으로 넣는다.
val packZip by tasks.registering(Zip::class) {
    from(rootDir.resolve("../resourcepack"))
    archiveFileName.set("pack.zip")
    destinationDirectory.set(layout.buildDirectory.dir("pack"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.processResources {
    from(packZip)
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.shadowJar {
    archiveClassifier.set("")
    archiveFileName.set("RandomWars.jar")
    relocate("kotlin", "com.randomwars.libs.kotlin")
}

tasks.jar {
    enabled = false
}

// 빌드 결과를 로컬 테스트 서버 plugins/ 로 복사한다.
val deploy by tasks.registering(Copy::class) {
    from(tasks.shadowJar)
    into(rootDir.resolve("../server/plugins"))
}

tasks.build {
    dependsOn(tasks.shadowJar)
    finalizedBy(deploy)
}
