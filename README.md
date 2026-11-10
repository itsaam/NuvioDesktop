<div align="center">

  <img src="https://nuvio.tv/assets/nuvio-app-logo-wordmark.webp" alt="Nuvio" width="320" />

  <p>
    An unofficial Nuvio Desktop fork for Windows and macOS.
    <br />
    Bring your own sources. Nuvio turns them into a library with artwork, ratings, subtitles, and your place saved on every screen.
  </p>

  [Upstream project](https://github.com/NuvioMedia/NuvioDesktop) · [This fork's releases](https://github.com/itsaam/NuvioDesktop/releases)

</div>

## ⚠️ Alpha Software - Slow Development - Testers Only

Nuvio Desktop is currently in alpha and is intended only for testers. It is under development and is not suitable for daily use.

Expect breaking changes with every update. Features, settings, stored data, and compatibility may change or stop working without notice. Do not rely on this build as your primary media app, and report any issues you encounter during testing.

## Get Nuvio Dev

Download the latest build from [this fork's GitHub Releases](https://github.com/itsaam/NuvioDesktop/releases).

This fork installs as **Nuvio Dev** beside the official desktop app. It keeps its local settings and cache separate and does not register the official app's URL schemes. Online account data remains shared when both apps use the same account, so use a test profile for experiments.

- Windows: MSI installer
- macOS: DMG installer

## Build from source

```bash
git clone https://github.com/itsaam/NuvioDesktop.git
cd NuvioDesktop
```

### Run the app

```bash
./gradlew :composeApp:run
```

On Windows PowerShell:

```powershell
.\gradlew.bat :composeApp:run
```

### Package the app

Build a release package for the current host:

```bash
./gradlew :composeApp:packageReleaseDistributionForCurrentOS
```

For platform-specific packaging:

Windows PowerShell:

```powershell
.\gradlew.bat :composeApp:packageReleaseMsi --rerun-tasks
```

macOS:

```bash
./scripts/build-macos-release-dmgs.sh --package-only
```

The shared app is built with Kotlin Multiplatform and Compose Multiplatform.

## License

[GNU General Public License v3.0](./LICENSE)
