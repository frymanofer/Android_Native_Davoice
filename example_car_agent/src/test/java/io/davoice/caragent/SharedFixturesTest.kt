package io.davoice.caragent

import io.davoice.caragent.car.BasicLanguage
import io.davoice.caragent.car.CarTurnAction
import io.davoice.caragent.car.FollowUp
import io.davoice.caragent.car.SpeechComposer
import io.davoice.caragent.car.StateAnswers
import io.davoice.caragent.car.groundMediaQuery
import io.davoice.caragent.util.asList
import io.davoice.caragent.util.asObj
import io.davoice.caragent.util.str
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

// Every case of the fixtures shared with the React Native and Flutter car apps: the three apps must decide and say the same thing.
class SharedFixturesTest {
    @Before fun setUp() = TestSupport.load()

    @Test fun `the fixtures are the React Native app's`() {
        for (name in listOf("follow_up.json", "state_answers.json", "car_dialogs.json", "media_query.json", "spoken_say.json", "basic_hints.json",
            "conversation_flow.json", "conversation_barge_in.json", "conversation_wake_phrase.json")) {
            val rn = TestSupport.rnFixture(name) ?: continue
            assertEquals(name, rn, TestSupport.fixtureText(name))
        }
    }

    @Test fun `follow-ups (follow_up json)`() {
        val diffs = mutableListOf<String>()
        for (c in TestSupport.fixture("follow_up.json").asList().map { it.asObj() }) {
            FollowUp.reset()
            BasicLanguage.clearPending()
            val lang = str(c["lang"])
            c["before"]?.asObj()?.let { FollowUp.noteCarReply(str(it["sent"]), str(it["reply"]), lang) }
            val got = FollowUp.carTurnInput(str(c["user"]), lang, (c["offer"] as? List<*>)?.map { str(it) }, musicOn = c["car"].asObj()["musicOn"] == true)
            val want = c["expect"].asObj()
            val ok = if ("reply" in want) got.reply == want["reply"] && got.text == null else got.text == want["text"] && got.reply == null
            if (!ok) diffs += "$lang \"${c["user"]}\": got ${got.text ?: got.reply}, want ${want["text"] ?: want["reply"]}"
        }
        assertEquals(emptyList<String>(), diffs)
    }

    @Test fun `questions about the car (state_answers json)`() {
        val diffs = mutableListOf<String>()
        for (c in TestSupport.fixture("state_answers.json").asList().map { it.asObj() }) {
            val got = StateAnswers.answer(str(c["user"]), str(c["lang"]), TestSupport.carWith(c["state"].asObj()))
            val want = c["expect"] as? Map<*, *>
            val ok = if (want == null) got == null else got != null && got.reply == want["reply"] && got.offer == want["offer"]
            if (!ok) diffs += "${c["lang"]} \"${c["user"]}\": got ${got?.reply} / ${got?.offer}, want ${want?.get("reply")} / ${want?.get("offer")}"
        }
        assertEquals(emptyList<String>(), diffs)
    }

    @Test fun `conversations about the car (car_dialogs json)`() {
        val diffs = mutableListOf<String>()
        for (d in TestSupport.fixture("car_dialogs.json").asList().map { it.asObj() }) {
            FollowUp.reset()
            BasicLanguage.clearPending()
            val lang = str(d["lang"])
            val state = TestSupport.carWith(d["state"].asObj())
            val flags = d["car"].asObj()
            for (s in d["steps"].asList().map { it.asObj() }) {
                val got = FollowUp.carTurnInput(str(s["user"]), lang, null, state = state, musicOn = flags["musicOn"] == true, lastActed = flags["lastActed"] as Boolean?)
                val want = s["expect"].asObj()
                val ok = if ("reply" in want) got.reply == want["reply"] && got.text == null else got.text == want["text"] && got.reply == null
                if (!ok) diffs += "$lang \"${s["user"]}\": got ${got.text ?: got.reply}, want ${want["text"] ?: want["reply"]}"
                FollowUp.noteCarSpoken(got.reply ?: str(s["heard"] ?: ""))
            }
        }
        assertEquals(emptyList<String>(), diffs)
    }

    @Test fun `media titles (media_query json)`() {
        val diffs = TestSupport.fixture("media_query.json").asList().map { it.asObj() }.mapNotNull { c ->
            val got = groundMediaQuery(str(c["query"]), str(c["user"]))
            if (got != c["expect"]) "\"${c["user"]}\" + \"${c["query"]}\": got $got, want ${c["expect"]}" else null
        }
        assertEquals(emptyList<String>(), diffs)
    }

    @Test fun `how a tool sentence is said (spoken_say json)`() {
        val diffs = TestSupport.fixture("spoken_say.json").asList().map { it.asList() }.mapNotNull { c ->
            val got = SpeechComposer.spokenSay(CarTurnAction(str(c[1]), str(c[2]), true, c[3].asObj()), str(c[0]))
            if (got != c[4]) "${c[0]} ${c[2]} -> $got (want ${c[4]})" else null
        }
        assertEquals(emptyList<String>(), diffs)
    }

    @Test fun `English hints for the Basic languages (basic_hints json, 252 sentences)`() {
        val diffs = TestSupport.fixture("basic_hints.json").asList().map { it.asList() }.mapNotNull { f ->
            val got = BasicLanguage.withCarHints(str(f[1]), str(f[0]))
            if (got != f[2]) "${f[0]} \"${f[1]}\": got $got, want ${f[2]}" else null
        }
        assertEquals(emptyList<String>(), diffs)
    }
}
