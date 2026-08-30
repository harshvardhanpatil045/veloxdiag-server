package com.veloxdiag.server.diagnosis;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Holds a list of Groq API keys and rotates to the next one when the
 * current key hits a 429 (quota exceeded). Shared by NarrativeService and
 * RecommendationService so both features benefit from the same pool.
 *
 * Provider-agnostic by design (just a key list + rotation index) — was
 * previously named GeminiKeyRotator from an earlier provider, renamed here
 * to match the actual provider now in use.
 *
 * Config: groq.api.key can be a single key OR a comma-separated list.
 * Example application.properties:
 *   groq.api.key=${GROQ_KEY_1},${GROQ_KEY_2},${GROQ_KEY_3}
 */
@Component
public class ApiKeyRotator {

    private final List<String> keys;
    private final AtomicInteger index = new AtomicInteger(0);

    public ApiKeyRotator(@Value("${groq.api.key:}") String keysCsv) {
        List<String> parsed = new ArrayList<>();
        if (keysCsv != null && !keysCsv.isBlank()) {
            for (String k : keysCsv.split(",")) {
                String trimmed = k.trim();
                if (!trimmed.isBlank()) {
                    parsed.add(trimmed);
                }
            }
        }
        this.keys = parsed;
    }

    public boolean hasKeys() {
        return !keys.isEmpty();
    }

    public int keyCount() {
        return keys.size();
    }

    public String current() {
        if (keys.isEmpty()) return null;
        return keys.get(Math.floorMod(index.get(), keys.size()));
    }

    /**
     * Advances to the next key. Returns false if there's only one key
     * (nothing to rotate to) so callers know not to bother retrying.
     */
    public boolean rotate() {
        if (keys.size() <= 1) return false;
        index.incrementAndGet();
        return true;
    }
}