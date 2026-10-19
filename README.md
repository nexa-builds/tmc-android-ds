# Minish Cap — Dual Screen

A dual-screen mod of *The Legend of Zelda: The Minish Cap* for the AYN Thor.
The handheld's bottom panel becomes a live map, quest status and touch
inventory, so the top screen can be nothing but the game.

The dual-screen mod was made with the help of Claude Code and opencode.

![](https://github.com/samyost1/tmc-android/releases/download/v1.0/showcase.png)

Everything on the panel is decoded from your own ROM as the game runs — the
map artwork, the menu chrome, the item icons, the font. No game data is stored
in this repository.

## What's on the panel

- **Map** — Hyrule with a follow cam. ZOOM steps out to the whole kingdom;
  tap a region to open the game's own enlarged map of it. Windcrest warps
  show as pins.
- **Dungeon** — the real automap: floor plaques, explored rooms, Link's
  position, and the small-key count beside your rupees.
- **Quest** — the pause menu's quest screen, reflowed for a square panel. Tap
  the kinstone bag or the technique scroll to open those lists.
- **Items** — tap a ring to arm it, then tap an item to equip that slot.
- **Settings** — hide the top HUD (the panel takes over your vitals), true
  widescreen, and the port-wide toggles the desktop build puts behind F8.

## Install

1. Download the APK from this repository's [Releases page](../../releases)
   and install it. Android may ask you to allow installation from your browser
   or file manager.
2. Launch. On first run the app asks you to select your own `baserom.gba`
   (USA, SHA-1 `b4bd50e4131b027c334547b4524e2dbbd4227130`) from anywhere on
   the device. It's copied into `Android/data/dev.picori.tmc/files/` once, so
   you're only asked the first time. EU, JP and the demo dumps are accepted
   too — the port detects the region from the ROM header.
3. The panel appears on the second display automatically.

If you prefer, you can still place the ROM yourself: put `baserom.gba` in
`Android/data/dev.picori.tmc/files/` on internal storage before launching.

Built for the Thor, but it is a plain Android `Presentation` — any device with
a second display will do.

## GammaOS Nano / Anbernic RG DS Plus

This build targets the GammaOS Nano dual-screen handhelds and works with no
manual configuration. In the app's GammaOS **Options** menu (X), enable
**Run on Primary Screen** and leave **Dual-Stack Display** off, then launch
from GammaOS's UI. (`adb shell am start` does not reproduce its display
routing.)

Out of the box the app runs the GammaOS layout: the game owns the upper
panel, the companion panel stays in the Activity on the lower one, and
controller input is forwarded to the game.

The defaults behind that are set for this hardware in `port_runtime_config.cpp`:

| Setting | Default | Why |
| --- | --- | --- |
| `second_screen_swap` | `true` | Puts the game on the upper panel. The other arrangement stalls on this device. |
| `render_backend` | `auto` | Falls back to SDL_Renderer/OpenGL ES, which presents correctly. Forcing `software` stalls the display buffer queue and freezes the game at startup. |
| `bg_fill` | `black` | The stretched ambient fill shows duplicated image strips on these panels. |

All three are still editable in the panel's SETTINGS or `config.json`. If you
edit `config.json` by hand while the app is closed, preserve the file's app
ownership/permissions: an unreadable config makes the shell and native port
fall back to defaults.

The swapped layout keeps the Activity and touch companion on the lower/default
display, creates SDL's surface in an upper-screen `Presentation`, and forwards
controller input from the Activity to SDL. This follows the Zelda3 Android
port's GammaOS arrangement and does not require Vulkan. With SWAP SCREENS off,
the original Thor layout is retained. If no single powered presentation display
is available, the app falls back to that layout.

## ROM regions

The native port detects the region from the ROM header and switches its data
tables at runtime, so the released USA APK also **loads** a EU (`BZMP`, SHA-1
`cff199b36ff173fb6faf152653d1bccf87c26fb7`) or JP (`BZMJ`) ROM — name it
`baserom_eu.gba` / `baserom_jp.gba` or just `baserom.gba`.

It is not the same as a EU *build*, though. Each APK is compiled against one
region's baseline: the blob-offset headers in `build/<region>/assets/` and the
region `#ifdef`s in `src/` that have not yet been converted to the port's
runtime `REGION_IS_*` form. On a baseline mismatch those compile-time sites
keep following the baseline region, so a USA APK running a EU ROM is a hybrid —
expect region-specific text, menu graphics and RNG to be off. The port logs
which combination it is on startup:

```
$ adb logcat -s tmc | grep 'Region baseline'
Region baseline: build=USA rom=EU (MISMATCH). ...
```

For a faithful EU port, build the EU APK (below). JP additionally needs the
data tables described in `docs/JP_PORT_ENABLEMENT.md`.

## Build from source

Needs the Android SDK and NDK r26. Build the native library for both ABIs,
then package:

```sh
for abi in arm64-v8a x86_64; do
  xmake f -y -p android -a $abi --ndk=$ANDROID_NDK_HOME \
      --game_version=USA --gpu_renderer=y --widescreen_width=384
  xmake -y
done
cd android && ./gradlew assembleRelease
```

`--widescreen_width=384` is what compiles the wide render paths in; without it
the WIDESCREEN row is hidden because the setting would have nothing to switch.
The APK lands in `android/app/build/outputs/apk/release/`.

Swap `--game_version=USA` for `--game_version=EU` to build the EU APK; the
tracked `build/EU/assets/*_offsets.h` mean that is the only change needed. For a
region with no tracked headers (JP, the demos) generate them on the host first —
the Android toolchain cannot produce them, since `asset_processor` has to *run*
on your machine:

```sh
xmake f -P . -y -p linux                     # your host platform
xmake build -P . -y asset_processor
tools/bin/asset_processor extract JP build/JP/assets
```

The build does this for you when it can and otherwise stops with these
instructions, rather than failing on a missing `assets/map_offsets.h`.

For the desktop port, see [`INSTALL.md`](INSTALL.md).

## GitHub Actions builds and releases

The single **Release** workflow (`.github/workflows/release.yaml`) builds fresh
native libraries for `arm64-v8a` and `x86_64` with xmake, then packages the
normal `dev.picori.tmc` release APK with Gradle. It needs no ROM, and the APK
never contains game data or signing keys.

- **Release:** push a version tag such as `v1.3`. The workflow verifies the
  APK's signature, package ID and both native ABIs before attaching it to a
  GitHub Release.
- **Manual build:** run the workflow from the Actions tab. An optional version
  overrides the APK version name; otherwise it uses `0.0.0-dev.<run>`. Manual
  runs upload an artifact and do not publish a release.

For upgrade-stable signing, configure these repository or organization Actions
secrets:

| Secret | Purpose |
| --- | --- |
| `KEYSTORE_BASE64` | Base64-encoded release keystore |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Signing-key alias |
| `KEY_PASSWORD` | Key password |

Keep the same signing key for every update, and never commit the keystore or
its passwords. Without the secrets the workflow still builds, but the APK is
debug-signed and not upgrade-stable across runners.

APK `versionCode` uses the existing
`major*1000000 + minor*10000 + patch*100 + revision` scheme, so versions stay
monotonic with the values already shipped.

## Built on

- [Project Picori](https://github.com/999sian/tmc) — the native Minish Cap
  port this mod extends (SDL3, software PPU, agbplay audio).
- [Raekwon1603/tmc-android](https://github.com/Raekwon1603/tmc-android) — the
  Android packaging and second-screen scaffold this forked from.
- [zeldaret/tmc](https://github.com/zeldaret/tmc) — the decompilation
  underneath all of it.

## License

GPL-3.0 — see [`LICENSE`](LICENSE). Bundled and linked third-party components
keep their own GPL-compatible licenses, listed in
[`THIRD-PARTY-LICENSES.md`](THIRD-PARTY-LICENSES.md); notably agbplay is
LGPL-3.0 and is not relicensed by being linked here.

This builds on a decompilation of a copyrighted game. All Nintendo
intellectual property remains Nintendo's, and a legitimately-owned ROM is
required to play.
