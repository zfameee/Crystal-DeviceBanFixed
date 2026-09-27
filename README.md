# Crystal-DeviceBan

Configurable connection-fingerprint bans and device mutes for Purpur 1.21.11.

## Important limitation

A vanilla Minecraft client does not send a real hardware ID to the server. This plugin therefore uses a SHA-256 fingerprint of the connection IP by default. It is **not a true HWID ban**.

Consequences:
- changing IP/VPN can bypass the block;
- players behind the same NAT can share a fingerprint;
- a real hardware/device identifier requires a cooperating client mod and still cannot be made unforgeable.

## Commands

- `/deviceban <player> [reason...]` — permanently ban the player's current connection fingerprint.
- `/deviceunban <player|fingerprint>` — unban by the player's latest recorded banned device, or by fingerprint.
- `/devicemute <player> [duration] [reason...]` — mute the player's current device. Duration: `10m`, `2h`, `7d`, `1w`, `permanent`.
- `/deviceunmute <player|fingerprint>` — remove the latest mute for a player or a fingerprint.
- `/deviceinfo [player]` — show fingerprint and ban/mute state.
- `/devicebanreload` — reload `config.yml`.

## Custom messages

All plugin messages are in `config.yml`. Messages can be either a string or a YAML list. Lists are shown line-by-line.

Placeholders:
- `{prefix}`
- `{player}`
- `{reason}`
- `{admin}`
- `{fingerprint}`
- `{duration}`
- `{expires}`

Legacy color codes such as `&c`, `&l`, `&7`, etc. are supported.

The previous `MemorySection[path=...]` issue is fixed: the plugin now detects YAML sections/lists correctly and never prints a configuration section object as the kick message.

## Build

Requires JDK 21 and Maven:

```bash
mvn clean package
```

The JAR is created in `target/`.


## Build fix 1.1.2

The main Java class is intentionally named `CrystalDeviceBanPlugin`.
Java identifiers cannot contain `-`; the plugin display name remains `Crystal-DeviceBan`.
The GitHub Actions workflow also checks for invalid Java class names before Maven compilation.
