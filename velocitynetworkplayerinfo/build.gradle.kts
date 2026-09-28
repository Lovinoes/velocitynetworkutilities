dependencies {
    compileOnly(project(":networkutilitiescommon"))
    compileOnly(project(":velocitynetworkutilities"))
    compileOnly(project(":playeruuidcachevelocity"))
    compileOnly(rootProject.extra["velocityApi"] as String)
    annotationProcessor(rootProject.extra["velocityApi"] as String)
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveBaseName.set("VelocityNetworkPlayerInfo")
}
