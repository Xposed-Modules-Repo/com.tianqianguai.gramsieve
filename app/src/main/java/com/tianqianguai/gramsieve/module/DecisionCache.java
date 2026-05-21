package com.tianqianguai.gramsieve.module;

import com.tianqianguai.gramsieve.core.FilterConfig;
import com.tianqianguai.gramsieve.core.FilterDecision;
import com.tianqianguai.gramsieve.core.MessageSnapshot;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

final class DecisionCache {
    private static final int MAX_ENTRIES = 512;

    private final Map<String, FilterDecision> cache = new LinkedHashMap<>(MAX_ENTRIES, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, FilterDecision> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    private long currentToken = Long.MIN_VALUE;

    synchronized FilterDecision get(FilterConfig config, MessageSnapshot snapshot, Supplier<FilterDecision> loader) {
        long token = config == null ? Long.MIN_VALUE : config.updatedAtEpochMs;
        if (token != currentToken) {
            cache.clear();
            currentToken = token;
        }
        String key = snapshot.stableKey();
        FilterDecision cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        FilterDecision computed = loader.get();
        cache.put(key, computed);
        return computed;
    }

    synchronized void clear() {
        cache.clear();
        currentToken = Long.MIN_VALUE;
    }
}
