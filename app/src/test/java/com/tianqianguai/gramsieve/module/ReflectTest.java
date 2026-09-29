package com.tianqianguai.gramsieve.module;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

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

    private static class ParentTarget {
        private boolean isSettings() {
            return true;
        }

        private String addSubItem(int id, int icon, CharSequence label) {
            return id + ":" + icon + ":" + label;
        }
    }

    private static final class ChildTarget extends ParentTarget {
    }
}
