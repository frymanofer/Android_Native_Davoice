package io.davoice.caragent

import io.davoice.caragent.car.FollowUp
import io.davoice.caragent.flow.UNFINISHED_EXTRA_MS
import io.davoice.caragent.flow.looksUnfinished
import io.davoice.caragent.flow.silenceFor
import org.junit.Assert.assertEquals
import org.junit.Test

// Words that cannot end a request wait longer (flow/EndOfSpeech.kt), and "Lock the sunroof" is offered as closing it (car/FollowUp.kt).
// The same cases as the React Native app's endOfSpeech.test.ts and followUp.test.ts.
class EndOfSpeechTest {
    @Test fun `unfinished - the fragments that were sent too early`() {
        for (t in listOf("Look at", "Please", "Yeah, it's", "Set the", "Can you", "Open", "Set the heat of the", "Turn on", "Open all the doors and"))
            assertEquals(t, true, looksUnfinished(t, "en"))
        for (t in listOf("Fecha a", "Você pode", "Abre", "Coloca o volume em", "Por favor")) assertEquals(t, true, looksUnfinished(t, "pt-BR"))
    }

    @Test fun `finished requests and answers keep the normal silence`() {
        for (t in listOf("Open the sunroof", "Yes", "Yes please", "Stop", "No", "Close it please", "Set the fan to five", "Thank you", "Lock the car", "What's that"))
            assertEquals(t, false, looksUnfinished(t, "en"))
        for (t in listOf("Fecha a janela, por favor", "Sim", "Pode", "Liga o ar", "Tranca o carro")) assertEquals(t, false, looksUnfinished(t, "pt-BR"))
        assertEquals(false, looksUnfinished("Mach das", "de"))
        assertEquals(1500 + UNFINISHED_EXTRA_MS, silenceFor("Set the", 1500, "en"))
        assertEquals(1500, silenceFor("Open the window", 1500, "en"))
    }

    @Test fun `lock the sunroof is offered as closing it, a yes sends that request`() {
        FollowUp.reset()
        assertEquals("I can't lock the sunroof, but I can close it. Want me to close it?", FollowUp.carTurnInput("Can you lock the sunroof?", "en", null).reply)
        assertEquals("Close the sunroof", FollowUp.carTurnInput("Yes", "en", null).text)
        FollowUp.reset()
        FollowUp.carTurnInput("Lock the sunroof", "en", null)
        assertEquals("Close the sunroof", FollowUp.carTurnInput("Close it", "en", null).text)
        FollowUp.reset()
        FollowUp.carTurnInput("Unlock the windows", "en", null)
        assertEquals("Okay.", FollowUp.carTurnInput("No", "en", null).reply)
        FollowUp.reset()
        FollowUp.carTurnInput("Tranca o teto solar", "pt-BR", null)
        assertEquals("Fecha o teto solar", FollowUp.carTurnInput("Sim", "pt-BR", null).text)
        FollowUp.reset()
        FollowUp.carTurnInput("Destranca os vidros", "pt-BR", null)
        assertEquals("Abre todos os vidros", FollowUp.carTurnInput("Pode abrir", "pt-BR", null).text)
    }
}
