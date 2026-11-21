plugins {
    id("java")
    id("com.gradleup.shadow") version "9.0.0"
}

group = "me.stormizn"
version = "1.0.0"

repositories {
    mavenCentral()
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencies {
    //paper api
    compileOnly("io.papermc.paper:paper-api:26.2.build.92-stable")

    //database
    // Paper already ships org.slf4j:slf4j-api (it is a paper-api dependency), so the
    // copies HikariCP and sqlite-jdbc drag in must not end up inside the shaded jar:
    // a plugin-local slf4j-api shadows the server one and silences pool logging.
    implementation("com.zaxxer:HikariCP:5.1.0") {
        exclude(group = "org.slf4j", module = "slf4j-api")
    }
    implementation("org.xerial:sqlite-jdbc:3.49.1.0") {
        exclude(group = "org.slf4j", module = "slf4j-api")
    }

    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Paper supplies this at runtime; tests need it because HikariCP reads it on class load.
    testRuntimeOnly("org.slf4j:slf4j-api:2.0.17")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
    options.compilerArgs.add("-Xlint:deprecation")
}

tasks.processResources {
    val props = mapOf("version" to version)
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}

// The shaded jar is the only artifact we ship; leaving the plain jar enabled would
// make both tasks write the same file name and silently overwrite one another.
tasks.jar {
    enabled = false
}

tasks.shadowJar {
    archiveClassifier.set("")

    relocate("com.zaxxer.hikari", "me.stormizn.playerpings.libs.hikari")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.test {
    useJUnitPlatform()
}
