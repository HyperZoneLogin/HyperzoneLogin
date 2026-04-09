import org.gradle.api.publish.maven.MavenPublication
import org.gradle.jvm.tasks.Jar

plugins {
    alias(libs.plugins.kotlin)
    id("maven-publish")
}

dependencies {
//    VC
    compileOnly(libs.velocityApi)
    compileOnly(libs.velocityProxy)
// Limbo is optional; implementations may provide a bridge adapter. Do not
// require the limbo API here at compile time.
    // Netty is needed by API types (connection/player extensions) but only at compile time
    compileOnly(libs.nettyAll)
// Exposed ORM
    implementation(libs.exposedCore)

    testImplementation(platform(libs.junitBom))
    testImplementation(libs.junitJupiter)
    testRuntimeOnly(libs.junitPlatformLauncher)
}

tasks.test {
    useJUnitPlatform()
}

// Publish configuration to allow `api` to be published to the local Maven repository
// Use `./gradlew :api:publishToMavenLocal` to publish.
publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            groupId = project.group.toString()
            artifactId = "api"
            version = project.version.toString()
            from(components["java"])

            // include sources JAR for better IDE support when consumed from mavenLocal
            val sourcesJar = tasks.register("sourcesJar", Jar::class) {
                archiveClassifier.set("sources")
                from(sourceSets.main.get().allSource)
            }
            artifact(sourcesJar)
        }
    }

    repositories {
        mavenLocal()
    }
}
