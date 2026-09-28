# VelocityNetworkUtilities

Chat, vanish, moderation and player info for a Velocity network.

> Built for my own server with Claude Opus 5.5. Use at your own risk.

## Plugins

| Plugin | Platform | Description |
|---|---|---|
| VelocityNetworkUtilities | Proxy | Shared database and messaging, required by all proxy plugins |
| PlayerUUIDCacheVelocity | Proxy | Player name and UUID cache |
| VelocityNetworkChat | Proxy | Chat channels, mentions, private messages, item showcase |
| VelocityNetworkVanish | Proxy | Network-wide vanish |
| VelocityNetworkModeration | Proxy | Bans, mutes, kicks, warnings and chat filter |
| VelocityNetworkPlayerInfo | Proxy | Join and leave messages, player lookup |
| PaperNetworkChat | Paper | Backend part of the chat |
| PaperNetworkVanish | Paper | Backend part of vanish |

Install the Paper plugins on every backend, and always update both chat plugins together.

## Requirements

| Software | Version |
|---|---|
| Java | 25 |
| Proxy | [Velocity-CTD](https://github.com/GemstoneGG/Velocity-CTD) (recommended) or Velocity 4.2+ |
| Backend | Paper 26.3 |
| Database | MariaDB or MySQL |
| [SignedVelocity](https://github.com/4drian3d/SignedVelocity) | On the proxy and every backend |
| [PAPIProxyBridge](https://github.com/WiIIiam278/PAPIProxyBridge) | Optional, for PlaceholderAPI placeholders |

## Configuration

Each plugin has a `config.yml` for settings and a `languages/` folder for messages (English and German). The chat filter words are in `filter.yml`.

## More

- [Commands and permissions](commands.md)
- [Building](building.md)
