package io.davoice.caragent

import io.davoice.caragent.agent.ToolReply
import io.davoice.caragent.car.BasicLanguage
import io.davoice.caragent.car.Car
import io.davoice.caragent.car.CarTools
import io.davoice.caragent.car.SpeechComposer
import io.davoice.caragent.flow.AppLanguage
import io.davoice.caragent.util.asObj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// The car's confirmed replies: the same cases and expected sentences as the Flutter and React Native car apps' car tool tests (all
// three apps must say the same thing for the same call).
class CarToolsTest {
    private fun say(name: String, args: Map<String, Any?>) = (CarTools.run(name, args) as ToolReply).say
    private fun result(name: String, args: Map<String, Any?>) = (CarTools.run(name, args) as ToolReply).result.asObj()

    @Before fun setUp() {
        TestSupport.load()
        Car.reset()
        Car.setDriving(false)
        Car.setSteering("left")
        CarTools.resetForTests()
        BasicLanguage.clearPending()
        AppLanguage.set("en")
    }

    @Test fun `the h3d demo profile has the dual-zone tool, the v1 fallback does not`() {
        assertTrue(CarTools.tools(wording = "h3d").any { it.name == "set_dual_zone_temperature" })
        assertFalse(CarTools.tools(wording = "v1").any { it.name == "set_dual_zone_temperature" })
        assertTrue(CarTools.tools(wording = "h3d").first { it.name == "unlock_car" }.execution != null)
    }

    @Test fun `windows by place, en`() {
        assertEquals("I opened the front left window.", say("set_window", mapOf("window" to "front_left", "position" to "open")))
        assertEquals("open", Car.state.windows["front_left"])
        assertEquals("The front left window is already open.", say("set_window", mapOf("window" to "front_left", "position" to "open")))
        assertEquals("I opened the rear windows halfway.", say("set_window", mapOf("window" to "rear", "position" to "half")))
        assertEquals("I closed all the windows.", say("set_window", mapOf("window" to "all", "position" to "close")))
        assertEquals("All the windows are already closed.", say("set_window", mapOf("window" to "all", "position" to "close")))
    }

    @Test fun `windows by place, pt-BR agreement`() {
        AppLanguage.set("pt-BR")
        assertEquals("Abri o vidro traseiro direito.", say("set_window", mapOf("window" to "rear_right", "position" to "open")))
        assertEquals("O vidro traseiro direito já está aberto.", say("set_window", mapOf("window" to "rear_right", "position" to "open")))
        assertEquals("Os vidros do lado esquerdo já estão fechados.", say("set_window", mapOf("window" to "left", "position" to "close")))
        assertEquals("Deixei os vidros do lado esquerdo com uma fresta.", say("set_window", mapOf("window" to "left", "position" to "crack")))
        assertEquals("Fechei os vidros do lado esquerdo.", say("set_window", mapOf("window" to "left", "position" to "close")))
        assertEquals("Os vidros da frente já estão fechados.", say("set_window", mapOf("window" to "front", "position" to "close")))
    }

    @Test fun `driver and passenger follow the steering wheel`() {
        say("set_window", mapOf("window" to "driver", "position" to "open"))
        assertEquals("open", Car.state.windows["front_left"])
        Car.setSteering("right")
        say("set_window", mapOf("window" to "driver", "position" to "crack"))
        assertEquals("crack", Car.state.windows["front_right"])
        say("set_window", mapOf("window" to "passenger", "position" to "half"))
        assertEquals("half", Car.state.windows["front_left"])
    }

    @Test fun `blocked while driving and an unknown window`() {
        Car.setDriving(true)
        val r = CarTools.run("set_window", mapOf("window" to "all", "position" to "open")) as ToolReply
        assertEquals("I can't do that while the car is moving.", r.say)
        assertEquals(false, r.result.asObj()["ok"])
        assertEquals("close", Car.state.windows["front_left"])
        assertEquals(false, result("set_window", mapOf("window" to "sunroof", "position" to "open"))["ok"])
    }

    @Test fun `doors and locks`() {
        assertEquals("I opened the rear left door.", say("open_door", mapOf("door" to "rear_left")))
        assertEquals("The rear left door is already open.", say("open_door", mapOf("door" to "rear_left")))
        AppLanguage.set("pt-BR")
        assertEquals("Fechei a porta traseira esquerda.", say("close_doors", mapOf("door" to "rear_left")))
        assertEquals("Todas as portas já estão fechadas.", say("close_doors", emptyMap()))
    }

    @Test fun `a locked door - the app asks, nothing opens, nothing is unlocked`() {
        say("lock_car", emptyMap())
        val r = CarTools.run("open_door", mapOf("door" to "all")) as ToolReply
        assertEquals("The car is locked. Should I unlock it?", r.say)
        assertEquals(false, r.result.asObj()["ok"])
        assertTrue(Car.state.locked)
        assertTrue(Car.state.doors.values.all { it == "closed" })
    }

    @Test fun `one door locked, en and pt`() {
        assertEquals("I locked the front right door.", say("lock_car", mapOf("door" to "front_right")))
        assertFalse(Car.state.locked)
        assertEquals("The front right door is locked. Should I unlock it?", say("open_door", mapOf("door" to "passenger")))
        AppLanguage.set("pt-BR")
        assertEquals("A porta dianteira direita já está trancada.", say("lock_car", mapOf("door" to "front_right")))
        assertEquals("Destranquei a porta do passageiro.", say("unlock_car", mapOf("door" to "passenger")))
        assertEquals("O carro já está destrancado.", say("unlock_car", emptyMap()))
    }

    @Test fun `no locking with a door open`() {
        say("open_door", mapOf("door" to "front_left"))
        assertEquals("I can't lock it while the front left door is open. Close it first?", say("lock_car", emptyMap()))
        assertFalse(Car.state.locked)
    }

    @Test fun `temperature one zone and dual zone`() {
        assertEquals("I set the driver's side to 22°C.", say("set_temperature", mapOf("temperature" to 22, "zone" to "driver")))
        assertEquals("The driver's side is already at 22°C.", say("set_temperature", mapOf("temperature" to 22, "zone" to "driver")))
        val r = CarTools.run("set_temperature", mapOf("temperature" to 40)) as ToolReply
        assertEquals(false, r.result.asObj()["ok"])
        assertEquals("I didn't change it: the temperature must be between 16 and 30.", r.say)
        assertEquals("I set the driver's side to 20°C and the passenger side to 23°C.", say("set_dual_zone_temperature", mapOf("driver" to 20, "passenger" to 23)))
        assertEquals("The driver's side is already at 20°C and the passenger side at 23°C.", say("set_dual_zone_temperature", mapOf("driver" to 20, "passenger" to 23)))
        AppLanguage.set("pt-BR")
        assertEquals("Coloquei o lado do motorista em 21°C, mas não o do passageiro: precisa ficar entre 16 e 30.", say("set_dual_zone_temperature", mapOf("driver" to 21, "passenger" to 35)))
        assertEquals("Não mudei a temperatura: os dois valores precisam ficar entre 16 e 30.", say("set_dual_zone_temperature", mapOf("driver" to 5, "passenger" to 50)))
    }

    @Test fun `seat, defrost, volume`() {
        assertEquals("The heater in the driver's seat is already off.", say("set_seat_climate", mapOf("feature" to "heater", "level" to "off")))
        assertEquals("I set the heater in the driver's seat to medium.", say("set_seat_climate", mapOf("feature" to "heater", "level" to "medium")))
        assertEquals("The rear defogger is already off.", say("set_defrost", mapOf("target" to "rear", "on" to false)))
        assertEquals("The sound is already on, volume 12.", say("set_volume", mapOf("action" to "unmute")))
        assertEquals("I muted the sound.", say("set_volume", mapOf("action" to "mute")))
    }

    @Test fun `volume steps and targets`() {
        val cases = listOf(
            mapOf("action" to "up", "change" to 5) to "I turned the volume up to 17.",
            mapOf("action" to "down", "change" to -2) to "I turned the volume down to 15.",
            mapOf("action" to "down", "change" to 4) to "I turned the volume down to 11.",
            mapOf("action" to "set", "change" to 3) to "I turned the volume up to 14.",
            mapOf("action" to "up", "change" to 50) to "I turned the volume up to 30.",
            mapOf("action" to "set", "level" to 12) to "I set the volume to 12.",
            mapOf("action" to "up", "level" to 2) to "I turned the volume up to 14.",
            mapOf("action" to "down", "level" to 20) to "I turned the volume down to 0.",
            mapOf("action" to "set", "level" to 12) to "I set the volume to 12.",
            mapOf("action" to "down", "level" to 5) to "I turned the volume down to 5.",
            mapOf("action" to "up", "level" to 20) to "I turned the volume up to 20.",
            mapOf("action" to "up") to "I turned the volume up to 23.",
            mapOf("action" to "down") to "I turned the volume down to 20.",
            mapOf("action" to "set", "level" to 12) to "I set the volume to 12.",
        )
        for ((args, want) in cases) assertEquals(args.toString(), want, say("set_volume", args))
    }

    @Test fun `fan, lookups, ETA`() {
        AppLanguage.set("pt-BR")
        assertEquals("A ventilação já está no nível 3.", say("set_fan_speed", mapOf("level" to 3)))
        assertEquals("Coloquei a ventilação no nível 5.", say("set_fan_speed", mapOf("level" to 5)))
        assertEquals("A ventilação vai até o nível 7, então coloquei no nível 7.", say("set_fan_speed", mapOf("level" to 9)))
        assertEquals("A ventilação vai até o nível 7, e já está nele.", say("set_fan_speed", mapOf("change" to 2)))
        AppLanguage.set("en")
        assertEquals("The lowest fan level is 1, so I set it to level 1.", say("set_fan_speed", mapOf("level" to 0)))
        assertEquals("You have about 312 km of range left.", say("get_vehicle_status", mapOf("item" to "range")))
        assertEquals("There's no active route. Where do you want to go?", say("get_trip_eta", emptyMap()))
        say("find_along_route", mapOf("place_type" to "gas_station"))
        say("navigate_to", mapOf("destination" to "the nearest gas station"))
        assertEquals("You'll get to the nearest gas station in about 3 minutes, 2.1 km to go.", say("get_trip_eta", emptyMap()))
        say("navigate_to", mapOf("destination" to "airport"))
        assertEquals("You'll get to airport in about 24 minutes, 18 km to go.", say("get_trip_eta", emptyMap()))
        AppLanguage.set("pt-BR")
        say("find_along_route", mapOf("place_type" to "coffee"))
        say("navigate_to", mapOf("destination" to "o café mais próximo"))
        assertEquals("Você chega em o café mais próximo em cerca de 3 minutos; faltam 2,1 km.", say("get_trip_eta", emptyMap()))
    }

    @Test fun `airflow with the article in pt-BR`() {
        AppLanguage.set("pt-BR")
        assertEquals("O ar agora vai para os pés.", say("set_airflow", mapOf("mode" to "feet")))
        assertEquals("O ar já vai para os pés.", say("set_airflow", mapOf("mode" to "feet")))
        assertEquals("O ar agora vai para o rosto e os pés.", say("set_airflow", mapOf("mode" to "face_and_feet")))
    }

    @Test fun `play the driver's words when the model copied the title wrong`() {
        CarTools.noteUserText("Play Pretty Woman")
        assertEquals("Playing Pretty Woman.", say("play_media", mapOf("query" to "Perma Woman")))
    }

    private fun compose(says: List<Triple<String, String, Boolean>>, text: String, lang: String = "en", acked: Boolean = false): String {
        SpeechComposer.beginCarTurn()
        SpeechComposer.resetVariant()
        for ((tool, said, merge) in says) SpeechComposer.noteCarSay(tool, said, merge)
        return SpeechComposer.composeCarSpeech(text, SpeechComposer.carTurnActions(), lang, acked)
    }

    @Test fun `several actions are said as one sentence (the other apps' composer cases)`() {
        assertEquals("I closed all the doors and locked the car. Everything is secure.",
            compose(listOf(Triple("close_doors", "I closed all the doors.", true), Triple("lock_car", "I locked the car.", true)), "I closed all the doors. I locked the car."))
        assertEquals("I unlocked the car and opened all the doors. Come on in!",
            compose(listOf(Triple("unlock_car", "I unlocked the car.", true), Triple("open_door", "I opened all the doors.", true)), "I unlocked the car. I opened all the doors."))
        assertEquals("Fechei todas as janelas, fechei todas as portas e tranquei o carro. Agora o carro está todo seguro.",
            compose(listOf(Triple("set_window", "Fechei todas as janelas.", true), Triple("close_doors", "Fechei todas as portas.", true), Triple("lock_car", "Tranquei o carro.", true)),
                "Fechei todas as janelas. Fechei todas as portas. Tranquei o carro.", "pt-BR"))
        assertEquals("I locked the car. You're good to go.", compose(listOf(Triple("lock_car", "I locked the car.", true)), "I locked the car."))
        assertEquals("Destranquei todas as portas do carro. Pode entrar, o carro já está aberto.", compose(listOf(Triple("unlock_car", "Destranquei o carro.", true)), "Destranquei o carro.", "pt-BR"))
        // pt-BR: a short sentence said on its own is said longer (tested wording, as the RN example)
        assertEquals("Tranquei todas as portas do carro. Pode ficar tranquilo, está tudo seguro.", compose(listOf(Triple("lock_car", "Tranquei o carro.", true)), "Tranquei o carro.", "pt-BR"))
        assertEquals("Navigating home: about 24 minutes, 18 km. Let's go!",
            compose(listOf(Triple("navigate_to", "Navigating to home: about 24 minutes, 18 km.", true)), "Navigating to home: about 24 minutes, 18 km.", "en", true))
    }

    @Test fun `a Basic language asks first, a plain yes runs it`() {
        AppLanguage.set("de")
        SpeechComposer.beginCarTurn()
        val asked = CarTools.run("set_window", mapOf("window" to "driver", "position" to "open")) as ToolReply
        assertEquals(false, asked.result.asObj()["ok"])
        assertEquals("close", Car.state.windows["front_left"])
        assertEquals("yes", BasicLanguage.normalizeYesNo("Ja", "de"))
        SpeechComposer.beginCarTurn()
        assertEquals(true, result("set_window", mapOf("window" to "driver", "position" to "open"))["ok"])
        assertEquals("open", Car.state.windows["front_left"])
    }
}
