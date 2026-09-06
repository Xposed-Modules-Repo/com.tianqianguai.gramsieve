package com.tianqianguai.gramsieve.module;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class ReflectTest {
    @Test
    public void invokeIfExists_findsPrivateSuperclassMethod() {
        Object result = Reflect.invokeIfExists(new ChildTarget(), "isSettings", new Class<?>[0]);
        assertEquals(Boolean.TRUE, result);
    }

    @Test
    public void invokeIfExists_matchesCompatibleMethodWhenSignatureHintDiffers() {
        Object result = Reflect.invokeIfExists(
                new ChildTarget(),
                "addSubItem",
                new Class<?>[]{int.class, int.class, String.class},
                7,
                9,
                "GramSieve"
        );
        assertEquals("7:9:GramSieve", result);
    }

    @Test
    public void invokeIfExists_returnsNullWhenMethodMissing() {
        Object result = Reflect.invokeIfExists(new ChildTarget(), "missing", new Class<?>[0]);
        assertNull(result);
    }

    @Test
    public void fieldCacheReadsFreshValuesAndReusesPositiveMetadata() throws Exception {
        FieldTarget target = new FieldTarget();

        assertEquals("before", Reflect.field(target, "mutableValue"));
        Object cachedField = fieldCacheFor(FieldTarget.class).get("mutableValue");
        assertNotNull(cachedField);

        Reflect.setField(target, "mutableValue", "after");

        assertEquals("after", Reflect.field(target, "mutableValue"));
        assertSame(cachedField, fieldCacheFor(FieldTarget.class).get("mutableValue"));
    }

    @Test
    public void missingFieldCacheReusesNegativeMetadata() throws Exception {
        FieldTarget target = new FieldTarget();
        String missingName = "missingFieldForCacheReuse";

        assertNull(Reflect.field(target, missingName));
        Object cachedMissing = fieldCacheFor(FieldTarget.class).get(missingName);
        assertNotNull(cachedMissing);

        Reflect.setField(target, missingName, "ignored");
        assertNull(Reflect.staticField(FieldTarget.class, missingName));
        assertSame(cachedMissing, fieldCacheFor(FieldTarget.class).get(missingName));
    }

    @Test
    public void inheritedPrivateFieldsRemainReadableAndWritable() {
        ChildTarget target = new ChildTarget();

        assertEquals("parent", Reflect.field(target, "inheritedValue"));
        Reflect.setField(target, "inheritedValue", "updated");
        assertEquals("updated", Reflect.field(target, "inheritedValue"));

        Reflect.setField(target, "inheritedStaticValue", "static-updated");
        assertEquals("static-updated", Reflect.staticField(ChildTarget.class, "inheritedStaticValue"));
    }

    @Test
    public void cachesSeparateDifferentClassIdentities() throws Exception {
        FirstClassTarget first = new FirstClassTarget();
        SecondClassTarget second = new SecondClassTarget();

        assertEquals("first", Reflect.field(first, "sharedValue"));
        assertEquals("second", Reflect.field(second, "sharedValue"));
        assertNotSame(fieldCacheFor(FirstClassTarget.class), fieldCacheFor(SecondClassTarget.class));

        assertEquals("first:x", Reflect.invokeIfExists(
                first, "sharedMethod", new Class<?>[]{Object.class}, "x"));
        assertEquals("second:x", Reflect.invokeIfExists(
                second, "sharedMethod", new Class<?>[]{Object.class}, "x"));
        assertNotSame(methodCacheFor(FirstClassTarget.class), methodCacheFor(SecondClassTarget.class));
    }

    @Test
    public void compatibleOverloadsPreserveNullBoxingAndRuntimeTypeSelection() {
        OverloadedTarget target = new OverloadedTarget();

        assertEquals(
                "chars:null",
                Reflect.invokeIfExists(target, "classify", new Class<?>[]{Object.class}, (Object) null)
        );
        assertEquals(
                "chars:text",
                Reflect.invokeIfExists(target, "classify", new Class<?>[]{Object.class}, "text")
        );
        assertEquals(
                "int:7",
                Reflect.invokeIfExists(target, "classify", new Class<?>[]{Object.class}, 7)
        );
    }

    @Test
    public void exactSignatureWinsBeforeCompatibleFallback() {
        OverloadedTarget target = new OverloadedTarget();

        assertEquals(
                "char-sequence",
                Reflect.invokeIfExists(target, "priority", new Class<?>[]{CharSequence.class}, "text")
        );
        assertEquals(
                "string",
                Reflect.invokeIfExists(target, "priority", new Class<?>[]{Object.class}, "text")
        );
    }

    @Test
    public void methodCacheSeparatesArgumentShapesAndReusesMissingEntries() throws Exception {
        OverloadedTarget target = new OverloadedTarget();

        assertEquals("int:1", Reflect.invokeIfExists(
                target, "cacheShape", new Class<?>[]{Object.class}, 1));
        Map<?, ?> cache = methodCacheFor(OverloadedTarget.class);
        int afterPositive = cache.size();

        assertEquals("int:2", Reflect.invokeIfExists(
                target, "cacheShape", new Class<?>[]{Object.class}, 2));
        assertEquals(afterPositive, cache.size());

        String missingName = "missingMethodForCacheReuse";
        assertNull(Reflect.invokeIfExists(target, missingName, new Class<?>[]{Object.class}, "value"));
        int afterMissing = cache.size();
        assertTrue(afterMissing > afterPositive);
        assertNull(Reflect.invokeIfExists(target, missingName, new Class<?>[]{Object.class}, "value"));
        assertEquals(afterMissing, cache.size());

        assertEquals("chars:text", Reflect.invokeIfExists(
                target, "cacheShape", new Class<?>[]{Object.class}, "text"));
        assertTrue(cache.size() > afterPositive);
    }

    @Test
    public void invokeStaticAndStaticFieldUseTheirOwnCachedMetadata() throws Exception {
        assertEquals("static:3", Reflect.invokeStatic(
                StaticTarget.class, "staticClassify", new Class<?>[]{Object.class}, 3));
        assertEquals("initial", Reflect.staticField(StaticTarget.class, "mutableValue"));

        Map<?, ?> methodCache = methodCacheFor(StaticTarget.class);
        Object cachedMethod = methodCache.values().iterator().next();
        assertNotNull(cachedMethod);
        Object cachedField = fieldCacheFor(StaticTarget.class).get("mutableValue");
        assertNotNull(cachedField);

        Reflect.setField(new StaticTarget(), "mutableValue", "changed");
        assertEquals("changed", Reflect.staticField(StaticTarget.class, "mutableValue"));
        assertSame(cachedField, fieldCacheFor(StaticTarget.class).get("mutableValue"));
        assertSame(cachedMethod, methodCache.values().iterator().next());
    }

    @Test
    public void strictMethodKeepsDeclaredOnlyNoSuchMethodSemanticsAndCachesResults() throws Exception {
        try {
            Reflect.method(ChildTarget.class, "isSettings");
            fail("inherited methods must remain unavailable through Reflect.method");
        } catch (NoSuchMethodException expected) {
            // Reflect.method intentionally does not use the inherited lookup path.
        }

        Method first = Reflect.method(ChildTarget.class, "childOnly", String.class);
        Method second = Reflect.method(ChildTarget.class, "childOnly", String.class);
        assertSame(first, second);
        assertNotNull(strictMethodCacheFor(ChildTarget.class).get(
                findKey(strictMethodCacheFor(ChildTarget.class), "childOnly")));
    }

    @Test
    public void concurrentPositiveAndNegativeLookupsRemainStable() throws Exception {
        final OverloadedTarget target = new OverloadedTarget();
        ExecutorService executor = Executors.newFixedThreadPool(6);
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            Callable<Boolean> task = () -> {
                for (int i = 0; i < 100; i++) {
                    if (!"int:9".equals(Reflect.invokeIfExists(
                            target, "classify", new Class<?>[]{Object.class}, 9))) {
                        return false;
                    }
                    if (Reflect.invokeIfExists(
                            target,
                            "missingConcurrentMethod",
                            new Class<?>[]{Object.class},
                            "value"
                    ) != null) {
                        return false;
                    }
                }
                return true;
            };
            for (int i = 0; i < 6; i++) {
                futures.add(executor.submit(task));
            }
            for (Future<Boolean> future : futures) {
                assertTrue(future.get());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static Map<?, ?> fieldCacheFor(Class<?> type) throws Exception {
        java.lang.reflect.Field cacheField = Reflect.class.getDeclaredField("DECLARED_FIELD_CACHE");
        cacheField.setAccessible(true);
        Map<?, ?> cache = (Map<?, ?>) cacheField.get(null);
        return (Map<?, ?>) cache.get(type);
    }

    private static Map<?, ?> methodCacheFor(Class<?> type) throws Exception {
        java.lang.reflect.Field cacheField = Reflect.class.getDeclaredField("METHOD_CACHE");
        cacheField.setAccessible(true);
        Map<?, ?> cache = (Map<?, ?>) cacheField.get(null);
        return (Map<?, ?>) cache.get(type);
    }

    private static Map<?, ?> strictMethodCacheFor(Class<?> type) throws Exception {
        java.lang.reflect.Field cacheField = Reflect.class.getDeclaredField("STRICT_METHOD_CACHE");
        cacheField.setAccessible(true);
        Map<?, ?> cache = (Map<?, ?>) cacheField.get(null);
        return (Map<?, ?>) cache.get(type);
    }

    private static Object findKey(Map<?, ?> cache, String name) throws Exception {
        for (Object key : cache.keySet()) {
            java.lang.reflect.Field keyName = key.getClass().getDeclaredField("name");
            keyName.setAccessible(true);
            if (name.equals(keyName.get(key))) {
                return key;
            }
        }
        return null;
    }

    private static class ParentTarget {
        private String inheritedValue = "parent";
        private static String inheritedStaticValue = "static-parent";

        private boolean isSettings() {
            return true;
        }

        private String addSubItem(int id, int icon, CharSequence label) {
            return id + ":" + icon + ":" + label;
        }
    }

    private static final class ChildTarget extends ParentTarget {
        private String childOnly(String value) {
            return value;
        }
    }

    private static final class FieldTarget {
        private String mutableValue = "before";
    }

    private static final class FirstClassTarget {
        private String sharedValue = "first";

        private String sharedMethod(CharSequence value) {
            return "first:" + value;
        }
    }

    private static final class SecondClassTarget {
        private String sharedValue = "second";

        private String sharedMethod(CharSequence value) {
            return "second:" + value;
        }
    }

    private static final class OverloadedTarget {
        private String classify(CharSequence value) {
            return "chars:" + value;
        }

        private String classify(int value) {
            return "int:" + value;
        }

        private String cacheShape(CharSequence value) {
            return "chars:" + value;
        }

        private String cacheShape(int value) {
            return "int:" + value;
        }

        private String priority(CharSequence value) {
            return "char-sequence";
        }

        private String priority(String value) {
            return "string";
        }
    }

    private static final class StaticTarget {
        private static String mutableValue = "initial";

        private static String staticClassify(int value) {
            return "static:" + value;
        }
    }
}
