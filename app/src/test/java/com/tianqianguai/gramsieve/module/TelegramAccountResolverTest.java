package com.tianqianguai.gramsieve.module;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class TelegramAccountResolverTest {
    @Test
    public void resolvesCurrentAccountField() {
        assertEquals(2, TelegramAccountResolver.resolve(new FieldAccount(2)));
    }

    @Test
    public void resolvesGetterAfterUnknownCandidate() {
        assertEquals(1, TelegramAccountResolver.resolve(new Object(), new GetterAccount()));
    }

    @Test
    public void unknownDefaultsToFirstAccount() {
        assertEquals(0, TelegramAccountResolver.resolve(new Object()));
    }

    private static final class FieldAccount {
        private final int currentAccount;

        FieldAccount(int currentAccount) {
            this.currentAccount = currentAccount;
        }
    }

    private static final class GetterAccount {
        @SuppressWarnings("unused")
        private int getCurrentAccount() {
            return 1;
        }
    }
}
