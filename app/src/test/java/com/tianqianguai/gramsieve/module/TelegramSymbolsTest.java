package com.tianqianguai.gramsieve.module;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class TelegramSymbolsTest {
    static class Host {
        public void a(int value) { }
        public void a(String value) { }
    }
    static class Child extends Host {
        @Override public void a(int value) { }
    }

    @Test public void aliasesUseTheFullDescriptorAndInheritedOverrides() throws Exception {
        String row = "M\t" + Host.class.getName() + "\tsetCount\ta\t(I)V\n";
        TelegramSymbols symbols = new TelegramSymbols(new ByteArrayInputStream(row.getBytes(StandardCharsets.UTF_8)));
        assertEquals("setCount", symbols.methodName(Host.class.getDeclaredMethod("a", int.class)));
        assertEquals("a", symbols.methodName(Host.class.getDeclaredMethod("a", String.class)));
        assertEquals("setCount", symbols.methodName(Child.class.getDeclaredMethod("a", int.class)));
    }

    @Test(expected = IOException.class) public void malformedMapIsRejected() throws Exception {
        new TelegramSymbols(new ByteArrayInputStream("M\tbroken\n".getBytes(StandardCharsets.UTF_8)));
    }

    @Test public void uninitializedHostsUseNativeNames() throws Exception {
        assertEquals(String.class, TelegramSymbols.loadClass(getClass().getClassLoader(), "java.lang.String"));
        assertEquals(Host.class.getName(), TelegramSymbols.name(Host.class));
        assertEquals("Host", TelegramSymbols.simpleName(Host.class));
        assertEquals(Host.class.getDeclaredMethod("a", int.class), TelegramSymbols.declaredMethod(Host.class, "a", int.class));
    }
}
