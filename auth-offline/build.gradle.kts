plugins {
    alias(libs.plugins.kotlin)
}

dependencies {
    // The API is provided by the main plugin at runtime. Use compileOnly so
    // the submodule is built as a standalone Velocity plugin and doesn't
    // bundle the API classes (avoids duplication in the main shadow jar).
    compileOnly(project(":api"))
    // Direct reference to the main plugin module so we can call its API
    // (e.g. HyperZoneLoginMain.getInstance().registerModule(...)) without reflection.
    compileOnly(project(":velocity"))
//    VC
    compileOnly(libs.velocityApi)
    // Exposed ORM
    compileOnly(libs.exposedCore)
//    config
    compileOnly(libs.configurateHocon)
    compileOnly(libs.configurateExtraKotlin)
//    limbo
    compileOnly(libs.limboApi)

    testImplementation(platform(libs.junitBom))
    testImplementation(libs.junitJupiter)
    testRuntimeOnly(libs.junitPlatformLauncher)
}

tasks.test {
    useJUnitPlatform()
}