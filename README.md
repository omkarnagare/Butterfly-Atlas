# Butterfly Atlas

An offline desktop app for organising butterfly photographs by taxonomy (Family → Subfamily →
Tribe → Genus → Species). Everything stays on your device: the app never goes online. It opens in its own window
(Edge or Chrome in app mode, or a browser tab when neither is installed) and ships as an installer for Windows,
Linux and macOS (the macOS installer is for Apple Silicon Macs only; see Requirements).

## Privacy

Butterfly Atlas makes no network connections and sends nothing anywhere. It runs entirely on your device: its window
talks only to the app itself on this device, and your photos, their locations and your library never leave it.

## What it does today

- **Your library is an ordinary folder** (suggested: `Documents/Butterfly_Atlas`) with a folder for each species you
  photograph, plus a SQLite database. The files and folders Butterfly Atlas creates in your library and in your backups have
  no spaces (the OS-standard config folder is the one exception: it is named `Butterfly Atlas`, see below).
- **Taxonomy:** the India butterfly list from iNaturalist, bundled and checksum-verified. Browse it as cards or a map,
  and search by scientific name, common name or your own search words. Add **custom species** under "Custom additions"
  for anything not in the list.
- **Photos:** add them on a species page (drag and drop or "Add photos"; JPEG, PNG, TIFF, WebP, camera RAW and HEIC files),
  or import a whole folder in bulk with species suggestions. RAW files (NEF, CR2, CR3, ARW, ORF, RW2, RAF, PEF, DNG and
  others) are kept exactly as they are and shown through the JPEG preview the camera stores inside them; a RAW + JPEG
  pair of the same shot is filed as you choose (both, JPEG only or RAW only). HEIC/HEIF photos (iPhone, Android, and the
  `.HIF` files of recent cameras) are kept exactly as they are and shown through **the device's own decoder**, run as a
  separate program: macOS's built-in `sips`; Windows' own HEIF and HEVC decoders; on Linux, `heif-convert` from your
  distribution's packages. Butterfly Atlas includes no HEVC decoder of its own (see Licence). Without a decoder, HEIC photos are still added with their details and places and say what to install;
  **Check again** in Settings → About picks the decoder up without a restart. RAW + HEIC pairs are filed like RAW + JPEG.
  HEIC image sequences and AVIF are not supported, and say so. You get tags (built-in and your own), a viewer with zoom and pan, cover
  photos, moving photos to another species, and Recently deleted with restore. Changes made outside the app are picked
  up at startup.
- **Places:** GPS photos get their district, state and country automatically from bundled offline boundaries (India in
  detail, the rest of the world by country and first-level region). You can set or correct a place by hand and add
  your own districts.
- **Export:** one shared filter (species, tags, place, status, source, file type) with live counts. Export copies of the
  photos with location data removed, a species-list CSV, or both. RAW and HEIC files are exported exactly as they are, with
  any location they hold, and only after you tick that you accept it. Exports can be paused and resumed, survive a restart, and pause
  safely if the drive fills up or disappears.
- **Updates without losing anything:** newer taxonomy and place lists ship with the app and apply step by step at
  startup:
  - **taxonomy:** renames, merges, splits and moves, with the photo files following;
  - **places:** renames, merges, splits and retired places, with GPS photos re-checked;
  - photos in a place that no longer exists are labelled "(former district)", or "(former state)" in India and
    "(former region)" elsewhere.

  A Home note says what changed until you dismiss it.
- **An app, not a web page:** double-click opens its own window (no address bar or tabs), only one copy runs at a time,
  and closing the last window quits it. Opening it again, or **New window** in the top bar, gives another window on the
  same copy; changes that would clash (for example moving photos while a backup copies them) are refused with what to do. The local server answers only this device and only the window that started it
  (a per-launch secret that is never put in a program's arguments, so it is safe on a shared device).
- **Backup:** Settings → Back up now copies the database, or the database and all photos, into a new folder you choose each
  time, with the space needed shown first; copies are verified and can be paused, resumed or cancelled. A reminder preference
  (Remind me, less often, or none) controls a quiet note on Home; nothing is copied by itself.
- **Problems are shown, not hidden:** a start-up problem opens a plain page that says what happened and what to do, and
  everything is logged to a file you can find in Settings → About.

**Status:** Phases 0–6, 7a and 7b are done (Phase 6: the app window, one copy at a time, Backup and the installers; the
Windows and Linux installers are built from the same steps but have not been run on those systems yet, see
Troubleshooting. Phase 7a: camera RAW photos through their embedded previews; Phase 7b: HEIC photos through the device's own decoder,
checked on macOS only).

## Requirements

Every system needs **JDK 21, a full JDK** (not a JRE: `jpackage` and `jlink` are part of it, for example Eclipse Temurin
21 or Amazon Corretto 21), on the `PATH` or named by `JAVA_HOME`, and internet access for the first build (Maven, Node and
the libraries are downloaded; the app itself never goes online). Nothing else is needed to build and test: the Maven
wrapper downloads Maven, and the build downloads its own Node. To package, the JDK must also include its `jmods` folder, which
`jlink` reads: Temurin and Corretto have it; on Fedora and RHEL the `java-21-openjdk-devel` package does not, so also
install `java-21-openjdk-jmods` (or use Temurin 21).

| To... | Windows | Linux | macOS |
|---|---|---|---|
| Build and test | JDK 21 | JDK 21 | JDK 21 |
| Build the installer ("Package it yourself") | **Inno Setup 6.7 or later** (the build finds it; no `PATH` change needed) | `.deb`: `fakeroot` and `dpkg-dev`; `.rpm`: `rpm-build` (package `rpm`) | Xcode command line tools (`xcode-select --install`) |

**macOS: the released installer is for Apple Silicon (M1 or later) only.** The release workflow builds on an Apple Silicon
runner, and the Java runtime and launcher inside it are arm64, so the `.dmg` does not run on an Intel Mac. If you have an Intel
Mac, build the app from source with the steps below: they work on an Intel Mac and give a `-x64` `.dmg` made for it.

Optional: Node 22.12+ on your `PATH`, only for the UI live-reload workflow below.

### What these tools are for

You only install the tools in the table above; the build downloads the rest by itself. None of them is needed by the people
who install Butterfly Atlas, only by the machine that builds the installer.

| Tool | What it does here | Do you install it? |
|---|---|---|
| **JDK 21** | Compiles and runs the Java app. A full JDK also contains `jdeps`, `jlink` and `jpackage` (below) and the `jmods` folder they read. | Yes, on every system |
| **Maven wrapper** (`mvnw`, `mvnw.cmd`) | Runs the build. It downloads the right Maven the first time. | No, it is in the repository |
| **Node** | Builds and tests the Svelte user interface. | No, the build downloads its own copy (install your own only for live reload) |
| **`jlink`** | Builds a small private Java runtime that goes inside the installer, so users do not need Java. | Part of the JDK |
| **`jpackage`** | Turns the app and that runtime into an application image and then an installer for the system it runs on. It cannot build an installer for another system. | Part of the JDK |
| **Inno Setup 6.7 or later** (Windows) | Wraps the application image that `jpackage` made into the `.exe` installer (`ISCC.exe`, its compiler). The build looks for it on the `PATH` and in its usual folders (`Inno Setup 6` or `Inno Setup 7` under Program Files or your own Programs folder), and stops with "Inno Setup 6.7 or later was not found" and the install command if it is missing. Inno Setup 7 is found too (not yet tried). | Yes, on Windows only; GitHub's Windows runners already have it |
| **`fakeroot`, `dpkg-dev`** (Linux) | `dpkg-dev` builds the `.deb`; `fakeroot` lets it record files as owned by root without you being root. | Yes, to build the `.deb` |
| **`rpm-build`** (Linux, package `rpm` on Debian and Ubuntu) | Builds the `.rpm`. | Yes, to build the `.rpm` |
| **Xcode command line tools** (macOS) | Apple's own build tools; `jpackage` and the app's ad hoc seal use them. | Yes, on macOS (`xcode-select --install`) |

On Windows, use `mvnw.cmd` instead of `./mvnw` in Command Prompt or PowerShell.

Always run `./mvnw` from the repository root (use `-pl <module>` to target a module). The project
uses its own Maven settings (`.mvn/settings.xml`) so it builds from Maven Central regardless of
any machine-wide Maven configuration.

## Build and test

From a fresh checkout (`git clone`, then `cd` into it):

```bash
./mvnw -B -ntp clean verify
```

This builds and tests every module (UI tests with Vitest, Java tests with JUnit; the `packaging` module is part of
the build but does nothing without its profile). Run it before every push. The first run downloads Maven, Node and the
libraries, so it takes a few minutes; later runs are faster.

**RAW sample files (optional).** Some tests read real camera RAW files from `app/.cache/raw-samples/` (git-ignored);
without them those tests are skipped with a message, and CI never has them. The eleven files used in Phase 7a are free
(CC0) samples from [raw.pixls.us](https://raw.pixls.us/): Nikon D500 and Z6III (`.nef`), Canon EOS 7D Mark II (`.cr2`),
Canon EOS R7 (`.cr3`), Sony ILCE-6400A (`.arw`), Olympus E-M1 Mark III (`.orf`), Panasonic DC-G9 (`.rw2`), Fujifilm
X-S10 (`.raf`), Pentax K-1 Mark II (`.pef`), Pentax K-3 Mark III and Google Pixel 7 Pro (`.dng`). Save them under the
names the tests expect (`nikon-d500.nef`, `canon-eos-r7.cr3`, … listed in `RawSamples` in the app's test sources).

CI (`.github/workflows/build.yml`) builds on Linux, macOS and Windows, but **only when started by hand**, because
Actions minutes are limited. Start it from the Actions tab (Build → Run workflow), or with:

```bash
gh workflow run build.yml --ref <branch>
```

## Run

```bash
./mvnw install -DskipTests
./mvnw -pl app exec:java
```

Opens the app in its own window (Edge or Chrome in app mode; the default browser's tab when neither is found) on
local port 47821, or any free port when that is taken (`127.0.0.1` only). Closing the last window quits the app. Only
one copy runs at a time: opening it again opens a window on the running copy. After changing Java code, re-run `./mvnw install -DskipTests` before `exec:java`.
On first run the app shows a setup screen where you choose the folder that will hold your library.
Options:

- `-Datlas.dataDir=<folder>` opens that library and skips setup.
- `-Datlas.configDir=<folder>` keeps settings elsewhere.
- `-Datlas.port=7878` (or the `ATLAS_PORT` environment variable) fixes the port.
- `-Datlas.noBrowser=true` skips opening any window; the console prints a launch address to open by hand (it works
  once, for a minute), and the app never quits by itself until a window has connected.
- `-Datlas.heicDecoder=none|sips|wic|heif-convert` (developers) forces "no HEIC decoder" or one decoder, to try those
  screens.
- `-Datlas.browser=<program>` (or the `ATLAS_BROWSER` environment variable) names the browser for the app window.
- `-Datlas.quitSeconds=<n>` is how many seconds with no window open before the app quits (default 10);
  `-Datlas.startSeconds=<n>` is how long a window has to connect after start before the default browser is tried
  (default 60; the app quits after as long again).
- `-Datlas.taxonomyDir=<folder>` and `-Datlas.placesDir=<folder>` (developers) load taxonomy or place lists from a
  folder instead of the bundled ones. Use them to try an update before shipping it; see `UpdateBundles` and
  `UpdatePlaces` in the app's test sources, whose `main` writes such a folder.

Photos are added on a species page (drag and drop, or "Add photos"), or in bulk with "Import folder" in the top bar.

## Work on the UI with live reload

After the Run steps above (or `./mvnw install -DskipTests`), start the app on a fixed port:

```bash
./mvnw -pl app exec:java -Datlas.port=7878 -Datlas.noBrowser=true -Datlas.configDir=/tmp/atlas-config
```

Passing `-Datlas.configDir=<folder>` keeps your real config folder untouched while developing; the first-run setup
screen then appears. Add `-Datlas.dataDir=<folder>` to open a library directly and skip setup.

In a second terminal:

```bash
cd ui && npm install && npm run dev
```

Open http://localhost:5173. Vite serves the UI with hot reload and forwards `/api` calls to the app on port 7878.

## Update the taxonomy (developers)

The taxonomy bundle is built by the `taxonomy-importer` module, which is never part of the shipped app. It needs no other
module built first (its pom depends only on Jackson). From the repo root:

```bash
./mvnw -pl taxonomy-importer compile exec:java
```

Optionally add `-Dexec.args="--version 2026-11-08 --quality research"`. The version defaults to today's date. The importer refuses to run outside the repo root, and refuses if bundles exist but `taxonomy-importer/atlas-ids.json` is missing.

It needs internet and takes a few minutes (about one request per second, with retries). Outputs:

- `app/src/main/resources/taxonomy/taxonomy-india-YYYY-MM-DD.json` (the file name and `version` are the import date)
- updates to `taxonomy-importer/atlas-ids.json`, the permanent Atlas id registry
- `app/src/main/resources/taxonomy/bundles.sha256`, the checksum list of every bundle in that folder (rewritten after each import)

Commit all three. Before committing, review the printed counts and the list of every change since the previous bundle; a WARNING appears if more than 20% of the previous taxa disappeared.

Responses are cached in `taxonomy-importer/.cache/<date>/` (gitignored). Re-running the same date replays the cache; delete that folder to refetch.

Quality: the default `verifiable` keeps every species with at least one usable iNaturalist observation in India, confirmed or not. `--quality research` keeps only community-confirmed species. Genus-only identifications are always excluded.

## Update the place lists (developers)

The place boundaries and names are built by the `place-importer` module, which is also never part of the shipped app.
From the repo root:

```bash
./mvnw -pl place-importer compile exec:java -Dexec.args="--lgd <districts.csv>"
```

It needs no other module built first. It downloads geoBoundaries (India's states and districts) and Natural Earth
(countries and first-level regions), and uses the Local Government Directory district list (`--lgd`, optional) for
official Indian names and codes. Run it with `--sources <dir>`
to read the source files from a folder instead of downloading them. Other options are listed at the top of
`PlaceImporterMain.java`.

Outputs, in `app/src/main/resources/places/`:

- `places-YYYY-MM-DD.json` (names and the place tree) and `places-YYYY-MM-DD.bin` (boundaries)
- `places.sha256`
- updates to `place-importer/place-ids.json`, the permanent place id registry

**Every released list stays in the app.** A new list is compared with the previous one:

- each merge, split or retirement must be listed in the reviewed `place-importer/src/main/resources/place-changes.csv`;
- a place that disappears without a line there stops the build, with the place named.

Review the printed change table, then commit the new list together with the registry and the checksum file. Sources and
their licences are in `app/src/main/resources/places/NOTICE.txt`. Downloads are cached in `place-importer/.cache/`,
which git ignores.

## Package it yourself

One command per operating system builds the installer for the system you run it on (an installer cannot be built for
another system). Everything goes through the `package` profile, which a plain `verify` never uses. The steps, in order:
the UI and the app are built and tested; `jlink` builds a small Java runtime; `jpackage` builds the application image; a
**smoke test** starts that app and drives every route of its API (it fails the build and names the step); only then are the installers
made. The smoke test alone can be re-run on the image with
`java packaging/smoke/SmokeTest.java --app <image folder> --version 1.0.0 --smoke-dir packaging/smoke`.

The version is the root `pom.xml`'s (digits only, `1.0.0`; jpackage needs that). Installers land in
`packaging/target/dist/` as `Butterfly_Atlas-<version>-<system>-<cpu>.<ext>`. To skip the tests (not the smoke test)
while experimenting, add `-DskipTests`.

**Where the files land when you build locally** (all under `packaging/target/`, which git ignores):

| Folder | What is in it |
|---|---|
| `packaging/target/dist/` | **The installers**: `Butterfly_Atlas-1.0.0-<system>-<cpu>.exe` (Windows), `.deb` and `.rpm` (Linux) or `.dmg` (macOS). This is what you give to people. |
| `packaging/target/work/image/` | The built application itself, before it is wrapped in an installer (`Butterfly Atlas.app` on macOS, a `Butterfly Atlas` folder on Windows and Linux). You can run it from there to try it without installing; the smoke test runs this. |

`./mvnw clean …` (and `clean verify`) **deletes the whole `packaging/target/` folder**, installers
included, so copy what you want to keep before the next clean build.

**macOS** (Terminal; Xcode command line tools installed):

```bash
./mvnw -B -ntp -Ppackage -pl packaging -am verify
```

Result: `packaging/target/dist/Butterfly_Atlas-1.0.0-macos-arm64.dmg` on an Apple Silicon Mac (`-x64` when you run the
same command on an Intel Mac, which is the only way to get an Intel build: the release workflow makes the Apple Silicon one
only): an unsigned disk image with `Butterfly Atlas.app`, the licence, the NOTICE and the third-party notices.

**Windows** (PowerShell or Command Prompt; JDK 21 and Inno Setup 6.7 or later installed):

```powershell
.\mvnw.cmd -B -ntp -Ppackage -pl packaging -am verify
```

**Set up Inno Setup once on Windows:**

1. Install Inno Setup **6.7** for yourself only: in PowerShell run `winget install --id JRSoftware.InnoSetup -e --scope user`,
   or download `innosetup-6.7.3.exe` from Inno Setup's download page (`jrsoftware.org/isdl.php`), run it and choose
   **Install for me only** (the default, "for all users", asks for an administrator). No `PATH` change or .NET Framework is
   needed.
2. Open a **new** terminal.
3. Run the package command shown above. It prints `Using Inno Setup: <path>` when it has found it.

Result: `packaging\target\dist\Butterfly_Atlas-1.0.0-windows-x64.exe`, an installer wizard: the licence, then a tick box
"Create a desktop shortcut" (ticked), then it installs **for the current Windows user only, without administrator rights**,
into `%LOCALAPPDATA%\Programs\Butterfly_Atlas`, adds **Butterfly Atlas** to the Start Menu, and offers to start it. A newer
version installs over an older one in the same place (one entry in Settings → Apps). It never touches your library, the
config folder or the browser profile.

**Linux** (JDK 21, `fakeroot`, `dpkg-dev` and `rpm-build` installed):

```bash
# Debian, Ubuntu, Mint
sudo apt-get install fakeroot dpkg-dev rpm
./mvnw -B -ntp -Ppackage -pl packaging -am verify

# Fedora, RHEL, openSUSE (builds the .rpm natively; the .deb needs dpkg-dev and fakeroot, which Fedora also ships)
sudo dnf install fakeroot dpkg-dev rpm-build
./mvnw -B -ntp -Ppackage -pl packaging -am verify
# only the .rpm, if you do not want dpkg:
./mvnw -B -ntp -Ppackage -pl packaging -am verify -Dpackaging.linuxTypes=rpm
```

Result: `packaging/target/dist/Butterfly_Atlas-1.0.0-linux-x64.deb` and `...-linux-x64.rpm`. Both install under `/opt`,
add a menu entry with the icon, and carry the licence.

**Which Linux systems can install them.** The release workflow builds on Ubuntu 22.04 (glibc 2.35), and the installers need that
glibc or newer: Ubuntu 22.04 or later, Debian 12 or later, Fedora 36 or later. On an older system they refuse to install. If you
build them yourself, they need the glibc of the system that built them.

The icons are committed (`packaging/icons`), so no image tools are needed.

## Installing and the first-launch warnings

**Quit Butterfly Atlas before installing or upgrading.** Windows cannot replace the files of a running app; closing the
app's window quits it (wait ten seconds).

- **Windows:** double-click the `.exe`. The installers are not signed, so SmartScreen says "Windows protected your PC":
  click **More info**, then **Run anyway**. Then accept the licence, keep or untick "Create a desktop shortcut", and click
  **Install**; no administrator password is asked for. Each Windows user installs it for themselves, and every user has
  their own library and settings; on a shared device, other users do not see your copy.
  - If the installer says Butterfly Atlas is still running, or that it was "unable to automatically close all
    applications", close the app's window, wait ten seconds, then click **Next**.
  - If it says a file is in use, quit Butterfly Atlas and click **Retry**. Never skip files: that would leave a mix of two
    versions.
- **macOS (Apple Silicon only; on an Intel Mac, build it from source):** open the `.dmg` and drag `Butterfly Atlas.app` to Applications. The app is not signed or notarized, so the first
  time macOS says it cannot verify it: open **System Settings → Privacy & Security**, scroll to the message about
  Butterfly Atlas and click **Open Anyway**, then confirm.
- **Linux:** `sudo apt install ./Butterfly_Atlas-1.0.0-linux-x64.deb` or `sudo dnf install ./Butterfly_Atlas-1.0.0-linux-x64.rpm`.
  No warning. Start it from the application menu.

## Where the data, config, log and browser profile live

Your **library** (photos and `atlas.db`) is the folder you chose on first run (suggested `Documents/Butterfly_Atlas`). The
**config folder** (named by the system's standard location, so on Windows and macOS it is `Butterfly Atlas`, with a space, and
on Linux `butterfly-atlas`) holds `config.json` (which library), `logs/atlas.log`, `browser_profile/` (the app window's own browser
profile) and, while the app runs, `instance.json`:

| | Config folder |
|---|---|
| Windows | `%APPDATA%\Butterfly Atlas` |
| macOS | `~/Library/Application Support/Butterfly Atlas` |
| Linux | `~/.config/butterfly-atlas` (or `$XDG_CONFIG_HOME/butterfly-atlas`) |

Settings → About shows the version and all three folders. Uninstalling never touches any of them; to remove them by hand,
quit the app and delete the config folder (and the library folder, only if you no longer want your photos).

## Backing up and restoring

Settings → **Back up now** (or the link on Home) copies the database (**Database and settings only**) or the database and
all photos (**Everything**) into a new folder `Butterfly_Atlas_backup_<date>` in a place you choose; nothing is copied
automatically. A backup is a plain folder: `atlas.db` (a complete, stand-alone copy of the database, so no `-wal` or `-shm`
files come with it) and, for **Everything**, the species folders with the photos. Photos in Recently deleted are not in a
backup.

**To restore, quit Butterfly Atlas first.** The app keeps two helper files next to `atlas.db` while it works
(`atlas.db-wal` and `atlas.db-shm`), and a copy of an older `atlas.db` laid over an old library folder can leave them
behind: SQLite would then replay them onto the restored file. So restore into a **new, empty folder**:

1. Create a new empty folder, for example `Documents/Butterfly_Atlas_restored`, and copy the whole contents of the backup
   folder into it.
2. Tell the app to use that folder. The setup screen appears only when no library is set, so close the app and delete
   `config.json` in the config folder (see above; it holds nothing but the library's location, and your old library and
   photos are not touched). Start the app. In the setup screen, enter the new folder: it says "This folder already has a
   Butterfly Atlas library. It will open as it is." and the button reads **Open this existing library**. The app then
   opens that `atlas.db` (it brings an older database up to date, and refuses one made by a newer version of the app,
   saying so).
3. Once everything looks right, you can delete the old library folder yourself; the app never does.

If you would rather restore over the **same** library folder, first delete `atlas.db-wal` and `atlas.db-shm` in it (if they
are there), then copy the backup's contents over it. The app opens it on the next start with no setup step, because the
folder is the one it already remembers.

If the app cannot find the remembered library folder at all (a lost drive, a deleted folder), it shows the same setup
screen on start: choose the restored folder there.

## Uninstalling

- **Windows:** quit Butterfly Atlas first, then Settings → Apps → Butterfly Atlas → Uninstall. If the uninstaller says some
  elements could not be removed, quit the app and delete the folder `%LOCALAPPDATA%\Programs\Butterfly_Atlas` yourself.
- **macOS:** drag `Butterfly Atlas.app` from Applications to the Bin.
- **Linux:** `sudo apt remove butterfly-atlas` or `sudo dnf remove butterfly-atlas`.

Only the app's own folder is removed. Your library, the config folder and the browser profile stay (see above for how to
delete them by hand).

## Troubleshooting

- **No window opens in app mode / "Running in a browser tab":** Edge or Chrome was not found, so the app opened a tab of
  your default browser. It works the same; closing the tab quits the app. To name a browser, set `ATLAS_BROWSER` to its
  program path.
- **"Butterfly Atlas is running, but couldn't open another window.":** a copy is open (maybe minimised): use one of its
  windows, or New window there. If none is open, quit the app (close its windows; wait ten seconds) and start it again.
- **Port 47821 is blocked or taken:** the app uses 47821 first and any free port when it is taken. If it cannot start at
  all it opens a page that says why. To force a port, set `ATLAS_PORT`.
- **Nothing happens at all:** read the log, `logs/atlas.log` in the config folder (see above). It never contains your launch
  key; a message "Butterfly Atlas hit an unexpected problem" refers to it.
- **Linux: no menu entry appears, or the install fails over the space in the name "Butterfly Atlas":** this is untested on Linux
  (the installers could only be built and run on macOS here). The fallback is to rebuild with the app's installer name set
  without the space: change `NAME` in `packaging/scripts/PackageApp.java` to `Butterfly_Atlas` for Linux only, then package again.
- **The window stays blank, or a confined Chromium (a snap) cannot open it:** Butterfly Atlas opens its window from a small
  file in the `launch` folder of the config folder (it holds a one-use key, is readable only by you, and is deleted as soon as
  the window has used it; no key is ever put in a program's arguments, so other people on a shared device cannot read it
  with `ps` or Task Manager). A snap Chromium cannot read hidden folders such as `~/.config`; after 60 seconds the app opens
  your default browser the same way, and if that cannot read it either, install Google Chrome or Edge from their own
  packages. This is untested on Windows and Linux.
- **HEIC photos say "Can't show this photo":** this device has no HEIC decoder Butterfly Atlas can use. Settings → About
  says which one it found, or what to install (Windows: Windows' HEVC decoder, and its HEIF decoder on editions without
  one; Linux: `heif-convert` from your distribution's packages). After installing, choose **Check again** there. The photos are kept exactly
  as they are meanwhile, with their places and details. On Windows the decoder runs through Windows PowerShell: security
  software that blocks PowerShell also blocks HEIC (the note then says so). Windows and Linux decoding are untested.
- **Building the Windows installer stops with "EndUpdateResource failed" or "Access is denied":** security software is
  scanning the new file while Inno Setup writes it. Run the package command again; if it keeps happening, exclude the folder
  `packaging\target` from real-time scanning while you build.
- **"To open Butterfly Atlas, use its icon, or New window in a Butterfly Atlas window.":** the address was opened from a
  bookmark, an old tab or another browser; start the app from its icon, or use New window in one of its windows. (Before
  2026-10-28 every launch ended here with "Open Butterfly Atlas from its icon."; that was the launch bug this release fixes.)

## Running the release workflow by hand

`.github/workflows/release.yml` builds the installers on Windows, Linux and macOS and prepares a **draft** GitHub Release.
It runs only when you start it (Actions tab → Release → Run workflow, or `gh workflow run release.yml --ref <branch> -f
version=1.0.0`); the version must equal the one in the root `pom.xml`. Each system installs its prerequisites, runs the
`package` command above (the smoke test included), and uploads its installers. The last job creates the draft release with
all installers (the macOS one labelled "Apple Silicon only, unsigned, expect a Gatekeeper warning"); you publish it by hand. GitHub's
runners are free for a public repository.

### Where the files appear on GitHub

Open the repository on GitHub, then:

- **The installers, as a draft release:** **Releases** (right-hand side of the repository's front page) → the draft
  **"Butterfly Atlas 1.0.0"** (tag `v1.0.0`). It lists the `.exe`, `.deb`, `.rpm` and `.dmg` as downloadable files. Nothing
  is public until you open the draft, check it and press **Publish release**: a draft is seen only by people who can
  write to the repository. A release never expires.
- **The same installers as workflow artifacts:** **Actions** → **Release** → click the run → scroll to the **Artifacts**
  section at the bottom of the run's summary page: `installers-windows`, `installers-linux` and `installers-macos` (zip files).
  They are deleted after **30 days**. In a public repository anyone signed in to GitHub can download them.
- Artifacts count against your repository's Actions storage (500 MB on GitHub's Free plan; the macOS installer alone is
  about 66 MB), so delete old runs' artifacts (Actions → the run → the trash icon next to an artifact) if GitHub says
  storage is full. The draft release keeps its own copy of the installers, so this is safe.

Command line (needs `gh` logged in): `gh run list --workflow release.yml` shows the runs; `gh run download <run-id> -D <folder>`
downloads all of a run's artifacts.

**Release checklist (copy this when you make a release):** version raised in the root `pom.xml` and the workflow input · run
green · installers checked on a clean machine · draft release published.

## Licence

Butterfly Atlas is free and open-source software: Copyright 2026 We are Naturalists, licensed under the
[Apache License 2.0](LICENSE) (see also [NOTICE](NOTICE)). The bundled taxonomy and place data are **not** covered by the
Apache License, and the libraries and fonts keep their own licences: all are listed in
[THIRD_PARTY_NOTICES.txt](THIRD_PARTY_NOTICES.txt), which ships inside every installer with LICENSE and NOTICE.

**HEIC photos and decoder licences.** Butterfly Atlas includes no HEIC, HEIF or HEVC picture decoder. It shows HEIC photos
by starting, as a separate program, a decoder that the device's vendor or the user provides (`sips` on macOS, Windows'
own HEIF and HEVC decoders, `heif-convert` on Linux). Using that decoder relies on the licence of whoever provides it —
the device's vendor or the user — not on any licence of Butterfly Atlas. Where a decoder is restricted by patents in a
country, obtaining and using it lawfully is a matter for the user and the decoder's supplier. The app carries two tiny HEIC
test pictures of its own (simple shapes, about 800 bytes each) only to check that the decoder works and how it turns
pictures. Reading a HEIC file's details (date, GPS) in the app's own code is still under legal review.

## Contributing

Contributions are welcome: see [CONTRIBUTING.md](CONTRIBUTING.md). To report a security problem, follow
[SECURITY.md](SECURITY.md) instead of opening a public issue.
