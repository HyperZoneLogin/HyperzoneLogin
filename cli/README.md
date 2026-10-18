# HyperZoneLogin CLI

Command-line interface for deploying and managing HyperZoneLogin server stacks.

## Overview

The CLI module provides the `easydeploy` command to automatically set up a complete HyperZoneLogin server infrastructure in a single command. It generates configuration files, downloads server JARs, and creates startup scripts for Windows and Unix-like systems.

## Installation

The CLI is built as part of the main HyperZoneLogin build:

```bash
./gradlew build
```

The resulting JAR will be available at:
```
build/HZL/HyperZoneLogin-<version>-<commit>-all.jar
```

## Usage

### Basic Deployment

Run the easydeploy command to set up a complete server stack:

```bash
java -jar HyperZoneLogin-<version>-all.jar easydeploy
```

This will:
1. Create two directories:
   - `velocity/` — Velocity proxy (public entry point, port 25577)
   - `play/` — Game server (port 30067)

2. Generate configuration files for both servers
3. Download Velocity and Paper JARs
4. Generate startup scripts for both Windows and Linux/Mac
5. Pre-download and cache Paper bootstrap files

The login waiting-area is handled by HyperZoneLogin's built-in virtual lobby
server (backend-hzlimbo), so no separate lobby backend is needed.

### Command Options

#### Port Configuration

- `--velocity-port <port>` — Velocity proxy listen port (default: 25577)
- `--game-port <port>` — Game server port (default: 30067)
- `--bind <address>` — Velocity bind address (default: 0.0.0.0)

#### Version Selection

- `--paper-version <version>` — Paper version to download (default: latest)
  - Examples: `1.21.4`, `latest`
  - Use `--list-paper-versions` to see all available versions

- `--velocity-version <version>` — Velocity version (default: latest)
  - Use `--list-velocity-versions` to see all available versions

- `--paper-config <mode>` — Paper config format (default: auto)
  - `auto` — Automatically detect based on version (Paper 1.19+ uses modern, <1.19 uses legacy)
  - `modern` — Force modern config format (1.19+)
  - `legacy` — Force legacy config format (1.18 and older)

#### File Handling

- `--overwrite` — Overwrite existing config files and re-download JARs
- `--no-paper-download` — Skip downloading Paper, place JARs manually in `play/`
- `--no-velocity-download` — Skip downloading Velocity, place JAR manually in `velocity/`
- `--plugin-jar <path>` — Path to HyperZoneLogin plugin JAR (will be copied to `velocity/plugins/`)

#### Security

- `--forwarding-secret <secret>` — Modern forwarding secret for Velocity
  - If omitted, a secure random secret is generated automatically
  - The secret is written to `velocity/forwarding.secret` and the game backend config

### Examples

#### Minimal deployment (download everything)
```bash
java -jar HyperZoneLogin-all.jar easydeploy
```

#### Use specific versions
```bash
java -jar HyperZoneLogin-all.jar easydeploy \
  --paper-version 1.21.3 \
  --velocity-version 3.3.0
```

#### Deploy without downloads (manual JAR placement)
```bash
java -jar HyperZoneLogin-all.jar easydeploy \
  --no-paper-download \
  --no-velocity-download
```

Then place JARs in the generated directories:
```
play/paper-1.21.4-xxx.jar
velocity/velocity-3.4.0-xxx.jar
```

#### Include the plugin JAR
```bash
java -jar HyperZoneLogin-all.jar easydeploy \
  --plugin-jar ./HyperZoneLogin-plugin.jar
```

#### Custom security secret
```bash
java -jar HyperZoneLogin-all.jar easydeploy \
  --forwarding-secret "your-secret-here"
```

#### Overwrite and regenerate everything
```bash
java -jar HyperZoneLogin-all.jar easydeploy --overwrite
```

## Generated Directory Structure

```
deploy-directory/
├── velocity/
│   ├── velocity.toml              # Main Velocity config
│   ├── forwarding.secret          # Modern forwarding secret
│   ├── plugins/
│   │   ├── HyperZoneLogin-xxx.jar # Plugin JAR (if --plugin-jar provided)
│   │   └── hyperzonelogin/
│   │       └── libs/              # Plugin runtime dependencies
│   ├── start.bat                  # Windows startup script
│   └── start.sh                   # Linux/Mac startup script
│
├── play/
│   ├── server.properties          # Minecraft server properties
│   ├── config/
│   │   └── paper-global.yml       # Paper config (modern format)
│   ├── spigot.yml                 # Spigot overrides
│   ├── start.bat
│   └── start.sh
│
├── start-all.bat                  # Windows: Start all servers
├── stop-all.bat                   # Windows: Stop all servers
└── paper-*.jar                    # (After download) Paper JAR file
```

> **Note:** The `eula.txt` file is intentionally NOT generated. You must accept
> the Minecraft EULA yourself by setting `eula=true` in `play/eula.txt` before
> starting the game server. Likewise, the plugin's `start.conf` is NOT
> auto-generated — create `velocity/plugins/hyperzonelogin/start.conf` with
> `ready=true` once you have reviewed the startup configuration.

## Server Architecture

### Network Setup

```
Players → [Velocity Proxy (0.0.0.0:25577)]
          ↓
          └→ Game Server (127.0.0.1:30067) — Main gameplay server
```

The login waiting-area is provided by the plugin's built-in virtual lobby
server (backend-hzlimbo), which runs inside Velocity itself.

### Velocity Configuration

The `velocity.toml` is pre-configured with:
- **Modern Forwarding**: Enabled with shared secret
- **Backend Servers**:
  - `play` — Game server (main gameplay backend)
- **Default Route**: `try = ["play"]` — Players are sent to the game server

### Boot Sequence

1. **Start Game Server**
   ```bash
   cd play && ./start.sh
   ```

2. **Start Velocity Proxy**
   ```bash
   cd velocity && ./start.sh
   ```

### Windows Startup

All-in-one startup script:
```bash
start-all.bat    # Launches both servers in Windows Terminal tabs (or separate windows)
```

### Windows Shutdown

Graceful shutdown script:
```bash
stop-all.bat     # Stops all Java processes associated with the servers
```

## Bootstrap Cache

Paper uses a bootstrap cache system (`paperclip`) to download Mojang's server libraries. The CLI optimizes this by prewarming the cache before first run.

## Troubleshooting

### Velocity won't start
- Check `velocity.toml` for syntax errors
- Ensure `forwarding.secret` file exists and is readable
- Verify backend servers are running on the configured ports

### Backend server won't connect
- Verify `server.properties` has `online-mode=false`
- Check that `spigot.yml` has `bungeecord: false` (not true)
- Ensure the forwarding secret matches in the game backend config

### Game server won't start
- Accept the Minecraft EULA by setting `eula=true` in `play/eula.txt`
- Ensure the Paper JAR is present in `play/`

### Windows Terminal integration not working
- `start-all.bat` falls back to regular `start` command if `wt.exe` not found
- Windows Terminal can be installed from Microsoft Store or via `winget`:
  ```bash
  winget install Microsoft.WindowsTerminal
  ```
- After installation, you may need to restart or add Windows Terminal to your PATH
- Check firewall settings if communication between Velocity and the game backend fails

### JAR not found errors
- Ensure downloaded Paper/Velocity JARs are in the correct directories
- Use `--no-paper-download` and `--no-velocity-download` if manually placing JARs
- Check file permissions (JAR must be readable)

### Out of memory
- Increase heap size when running servers
- Example: `java -Xmx2G -jar paper-*.jar nogui`

## Development

### Building from source

```bash
cd HyperzoneLogin
./gradlew cli:build
```

### Key Components

- **ScriptGenerator**: Generates platform-specific startup scripts
- **VelocityDeployer**: Configures Velocity proxy
- **GameDeployer**: Configures the game Paper backend server
- **PaperServerDeployer**: Shared logic for Paper backend servers
- **PaperMcDownloader**: Fetches JARs from PaperMC API
- **EasyDeployCommand**: Main command orchestrator

### Configuration-Driven Design

The CLI uses a data-driven approach:
- `ServerScript` data class defines server configurations
- `ScriptGenerator` dynamically generates scripts from `ServerScript` list
- Easy to add new server types or modify existing ones

## Advanced Configuration

### Custom Velocity Backend Behavior

Edit `velocity/velocity.toml` after deployment:

```toml
[servers]
play = "127.0.0.1:30067"
try = ["play"]  # Route players to the game server

[forced-hosts]
# Add domain-based server routing here
```

### Paper Server Memory

Adjust in your startup script or wrapper:
```bash
java -Xmx2G -Xms1G -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -jar paper-*.jar nogui
```

### Plugin Installation

Place plugins in `velocity/plugins/` and `play/plugins/` directories.

## Support

For issues or feature requests, refer to the main HyperZoneLogin documentation at `https://docs.h2l.icu/`

