# Building

Requires Java 25. Gradle is included through the wrapper.

```bash
./gradlew clean build
```

The finished plugins are placed in `deploy/`:

| Jar | Install on |
|---|---|
| `VelocityNetworkUtilities-1.0.0-all.jar` | Proxy |
| `PlayerUUIDCacheVelocity-1.0.0-all.jar` | Proxy |
| `VelocityNetworkChat-1.0.0-all.jar` | Proxy |
| `VelocityNetworkVanish-1.0.0-all.jar` | Proxy |
| `VelocityNetworkModeration-1.0.0-all.jar` | Proxy |
| `VelocityNetworkPlayerInfo-1.0.0-all.jar` | Proxy |
| `PaperNetworkChat-1.0.0-all.jar` | Every Paper backend |
| `PaperNetworkVanish-1.0.0-all.jar` | Every Paper backend |

## Velocity-CTD

```bash
./gradlew clean build -Pctd
```

The `-Pctd` flag builds against the [Velocity-CTD](https://github.com/GemstoneGG/Velocity-CTD) API instead of Velocity's. The resulting jars are the same either way and run on both proxies, so the flag is only a compatibility check: the build fails if the plugins use anything Velocity-CTD does not have. CI runs this check on every build.

## Versions

All versions are set in `gradle.properties`:

| Property | Description |
|---|---|
| `minecraftVersion` | Minecraft version, also used as the `api-version` of the Paper plugins |
| `paperApiVersion` | Paper API |
| `velocityApiVersion` | Velocity API |
| `ctdApiVersion` | Velocity-CTD API, used with `-Pctd` |

To update to a new Minecraft version, create a branch named after it (for example `26.4`) and change these values.

## Releases

Every push to a version branch is built by GitHub Actions and released as **VelocityNetworkUtilities (version)** with all jars attached. Changes to documentation only are not built.
