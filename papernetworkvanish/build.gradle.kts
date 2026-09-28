dependencies {
    // paperApiVersion in gradle.properties must match the Paper the backends run. Building against
    // an older API and declaring an older api-version leaves the plugin unloadable on them.
    compileOnly(rootProject.extra["paperApi"] as String)
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveBaseName.set("PaperNetworkVanish")
}
