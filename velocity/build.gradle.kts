import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.provider.Provider

fun moduleId(dependency: Provider<MinimalExternalModuleDependency>): String {
    val module = dependency.get().module
    return "${module.group}:${module.name}"
}

plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.shadow)
}

dependencies {
    // Modules (auth-offline, auth-yggd, data-merge) are now separate Velocity plugins
    // and will register themselves with the main plugin at runtime. Do not include
    // them as project dependencies here so they are not bundled into the main shadow jar.
    implementation(project(":api"))
//    implementation(project(":vcinjector"))

// Exposed ORM
    implementation(libs.exposedCore)
    implementation(libs.exposedDao)
    implementation(libs.exposedJdbc)
    implementation(libs.exposedJavaTime)
// Database drivers
    implementation(libs.h2)
    implementation(libs.sqliteJdbc)
    implementation(libs.mysql)
    implementation(libs.mariadb)
    implementation(libs.hikari)
//    VC
    compileOnly(libs.velocityApi)
    compileOnly(libs.velocityProxy) // From Elytrium Repo.
//    limbo
    compileOnly(libs.limboApi)

    implementation(libs.configurateExtraKotlin)
    compileOnly(libs.configurateHocon)
    compileOnly(libs.nettyAll)
    compileOnly(libs.gson)
    compileOnly(libs.log4jApi)
    compileOnly(libs.adventureTextSerializerGson)
    compileOnly(libs.adventureTextLoggerSlf4j)
    compileOnly(libs.adventureTextMinimessage)
    compileOnly(libs.guice)
    compileOnly(libs.guava)
    compileOnly(libs.brigadier)

    annotationProcessor(libs.velocityApi)

    testImplementation(platform(libs.junitBom))
    testImplementation(libs.junitJupiter)
}

tasks {
    shadowJar {
        archiveBaseName.set("HyperZoneLogin")
        archiveClassifier.set("")
        dependencies {
//            mckotlin带
            exclude(dependency(moduleId(libs.kotlinStdlib)))
            exclude(dependency(moduleId(libs.kotlinReflect)))

            exclude(dependency(moduleId(libs.jetbrainsAnnotations)))
//            extra-kotlin
            include(dependency(moduleId(libs.configurateExtraKotlin)))
//            Exposed ORM
            include(dependency(moduleId(libs.exposedCore)))
            include(dependency(moduleId(libs.exposedDao)))
            include(dependency(moduleId(libs.exposedJdbc)))
            include(dependency(moduleId(libs.exposedJavaTime)))
//            Database drivers
            include(dependency(moduleId(libs.h2)))
            include(dependency(moduleId(libs.sqliteJdbc)))
            include(dependency(moduleId(libs.mysql)))
            include(dependency(moduleId(libs.mariadb)))
            include(dependency(moduleId(libs.hikari)))
//           api
            include(dependency(":api"))
//            模块 are now separate Velocity plugins and should not be bundled here
        }
    }
    build {
        dependsOn(shadowJar)
    }
}
