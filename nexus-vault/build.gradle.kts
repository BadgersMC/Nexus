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
    compileOnly("io.papermc.paper:paper-api:26.2.build.129-stable")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testImplementation("io.papermc.paper:paper-api:26.2.build.129-stable")
    testImplementation("com.github.MilkBowl:VaultAPI:1.7.1")
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
            artifactId = "nexus-vault"
            version = rootProject.version.toString()
            from(components["java"])
        }
    }
}
