package com.tianqianguai.gramsieve.config;

import android.content.Context;

import com.tianqianguai.gramsieve.core.ModuleConflictDetector;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/** Detects module code loaded into the current Telegram process without root or app launches. */
public final class RuntimeModuleProbe {
    public static final String SOURCE_PACKAGE_PROBE = "package-probe";
    public static final String SOURCE_RUNTIME_DEX = "runtime-dex";
    public static final String SOURCE_LSPOSED_READONLY = "lsposed-readonly";
    public static final String SOURCE_NONE = "none";

    private static final int BUFFER_SIZE = 64 * 1024;
    private static final long MAX_MAPPING_BYTES = 16L * 1024L * 1024L;
    private static final long MAX_TOTAL_BYTES = 64L * 1024L * 1024L;

    private RuntimeModuleProbe() {
    }

    public static final class Result {
        public final Set<ModuleConflictDetector.KnownModule> modules;
        public final String source;

        private Result(Set<ModuleConflictDetector.KnownModule> modules, String source) {
            this.modules = modules.isEmpty()
                    ? Collections.emptySet()
                    : Collections.unmodifiableSet(EnumSet.copyOf(modules));
            this.source = source;
        }
    }

    public static Result scan(Context context, XposedModule module) {
        EnumSet<ModuleConflictDetector.KnownModule> modules = EnumSet.noneOf(
                ModuleConflictDetector.KnownModule.class
        );
        modules.addAll(InstalledModuleScanner.scan(context));
        if (!modules.isEmpty()) {
            return new Result(modules, SOURCE_PACKAGE_PROBE);
        }
        modules.addAll(scanLoadedDex(module));
        if (!modules.isEmpty()) {
            return new Result(modules, SOURCE_RUNTIME_DEX);
        }
        modules.addAll(LSPosedModuleStateReader.scanRegisteredWithoutRoot());
        return new Result(
                modules,
                modules.isEmpty() ? SOURCE_NONE : SOURCE_LSPOSED_READONLY
        );
    }

    private static Set<ModuleConflictDetector.KnownModule> scanLoadedDex(XposedModule module) {
        List<MemoryRange> ranges = readDexRanges();
        if (ranges.isEmpty()) {
            return Collections.emptySet();
        }
        Map<ModuleConflictDetector.KnownModule, List<byte[]>> markers = buildMarkers();
        EnumSet<ModuleConflictDetector.KnownModule> found = EnumSet.noneOf(
                ModuleConflictDetector.KnownModule.class
        );
        MemoryReader memoryReader = createMemoryReader(module);
        if (memoryReader == null) {
            return Collections.emptySet();
        }
        long scanned = 0L;
        try {
            for (MemoryRange range : ranges) {
                long length = range.end - range.start;
                if (length <= 0L || length > MAX_MAPPING_BYTES || scanned + length > MAX_TOTAL_BYTES) {
                    continue;
                }
                scanned += length;
                scanRange(memoryReader, range, markers, found);
            }
        } catch (RuntimeException ignored) {
            return Collections.emptySet();
        }
        return found;
    }

    private static MemoryReader createMemoryReader(XposedModule module) {
        if (module == null) {
            return null;
        }
        try {
            Class<?> memoryClass = Class.forName("libcore.io.Memory");
            Method peekByteArray = memoryClass.getDeclaredMethod(
                    "peekByteArray",
                    long.class,
                    byte[].class,
                    int.class,
                    int.class
            );
            XposedInterface.Invoker<?, Method> invoker = module.getInvoker(peekByteArray);
            return (address, buffer, offset, length) ->
                    invoker.invoke(null, address, buffer, offset, length);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void scanRange(
            MemoryReader memory,
            MemoryRange range,
            Map<ModuleConflictDetector.KnownModule, List<byte[]>> markers,
            EnumSet<ModuleConflictDetector.KnownModule> found
    ) {
        int overlap = 128;
        byte[] buffer = new byte[BUFFER_SIZE + overlap];
        int carry = 0;
        long remaining = range.end - range.start;
        try {
            long address = range.start;
            while (remaining > 0L && found.size() < ModuleConflictDetector.KnownModule.values().length) {
                int requested = (int) Math.min(BUFFER_SIZE, remaining);
                memory.read(address, buffer, carry, requested);
                int read = requested;
                int length = carry + read;
                for (Map.Entry<ModuleConflictDetector.KnownModule, List<byte[]>> entry : markers.entrySet()) {
                    if (found.contains(entry.getKey())) {
                        continue;
                    }
                    for (byte[] marker : entry.getValue()) {
                        if (contains(buffer, length, marker)) {
                            found.add(entry.getKey());
                            break;
                        }
                    }
                }
                carry = Math.min(overlap, length);
                System.arraycopy(buffer, length - carry, buffer, 0, carry);
                remaining -= read;
                address += read;
            }
        } catch (Throwable ignored) {
            // A mapping can disappear while Telegram is running; continue with the next one.
        }
    }

    private static Map<ModuleConflictDetector.KnownModule, List<byte[]>> buildMarkers() {
        Map<ModuleConflictDetector.KnownModule, List<byte[]>> markers = new EnumMap<>(
                ModuleConflictDetector.KnownModule.class
        );
        for (ModuleConflictDetector.KnownModule module : ModuleConflictDetector.KnownModule.values()) {
            List<byte[]> values = new ArrayList<>();
            for (String packageName : module.packageNames) {
                // Build class-descriptor prefixes at runtime so these markers are not embedded in
                // GramSieve's own DEX and cannot make every module look loaded.
                values.add(("L" + packageName.replace('.', '/') + "/")
                        .getBytes(StandardCharsets.ISO_8859_1));
            }
            markers.put(module, values);
        }
        return markers;
    }

    private static List<MemoryRange> readDexRanges() {
        List<MemoryRange> ranges = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/maps"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.contains("[anon:dalvik-DEX data]")) {
                    continue;
                }
                int separator = line.indexOf(' ');
                String addressRange = separator < 0 ? line : line.substring(0, separator);
                int dash = addressRange.indexOf('-');
                if (dash <= 0) {
                    continue;
                }
                try {
                    long start = Long.parseLong(addressRange.substring(0, dash), 16);
                    long end = Long.parseLong(addressRange.substring(dash + 1), 16);
                    ranges.add(new MemoryRange(start, end));
                } catch (NumberFormatException ignored) {
                    // Ignore a malformed or concurrently changing maps entry.
                }
            }
        } catch (IOException | SecurityException ignored) {
            return Collections.emptyList();
        }
        return ranges;
    }

    private static boolean contains(byte[] haystack, int length, byte[] needle) {
        if (needle.length == 0 || length < needle.length) {
            return false;
        }
        int limit = length - needle.length;
        for (int i = 0; i <= limit; i++) {
            int j = 0;
            while (j < needle.length && haystack[i + j] == needle[j]) {
                j++;
            }
            if (j == needle.length) {
                return true;
            }
        }
        return false;
    }

    private static final class MemoryRange {
        final long start;
        final long end;

        MemoryRange(long start, long end) {
            this.start = start;
            this.end = end;
        }
    }

    private interface MemoryReader {
        void read(long address, byte[] buffer, int offset, int length) throws Throwable;
    }
}
