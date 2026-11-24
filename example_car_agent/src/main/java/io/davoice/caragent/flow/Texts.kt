package io.davoice.caragent.flow

// What the app itself says, per language (the same sentences as the React Native and Flutter example_car_agent).

/** "Didn't catch that" for speech noise (English for the others). */
val didNotCatchTexts = mapOf(
    "en" to "Sorry, I didn't catch that. Could you say it again?",
    "pt-BR" to "Desculpe, não entendi o que você disse. Pode repetir, por favor?",
    "pt-PT" to "Desculpe, não percebi. Pode repetir?",
)

/** What the car demo says for a request this demo can't do (English for the others). */
val notInDemoTexts = mapOf(
    "en" to "This is not available in this demo, to make it available please contact info@davoice.io",
    "pt-BR" to "Isso não está disponível nesta demonstração. Para disponibilizar, entre em contato com info@davoice.io",
    "pt-PT" to "Isto não está disponível nesta demonstração. Para o disponibilizar, contacte info@davoice.io",
    "he" to "זה לא זמין בהדגמה הזאת. כדי לקבל את זה, צרו איתנו קשר בכתובת info@davoice.io",
    "da" to "Det er ikke tilgængeligt i denne demo. Kontakt info@davoice.io for at få det.",
    "sv" to "Det är inte tillgängligt i den här demon. Kontakta info@davoice.io för att få det.",
    "el" to "Αυτό δεν είναι διαθέσιμο σε αυτή την επίδειξη. Για να το αποκτήσετε, επικοινωνήστε με το info@davoice.io.",
)

/** A handed-over request when no remote model is set up (English for the others). */
val noRemoteTexts = mapOf(
    "en" to "I need to send this to a remote Model. To make this work, please setup a remote model in the App.",
    "pt-BR" to "Preciso enviar isso para um modelo remoto. Para isso funcionar, configure um modelo remoto no aplicativo.",
    "pt-PT" to "Preciso de enviar isto para um modelo remoto. Para isto funcionar, configure um modelo remoto na aplicação.",
    "he" to "אני צריך לשלוח את זה למודל מרוחק. כדי שזה יעבוד, הגדירו מודל מרוחק באפליקציה.",
    "da" to "Jeg skal sende det her til en ekstern model. Konfigurer en ekstern model i appen, så det virker.",
    "sv" to "Jag behöver skicka det här till en fjärrmodell. Ställ in en fjärrmodell i appen så att det fungerar.",
    "el" to "Πρέπει να το στείλω σε ένα απομακρυσμένο μοντέλο. Για να λειτουργήσει, ρυθμίστε ένα απομακρυσμένο μοντέλο στην εφαρμογή.",
)

/** Local confidence held a command back and no remote agent is set up. */
val lowConfidenceTexts = mapOf(
    "en" to "For this command I need a remote agent.",
    "pt-BR" to "Para esse comando eu preciso de um agente remoto.",
)

/** General questions ("who is the president", "tell me a joke"): no remote assistant in this demo. */
val generalQuestionsMessage = mapOf(
    "en" to "General questions go to a remote assistant, which isn't set up in this demo. I can help you with the car.",
    "pt-BR" to "Perguntas gerais vão para um assistente remoto, que não está configurado nesta demonstração. Posso te ajudar com o carro.",
)
