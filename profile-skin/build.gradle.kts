plugins {
    alias(libs.plugins.kotlin)
}

dependencies {
    compileOnly(project(":api"))
    compileOnly(project(":velocity"))

    compileOnly(libs.velocityApi)
    compileOnly(libs.velocityProxy)
    compileOnly(libs.exposedCore)
    compileOnly(libs.configurateHocon)
    compileOnly(libs.configurateExtraKotlin)
    compileOnly(libs.gson)

    testImplementation(platform(libs.junitBom))
    testImplementation(libs.junitJupiter)
    testRuntimeOnly(libs.junitPlatformLauncher)
}

tasks.test {
    useJUnitPlatform()
}

