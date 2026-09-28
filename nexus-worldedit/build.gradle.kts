plugins {
    kotlin("jvm")
    `maven-publish`
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.enginehub.org/repo/")
    maven("https://repo.fastasyncworldedit.com/releases")
    if (providers.gradleProperty("useMavenLocal").orNull == "true") {
        mavenLocal()
    }
}

dependencies {
    api(project(":nexus-core"))
    compileOnly("io.papermc.paper:paper-api:26.2.build.129-stable")
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.3.0")
    compileOnly("com.fastasyncworldedit:FastAsyncWorldEdit-Bukkit:2.11.0")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
}

tasks.test { useJUnitPlatform() }

kotlin { jvmToolchain(25) }

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "net.badgersmc"
            artifactId = "nexus-worldedit"
            version = rootProject.version.toString()
            from(components["java"])
        }
    }
}
