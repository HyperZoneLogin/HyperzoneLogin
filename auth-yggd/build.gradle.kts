plugins {
    alias(libs.plugins.kotlin)
}

dependencies {
    // Build as standalone plugin; api is provided at runtime by the main plugin
    compileOnly(project(":api"))
    // Direct reference to the main plugin to call its API without reflection
    compileOnly(project(":velocity"))
//    VC
    compileOnly(libs.velocityApi)
    compileOnly(libs.velocityProxy) // From Elytrium Repo.
    compileOnly(libs.nettyAll)
// Exposed ORM
    compileOnly(libs.exposedCore)
//    config
    compileOnly(libs.configurateHocon)
    compileOnly(libs.configurateExtraKotlin)

    testImplementation(platform(libs.junitBom))
    testImplementation(libs.junitJupiter)
    testRuntimeOnly(libs.junitPlatformLauncher)
}

tasks.test {
    useJUnitPlatform()
}