import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    java
    id("io.github.goooler.shadow") version "8.1.8" apply false
}

// Which proxy API the Velocity modules compile against. Velocity's by default; Velocity-CTD's
// with -Pctd. Both use the same packages and CTD's is a superset, so as long as nothing CTD-only
// is referenced the jars are identical and run on either proxy.
val buildAgainstCtd = providers.gradleProperty("ctd").isPresent
val velocityApiDependency = if (buildAgainstCtd) {
    "com.velocityctd:velocity-api:${property("ctdApiVersion")}"
} else {
    "com.velocitypowered:velocity-api:${property("velocityApiVersion")}"
}
val paperApiDependency = "io.papermc.paper:paper-api:${property("paperApiVersion")}"

// Read by the modules' own build files as rootProject.extra, so each version is written down
// exactly once.
extra["velocityApi"] = velocityApiDependency
extra["paperApi"] = paperApiDependency

allprojects {
    group = "de.lovinoes"
    version = "1.0.0"

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://repo.velocitypowered.com/snapshots/")
        // PAPIProxyBridge, for resolving backend PlaceholderAPI placeholders from the proxy.
        // exclusiveContent, not a plain maven(): the repos above answer a 200 HTML page rather
        // than a 404 for artifacts they do not have, which Gradle then tries to parse as a POM
        // and logs a fatal XML error for on every build. This pins the group to the one repo
        // that actually hosts it, so the others are never asked.
        exclusiveContent {
            forRepository { maven("https://repo.william278.net/releases") }
            filter { includeGroup("net.william278") }
        }
        // Velocity-CTD's API, only ever asked for its own group, for the same reason.
        exclusiveContent {
            forRepository { maven("https://repo.velocityctd.com/snapshots") }
            filter { includeGroup("com.velocityctd") }
        }
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "io.github.goooler.shadow")

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        // Velocity's API, Velocity-CTD's and Paper's are all built for Java 25, and Gradle's
        // variant resolution refuses a lower-targeted consumer, so this has to stay at 25.
        options.release.set(25)
    }

    tasks.withType<ProcessResources> {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE

        // The Paper plugins' api-version comes from minecraftVersion in gradle.properties, so it
        // can never drift from the paper-api they were compiled against.
        val minecraftVersion = rootProject.property("minecraftVersion").toString()
        inputs.property("minecraftVersion", minecraftVersion)
        filesMatching("paper-plugin.yml") {
            expand("minecraftVersion" to minecraftVersion)
        }
    }

    tasks.withType<Jar> {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }

    tasks.withType<ShadowJar> {
        // Keep a classifier so this stays an EXTRA artifact and the plain jar remains the
        // project's default published artifact. Sibling compileOnly(project(...)) dependencies
        // then resolve to the plain jar (just class files, no shading) instead of implicitly
        // depending on this task, which only needs to run for the final deployable plugin jar.
        archiveClassifier.set("all")
        mergeServiceFiles()
    }

    tasks.named("build") {
        dependsOn(tasks.named("shadowJar"))
    }
}

// Empties deploy/ on demand. Deliberately NOT wired into collectJars: as a separate task it
// could delete the jars and then never reach the copy, if the build was interrupted or a task
// in between failed, leaving an empty deploy/ folder. Sync below does the same pruning as part
// of one task, so there is no window where the jars are gone.
tasks.register<Delete>("cleanDeployFolder") {
    group = "build"
    description = "Deletes the deploy folder."
    delete(layout.projectDirectory.dir("deploy"))
}

// Gathers every subproject's shaded plugin jar into one deploy/ folder at the repo root.
// networkutilitiescommon is skipped: it has no velocity-plugin.json and its shadowJar is
// disabled, since it's an internal library, not a deployable plugin.
//
// Sync, not Copy: it makes deploy/ match the current set of jars exactly, so a renamed or
// removed module's old jar is pruned in the same step that writes the new ones. Anything else
// put in deploy/ by hand is removed too, which is why nothing else belongs there.
tasks.register<Sync>("collectJars") {
    group = "build"
    description = "Copies all subproject shadow jars into a single root deploy/ folder."

    into(layout.projectDirectory.dir("deploy"))

    subprojects
        .filter { it.name != "networkutilitiescommon" }
        .forEach { subproject ->
            subproject.tasks.matching { it.name == "shadowJar" }.forEach { shadowTask ->
                from(shadowTask)
            }
        }
}

tasks.named("build") {
    finalizedBy("collectJars")
}
