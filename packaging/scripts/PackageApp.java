import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The packaging chain, one cross-platform program the packaging module runs from Maven (the JDK's own
 * jdeps, jlink and jpackage, and on Windows Inno Setup's compiler):
 *
 * <pre>
 *   java packaging/scripts/PackageApp.java check-windows --root REPO  (any OS: the Windows installer script and the Inno Setup lookup)
 *   java packaging/scripts/PackageApp.java runtime    --work DIR
 *   java packaging/scripts/PackageApp.java image      --work DIR --root REPO --version 1.0.0
 *   java packaging/scripts/PackageApp.java smoke      --work DIR --root REPO --version 1.0.0
 *   java packaging/scripts/PackageApp.java installers --work DIR --root REPO --version 1.0.0 --dist DIR [--linux-types deb,rpm]
 * </pre>
 *
 * {@code DIR} is packaging/target/work: {@code in/} (the app's own jar), {@code libs/} (the libraries, untouched),
 * and what this makes: {@code runtime/} (the trimmed Java runtime), {@code app/} (the jars jpackage takes), {@code image/}
 * (the application image, which the smoke test runs) and the installers, moved to {@code --dist} under one naming scheme.
 * On Windows the installer is made by Inno Setup from the same application image. Any failing step ends the build and
 * says which one.
 */
public final class PackageApp {

    static final String NAME = "Butterfly Atlas";
    static final String MAIN_JAR = "butterfly-atlas-app.jar";
    static final String MAIN_CLASS = "com.butterflyatlas.app.Main";
    static final String VENDOR = "We are Naturalists";
    static final String DESCRIPTION = "Organise your butterfly photographs by taxonomy, offline.";
    /** Lines the first page of the Windows installer needs: the notices file, its button, and the opening it uses. */
    static final List<String> INNO_NOTICES = List.of("Source: \"{#NoticesFile}\"; Flags: dontcopy",
            "Button.Parent := WizardForm.InfoBeforePage;", "ExtractTemporaryFile('THIRD_PARTY_NOTICES.txt');");
    /** The installer's first page (information only, nothing to accept), relative to the repository root. */
    static final String INFO_PAGE = "packaging/installer/before-install.rtf";
    /** What that page must say: the privacy statement, the licence and the button for the notices. */
    static final List<String> INFO_WORDS = List.of("Free and open-source software", "Privacy",
            "makes no network connections and sends nothing anywhere", "Apache License 2.0", "Third-party notices...");
    static final String COPYRIGHT = "Copyright 2026 We are Naturalists";
    /** The files every package carries next to the app (relative to the repository root). */
    static final List<String> LICENCE_FILES = List.of("LICENSE", "NOTICE", "THIRD_PARTY_NOTICES.txt");
    /** Lines that show LICENSE is the Apache License 2.0, word for word as apache.org publishes it. */
    static final List<String> APACHE_WORDS = List.of("Apache License", "Version 2.0, January 2004",
            "TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION", "END OF TERMS AND CONDITIONS");
    /** What NOTICE must say: whose the copyright is, the licence, and that the data is not under it. */
    static final List<String> NOTICE_WORDS = List.of("Butterfly Atlas", "Copyright 2026 We are Naturalists",
            "Apache License, Version 2.0", "are NOT covered by the Apache License", "THIRD_PARTY_NOTICES.txt");
    /** The Windows installer's script (Inno Setup), relative to the repository root. */
    static final String INNO_SCRIPT = "packaging/installer/windows/ButterflyAtlas.iss";
    /**
     * Lines the Inno Setup script must keep, checked on every OS. The AppId never changes: it is how Windows knows a newer
     * installer replaces an older one ("{{" is Inno's escaped "{").
     */
    static final List<String> INNO_REQUIRED = List.of("AppId={{1e9d8bc9-7c29-412c-8c72-a91e9f869f5a}",
            "PrivilegesRequired=lowest", "DefaultDirName={userpf}\\Butterfly_Atlas", "DisableDirPage=yes",
            "DisableProgramGroupPage=yes", "CloseApplications=yes", "RestartApplications=no",
            "Type: filesandordirs; Name: \"{app}\\app\"", "Type: filesandordirs; Name: \"{app}\\runtime\"",
            "InfoBeforeFile={#InfoFile}");
    /** Settings that would let the installer ask for administrator rights, or make people accept a licence. */
    static final List<String> INNO_FORBIDDEN = List.of("PrivilegesRequiredOverridesAllowed", "LicenseFile=");
    /** Modules the libraries load by name (or that jdeps cannot see); the smoke test proves the list is enough. */
    static final List<String> EXTRA_MODULES = List.of("java.sql", "java.naming", "java.management", "java.xml",
            "java.desktop", "java.net.http", "java.logging", "java.prefs", "jdk.unsupported", "jdk.zipfs");

    private static final String OS = os();

    private PackageApp() {
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            fail("arguments", "expected: runtime | image | installers");
        }
        Map<String, String> options = options(args);
        try {
            switch (args[0]) {
                case "check-windows" -> checkWindows(path(options, "root"));
                case "runtime" -> runtime(path(options, "work"));
                case "image" -> image(path(options, "work"), path(options, "root"), required(options, "version"));
                case "smoke" -> smoke(path(options, "work"), path(options, "root"), required(options, "version"));
                case "installers" -> installers(path(options, "work"), path(options, "root"), required(options, "version"),
                        path(options, "dist"), options.getOrDefault("linux-types", "deb,rpm"));
                default -> fail("arguments", "unknown command " + args[0]);
            }
        } catch (StepFailed e) {
            System.err.println();
            System.err.println("PACKAGING FAILED at step: " + e.step);
            System.err.println(e.getMessage());
            System.exit(1);
        } catch (IOException | RuntimeException e) {
            System.err.println();
            System.err.println("PACKAGING FAILED: " + e);
            System.exit(1);
        }
    }

    // ---- the Windows installer's script and the Inno Setup lookup, checked on every OS (Inno Setup runs only on Windows)

    static void checkWindows(Path root) throws IOException {
        Path script = root.resolve(INNO_SCRIPT);
        if (!Files.isRegularFile(script)) {
            throw new StepFailed("check-windows", "The Windows installer script is missing: " + script);
        }
        List<String> lines = Files.readAllLines(script).stream().map(String::strip).toList();
        List<String> problems = new ArrayList<>();
        // Inno Setup ignores the case of names, so the checks do too; a setting given twice is refused (the last one wins).
        for (String line : INNO_REQUIRED) {
            if (lines.stream().noneMatch(line::equalsIgnoreCase)) {
                problems.add("missing the line: " + line);
            }
            int equals = line.indexOf('=');
            if (!line.startsWith("Type:") && equals > 0) {
                String key = line.substring(0, equals + 1).toLowerCase(Locale.ROOT);
                if (lines.stream().filter(l -> l.toLowerCase(Locale.ROOT).replace(" ", "").startsWith(key)).count() > 1) {
                    problems.add("sets " + line.substring(0, equals) + " more than once");
                }
            }
        }
        for (String word : INNO_FORBIDDEN) {
            if (lines.stream().anyMatch(l -> l.toLowerCase(Locale.ROOT).startsWith(word.toLowerCase(Locale.ROOT)))) {
                problems.add("must not have: " + word);
            }
        }
        for (String line : INNO_NOTICES) {
            if (lines.stream().noneMatch(line::equalsIgnoreCase)) {
                problems.add("missing the line: " + line);
            }
        }
        for (String define : List.of("NoticesFile", "InfoFile")) {
            if (lines.stream().noneMatch(("#ifndef " + define)::equals)) {
                problems.add("missing the check that /D" + define + "=... is given");
            }
        }
        // Windows shows the program's description as its name (Task Manager, Open with), so there it is the name.
        if (!description("windows").equals(NAME) || !description("linux").equals(DESCRIPTION)) {
            problems.add("the app's description is \"" + description("windows") + "\" on Windows (expected the name, " + NAME
                    + ") and \"" + description("linux") + "\" elsewhere");
        }
        Path info = root.resolve(INFO_PAGE);
        problems.addAll(missingWords(info, INFO_WORDS));
        if (Files.isRegularFile(info) && !Files.readString(info).chars().allMatch(c -> c < 128)) {
            problems.add(info.getFileName() + " has characters outside plain ASCII (write them as RTF escapes)");
        }
        // Every package carries LICENSE and NOTICE (checked here because this step runs first, on every OS).
        problems.addAll(missingWords(root.resolve("LICENSE"), APACHE_WORDS));
        problems.addAll(missingWords(root.resolve("NOTICE"), NOTICE_WORDS));
        // Windows names it "Path", and System.getenv()'s map is case-sensitive; a malformed entry is skipped.
        Map<String, String> env = Map.of("Path", "C:\\Tools;;\"C:\\Quoted Dir\";C:\\bad<name", "ProgramFiles(x86)",
                "C:\\PF86", "ProgramFiles", "C:\\PF", "LOCALAPPDATA", "C:\\Users\\a\\AppData\\Local");
        List<String> expected = List.of("C:\\Tools\\ISCC.exe", "C:\\Quoted Dir\\ISCC.exe",
                "C:\\PF86\\Inno Setup 6\\ISCC.exe", "C:\\PF86\\Inno Setup 7\\ISCC.exe",
                "C:\\PF\\Inno Setup 6\\ISCC.exe", "C:\\PF\\Inno Setup 7\\ISCC.exe",
                "C:\\Users\\a\\AppData\\Local\\Programs\\Inno Setup 6\\ISCC.exe",
                "C:\\Users\\a\\AppData\\Local\\Programs\\Inno Setup 7\\ISCC.exe");
        List<String> looked = isccCandidates(env);
        if (!looked.equals(expected)) {
            problems.add("the Inno Setup lookup looks in " + looked + ", expected " + expected);
        }
        if (!isccCandidates(Map.of()).isEmpty()) {
            problems.add("the Inno Setup lookup invents places when no folder is known");
        }
        if (!problems.isEmpty()) {
            throw new StepFailed("check-windows", script + ":\n  " + String.join("\n  ", problems));
        }
        System.out.println("Windows installer script and Inno Setup lookup: OK");
    }

    // ---- step 1: the trimmed Java runtime (jdeps + jlink)

    static void runtime(Path work) throws IOException {
        Path jar = work.resolve("in").resolve(MAIN_JAR);
        if (!Files.isRegularFile(jar)) {
            throw new StepFailed("jlink", "The app jar is missing: " + jar + " (did the build run?)");
        }
        List<String> jdeps = new ArrayList<>(List.of(tool("jdeps"), "--multi-release", "21", "--ignore-missing-deps",
                "--print-module-deps", jar.toString()));
        try (Stream<Path> libs = Files.list(work.resolve("libs"))) {
            libs.filter(p -> p.toString().endsWith(".jar")).sorted().forEach(p -> jdeps.add(p.toString()));
        }
        String found = run("jdeps", jdeps, true).strip();
        Set<String> modules = new LinkedHashSet<>();
        for (String module : found.split(",")) {
            if (!module.isBlank()) {
                modules.add(module.strip());
            }
        }
        modules.addAll(EXTRA_MODULES);
        System.out.println("Runtime modules: " + String.join(",", modules));

        Path runtime = work.resolve("runtime");
        deleteTree(runtime);
        run("jlink", List.of(tool("jlink"), "--add-modules", String.join(",", modules), "--strip-debug",
                "--no-header-files", "--no-man-pages", "--compress", "zip-6", "--output", runtime.toString()), false);
    }

    // ---- step 2: the application image (jpackage --type app-image)

    static void image(Path work, Path root, String version) throws IOException {
        Path appJar = work.resolve("in").resolve(MAIN_JAR);
        Path runtime = work.resolve("runtime");
        if (!Files.isRegularFile(appJar) || !Files.isDirectory(runtime)) {
            throw new StepFailed("jpackage image", "Run the runtime step first (needs " + appJar + " and " + runtime + ")");
        }
        Path input = work.resolve("app");
        deleteTree(input);
        Files.createDirectories(input);
        Files.copy(appJar, input.resolve(MAIN_JAR));
        try (Stream<Path> libs = Files.list(work.resolve("libs"))) {
            for (Path jar : libs.filter(p -> p.toString().endsWith(".jar")).toList()) {
                Files.copy(jar, input.resolve(jar.getFileName()));
            }
        }
        Path image = work.resolve("image");
        deleteTree(image);
        Files.createDirectories(image);

        List<String> command = new ArrayList<>(List.of(tool("jpackage"), "--type", "app-image", "--name", NAME,
                "--app-version", appVersion(version), "--vendor", VENDOR, "--copyright", COPYRIGHT, "--description", description(OS),
                "--input", input.toString(), "--main-jar", MAIN_JAR, "--main-class", MAIN_CLASS,
                "--runtime-image", work.resolve("runtime").toString(), "--dest", image.toString(),
                "--icon", root.resolve("packaging/icons").resolve(icon()).toString(),
                "--temp", work.resolve("jpackage-image-temp").toString()));
        deleteTree(work.resolve("jpackage-image-temp"));
        if (!OS.equals("macos")) {
            // Windows and Linux: the licence, NOTICE and the notices sit in the application folder.
            command.addAll(List.of("--app-content", String.join(",", licenceFiles(root))));
        }
        if (OS.equals("macos")) {
            command.addAll(List.of("--mac-package-identifier", "com.wearenaturalists.butterflyatlas", "--mac-package-name",
                    "Butterfly Atlas"));
        }
        run("jpackage (application image)", command, false);
        if (OS.equals("macos")) {
            // Loose files in Contents/ break the seal, so they go in Contents/Resources, and the app is sealed again:
            // ad hoc ("-"), which Apple Silicon needs to run it at all. This is not signing; there is no identity.
            Path app = imageDir(work);
            Path resources = app.resolve("Contents/Resources");
            for (String file : LICENCE_FILES) {
                Files.copy(root.resolve(file), resources.resolve(file), StandardCopyOption.REPLACE_EXISTING);
            }
            run("codesign (ad hoc seal)", List.of("/usr/bin/codesign", "--force", "--deep", "--sign", "-", app.toString()),
                    false);
        }
        System.out.println("Application image: " + imageDir(work));
    }

    /** The folder the smoke test runs: the .app on macOS, the application folder elsewhere. */
    static Path imageDir(Path work) throws IOException {
        try (Stream<Path> files = Files.list(work.resolve("image"))) {
            return files.filter(Files::isDirectory).findFirst()
                    .orElseThrow(() -> new StepFailed("jpackage image", "jpackage made no application image"));
        }
    }

    // ---- the smoke test of the application image (before any installer is made)

    static void smoke(Path work, Path root, String version) throws IOException {
        Path image = imageDir(work);
        Path smoke = root.resolve("packaging/smoke");
        run("smoke test", List.of(Path.of(System.getProperty("java.home"), "bin", OS.equals("windows") ? "java.exe" : "java").toString(),
                smoke.resolve("SmokeTest.java").toString(), "--app", image.toString(), "--version", version,
                "--smoke-dir", smoke.toString()), false);
    }

    // ---- step 3: the installers (jpackage from the application image; Inno Setup on Windows)

    static void installers(Path work, Path root, String version, Path dist, String linuxTypes) throws IOException {
        Path image = imageDir(work);
        Files.createDirectories(dist);
        if (OS.equals("windows")) {
            windowsInstaller(work, root, version, dist, image);
            return;
        }
        List<String> types = OS.equals("macos") ? List.of("dmg") : List.of(linuxTypes.split(","));
        for (String listed : types) {
            String type = listed.strip();
            Path out = work.resolve("installers-" + type);
            deleteTree(out);
            Files.createDirectories(out);
            deleteTree(work.resolve("jpackage-" + type + "-temp"));
            List<String> command = new ArrayList<>(List.of(tool("jpackage"), "--type", type, "--name", NAME,
                    "--app-version", appVersion(version), "--vendor", VENDOR, "--copyright", COPYRIGHT, "--description", description(OS),
                    "--app-image", image.toString(), "--dest", out.toString(),
                    "--temp", work.resolve("jpackage-" + type + "-temp").toString()));
            if (OS.equals("macos")) {
                command.addAll(List.of("--mac-dmg-content", String.join(",", licenceFiles(root))));
            } else {
                command.addAll(List.of("--linux-package-name", "butterfly-atlas", "--linux-shortcut",
                        "--linux-menu-group", "Education;Science;", "--linux-app-category", "science",
                        "--license-file", root.resolve("LICENSE").toString()));
            }
            if (OS.equals("linux") && type.equals("rpm")) {
                command.addAll(List.of("--linux-rpm-license-type", "Apache-2.0"));
            }
            run("jpackage (" + type + ")", command, false);
            moveInstaller(out, type, "jpackage", version, dist);
        }
    }

    /**
     * Windows: Inno Setup wraps the application image into the .exe installer (per-user, licence page, shortcuts, upgrade in
     * place; the script is {@link #INNO_SCRIPT}). Every value that changes per build is passed to it here.
     */
    static void windowsInstaller(Path work, Path root, String version, Path dist, Path image) throws IOException {
        // System.getenv()'s map is case-sensitive, and Windows names the variable "Path".
        Map<String, String> env = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        env.putAll(System.getenv());
        List<String> looked = isccCandidates(env);
        Path iscc = looked.stream().map(Path::of).filter(Files::isRegularFile).findFirst()
                .orElseThrow(() -> new StepFailed("Inno Setup", "Inno Setup 6.7 or later was not found (looked on the PATH and"
                        + " in: " + String.join(", ", looked) + "). Install it with `winget install --id JRSoftware.InnoSetup -e --scope user`,"
                        + " then open a new terminal."));
        System.out.println("Using Inno Setup: " + iscc);
        Path out = work.resolve("installers-exe");
        deleteTree(out);
        Files.createDirectories(out);
        List<String> command = List.of(iscc.toString(), "/O" + out, "/FButterfly_Atlas-" + version,
                "/DAppName=" + NAME, "/DAppVersion=" + appVersion(version), "/DAppPublisher=" + VENDOR,
                "/DAppCopyright=" + COPYRIGHT, "/DImageDir=" + image,
                "/DIconFile=" + root.resolve("packaging/icons").resolve(icon()),
                "/DInfoFile=" + root.resolve(INFO_PAGE),
                "/DNoticesFile=" + root.resolve("THIRD_PARTY_NOTICES.txt"), root.resolve(INNO_SCRIPT).toString());
        try {
            run("Inno Setup", command, false);
        } catch (StepFailed e) {
            throw new StepFailed("Inno Setup", "Inno Setup could not make the installer (" + e.getMessage() + "). Its own message"
                    + " is above. The Inno Setup used was " + iscc + ".");
        }
        moveInstaller(out, "exe", "Inno Setup", version, dist);
    }

    /** {@link #LICENCE_FILES} as full paths. */
    static List<String> licenceFiles(Path root) {
        return LICENCE_FILES.stream().map(f -> root.resolve(f).toString()).toList();
    }

    /** One problem for each of {@code words} the file does not say (or one for the whole file if it is missing). */
    static List<String> missingWords(Path file, List<String> words) throws IOException {
        if (!Files.isRegularFile(file)) {
            return List.of(file.getFileName() + " is missing at " + file);
        }
        String text = Files.readString(file);
        return words.stream().filter(w -> !text.contains(w)).map(w -> file.getFileName() + " does not say: " + w).toList();
    }

    /**
     * Where the build looks for Inno Setup's compiler, in order: each folder on the PATH (the variable's name in any case),
     * then the "Inno Setup 6" and "Inno Setup 7" folders under Program Files (x86), Program Files and the per-user programs
     * folder (where winget puts a per-user install). Plain strings, so the order can be checked on any OS. The first one
     * that exists is used, even if it is older than 6.7 (the script then says so).
     */
    static List<String> isccCandidates(Map<String, String> env) {
        List<String> places = new ArrayList<>();
        String pathVariable = env.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase("PATH")).map(Map.Entry::getValue)
                .findFirst().orElse(null);
        if (pathVariable != null) {
            for (String folder : pathVariable.split(";")) {
                String dir = folder.strip().replace("\"", "");
                if (!dir.isEmpty() && dir.chars().noneMatch(c -> "<>|?*".indexOf(c) >= 0)) { // a malformed entry is skipped
                    places.add(windowsJoin(dir, "ISCC.exe"));
                }
            }
        }
        List<String> roots = new ArrayList<>();
        for (String variable : List.of("ProgramFiles(x86)", "ProgramFiles")) {
            if (env.get(variable) != null && !env.get(variable).isBlank()) {
                roots.add(env.get(variable).strip());
            }
        }
        if (env.get("LOCALAPPDATA") != null && !env.get("LOCALAPPDATA").isBlank()) {
            roots.add(windowsJoin(env.get("LOCALAPPDATA").strip(), "Programs"));
        }
        for (String folder : roots) {
            for (String inno : List.of("Inno Setup 6", "Inno Setup 7")) {
                places.add(windowsJoin(windowsJoin(folder, inno), "ISCC.exe"));
            }
        }
        return places;
    }

    private static String windowsJoin(String folder, String name) {
        return folder.endsWith("\\") ? folder + name : folder + "\\" + name;
    }

    /** Moves the one installer a tool made in {@code out} to {@code dist}, under the release naming scheme. */
    private static void moveInstaller(Path out, String type, String tool, String version, Path dist) throws IOException {
        try (Stream<Path> made = Files.list(out)) {
            Path file = made.filter(p -> p.getFileName().toString().endsWith("." + extension(type))).findFirst()
                    .orElseThrow(() -> new StepFailed(tool, tool + " made no ." + extension(type) + " file in " + out));
            Path target = dist.resolve("Butterfly_Atlas-" + version + "-" + OS + "-" + arch() + "." + extension(type));
            Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("Installer: " + target + " (" + Files.size(target) + " bytes)");
        }
    }

    // ---- helpers

    /** The version jpackage is given: the root pom's, digits only (macOS needs the first number above zero). */
    static String appVersion(String version) {
        return version;
    }

    /** The app's description for jpackage on {@code os}. */
    static String description(String os) {
        return os.equals("windows") ? NAME : DESCRIPTION;
    }

    private static String icon() {
        return switch (OS) {
            case "windows" -> "butterfly-atlas.ico";
            case "macos" -> "butterfly-atlas.icns";
            default -> "butterfly-atlas.png";
        };
    }

    private static String extension(String type) {
        return type;
    }

    private static String os() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (name.contains("win")) {
            return "windows";
        }
        if (name.contains("mac") || name.contains("darwin")) {
            return "macos";
        }
        return "linux";
    }

    private static String arch() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return arch.equals("amd64") || arch.equals("x86_64") ? "x64" : arch.equals("aarch64") ? "arm64" : arch;
    }

    /** A tool of the JDK running this program (jdeps, jlink, jpackage), so the build never mixes two JDKs. */
    private static String tool(String name) {
        Path bin = Path.of(System.getProperty("java.home"), "bin");
        Path exe = bin.resolve(OS.equals("windows") ? name + ".exe" : name);
        if (!Files.isRegularFile(exe)) {
            throw new StepFailed(name, name + " is not in " + bin + ". Use a full JDK 21 (not a JRE).");
        }
        return exe.toString();
    }

    private static String run(String step, List<String> command, boolean capture) throws IOException {
        System.out.println("[" + step + "] " + String.join(" ", command));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectError(ProcessBuilder.Redirect.INHERIT);
        if (!capture) {
            builder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        }
        Process process = builder.start();
        String output = capture ? new String(process.getInputStream().readAllBytes()) : "";
        try {
            int code = process.waitFor();
            if (code != 0) {
                throw new StepFailed(step, "exit code " + code + (capture ? "\n" + output : ""));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StepFailed(step, "interrupted");
        }
        return output;
    }

    private static void deleteTree(Path folder) throws IOException {
        if (!Files.exists(folder)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(folder)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> options = new java.util.HashMap<>();
        for (int i = 1; i + 1 < args.length; i += 2) {
            options.put(args[i].replaceFirst("^--", ""), args[i + 1]);
        }
        return options;
    }

    private static String required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) {
            throw new StepFailed("arguments", "--" + name + " is required");
        }
        return value;
    }

    private static Path path(Map<String, String> options, String name) {
        return Path.of(required(options, name)).toAbsolutePath().normalize();
    }

    private static void fail(String step, String message) {
        throw new StepFailed(step, message);
    }

    /** A step that failed, named. */
    static final class StepFailed extends RuntimeException {
        final String step;

        StepFailed(String step, String message) {
            super(message);
            this.step = step;
        }
    }
}
