dependencies {
    compileOnly(project(":networkutilitiescommon"))
    compileOnly(project(":velocitynetworkutilities"))
    compileOnly(rootProject.extra["velocityApi"] as String)
    annotationProcessor(rootProject.extra["velocityApi"] as String)
    // Optional at runtime: chat degrades to leaving %placeholders% untouched when the
    // PAPIProxyBridge plugin is not installed on the proxy. compileOnly so it is never shaded.
    compileOnly("net.william278:papiproxybridge:1.8.4")
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveBaseName.set("VelocityNetworkChat")
}
