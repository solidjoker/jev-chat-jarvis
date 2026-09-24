package com.jev.probe.core

import android.content.Context
import android.util.Log
import com.jev.probe.BuildConfig

/**
 * App-private config store. Holds the three API routes (judge / reply / vision),
 * the relationship description used in Jev's state, the conversation whitelist,
 * plus the context (D stage) and OCR (B stage) switches.
 *
 * Key handling: stored in app-private SharedPreferences (not world-readable,
 * never logged, never in code/git). Only key *lengths* are ever logged.
 *
 * Private build exception: when the (gitignored) apikey.toml was present at
 * build time, a blank stored key falls back to the value baked into
 * [BuildConfig] — the private APK then works with nothing typed in. Public
 * builds bake empty strings, so the fallback is a no-op there.
 */
class Prefs(context: Context, prefsName: String = PREFS_MAIN) {

    private val sp = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    /**
     * Only the real config migrates — and only the real config logs it. The
     * throwaway instances behind the settings test buttons and the KB self-check
     * have nothing to carry over, and used to print one migration line per tap.
     */
    init { if (prefsName == PREFS_MAIN) { migrateIfNeeded(); pinLegacyProviderIfUnset(); remapGlmBaseForPrivateBuild(); unseedBochaDefaultIfUnconfigured() } }

    /**
     * Private build with a baked Coding-Plan GLM base: a stored base still
     * pointing at the pay-as-you-go host (persisted by an earlier install, or
     * by 保存全部设置 under a build that preset it) is dropped, so the baked
     * default — the endpoint the baked key actually works on — takes over.
     * Runs once, private builds only, and only when the baked base differs;
     * any other stored base (DeepSeek, DashScope, a custom URL) is a real
     * choice and stays untouched.
     */
    private fun remapGlmBaseForPrivateBuild() {
        if (!BuildConfig.PRIVATE_BUILD) return
        if (sp.getBoolean(K_REMAPPED_GLM_BASE, false)) return
        val e = sp.edit().putBoolean(K_REMAPPED_GLM_BASE, true)
        val baked = BuildConfig.PRIVATE_GLM_BASE
        if (baked.isNotBlank() && baked != GLM_BASE_PAYG) {
            if ((sp.getString(K_REPLY_BASE, null) ?: "") == GLM_BASE_PAYG) e.remove(K_REPLY_BASE)
            if ((sp.getString(K_VISION_BASE, null) ?: "") == GLM_BASE_PAYG) e.remove(K_VISION_BASE)
        }
        e.apply()
    }

    /**
     * v1.2 -> v1.3: the single `openrouter_key` becomes the judge route's key.
     * `reply_model` keeps its old storage key, so it carries over untouched.
     */
    private fun migrateIfNeeded() {
        if (sp.getBoolean(K_MIGRATED_V13, false)) return   // runs exactly once
        val legacy = sp.getString(K_LEGACY_KEY, "") ?: ""
        val current = sp.getString(K_JUDGE_KEY, "") ?: ""
        val e = sp.edit().putBoolean(K_MIGRATED_V13, true)
        if (current.isBlank() && legacy.isNotBlank()) {
            e.putString(K_JUDGE_KEY, legacy)
            Log.i(TAG, "prefs migrated judgeKey.len=${legacy.length}")
        } else {
            Log.i(TAG, "prefs migrated judgeKey.len=${current.length} (no legacy key to copy)")
        }
        e.apply()
    }

    /**
     * v1.5 flips the judge getter defaults from OpenRouter to TypeSafe. An
     * upgrader holding a key but no persisted provider (migrateIfNeeded writes
     * only the key; only the settings Save button pins the provider) resolved
     * to OpenRouter under the old defaults — their key works there and not on
     * TypeSafe. Pin exactly once: key present + provider never written means
     * they were configured under the OpenRouter default, so keep them on it;
     * only truly fresh installs fall through to TypeSafe.
     */
    private fun pinLegacyProviderIfUnset() {
        if (sp.getBoolean(K_PINNED_LEGACY_PROVIDER, false)) return
        val e = sp.edit().putBoolean(K_PINNED_LEGACY_PROVIDER, true)
        val hasKey = !(sp.getString(K_JUDGE_KEY, "") ?: "").isBlank()
        if (hasKey && sp.getString(K_JUDGE_PROVIDER, null) == null) {
            e.putString(K_JUDGE_PROVIDER, PROVIDER_OPENROUTER)
                .putString(K_JUDGE_BASE, DEFAULT_JUDGE_BASE_OPENROUTER)
                .putString(K_JUDGE_MODEL, DEFAULT_JUDGE_MODEL_OPENROUTER)
            Log.i(TAG, "prefs: pinned legacy judge config to openrouter preset")
        }
        e.apply()
    }

    /**
     * Historical: v1.4.0 seeded fresh installs to Bocha Jev; v1.4.1 removed
     * that seed. Undo it exactly once, and only when the user never entered a
     * key and never picked a provider by hand — a saved key, or any non-Bocha
     * provider, means a real choice we must not touch. Since v1.5 the cleared
     * fields fall through to the TypeSafe-direct defaults.
     */
    private fun unseedBochaDefaultIfUnconfigured() {
        if (sp.getBoolean(K_UNSEEDED_BOCHA, false)) return
        val e = sp.edit().putBoolean(K_UNSEEDED_BOCHA, true)
        val prov = sp.getString(K_JUDGE_PROVIDER, null)
        val key = sp.getString(K_JUDGE_KEY, "") ?: ""
        if (prov == PROVIDER_BOCHA && key.isBlank()) {
            e.remove(K_JUDGE_PROVIDER).remove(K_JUDGE_BASE).remove(K_JUDGE_MODEL)
            Log.i(TAG, "prefs: cleared auto-seeded bocha default")
        }
        e.apply()
    }

    // ---------------------------------------------------------------- judge

    /** "bocha" | "openrouter" | "typesafe" | "vercel" | "custom". Default typesafe. */
    var judgeProvider: String
        get() = sp.getString(K_JUDGE_PROVIDER, PROVIDER_TYPESAFE) ?: PROVIDER_TYPESAFE
        set(v) = sp.edit().putString(K_JUDGE_PROVIDER, v.trim()).apply()

    /** Host root; the path is appended per provider (see [judgeEndpoint]). */
    var judgeBaseUrl: String
        get() = sp.getString(K_JUDGE_BASE, DEFAULT_JUDGE_BASE_TYPESAFE) ?: DEFAULT_JUDGE_BASE_TYPESAFE
        set(v) = sp.edit().putString(K_JUDGE_BASE, v.trim()).apply()

    /** Blank stored key = the private build's baked judge key ("" when public). */
    var judgeKey: String
        get() = (sp.getString(K_JUDGE_KEY, "") ?: "").ifBlank { BuildConfig.PRIVATE_JUDGE_KEY }
        set(v) = sp.edit().putString(K_JUDGE_KEY, v.trim()).apply()

    var judgeModel: String
        get() = sp.getString(K_JUDGE_MODEL, DEFAULT_JUDGE_MODEL_TYPESAFE) ?: DEFAULT_JUDGE_MODEL_TYPESAFE
        set(v) = sp.edit().putString(K_JUDGE_MODEL, v.trim()).apply()

    /** Back-compat alias so older call sites keep compiling. */
    var openRouterKey: String
        get() = judgeKey
        set(v) { judgeKey = v }

    // ---------------------------------------------------------------- reply

    /**
     * OpenAI-compatible base, up to and including `/v1`. A complete
     * `/chat/completions` URL is also accepted and POSTed verbatim (see
     * [appendChatCompletions]).
     */
    var replyBaseUrl: String
        get() = sp.getString(K_REPLY_BASE, DEFAULT_REPLY_BASE) ?: DEFAULT_REPLY_BASE
        set(v) = sp.edit().putString(K_REPLY_BASE, v.trim()).apply()

    /** Blank stored key = the private build's baked GLM key; then [judgeKey]. */
    var replyKey: String
        get() = (sp.getString(K_REPLY_KEY, "") ?: "").ifBlank { BuildConfig.PRIVATE_GLM_KEY }
        set(v) = sp.edit().putString(K_REPLY_KEY, v.trim()).apply()

    /** Generative model for drafting the 3 candidate replies. */
    var replyModel: String
        get() = sp.getString(K_REPLY_MODEL, DEFAULT_REPLY_MODEL) ?: DEFAULT_REPLY_MODEL
        set(v) = sp.edit().putString(K_REPLY_MODEL, v.trim()).apply()

    // --------------------------------------------------------------- vision

    /**
     * Blank = the GLM vision default. Deliberately does NOT follow
     * [replyBaseUrl]: the vision route may need a different host than the
     * reply one, so it keeps its own address box.
     */
    var visionBaseUrl: String
        get() = sp.getString(K_VISION_BASE, DEFAULT_VISION_BASE) ?: DEFAULT_VISION_BASE
        set(v) = sp.edit().putString(K_VISION_BASE, v.trim()).apply()

    /** Blank stored key = the private build's baked GLM key; then the reply chain. */
    var visionKey: String
        get() = (sp.getString(K_VISION_KEY, "") ?: "").ifBlank { BuildConfig.PRIVATE_GLM_KEY }
        set(v) = sp.edit().putString(K_VISION_KEY, v.trim()).apply()

    var visionModel: String
        get() = sp.getString(K_VISION_MODEL, DEFAULT_VISION_MODEL) ?: DEFAULT_VISION_MODEL
        set(v) = sp.edit().putString(K_VISION_MODEL, v.trim()).apply()

    // -------------------------------------------------------- context (D)

    /**
     * Record per-contact history and inject it into analysis. Default OFF:
     * nothing about the user's chats is written to disk unless they opt in
     * (v1.3 revision, D stage).
     */
    var contextEnabled: Boolean
        get() = sp.getBoolean(K_CTX_ENABLED, false)
        set(v) = sp.edit().putBoolean(K_CTX_ENABLED, v).apply()

    /** How many recent history entries to inject. */
    var contextHistoryCount: Int
        get() = sp.getInt(K_CTX_COUNT, 30)
        set(v) = sp.edit().putInt(K_CTX_COUNT, v).apply()

    /** Auto-summarize a contact once enough history accumulates. */
    var autoSummary: Boolean
        get() = sp.getBoolean(K_AUTO_SUMMARY, true)
        set(v) = sp.edit().putBoolean(K_AUTO_SUMMARY, v).apply()

    // ------------------------------------------------------------ OCR (B)

    /**
     * WeChat support (v1.5, experimental). ON: read WeChat chat bubbles via the
     * accessibility tree (disguised service) plus incoming messages via
     * notification access. Screenshots/OCR are NEVER taken in WeChat either
     * way — its chat screens are FLAG_SECURE for many accounts and screenshots
     * are what its risk control watches. OFF restores the v1.4 "not supported"
     * short-circuit.
     */
    var wechatEnabled: Boolean
        get() = sp.getBoolean(K_WECHAT_ENABLED, true)
        set(v) = sp.edit().putBoolean(K_WECHAT_ENABLED, v).apply()

    /** "mlkit" | "vision". */
    var ocrEngine: String
        get() = sp.getString(K_OCR_ENGINE, OCR_MLKIT) ?: OCR_MLKIT
        set(v) = sp.edit().putString(K_OCR_ENGINE, v.trim()).apply()

    /** Run generic OCR capture on apps with no dedicated adapter. */
    var ocrForUnknownApps: Boolean
        get() = sp.getBoolean(K_OCR_UNKNOWN, true)
        set(v) = sp.edit().putBoolean(K_OCR_UNKNOWN, v).apply()

    /** Fall back to OCR when an adapted app's node tree comes back empty. */
    var ocrFallback: Boolean
        get() = sp.getBoolean(K_OCR_FALLBACK, true)
        set(v) = sp.edit().putBoolean(K_OCR_FALLBACK, v).apply()

    /** Auto-analyze in OCR mode (default off: OCR costs a screenshot each time). */
    var ocrAutoAnalyze: Boolean
        get() = sp.getBoolean(K_OCR_AUTO, false)
        set(v) = sp.edit().putBoolean(K_OCR_AUTO, v).apply()

    // ------------------------------------------------------------- existing

    /** Free-text describing who the other person is; goes into Jev's state. */
    var relationship: String
        get() = sp.getString(K_REL, DEFAULT_REL) ?: DEFAULT_REL
        set(v) = sp.edit().putString(K_REL, v).apply()

    /** Master on/off for showing the overlay + running analysis. */
    var enabled: Boolean
        get() = sp.getBoolean(K_ENABLED, true)
        set(v) = sp.edit().putBoolean(K_ENABLED, v).apply()

    /**
     * Conversation whitelist: titles the assistant is allowed to act on. Empty
     * set means "all conversations". Stored as a plain string set.
     */
    var whitelist: Set<String>
        get() = sp.getStringSet(K_WHITELIST, emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet(K_WHITELIST, v).apply()

    /** Overlay panel opacity, 60..100 (%). Lower lets the chat show through. */
    var overlayOpacity: Int
        get() = sp.getInt(K_OPACITY, 92).coerceIn(60, 100)
        set(v) = sp.edit().putInt(K_OPACITY, v.coerceIn(60, 100)).apply()

    /** Remembered vertical position of the bubble (px); -1 = default. */
    var bubbleY: Int
        get() = sp.getInt(K_BUBBLE_Y, -1)
        set(v) = sp.edit().putInt(K_BUBBLE_Y, v).apply()

    /** Remembered horizontal position of the bubble (px); -1 = default. */
    var bubbleX: Int
        get() = sp.getInt(K_BUBBLE_X, -1)
        set(v) = sp.edit().putInt(K_BUBBLE_X, v).apply()

    /** Auto-analyze on every incoming message; if false, user taps to analyze. */
    var autoAnalyze: Boolean
        get() = sp.getBoolean(K_AUTO, true)
        set(v) = sp.edit().putBoolean(K_AUTO, v).apply()

    // ------------------------------------------------------------- helpers

    /** Reply route key, falling back to the judge key. */
    fun effectiveReplyKey(): String = replyKey.ifBlank { judgeKey }

    /** Vision route key, falling back to reply then judge. */
    fun effectiveVisionKey(): String = visionKey.ifBlank { effectiveReplyKey() }

    /** Full POST URL for the Jev decisions call, per provider. */
    fun judgeEndpoint(): String {
        val base = judgeBaseUrl.trim().trimEnd('/')
        return when (judgeProvider) {
            PROVIDER_BOCHA -> "$base/v1/systemone"    // same path as TypeSafe
            PROVIDER_TYPESAFE -> "$base/v1/systemone"
            PROVIDER_VERCEL -> "$base/v1/systemone"   // TypeSafe-compatible gateway
            PROVIDER_CUSTOM -> judgeBaseUrl.trim()   // user supplies the full URL
            else -> "$base/alpha/decisions"
        }
    }

    /** Full POST URL for the OpenAI-compatible chat completions call. */
    fun replyEndpoint(): String = chatCompletionsUrl(replyBaseUrl, DEFAULT_REPLY_BASE)

    /** Same shape as [replyEndpoint]; blank falls back to the GLM default. */
    fun visionEndpoint(): String = chatCompletionsUrl(visionBaseUrl, DEFAULT_VISION_BASE)

    /**
     * Base -> endpoint, with the blank fallback applied. The append rule itself
     * lives in [appendChatCompletions] so the settings-page preview cannot
     * diverge from what actually gets POSTed.
     */
    private fun chatCompletionsUrl(base: String, fallback: String): String =
        appendChatCompletions(base.ifBlank { fallback })

    fun isAllowed(title: String?): Boolean {
        val wl = whitelist
        if (wl.isEmpty()) return true
        if (title == null) return false
        return wl.any { title.contains(it) }
    }

    /** Readiness gate: the judge route is the one that must be configured. */
    fun hasKey(): Boolean = judgeKey.isNotBlank()

    companion object {
        private const val TAG = "JEVASSIST"

        /** The one real config file. Anything else is a scratch instance. */
        const val PREFS_MAIN = "jev_assistant"

        private const val K_LEGACY_KEY = "openrouter_key"
        private const val K_MIGRATED_V13 = "prefs_migrated_v13"
        private const val K_PINNED_LEGACY_PROVIDER = "prefs_pinned_legacy_provider_v15"
        private const val K_REMAPPED_GLM_BASE = "prefs_remapped_glm_base_private"
        private const val K_UNSEEDED_BOCHA = "unseeded_bocha_v141"
        private const val K_JUDGE_PROVIDER = "judge_provider"
        private const val K_JUDGE_BASE = "judge_base_url"
        private const val K_JUDGE_KEY = "judge_key"
        private const val K_JUDGE_MODEL = "judge_model"
        private const val K_REPLY_BASE = "reply_base_url"
        private const val K_REPLY_KEY = "reply_key"
        private const val K_REPLY_MODEL = "reply_model"
        private const val K_VISION_BASE = "vision_base_url"
        private const val K_VISION_KEY = "vision_key"
        private const val K_VISION_MODEL = "vision_model"
        private const val K_CTX_ENABLED = "context_enabled"
        private const val K_CTX_COUNT = "context_history_count"
        private const val K_AUTO_SUMMARY = "auto_summary"
        private const val K_OCR_ENGINE = "ocr_engine"
        private const val K_WECHAT_ENABLED = "wechat_enabled"
        private const val K_OCR_UNKNOWN = "ocr_unknown_apps"
        private const val K_OCR_FALLBACK = "ocr_fallback"
        private const val K_OCR_AUTO = "ocr_auto_analyze"
        private const val K_REL = "relationship"
        private const val K_ENABLED = "enabled"
        private const val K_WHITELIST = "whitelist"
        private const val K_OPACITY = "overlay_opacity"
        private const val K_BUBBLE_Y = "bubble_y"
        private const val K_BUBBLE_X = "bubble_x"
        private const val K_AUTO = "auto_analyze"

        const val PROVIDER_BOCHA = "bocha"
        const val PROVIDER_OPENROUTER = "openrouter"
        const val PROVIDER_TYPESAFE = "typesafe"
        const val PROVIDER_VERCEL = "vercel"
        const val PROVIDER_CUSTOM = "custom"

        const val OCR_MLKIT = "mlkit"
        const val OCR_VISION = "vision"

        /**
         * The one base->endpoint rule for OpenAI-compatible routes: append
         * `/chat/completions` unless the URL already ends in it. Case-insensitive
         * so a hand-typed `HTTP://…/Chat/Completions` is not doubled. Public
         * because the settings page previews exactly what this computes.
         */
        fun appendChatCompletions(url: String): String {
            val b = url.trim().trimEnd('/')
            return if (b.lowercase().endsWith("/chat/completions")) b else "$b/chat/completions"
        }

        // Judge route presets.
        // Bocha Jev: same protocol/path as TypeSafe (/v1/systemone). Limited-time free.
        const val DEFAULT_JUDGE_BASE_BOCHA = "https://jev.bocha.cn"
        const val DEFAULT_JUDGE_MODEL_BOCHA = "bocha-jev-v1"
        const val DEFAULT_JUDGE_BASE_OPENROUTER = "https://openrouter.ai/api"
        const val DEFAULT_JUDGE_MODEL_OPENROUTER = "typesafe/jev-1.13"
        // TypeSafe direct is the judge route's default: the Jev model's own host.
        const val DEFAULT_JUDGE_BASE_TYPESAFE = "https://api.typesafe.ai"
        const val DEFAULT_JUDGE_MODEL_TYPESAFE = "jev-latest"
        // Vercel AI Gateway's TypeSafe-compatible API. Same /v1/systemone body
        // and noul answers as TypeSafe direct; model id is the gateway's.
        const val DEFAULT_JUDGE_BASE_VERCEL = "https://ai-gateway.vercel.sh/typesafe"
        const val DEFAULT_JUDGE_MODEL_VERCEL = "typesafe-ai/jev"

        // Reply / vision route presets (OpenAI-compatible chat completions).
        // The two flash models take both plain text and image_url parts, so the
        // same pair serves the reply and the vision (OCR) route.
        // Pay-as-you-go host — NOT the /api/coding/paas/v4 Coding Plan host.
        const val GLM_BASE_PAYG = "https://open.bigmodel.cn/api/paas/v4"
        /**
         * The GLM base this build presets to. Private builds bake the Coding
         * Plan endpoint (their baked key is a plan key and answers 1113 余额
         * 不足 on pay-as-you-go); public builds preset [GLM_BASE_PAYG]. An
         * optional `glm_base = "..."` line in apikey.toml overrides.
         */
        val GLM_BASE: String get() = BuildConfig.PRIVATE_GLM_BASE.ifBlank { GLM_BASE_PAYG }
        const val GLM_MODEL = "glm-5.3-flash"
        const val DEEPSEEK_BASE = "https://api.deepseek.com/v1"
        const val DEEPSEEK_MODEL = "deepseek-v4-flash"
        const val DEEPSEEK_VISION_MODEL = "deepseek-v4-flash"
        const val DASHSCOPE_BASE = "https://dashscope.aliyuncs.com/compatible-mode/v1"
        const val DASHSCOPE_MODEL = "qwen-plus"
        const val DASHSCOPE_VISION_MODEL = "qwen-vl-max"

        // Blank-fallback defaults follow the preset of THIS build (GLM first;
        // its host is [GLM_BASE]). OpenRouter stays reachable through the
        // 自定义 pill by pasting its /v1 base.
        val DEFAULT_REPLY_BASE: String get() = GLM_BASE
        val DEFAULT_REPLY_MODEL = GLM_MODEL
        val DEFAULT_VISION_BASE: String get() = GLM_BASE
        val DEFAULT_VISION_MODEL = GLM_MODEL

        const val DEFAULT_REL = "对方是我的伴侣；from=me 的是我发的，from=other 的是对方发的"
    }
}
