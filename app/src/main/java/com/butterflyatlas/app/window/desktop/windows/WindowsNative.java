package com.butterflyatlas.app.window.desktop.windows;

import com.butterflyatlas.app.window.desktop.ProfileHolder;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.COM.Unknown;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.platform.win32.WinNT.HRESULT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.lang.ref.Reference;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The Windows calls behind the app's icon, through JNA (spec §4.2, contract §4). Nothing here loads JNA until a method
 * that needs it runs, so making one costs nothing and the plain-Java parts ({@link #browserOnProfile}, {@link #values})
 * are tested on every OS. The calls run only on Windows; elsewhere they throw.
 *
 * <ul>
 *   <li>{@link #adopt}: the visible top-level windows of a process ({@code EnumWindows}, {@code
 *   GetWindowThreadProcessId}, {@code IsWindowVisible}) with the class {@value #WINDOW_CLASS}, which Chrome and Edge both
 *   use, get through {@code SHGetPropertyStoreForWindow} and {@code IPropertyStore::SetValue} / {@code Commit} the four
 *   {@code System.AppUserModel} properties ({@link Property}).</li>
 *   <li>{@link #find}: the browser process holding our profile when this copy did not start it. Windows has no
 *   {@code SingletonLock} link, so the processes running the browser program are listed and each one's command line read
 *   ({@code NtQueryInformationProcess}, {@code ProcessCommandLineInformation} = {@value #PROCESS_COMMAND_LINE}); the one
 *   naming {@code --user-data-dir=<profile>} and without {@code --type=} (a helper) is it.</li>
 * </ul>
 */
public final class WindowsNative implements TaskbarWindows, ProfileHolder {

    /** The top-level window class of Chrome's and Edge's windows. */
    static final String WINDOW_CLASS = "Chrome_WidgetWin_1";
    /** The name the taskbar shows for a pinned window (its relaunch command's name). */
    static final String DISPLAY_NAME = "Butterfly Atlas";
    /** The property set of {@code System.AppUserModel.*} (propkey.h). */
    static final String APP_USER_MODEL_FMTID = "{9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3}";
    /** {@code IID_IPropertyStore}. */
    static final String IID_PROPERTY_STORE = "{886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99}";
    /** {@code PROCESSINFOCLASS.ProcessCommandLineInformation} (Windows 8.1 and later). */
    static final int PROCESS_COMMAND_LINE = 60;
    /** {@code PROCESS_QUERY_LIMITED_INFORMATION}: enough to read another process's command line of the same user. */
    static final int PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;
    /** {@code VT_LPWSTR}: a PROPVARIANT holding a wide string. */
    static final short VT_LPWSTR = 31;
    static final int STATUS_INFO_LENGTH_MISMATCH = 0xC0000004;

    /** The {@code System.AppUserModel} properties set on each window, with their property IDs (propkey.h). */
    public enum Property {
        ID(5), RELAUNCH_COMMAND(2), RELAUNCH_DISPLAY_NAME(4), RELAUNCH_ICON(3);

        private final int pid;

        Property(int pid) {
            this.pid = pid;
        }

        public int pid() {
            return pid;
        }
    }

    /** The values set, in order, for the installed program {@code app}: each as [property name, value]. */
    static List<List<String>> values(Path app) {
        return List.of(List.of(Property.ID.name(), WindowsDesktop.APP_ID),
                List.of(Property.RELAUNCH_COMMAND.name(), "\"" + app + "\""),
                List.of(Property.RELAUNCH_DISPLAY_NAME.name(), DISPLAY_NAME),
                List.of(Property.RELAUNCH_ICON.name(), app + ",0"));
    }

    @Override
    public int adopt(long pid, Path appPath) {
        Ole32.INSTANCE.CoInitializeEx(Pointer.NULL, Ole32.COINIT_APARTMENTTHREADED); // S_FALSE when already: harmless
        int set = 0;
        List<String> failures = new ArrayList<>();
        for (HWND window : windowsOf(pid)) {
            try {
                write(window, values(appPath));
                set++;
            } catch (RuntimeException e) {
                failures.add(e.getMessage());
            }
        }
        if (set == 0 && !failures.isEmpty()) {
            throw new IllegalStateException("No window of process " + pid + " took the taskbar properties: " + failures);
        }
        return set;
    }

    /** The visible top-level windows of {@code pid} with Chrome's and Edge's window class. */
    List<HWND> windowsOf(long pid) {
        List<HWND> found = new ArrayList<>();
        char[] name = new char[256];
        User32.INSTANCE.EnumWindows((window, data) -> {
            IntByReference owner = new IntByReference();
            User32.INSTANCE.GetWindowThreadProcessId(window, owner);
            if (owner.getValue() == pid && User32.INSTANCE.IsWindowVisible(window)) {
                int length = User32.INSTANCE.GetClassName(window, name, name.length);
                if (WINDOW_CLASS.equals(new String(name, 0, Math.max(0, length)))) {
                    found.add(window);
                }
            }
            return true;
        }, Pointer.NULL);
        return found;
    }

    /** Sets the values on one window and commits them. */
    private static void write(HWND window, List<List<String>> values) {
        PropertyStore store = store(window);
        try {
            for (List<String> value : values) {
                PropVariant variant = new PropVariant();
                Memory text = wide(value.get(1));
                variant.vt = VT_LPWSTR;
                variant.value = text;
                check(store.setValue(key(Property.valueOf(value.get(0))), variant), "SetValue " + value.get(0));
                Reference.reachabilityFence(text);
            }
            check(store.commit(), "Commit");
        } finally {
            store.Release();
        }
    }

    /** One property of a window, as the shell has it (the real-window check reads them back). */
    Optional<String> read(HWND window, Property property) {
        Ole32.INSTANCE.CoInitializeEx(Pointer.NULL, Ole32.COINIT_APARTMENTTHREADED);
        PropertyStore store = store(window);
        try {
            PropVariant variant = new PropVariant();
            check(store.getValue(key(property), variant), "GetValue " + property);
            variant.read();
            try {
                return variant.vt == VT_LPWSTR && variant.value != null
                        ? Optional.of(variant.value.getWideString(0)) : Optional.empty();
            } finally {
                Libraries.OLE32.PropVariantClear(variant.getPointer());
            }
        } finally {
            store.Release();
        }
    }

    private static PropertyStore store(HWND window) {
        PointerByReference found = new PointerByReference();
        check(Libraries.SHELL32.SHGetPropertyStoreForWindow(window, new Guid.GUID(IID_PROPERTY_STORE), found),
                "SHGetPropertyStoreForWindow");
        return new PropertyStore(found.getValue());
    }

    private static PropertyKey key(Property property) {
        PropertyKey key = new PropertyKey();
        key.fmtid = new Guid.GUID(APP_USER_MODEL_FMTID);
        key.pid = property.pid();
        return key;
    }

    private static Memory wide(String text) {
        Memory memory = new Memory((text.length() + 1L) * Native.WCHAR_SIZE);
        memory.setWideString(0, text);
        return memory;
    }

    private static void check(HRESULT result, String what) {
        if (result == null || result.intValue() < 0) {
            throw new IllegalStateException(what + " failed: 0x"
                    + (result == null ? "?" : Integer.toHexString(result.intValue()).toUpperCase(Locale.ROOT)));
        }
    }

    // ---- the browser process holding our profile

    @Override
    public OptionalLong find(Path browser, Path profile) {
        String program = browser.toAbsolutePath().normalize().toString();
        List<Long> candidates = ProcessHandle.allProcesses()
                .filter(p -> p.info().command().map(c -> c.equalsIgnoreCase(program)).orElse(false))
                .map(ProcessHandle::pid).toList();
        for (long pid : candidates) {
            Optional<String> line = commandLine(pid);
            if (line.isPresent() && browserOnProfile(line.get(), profile)) {
                return OptionalLong.of(pid);
            }
        }
        return OptionalLong.empty();
    }

    /** A process's command line, read with {@code NtQueryInformationProcess}; empty when it cannot be read. */
    Optional<String> commandLine(long pid) {
        HANDLE process = Kernel32.INSTANCE.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, (int) pid);
        if (process == null) {
            return Optional.empty();
        }
        try {
            int size = 4096;
            for (int attempt = 0; attempt < 3; attempt++) {
                Memory buffer = new Memory(size);
                IntByReference needed = new IntByReference();
                int status = Libraries.NTDLL.NtQueryInformationProcess(process, PROCESS_COMMAND_LINE, buffer, size, needed);
                if (status == STATUS_INFO_LENGTH_MISMATCH && needed.getValue() > size) {
                    size = needed.getValue();
                    continue;
                }
                if (status != 0) {
                    return Optional.empty();
                }
                // a UNICODE_STRING (USHORT Length, USHORT MaximumLength, PWSTR Buffer) at the start, its text after it
                int bytes = buffer.getShort(0) & 0xFFFF;
                Pointer text = buffer.getPointer(Native.POINTER_SIZE);
                return Optional.of(text == null ? "" : new String(text.getCharArray(0, bytes / 2)));
            }
            return Optional.empty();
        } finally {
            Kernel32.INSTANCE.CloseHandle(process);
        }
    }

    /**
     * The browser process (not a helper: no {@code --type=}) started on {@code profile} ({@code --user-data-dir=}, in any
     * case, quoted or not, with or without a trailing separator, as Windows treats paths).
     */
    static boolean browserOnProfile(String commandLine, Path profile) {
        List<String> arguments = arguments(commandLine);
        String wanted = trimmed(profile.toString());
        boolean ours = false;
        for (String argument : arguments) {
            if (argument.startsWith("--type=")) {
                return false;
            }
            if (argument.startsWith("--user-data-dir=")
                    && trimmed(argument.substring("--user-data-dir=".length())).equalsIgnoreCase(wanted)) {
                ours = true;
            }
        }
        return ours;
    }

    /** A command line split as Windows splits it: on spaces outside quotes, the quotes dropped. */
    static List<String> arguments(String commandLine) {
        List<String> arguments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        for (char c : commandLine.toCharArray()) {
            if (c == '"') {
                quoted = !quoted;
                any = true;
            } else if (Character.isWhitespace(c) && !quoted) {
                if (any) {
                    arguments.add(current.toString());
                    current.setLength(0);
                    any = false;
                }
            } else {
                current.append(c);
                any = true;
            }
        }
        if (any) {
            arguments.add(current.toString());
        }
        return arguments;
    }

    private static String trimmed(String path) {
        String p = path.strip();
        while (p.length() > 3 && (p.endsWith("\\") || p.endsWith("/"))) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    // ---- JNA mappings jna-platform does not have, loaded on first use

    /** The libraries, loaded when first used (never on another OS, unless something calls them there). */
    private static final class Libraries {
        static final Shell32Ex SHELL32 = Native.load("shell32", Shell32Ex.class, W32APIOptions.DEFAULT_OPTIONS);
        static final Ole32Ex OLE32 = Native.load("ole32", Ole32Ex.class, W32APIOptions.DEFAULT_OPTIONS);
        static final NtDll NTDLL = Native.load("ntdll", NtDll.class, W32APIOptions.DEFAULT_OPTIONS);
    }

    interface Shell32Ex extends StdCallLibrary {
        HRESULT SHGetPropertyStoreForWindow(HWND window, Guid.GUID riid, PointerByReference store);
    }

    interface Ole32Ex extends StdCallLibrary {
        HRESULT PropVariantClear(Pointer variant);
    }

    interface NtDll extends StdCallLibrary {
        int NtQueryInformationProcess(HANDLE process, int infoClass, Pointer info, int length, IntByReference returned);
    }

    /** {@code PROPERTYKEY}. */
    @Structure.FieldOrder({"fmtid", "pid"})
    public static class PropertyKey extends Structure {
        public Guid.GUID fmtid;
        public int pid;
    }

    /**
     * {@code PROPVARIANT} for a wide string: the type, three reserved words, and the union (two pointers wide, 16 bytes on
     * 64-bit Windows, so the structure is the 24 bytes the shell writes into).
     */
    @Structure.FieldOrder({"vt", "reserved1", "reserved2", "reserved3", "value", "rest"})
    public static class PropVariant extends Structure {
        public short vt;
        public short reserved1;
        public short reserved2;
        public short reserved3;
        public Pointer value;
        public Pointer rest;
    }

    /** {@code IPropertyStore}: IUnknown's three methods, then GetCount, GetAt, GetValue, SetValue, Commit. */
    static final class PropertyStore extends Unknown {

        PropertyStore(Pointer pointer) {
            super(pointer);
        }

        HRESULT getValue(PropertyKey key, PropVariant value) {
            return (HRESULT) _invokeNativeObject(5, new Object[] {getPointer(), key, value}, HRESULT.class);
        }

        HRESULT setValue(PropertyKey key, PropVariant value) {
            return (HRESULT) _invokeNativeObject(6, new Object[] {getPointer(), key, value}, HRESULT.class);
        }

        HRESULT commit() {
            return (HRESULT) _invokeNativeObject(7, new Object[] {getPointer()}, HRESULT.class);
        }
    }
}
