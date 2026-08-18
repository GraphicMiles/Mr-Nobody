package com.mrnobody.agent.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Sorting and light filtering for a provider's model catalogue.
 *
 * <p>A `/models` listing mixes chat models with speech, embedding, moderation
 * and image models. We do not pretend to know a provider's taxonomy — the user
 * can always see the whole list — but showing sixty ids with `whisper` and
 * `prompt-guard` in the middle is a worse default than showing the ones that
 * can hold a conversation first.
 *
 * <p>Pure string logic, so it is unit-tested rather than guessed at.
 */
public final class ModelCatalog {

    /** Substrings that mean "this model does not do chat". */
    private static final String[] NOT_CHAT = {
            "whisper", "tts", "speech", "voice", "orpheus", "audio",
            "embed", "embedding", "rerank",
            "guard", "moderation", "safeguard",
            "image", "vision-encoder", "dall-e", "imagen", "veo",
            "distil-whisper", "stable-diffusion",
    };

    private ModelCatalog() {
    }

    /** True if the id looks like something you can send a prompt to. */
    public static boolean looksLikeChatModel(String id) {
        if (id == null || id.trim().isEmpty()) return false;
        String lower = id.toLowerCase(Locale.ROOT);
        for (String marker : NOT_CHAT) {
            if (lower.contains(marker)) return false;
        }
        return true;
    }

    /**
     * Chat-capable models first (alphabetically), then the rest. Nothing is
     * removed: a user who wants an unusual model can still find it.
     */
    public static List<String> ordered(List<String> ids) {
        List<String> chat = new ArrayList<>();
        List<String> other = new ArrayList<>();
        if (ids != null) {
            for (String id : ids) {
                if (id == null || id.trim().isEmpty()) continue;
                (looksLikeChatModel(id) ? chat : other).add(id.trim());
            }
        }
        chat.sort(String::compareToIgnoreCase);
        other.sort(String::compareToIgnoreCase);
        List<String> out = new ArrayList<>(chat.size() + other.size());
        out.addAll(chat);
        out.addAll(other);
        return out;
    }

    /** How many of these look chat-capable — used to caption the picker. */
    public static int chatCount(List<String> ids) {
        int n = 0;
        if (ids != null) {
            for (String id : ids) {
                if (looksLikeChatModel(id)) n++;
            }
        }
        return n;
    }

    /**
     * Gemini reports names as {@code models/gemini-2.0-flash}; the request path
     * wants the bare id. Trim it once, here, rather than in three call sites.
     */
    public static String stripPrefix(String name) {
        if (name == null) return "";
        int slash = name.lastIndexOf('/');
        return slash >= 0 ? name.substring(slash + 1) : name;
    }
}
