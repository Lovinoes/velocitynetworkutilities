plugins {
    `java-library`
}

dependencies {
    api("com.zaxxer:HikariCP:6.2.1")
    api("org.mariadb.jdbc:mariadb-java-client:3.5.1")
    api("org.xerial:sqlite-jdbc:3.48.0.0")
    api("redis.clients:jedis:5.2.0")
    api("org.yaml:snakeyaml:2.3")

    // Adventure is a standalone, platform-agnostic library (also used outside Velocity), so
    // referencing it here does not couple this module to the proxy. compileOnly because every
    // Velocity plugin that ends up loading this class gets Adventure from the proxy at runtime.
    compileOnly("net.kyori:adventure-api:4.17.0")
    compileOnly("net.kyori:adventure-text-minimessage:4.17.0")
    compileOnly("net.kyori:adventure-text-serializer-legacy:4.17.0")
}

tasks.named("shadowJar") {
    enabled = false
}
