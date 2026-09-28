dependencies {
    // Nothing else is needed: the proxy renders every message, so this plugin only answers its
    // questions about players. Adventure comes in transitively via paper-api.
    compileOnly(rootProject.extra["paperApi"] as String)
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveBaseName.set("PaperNetworkChat")
}
