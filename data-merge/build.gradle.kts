plugins {
    alias(libs.plugins.kotlin)
}

dependencies {
    // Build as a standalone Velocity plugin; reference API at compile time only
    compileOnly(project(":api"))
    // Reference main plugin API to call registerModule(...) directly
    compileOnly(project(":velocity"))
    // The auth modules are separate plugins; keep compileOnly if you reference them
    compileOnly(project(":auth-yggd"))
    compileOnly(project(":auth-offline"))

    compileOnly(libs.velocityApi)

    compileOnly(libs.exposedCore)
    compileOnly(libs.exposedJdbc)

    compileOnly(libs.configurateHocon)
    compileOnly(libs.configurateExtraKotlin)

    testImplementation(platform(libs.junitBom))
    testImplementation(libs.junitJupiter)
    testRuntimeOnly(libs.junitPlatformLauncher)
}

tasks.test {
    useJUnitPlatform()
}