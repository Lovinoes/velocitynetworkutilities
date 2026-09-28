dependencies {
    implementation(project(":networkutilitiescommon"))
    compileOnly(rootProject.extra["velocityApi"] as String)
    annotationProcessor(rootProject.extra["velocityApi"] as String)
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveBaseName.set("VelocityNetworkUtilities")
    // No relocate() rules: Shadow only runs its ASM class-remapping pass when relocators are
    // configured, and that pass cannot parse Java 25 (class file major version 69) bytecode
    // regardless of shadow/ASM version. Bundled HikariCP/MariaDB/SQLite/Jedis/SnakeYAML stay
    // at their original package names as a result; if another Velocity plugin on the same
    // proxy bundles a conflicting version of one of these, that is the tradeoff.
}
