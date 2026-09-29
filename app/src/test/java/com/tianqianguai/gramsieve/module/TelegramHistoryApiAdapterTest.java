package com.tianqianguai.gramsieve.module;

import org.junit.Test;

import java.lang.reflect.InvocationHandler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class TelegramHistoryApiAdapterTest {
    @Test
    public void testProbeBindsTwoParameterHistoryApiByCapability() throws Exception {
        TelegramHistoryApiAdapter.ProbeResult probe = TelegramHistoryApiAdapter.probe(
                new FakeTelegramClassLoader(false, false));

        assertTrue(probe.isSupported());
        assertTrue(probe.adapter.describe().contains("telegram=12.8.3(69222)"));
        assertTrue(probe.adapter.describe().contains("sendParams=2"));
        assertTrue(probe.adapter.describe().contains("peerHydration=true"));

        Object peer = new Object();
        FakeHistoryRequest request = (FakeHistoryRequest) probe.adapter.newHistoryRequest(peer, 50);
        assertSame(peer, request.peer);
        assertEquals(50, request.limit);
        assertEquals(0, request.offset_id);

        FakeControllerTwo controller = (FakeControllerTwo) probe.adapter.getController(0);
        FakeConnectionsManagerTwo manager = (FakeConnectionsManagerTwo)
                probe.adapter.getConnectionsManager(controller);
        Object delegate = probe.adapter.newRequestDelegate(noOpHandler());
        assertEquals(41, probe.adapter.sendRequest(manager, request, delegate));
        probe.adapter.cancelRequest(manager, 41);
        assertEquals(41, manager.cancelledRequestId);
    }

    @Test
    public void testProbeUsesSafeFlagsOverloadWhenTwoParameterSendIsAbsent() throws Exception {
        TelegramHistoryApiAdapter.ProbeResult probe = TelegramHistoryApiAdapter.probe(
                new FakeTelegramClassLoader(true, false));

        assertTrue(probe.isSupported());
        assertTrue(probe.adapter.describe().contains("sendParams=3"));

        FakeControllerThree controller = (FakeControllerThree) probe.adapter.getController(0);
        FakeConnectionsManagerThree manager = (FakeConnectionsManagerThree)
                probe.adapter.getConnectionsManager(controller);
        Object request = probe.adapter.newHistoryRequest(new Object(), 50);
        Object delegate = probe.adapter.newRequestDelegate(noOpHandler());
        assertEquals(43, probe.adapter.sendRequest(manager, request, delegate));
        assertEquals(0, manager.flags);
    }

    @Test
    public void testProbeRejectsUnknownShapeWithoutUnsafeFallback() {
        TelegramHistoryApiAdapter.ProbeResult probe = TelegramHistoryApiAdapter.probe(
                new FakeTelegramClassLoader(false, true));

        assertFalse(probe.isSupported());
        assertTrue(probe.reason.contains("TL_messages_getHistory"));
    }

    private static InvocationHandler noOpHandler() {
        return (proxy, method, args) -> {
            if ("hashCode".equals(method.getName())) {
                return System.identityHashCode(proxy);
            }
            if ("equals".equals(method.getName())) {
                return args != null && args.length == 1 && proxy == args[0];
            }
            return null;
        };
    }

    private static final class FakeTelegramClassLoader extends ClassLoader {
        private final boolean flagsOnly;
        private final boolean missingRequest;

        FakeTelegramClassLoader(boolean flagsOnly, boolean missingRequest) {
            super(TelegramHistoryApiAdapterTest.class.getClassLoader());
            this.flagsOnly = flagsOnly;
            this.missingRequest = missingRequest;
        }

        @Override
        public Class<?> loadClass(String name) throws ClassNotFoundException {
            if ("org.telegram.messenger.BuildVars".equals(name)) {
                return FakeBuildVars.class;
            }
            if ("org.telegram.messenger.MessagesController".equals(name)) {
                return flagsOnly ? FakeControllerThree.class : FakeControllerTwo.class;
            }
            if ("org.telegram.tgnet.TLRPC$TL_messages_getHistory".equals(name)) {
                if (missingRequest) {
                    throw new ClassNotFoundException(name);
                }
                return FakeHistoryRequest.class;
            }
            if ("org.telegram.tgnet.RequestDelegate".equals(name)) {
                return FakeRequestDelegate.class;
            }
            if ("org.telegram.tgnet.TLObject".equals(name)) {
                return FakeTlObject.class;
            }
            return super.loadClass(name);
        }
    }

    public static final class FakeBuildVars {
        public static int BUILD_VERSION = 69222;
        public static String BUILD_VERSION_STRING = "12.8.3";
    }

    public static class FakeTlObject {
    }

    public interface FakeRequestDelegate {
        void run(FakeTlObject response, FakeError error);
    }

    public static final class FakeError {
    }

    public static final class FakeHistoryRequest extends FakeTlObject {
        public Object peer;
        public int offset_id = -1;
        public int offset_date = -1;
        public int add_offset = -1;
        public int limit = -1;
        public int max_id = -1;
        public int min_id = -1;
        public long hash = -1L;
    }

    public static final class FakeStorage {
        public Object getChatSync(long chatId) {
            return new Object();
        }
    }

    public static final class FakeConnectionsManagerTwo {
        int cancelledRequestId;

        public int sendRequest(FakeTlObject request, FakeRequestDelegate delegate) {
            return 41;
        }

        public void cancelRequest(int requestId, boolean notifyServer) {
            cancelledRequestId = requestId;
        }
    }

    public static final class FakeControllerTwo {
        private final FakeConnectionsManagerTwo manager = new FakeConnectionsManagerTwo();

        public static FakeControllerTwo getInstance(int account) {
            return new FakeControllerTwo();
        }

        public Object getInputPeer(long dialogId) {
            return new Object();
        }

        public Object getChat(Long chatId) {
            return null;
        }

        public FakeStorage getMessagesStorage() {
            return new FakeStorage();
        }

        public void putChat(Object chat, boolean fromCache) {
        }

        public FakeConnectionsManagerTwo getConnectionsManager() {
            return manager;
        }
    }

    public static final class FakeConnectionsManagerThree {
        int flags = -1;

        public int sendRequest(FakeTlObject request, FakeRequestDelegate delegate, int flags) {
            this.flags = flags;
            return 43;
        }

        public void cancelRequest(int requestId) {
        }
    }

    public static final class FakeControllerThree {
        private final FakeConnectionsManagerThree manager = new FakeConnectionsManagerThree();

        public static FakeControllerThree getInstance(int account) {
            return new FakeControllerThree();
        }

        public Object getInputPeer(long dialogId) {
            return new Object();
        }

        public Object getChat(Long chatId) {
            return null;
        }

        public FakeStorage getMessagesStorage() {
            return new FakeStorage();
        }

        public void putChat(Object chat, boolean fromCache) {
        }

        public FakeConnectionsManagerThree getConnectionsManager() {
            return manager;
        }
    }
}
