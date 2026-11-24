package io.davoice.caragent.speaker

import android.util.Log

// Who spoke the user's current request, for actions only the car's owner may do (unlock_car, open_door: tool authorization scope
// "door_locks"). The speech library reports the verified speakers of a sentence in its results when speaker verification is on; the
// app records them per request and clears them after each turn. With speaker verification on, the enrolled owner's voice must be in
// the request. With it skipped there is no way to tell voices apart: the demo allows it (logged), so the doors work without enrollment.

object CurrentSpeaker {
    @Volatile private var gateOn = false
    @Volatile private var ownerId: String? = null
    private val heard = java.util.concurrent.CopyOnWriteArraySet<String>()

    /** Called when speaker verification is applied (enrolled or skipped). */
    fun setGate(enabled: Boolean, enrolledOwnerId: String?) {
        gateOn = enabled && enrolledOwnerId != null
        ownerId = enrolledOwnerId
        heard.clear()
        Log.i("SVGate", if (gateOn) "on: owner \"$ownerId\"" else "off: speaker verification skipped")
    }

    /** Every STT partial and final result. */
    fun noteResult(speakerIds: List<String>, speakerId: String?) {
        heard.addAll(speakerIds)
        if (speakerId != null) heard.add(speakerId)
    }

    /** After a turn: the next request starts with nobody heard. */
    fun clearHeard() = heard.clear()

    /** May the current request do an owner-only action? (allowed, why) */
    fun ownerMayAct(): Pair<Boolean, String> {
        if (!gateOn) return true to "speaker verification is off (demo allows it)"
        val owner = ownerId
        if (owner != null && owner in heard) return true to "owner \"$owner\" verified"
        return false to (if (heard.isNotEmpty()) "heard ${heard.joinToString(", ")}, not the owner" else "no verified speaker in this request")
    }
}
