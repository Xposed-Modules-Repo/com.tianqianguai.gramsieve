package com.tianqianguai.gramsieve.module;

import android.view.View;
import com.tianqianguai.gramsieve.core.EnhancementConfig;
import org.junit.Test;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public class FeatureProbeTest {
    @Test
    public void distinguishesPresentButUnhookedFromRegisteredAndObserved() throws Exception {
        Method method = FakeTarget.class.getDeclaredMethod("canForwardMessage");
        FeatureProbe.Spec spec = new FeatureProbe.Spec(FakeTarget.class.getName(), "canForwardMessage", "boolean", "any");
        Map<String, Object> missing = FeatureProbe.inspectTargets(FakeTarget.class, spec,
                EnhancementConfig.Feature.ALLOW_FORWARD, Collections.emptySet(), Collections.emptyMap(), Collections.emptyMap());
        assertEquals("not_registered", missing.get("status"));
        Map<String, Object> hooked = FeatureProbe.inspectTargets(FakeTarget.class, spec,
                EnhancementConfig.Feature.ALLOW_FORWARD, Collections.singleton(method), Collections.singletonMap(method, 3L), Collections.singletonMap(method, "hookBooleanMethods"));
        assertEquals("registered_behavior_unverified", hooked.get("status"));
        assertEquals(1, hooked.get("registeredMethodCount"));
        Map<?, ?> entry = (Map<?, ?>) ((List<?>) hooked.get("methods")).get(0);
        assertEquals(3L, entry.get("invocations"));
        assertEquals(0, FakeTarget.invoked);
        Map<String, Object> wrongHandler = FeatureProbe.inspectTargets(FakeTarget.class, spec,
                EnhancementConfig.Feature.ALLOW_FORWARD, Collections.singleton(method), Collections.emptyMap(),
                Collections.singletonMap(method, "unrelatedHook"));
        assertEquals("registered_for_other_handler", wrongHandler.get("status"));
    }

    @Test
    public void missingMethodIsNotReportedAsInstalled() {
        FeatureProbe.Spec spec = new FeatureProbe.Spec(FakeTarget.class.getName(), "canSaveMedia", "boolean", "any");
        Map<String, Object> result = FeatureProbe.inspectTargets(FakeTarget.class, spec,
                EnhancementConfig.Feature.ALLOW_COPY, Collections.emptySet(), Collections.emptyMap(), Collections.emptyMap());
        assertEquals("method_missing", result.get("status"));
    }

    @Test
    public void primitiveRowAndViewArrayAreNotSingleViews() {
        assertFalse(FeatureProbe.compatible(int.class, "view"));
        assertFalse(FeatureProbe.compatible(View[].class, "view"));
        assertTrue(FeatureProbe.compatible(View.class, "view"));
        FeatureProbe.Spec spec = new FeatureProbe.Spec(FakeTarget.class.getName(), "", null, "view", "phoneRow", "nameTextView");
        Map<String, Object> result = FeatureProbe.inspectTargets(FakeTarget.class, spec,
                EnhancementConfig.Feature.HIDE_PHONE_NUMBER, Collections.emptySet(), Collections.emptyMap(), Collections.emptyMap());
        assertEquals("field_missing_or_incompatible", result.get("status"));
    }

    @Test
    public void absentOwnerNullFieldAndFalseValueRemainDistinct() {
        FakeTarget owner = new FakeTarget();
        Map<String, Object> result = FeatureUiProbe.fields(owner, "phoneRow", "enabled", "nameTextView", "unknown");
        List<?> fields = (List<?>) result.get("fields");
        assertEquals(0, ((Map<?, ?>) fields.get(0)).get("value"));
        assertEquals(false, ((Map<?, ?>) fields.get(1)).get("value"));
        assertTrue(((Map<?, ?>) fields.get(2)).containsKey("value"));
        assertNull(((Map<?, ?>) fields.get(2)).get("value"));
        assertEquals(false, ((Map<?, ?>) fields.get(3)).get("present"));
        assertEquals(false, FeatureUiProbe.fields(null, "enabled").get("available"));
    }

    @Test
    public void objectArraysAreNotRecursivelyTraversed() {
        Object[] cycle = new Object[1];
        cycle[0] = cycle;
        Map<?, ?> snapshot = (Map<?, ?>) FeatureUiProbe.value(cycle);
        assertEquals(1, snapshot.get("length"));
        assertTrue(((List<?>) snapshot.get("items")).isEmpty());
    }

    @Test
    public void anonymousUiSubclassesAreRecognizedByTheirSuperclass() {
        assertTrue(FeatureUiProbe.hasType(ChildTarget.class, FakeTarget.class.getName()));
        assertFalse(FeatureUiProbe.hasType(ChildTarget.class, "unknown.View"));
    }

    static class ChildTarget extends FakeTarget { }

    static class FakeTarget {
        static int invoked;
        int phoneRow;
        boolean enabled;
        View[] nameTextView;
        boolean canForwardMessage() { invoked++; return true; }
    }
}
