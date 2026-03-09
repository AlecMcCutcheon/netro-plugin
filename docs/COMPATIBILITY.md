# Netro — Version compatibility

## Supported versions

- **Minecraft / server:** **1.21–1.21.11** (Spigot or Paper). The plugin declares `api-version: '1.21'`, which covers this range.
- **Java:** 17+.

No code changes are required across 1.21.x; the plugin loads and runs on 1.21 through 1.21.11.

---

## 1.21.11–specific notes

- **Plugin config** — Netro uses `JavaPlugin.getConfig()` for its own config. That is unchanged. The deprecations in 1.21.11 are for **Server.Spigot** methods (`getConfig()`, `getBukkitConfig()`, etc.), which Netro does not use.
- **Guide book** — The in-game guide (`/netro guide`) is built with the Spigot/BungeeCord **BaseComponent** book API (`BookMeta.spigot().setPages(BaseComponent[][])`). On Paper 1.21.11 this API is deprecated in favor of Adventure **Component** and `BookMeta.addPages(Component...)`. The current implementation still works on both Spigot and Paper 1.21.11. A future change could migrate the guide book to Adventure `Component` for Paper and keep a fallback for Spigot.
- **GameRules / broadcasts** — Netro does not use `GameRule` or the deprecated `Server.Spigot` broadcast methods, so no action needed there.

---

## Building against 1.21.11 (optional)

The project is built against **Spigot API 1.21.4** by default (`pom.xml`: `spigot.version` = `1.21.4-R0.1-SNAPSHOT`). To compile against 1.21.11 for testing or to use 1.21.11-only APIs locally, you can override the property:

```bash
mvn -B clean package -Dspigot.version=1.21.11-R0.1-SNAPSHOT
```

If the official Spigot Nexus does not have 1.21.11, add a repository that does (e.g. a mirror or Paper’s repo for `paper-api`) and use the same property. The resulting JAR remains compatible with 1.21–1.21.11 servers.

---

## Future (26.x versioning)

Mojang is moving to year-based versions (e.g. 26.1). When that lands, update any version parsing or display logic if Netro checks server version. Netro does not currently parse the server version string.
