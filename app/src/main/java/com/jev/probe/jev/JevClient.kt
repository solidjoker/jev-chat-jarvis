package com.jev.probe.jev

import com.jev.probe.core.Analysis
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Prefs
import com.jev.probe.core.RankedReply
import com.jev.probe.core.kb.ChatContext

/**
 * Thin facade over the three split clients so callers keep one entry point.
 * Construct with [Prefs] — every route reads its own address / key / model from
 * there, so switching providers in settings takes effect on the next call.
 */
class JevClient(prefs: Prefs) {

    private val judgeClient = JudgeClient(prefs)
    private val replyClient = ReplyClient(prefs)

    /** The 7 judgment questions. Errors come back inside [Analysis.error]. */
    fun judge(snapshot: ChatSnapshot, relationship: String, ctx: ChatContext? = null): Analysis =
        judgeClient.judge(snapshot, relationship, ctx)

    /**
     * Draft 3 candidates on the reply route, then rank them on the judge route.
     * [onDrafted] fires (on the calling thread) between the two halves so the
     * caller can advance its progress indicator.
     */
    fun draftAndRank(
        snapshot: ChatSnapshot,
        relationship: String,
        ctx: ChatContext? = null,
        onDrafted: ((List<String>) -> Unit)? = null
    ): List<RankedReply> {
        val candidates = replyClient.draft(snapshot, relationship, ctx)
        onDrafted?.invoke(candidates)
        return judgeClient.rank(snapshot, relationship, candidates, ctx)
    }

    /**
     * Draft-only: the 3 candidates without Jev ranking (the panel's 回复
     * button). Throws whatever the reply route throws; no judge call at all.
     */
    fun draftOnly(
        snapshot: ChatSnapshot,
        relationship: String,
        ctx: ChatContext? = null
    ): List<String> = replyClient.draft(snapshot, relationship, ctx)

    /** Judge + replies, sequential. Used by the settings connectivity test. */
    fun analyze(snapshot: ChatSnapshot, relationship: String, ctx: ChatContext? = null): Analysis {
        val a = judge(snapshot, relationship, ctx)
        if (a.error != null) return a
        val ranked = try { draftAndRank(snapshot, relationship, ctx) } catch (e: Exception) { emptyList() }
        return a.copy(rankedReplies = ranked)
    }
}
