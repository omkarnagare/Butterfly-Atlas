package com.butterflyatlas.app.imaging.heif;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Windows' decoder (Phase 7b design §4, audit 4 and 13, review fixes J3, J7, J10): the system's image decoder (WIC,
 * through WPF's {@code BitmapDecoder}) run by the built-in Windows PowerShell, {@code powershell.exe -NoProfile
 * -NonInteractive -Command -}, with this fixed script on its standard input (no script file, so execution policy does
 * not apply). The script is a constant: the two working paths and the long side reach it as the environment variables
 * {@value #IN}, {@value #OUT} and {@value #SIDE}, so no path is ever part of a script text or an argument, whatever
 * characters the library's path has. The script prints {@value #READY} first (no such line: PowerShell itself was
 * stopped, {@code blocked}) and {@code ATLAS-ERROR <HRESULT>} when loading WPF or decoding fails, which {@link #reason}
 * maps; every way out has its own exit code, and the text ends with an empty line so PowerShell runs its last statement.
 * The photo is opened with {@code ReadWrite, Delete} sharing and read at once ({@code OnLoad}), and closed before
 * encoding, so the library can move or delete it meanwhile. Untested on Windows (no Windows computer was available).
 */
final class WicScript {

    static final String READY = "ATLAS-READY";
    static final String IN = "ATLAS_IN";
    static final String OUT = "ATLAS_OUT";
    static final String SIDE = "ATLAS_SIDE";
    private static final Pattern ERROR = Pattern.compile("ATLAS-ERROR ([0-9A-Fa-f]{8})");
    /** WINCODEC_ERR_COMPONENTNOTFOUND: no WIC decoder for the file (Windows' HEIF decoder absent). */
    private static final String NO_DECODER = "88982F50";

    /** The script, word for word; nothing is ever put into it. */
    static final String SCRIPT = String.join("\r\n",
            "$ErrorActionPreference = 'Stop'",
            "[Console]::Out.WriteLine('" + READY + "')",
            "try { Add-Type -AssemblyName PresentationCore }",
            "catch { [Console]::Out.WriteLine('ATLAS-ERROR ' + ('{0:X8}' -f $_.Exception.HResult)); exit 5 }",
            "$in = $env:" + IN,
            "$out = $env:" + OUT,
            "$side = [int]$env:" + SIDE,
            "try {",
            "  $s = [IO.File]::Open($in, 'Open', 'Read', 'ReadWrite, Delete')",
            "  try {",
            "    $d = [Windows.Media.Imaging.BitmapDecoder]::Create($s, [Windows.Media.Imaging.BitmapCreateOptions]::None,"
                    + " [Windows.Media.Imaging.BitmapCacheOption]::OnLoad)",
            "    $f = $d.Frames[0]",
            "  } finally { $s.Close() }",
            "  $long = [Math]::Max($f.PixelWidth, $f.PixelHeight)",
            "  if ($long -gt $side) {",
            "    $k = $side / $long",
            "    $f = New-Object Windows.Media.Imaging.TransformedBitmap($f, (New-Object Windows.Media.ScaleTransform($k, $k)))",
            "  }",
            "  $e = New-Object Windows.Media.Imaging.JpegBitmapEncoder",
            "  $e.QualityLevel = 90",
            "  $e.Frames.Add([Windows.Media.Imaging.BitmapFrame]::Create($f))",
            "  $o = [IO.File]::Open($out, 'CreateNew', 'Write', 'None')",
            "  try { $e.Save($o) } finally { $o.Close() }",
            "} catch {",
            "  $x = $_.Exception",
            "  while ($x.InnerException -ne $null) { $x = $x.InnerException }",
            "  [Console]::Out.WriteLine('ATLAS-ERROR ' + ('{0:X8}' -f $x.HResult))",
            "  exit 4",
            "}",
            "exit 0",
            "",
            "");

    private WicScript() {
    }

    /** {@code <SystemRoot>\System32\WindowsPowerShell\v1.0\powershell.exe}, from the system folder (never PATH). */
    static Path powershell(String systemRoot) {
        String root = systemRoot == null || systemRoot.isBlank() ? "C:\\Windows" : systemRoot;
        return Path.of(root, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
    }

    static List<String> arguments(Path powershell) {
        return List.of(powershell.toString(), "-NoProfile", "-NonInteractive", "-Command", "-");
    }

    /** The script's input: the working paths and the long side, as environment variables (fixes J3). */
    static Map<String, String> environment(Path input, Path output, int longSide) {
        return Map.of(IN, input.toString(), OUT, output.toString(), SIDE, String.valueOf(Math.max(1, longSide)));
    }

    /**
     * Why a run failed, from what the script said: {@code blocked} when it never reached its first line (PowerShell was
     * stopped by security software, AppLocker or Constrained Language mode), {@code no_heif} when WIC has no decoder
     * for HEIF at all, else {@code failed} (Windows' HEVC decoder missing or broken, or WPF could not be loaded).
     */
    static String reason(String output) {
        if (output == null || !output.contains(READY)) {
            return "blocked";
        }
        Matcher m = ERROR.matcher(output);
        if (m.find() && m.group(1).toUpperCase(Locale.ROOT).equals(NO_DECODER)) {
            return "no_heif";
        }
        return "failed";
    }
}
