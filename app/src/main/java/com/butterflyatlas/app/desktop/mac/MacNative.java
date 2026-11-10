package com.butterflyatlas.app.desktop.mac;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.DoubleByReference;
import com.sun.jna.ptr.LongByReference;

import java.util.ArrayList;
import java.util.List;

/**
 * The macOS calls behind the app's icon, through JNA (spec §4.1, contract §4). Nothing here loads JNA until a method runs,
 * so making one costs nothing. The calls run only on macOS; elsewhere they throw.
 *
 * <ul>
 *   <li>{@link #activate}: {@code [NSRunningApplication runningApplicationWithProcessIdentifier:]} then
 *   {@code activateWithOptions:} with all windows. On macOS 14 and later activation is cooperative: when this app is
 *   active (the user just clicked its tile or switched to it) it first yields activation to the browser
 *   ({@code [NSApp yieldActivationToApplication:]}), which lets the browser come forward.</li>
 *   <li>{@link #terminate}: {@code [NSRunningApplication terminate]}, the same as Quit from the browser's own menu; never
 *   {@code forceTerminate}.</li>
 *   <li>{@link #windows}: {@code CGWindowListCopyWindowInfo}, once for every window and once for those on screen, counting
 *   the process's ordinary ones (layer 0). The owner and the layer need no Screen Recording permission.</li>
 * </ul>
 * Each call runs inside an autorelease pool of its own, since it runs on a thread AppKit did not make. Every call is
 * made with a fixed number of arguments ({@code objc_msgSend} is not variadic on Apple Silicon).
 */
public final class MacNative implements MacApps {

    /** {@code NSApplicationActivateAllWindows}. */
    static final long ACTIVATE_ALL_WINDOWS = 1L;
    /** {@code NSApplicationActivateIgnoringOtherApps}: needed before macOS 14, ignored from it on. */
    static final long ACTIVATE_IGNORING_OTHER_APPS = 2L;
    /** {@code kCGWindowListOptionAll}. */
    static final int LIST_ALL = 0;
    /** {@code kCGWindowListOptionOnScreenOnly}. */
    static final int LIST_ON_SCREEN = 1;
    /** {@code kCGNullWindowID}. */
    static final int NULL_WINDOW = 0;
    /** {@code kCFNumberSInt64Type}. */
    static final long CF_SINT64 = 4L;
    /** {@code kCFNumberFloat64Type} (a window's bounds are CGFloat numbers). */
    static final long CF_FLOAT64 = 6L;
    /**
     * Smaller than this either way is not a window of ours: Chrome keeps hidden helper windows (strips 33 px tall, never on
     * screen) in the list, which would count as minimised windows that never close. Its app windows are never this small.
     */
    static final double MIN_SIDE = 100;
    /** {@code kCFStringEncodingUTF8}. */
    static final int CF_UTF8 = 0x08000100;

    /** The Objective-C runtime, with each {@code objc_msgSend} shape this class uses. */
    interface ObjC extends Library {
        Pointer objc_getClass(String name);

        Pointer sel_registerName(String name);

        Pointer objc_msgSend(Pointer receiver, Pointer selector);

        Pointer objc_msgSend(Pointer receiver, Pointer selector, int value);

        Pointer objc_msgSend(Pointer receiver, Pointer selector, long value);

        Pointer objc_msgSend(Pointer receiver, Pointer selector, Pointer value);

    }

    /** Core Foundation, for reading the window list. */
    interface CoreFoundation extends Library {
        long CFArrayGetCount(Pointer array);

        Pointer CFArrayGetValueAtIndex(Pointer array, long index);

        Pointer CFDictionaryGetValue(Pointer dictionary, Pointer key);

        byte CFNumberGetValue(Pointer number, long type, LongByReference value);

        byte CFNumberGetValue(Pointer number, long type, DoubleByReference value);

        Pointer CFStringCreateWithCString(Pointer allocator, String text, int encoding);

        void CFRelease(Pointer object);
    }

    /** Core Graphics' window list. */
    interface CoreGraphics extends Library {
        Pointer CGWindowListCopyWindowInfo(int option, int relativeToWindow);
    }

    /** The libraries, loaded on first use. */
    private static final class Libraries {
        static final NativeLibrary APP_KIT = NativeLibrary.getInstance("AppKit");
        static final ObjC OBJC = Native.load("objc", ObjC.class);
        static final CoreFoundation CF = Native.load("CoreFoundation", CoreFoundation.class);
        static final CoreGraphics CG = Native.load("CoreGraphics", CoreGraphics.class);
    }

    @Override
    public boolean activate(long pid) {
        return inPool(objc -> {
            Pointer app = runningApp(objc, pid);
            Pointer nsApp = Libraries.APP_KIT.getGlobalVariableAddress("NSApp").getPointer(0);
            Pointer yield = objc.sel_registerName("yieldActivationToApplication:");
            if (nsApp != null && bool(objc.objc_msgSend(nsApp, objc.sel_registerName("respondsToSelector:"), yield))) {
                objc.objc_msgSend(nsApp, yield, app);
            }
            return bool(objc.objc_msgSend(app, objc.sel_registerName("activateWithOptions:"),
                    ACTIVATE_ALL_WINDOWS | ACTIVATE_IGNORING_OTHER_APPS));
        });
    }

    @Override
    public boolean terminate(long pid) {
        return inPool(objc -> bool(objc.objc_msgSend(runningApp(objc, pid), objc.sel_registerName("terminate"))));
    }

    @Override
    public Windows windows(long pid) {
        Libraries.APP_KIT.getName(); // AppKit's classes are there for the pool
        return inPool(objc -> new Windows(ids(pid, LIST_ALL).size(), ids(pid, LIST_ON_SCREEN).size()));
    }

    /**
     * The window numbers of what {@link #windows} counts in every window, for a real-window check to tell one window from
     * another: Chrome 156 keeps a closed window in the list, the same in every detail as a minimised one.
     */
    List<Long> windowIds(long pid) {
        Libraries.APP_KIT.getName();
        return inPool(objc -> ids(pid, LIST_ALL));
    }

    /**
     * The process's ordinary windows (layer 0, at least {@link #MIN_SIDE} each way) in the window list made with
     * {@code option}.
     */
    private static List<Long> ids(long pid, int option) {
        CoreFoundation cf = Libraries.CF;
        Pointer list = Libraries.CG.CGWindowListCopyWindowInfo(option, NULL_WINDOW);
        if (list == null) {
            throw new IllegalStateException("The window list could not be read");
        }
        Pointer ownerKey = cf.CFStringCreateWithCString(null, "kCGWindowOwnerPID", CF_UTF8);
        Pointer layerKey = cf.CFStringCreateWithCString(null, "kCGWindowLayer", CF_UTF8);
        Pointer boundsKey = cf.CFStringCreateWithCString(null, "kCGWindowBounds", CF_UTF8);
        Pointer widthKey = cf.CFStringCreateWithCString(null, "Width", CF_UTF8);
        Pointer heightKey = cf.CFStringCreateWithCString(null, "Height", CF_UTF8);
        Pointer numberKey = cf.CFStringCreateWithCString(null, "kCGWindowNumber", CF_UTF8);
        try {
            List<Long> found = new ArrayList<>();
            long size = cf.CFArrayGetCount(list);
            for (long i = 0; i < size; i++) {
                Pointer window = cf.CFArrayGetValueAtIndex(list, i);
                if (number(cf, cf.CFDictionaryGetValue(window, ownerKey)) == pid
                        && number(cf, cf.CFDictionaryGetValue(window, layerKey)) == 0) {
                    Pointer bounds = cf.CFDictionaryGetValue(window, boundsKey);
                    if (bounds != null && side(cf, cf.CFDictionaryGetValue(bounds, widthKey)) >= MIN_SIDE
                            && side(cf, cf.CFDictionaryGetValue(bounds, heightKey)) >= MIN_SIDE) {
                        found.add(number(cf, cf.CFDictionaryGetValue(window, numberKey)));
                    }
                }
            }
            return found;
        } finally {
            cf.CFRelease(list);
            if (ownerKey != null) {
                cf.CFRelease(ownerKey);
            }
            for (Pointer key : new Pointer[] {layerKey, boundsKey, widthKey, heightKey, numberKey}) {
                if (key != null) {
                    cf.CFRelease(key);
                }
            }
        }
    }

    /**
     * Every window of {@code pid} in both lists, unfiltered, one line each ({@code all|on-screen #id layer=L WxH at X,Y alpha= store= sharing= memory=}): for a
     * real-window check to say what it saw when a count is not what it expected. Needs no Screen Recording permission.
     */
    List<String> describe(long pid) {
        return inPool(objc -> {
            List<String> lines = new ArrayList<>();
            for (int option : new int[] {LIST_ALL, LIST_ON_SCREEN}) {
                CoreFoundation cf = Libraries.CF;
                Pointer list = Libraries.CG.CGWindowListCopyWindowInfo(option, NULL_WINDOW);
                if (list == null) {
                    lines.add("no window list");
                    continue;
                }
                Pointer[] keys = {cf.CFStringCreateWithCString(null, "kCGWindowOwnerPID", CF_UTF8),
                        cf.CFStringCreateWithCString(null, "kCGWindowLayer", CF_UTF8),
                        cf.CFStringCreateWithCString(null, "kCGWindowBounds", CF_UTF8),
                        cf.CFStringCreateWithCString(null, "Width", CF_UTF8),
                        cf.CFStringCreateWithCString(null, "Height", CF_UTF8),
                        cf.CFStringCreateWithCString(null, "kCGWindowNumber", CF_UTF8),
                        cf.CFStringCreateWithCString(null, "X", CF_UTF8),
                        cf.CFStringCreateWithCString(null, "Y", CF_UTF8),
                        cf.CFStringCreateWithCString(null, "kCGWindowAlpha", CF_UTF8),
                        cf.CFStringCreateWithCString(null, "kCGWindowStoreType", CF_UTF8),
                        cf.CFStringCreateWithCString(null, "kCGWindowSharingState", CF_UTF8),
                        cf.CFStringCreateWithCString(null, "kCGWindowMemoryUsage", CF_UTF8)};
                try {
                    for (long i = 0, size = cf.CFArrayGetCount(list); i < size; i++) {
                        Pointer window = cf.CFArrayGetValueAtIndex(list, i);
                        if (number(cf, cf.CFDictionaryGetValue(window, keys[0])) != pid) {
                            continue;
                        }
                        Pointer bounds = cf.CFDictionaryGetValue(window, keys[2]);
                        lines.add((option == LIST_ALL ? "all" : "on-screen") + " #"
                                + number(cf, cf.CFDictionaryGetValue(window, keys[5])) + " layer="
                                + number(cf, cf.CFDictionaryGetValue(window, keys[1])) + " "
                                + (bounds == null ? "?" : Math.round(side(cf, cf.CFDictionaryGetValue(bounds, keys[3])))
                                + "x" + Math.round(side(cf, cf.CFDictionaryGetValue(bounds, keys[4])))
                                + " at " + Math.round(side(cf, cf.CFDictionaryGetValue(bounds, keys[6]))) + ","
                                + Math.round(side(cf, cf.CFDictionaryGetValue(bounds, keys[7]))))
                                + " alpha=" + side(cf, cf.CFDictionaryGetValue(window, keys[8]))
                                + " store=" + number(cf, cf.CFDictionaryGetValue(window, keys[9]))
                                + " sharing=" + number(cf, cf.CFDictionaryGetValue(window, keys[10]))
                                + " memory=" + number(cf, cf.CFDictionaryGetValue(window, keys[11])));
                    }
                } finally {
                    cf.CFRelease(list);
                    for (Pointer key : keys) {
                        if (key != null) {
                            cf.CFRelease(key);
                        }
                    }
                }
            }
            return lines;
        });
    }

    private static long number(CoreFoundation cf, Pointer number) {
        if (number == null) {
            return Long.MIN_VALUE;
        }
        LongByReference value = new LongByReference();
        return cf.CFNumberGetValue(number, CF_SINT64, value) != 0 ? value.getValue() : Long.MIN_VALUE;
    }

    /** A window side from its bounds (a CGFloat number); 0 when it cannot be read. */
    private static double side(CoreFoundation cf, Pointer number) {
        if (number == null) {
            return 0;
        }
        DoubleByReference value = new DoubleByReference();
        return cf.CFNumberGetValue(number, CF_FLOAT64, value) != 0 ? value.getValue() : 0;
    }

    /** The {@code NSRunningApplication} of the process; throws when there is none (it is not running, or not an app). */
    private static Pointer runningApp(ObjC objc, long pid) {
        Pointer app = objc.objc_msgSend(objc.objc_getClass("NSRunningApplication"),
                objc.sel_registerName("runningApplicationWithProcessIdentifier:"), processId(pid));
        if (app == null) {
            throw new IllegalStateException("No running app has the process ID " + pid);
        }
        return app;
    }

    private static int processId(long pid) {
        if (pid <= 0 || pid > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Not a process ID: " + pid);
        }
        return (int) pid;
    }

    /** An Objective-C {@code BOOL} returned in a register: only its low byte counts. */
    private static boolean bool(Pointer value) {
        return value != null && (Pointer.nativeValue(value) & 0xFF) != 0;
    }

    private interface Call<T> {
        T run(ObjC objc);
    }

    /** Runs {@code call} inside an autorelease pool of its own. */
    private static <T> T inPool(Call<T> call) {
        Libraries.APP_KIT.getName(); // loads AppKit, whose classes the calls use
        ObjC objc = Libraries.OBJC;
        Pointer pool = objc.objc_msgSend(objc.objc_msgSend(objc.objc_getClass("NSAutoreleasePool"),
                objc.sel_registerName("alloc")), objc.sel_registerName("init"));
        try {
            return call.run(objc);
        } finally {
            if (pool != null) {
                objc.objc_msgSend(pool, objc.sel_registerName("drain"));
            }
        }
    }
}
