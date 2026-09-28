plugins {
    kotlin("jvm")
    `maven-publish`
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io")
    if (providers.gradleProperty("useMavenLocal").orNull == "true") {
        mavenLocal() // opt-in via -PuseMavenLocal=true — never on CI
    }
}

dependencies {
    api(project(":nexus-core"))
    api(project(":nexus-i18n"))
    api(project(":nexus-scheduler"))
    api("com.github.stefvanschie.inventoryframework:IF:0.12.2-SNAPSHOT")
    compileOnly("io.papermc.paper:paper-api:26.2.build.129-stable")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testImplementation("io.papermc.paper:paper-api:26.2.build.129-stable")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(25)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "net.badgersmc"
            artifactId = "nexus-paper-gui"
            version = rootProject.version.toString()
            from(components["java"])
        }
    }
}
