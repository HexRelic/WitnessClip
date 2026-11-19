// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 HexRelic

package io.github.hexrelic.witnessclip

import android.content.Context
import java.util.Locale

/**
 * All user-facing text, English and Italian. Kept in code so the language can be switched
 * instantly and the PDF report can always print both.
 */
class Str(val italian: Boolean) {
    private fun t(en: String, it: String) = if (italian) it else en
    val locale: Locale get() = if (italian) Locale.ITALIAN else Locale.ENGLISH

    val appName = "WitnessClip"
    val tap get() = t("Tap to record", "Tocca per registrare")
    val tapSub get() = t("Sealed second by second, from the first moment", "Sigillata secondo per secondo, dal primo istante")
    val timeReady get() = t("Certified time ready", "Ora certificata pronta")
    val timeOffline get() = t("Offline · seals when online", "Offline · sigillo al ritorno online")
    val recent get() = t("Recent", "Recenti")
    val seeAll get() = t("See all", "Vedi tutte")
    val sealed get() = t("Sealed", "Sigillata")
    val pending get() = t("Pending", "In attesa")
    val pendingLong get() = t("seals when online", "sigillo al ritorno online")
    val recording get() = t("Recording", "Registrazione")
    val lossless get() = t("Lossless · 48 kHz", "Senza perdita · 48 kHz")
    val sealing get() = t("Sealing as you record", "Sigillo in corso mentre registri")
    fun secondsLocked(n: Int) = t("$n seconds locked · cannot be altered", "$n secondi bloccati · non modificabili")
    fun startCertified(time: String) = t("Start time certified $time", "Ora d'inizio certificata $time")
    val startChecking get() = t("Certifying start time…", "Certifico l'ora d'inizio…")
    val startOffline get() = t("Offline · start time from phone", "Offline · ora d'inizio dal telefono")
    val noGaps get() = t("No gaps", "Nessuna interruzione")
    val micSilenced get() = t("Microphone blocked by the system", "Microfono bloccato dal sistema")
    val keepsRunning get() = t("Keeps running when locked", "Continua a schermo bloccato")
    val noPause get() = t("A recording cannot be paused or edited. That is what makes it trustworthy.", "Una registrazione non si può mettere in pausa né modificare. È questo che la rende affidabile.")
    val mark get() = t("Mark moment", "Segna momento")
    val addNote get() = t("Add note", "Aggiungi nota")
    val holdToStop get() = t("Hold to stop", "Tieni premuto per fermare")
    val sealingNow get() = t("Sealing the recording…", "Sigillo la registrazione…")
    fun markedAt(at: String) = t("Moment marked at $at", "Momento segnato a $at")
    fun notedAt(at: String) = t("Note added at $at", "Nota aggiunta a $at")

    val noteTitle get() = t("Add a note", "Aggiungi una nota")
    val noteSub get() = t("Write what is happening right now. The note is saved with the exact time and sealed with the recording. It does not change the audio.", "Scrivi cosa sta succedendo adesso. La nota viene salvata con l'ora esatta e sigillata insieme alla registrazione. Non modifica l'audio.")
    val noteHint get() = t("E.g. who is present, where you are…", "Es. chi è presente, dove ti trovi…")
    val stillRecording get() = t("Still recording", "Registrazione in corso")
    val saveNote get() = t("Save note", "Salva nota")

    val micPhone get() = t("Phone microphone", "Microfono del telefono")
    val micPhoneSub get() = t("Recommended · strongest proof", "Consigliato · prova più solida")
    val micTitle get() = t("Microphone", "Microfono")
    val micSub get() = t("The one you choose is written into the seal.", "Quello che scegli viene scritto nel sigillo.")
    val micConnected get() = t("Connected", "Collegato")
    val micWarn get() = t("With an external microphone the recording is sealed in the same way, but the report will say the sound did not come from the phone's own microphone.", "Con un microfono esterno la registrazione è sigillata allo stesso modo, ma la relazione indicherà che il suono non proviene dal microfono del telefono.")

    val setupTitle get() = t("Allow uninterrupted recording", "Consenti la registrazione continua")
    val setupSub get() = t("So the phone never stops it to save battery.", "Così il telefono non la ferma mai per risparmiare batteria.")
    val allow get() = t("Allow", "Consenti")

    val levelHigh get() = t("Highest protection level", "Massimo livello di protezione")
    val levelMid get() = t("Sealed · with remarks", "Sigillata · con osservazioni")
    val levelPending get() = t("Sealed on this phone · waiting for certified time", "Sigillata sul telefono · in attesa dell'ora certificata")
    val levelFail get() = t("Verification failed", "Verifica fallita")
    val verdictOk get() = t("Sealed and verifiable", "Sigillata e verificabile")
    val verdictOkSub get() = t("Anyone can check this recording is original, without this app and without trusting us.", "Chiunque può controllare che la registrazione è originale, senza questa app e senza fidarsi di noi.")
    val verdictFail get() = t("This file does not match its seal", "Questo file non corrisponde al suo sigillo")
    val verifying get() = t("Checking the seal…", "Controllo il sigillo…")
    val markLegend get() = t("Marked moments", "Momenti segnati")
    val proves get() = t("What this proves", "Cosa dimostra")
    val honest get() = t("This does not prove that what was said is true, and it does not guarantee a court will admit it. Ask your lawyer.", "Questo non dimostra che quanto detto sia vero e non garantisce che un giudice la ammetta. Chiedi al tuo avvocato.")
    val details get() = t("Details", "Dettagli")
    val recorded get() = t("Recorded", "Registrata")
    val duration get() = t("Duration", "Durata")
    val microphone get() = t("Microphone", "Microfono")
    val deviceId get() = t("Device ID", "ID dispositivo")
    val fileSize get() = t("File size", "Dimensione")
    val notesMarks get() = t("Notes and marks", "Note e segni")
    val markedMoment get() = t("Marked moment", "Momento segnato")
    val saveToPhone get() = t("Save to phone", "Salva sul telefono")
    val forCourt get() = t("For court", "Per il tribunale")
    val technical get() = t("Technical details", "Dettagli tecnici")
    val delete get() = t("Delete recording", "Elimina registrazione")
    val deleteAsk get() = t("Delete this recording for good? This cannot be undone.", "Eliminare definitivamente questa registrazione? Non si può annullare.")
    val cancel get() = t("Cancel", "Annulla")
    val close get() = t("Close", "Chiudi")
    val ok get() = "OK"

    /** Plain-language title and explanation for a verifier check id + status. */
    fun check(id: String): Pair<String, String> = when (id) {
        "audio" -> t("Not edited", "Non modificata") to t("Every second is locked. Changing one instant breaks the seal.", "Ogni secondo è bloccato. Cambiare un istante rompe il sigillo.")
        "time" -> t("Certain date and time", "Data e ora certe") to t("Certified by an EU-qualified time authority.", "Certificate da un'autorità di marcatura temporale qualificata UE.")
        "continuity" -> t("Nothing cut out", "Nessun taglio") to t("Continuous from start to stop. No pauses, no gaps.", "Continua dall'inizio alla fine. Nessuna pausa, nessun vuoto.")
        "device" -> t("Identified device", "Dispositivo identificato") to t("Signed by the device identified by its Device ID.", "Firmata dal dispositivo identificato dal suo ID dispositivo.")
        "device-attested" -> t("This phone, official app", "Questo telefono, app ufficiale") to t("Recorded on an unmodified phone, identified by its Device ID.", "Registrata su un telefono non modificato, identificato dal suo ID dispositivo.")
        else -> id to ""
    }
    val timeUnlisted get() = t("A timestamp in this file does not come from an EU-recognised authority and is not counted.", "Una marca temporale in questo file non proviene da un'autorità riconosciuta nell'UE e non viene considerata.")
    val timePendingSub get() = t("Waiting for internet to get the certified time.", "In attesa di internet per ottenere l'ora certificata.")
    val timeOpenMissing get() = t("End time certified. Start time comes from the phone (recorded offline).", "Ora di fine certificata. L'ora d'inizio viene dal telefono (registrata offline).")
    val continuityWarn get() = t("An interruption happened and is declared in the seal.", "C'è stata un'interruzione, dichiarata nel sigillo.")
    val audioFail get() = t("The audio or its proof has been changed.", "L'audio o la sua prova sono stati modificati.")
    val timeFail get() = t("A time certificate is not valid.", "Una certificazione dell'ora non è valida.")
    val deviceFail get() = t("The device signature is not valid.", "La firma del dispositivo non è valida.")
    val levelSealed get() = t("Sealed · certified time", "Sigillata · ora certificata")
    val legal get() = t("Legal", "Note legali")
    val externalMic get() = t("Recorded with an external microphone.", "Registrata con un microfono esterno.")
    val statusOk get() = t("VERIFIED", "VERIFICATO")
    val statusNote get() = t("NOTE", "NOTA")
    val statusFail get() = t("FAILED", "FALLITA")

    val dashboard get() = t("Dashboard", "Riepilogo")
    val recordings get() = t("Recordings", "Registrazioni")
    val totalTime get() = t("Total time", "Tempo totale")
    val spaceUsed get() = t("Space used", "Spazio usato")
    val last7 get() = t("Minutes recorded · last 7 days", "Minuti registrati · ultimi 7 giorni")
    val protection get() = t("Protection", "Protezione")
    fun fullySealed(n: Int) = t("$n fully sealed", "$n sigillate")
    fun waitingNet(n: Int) = t("$n waiting for internet", "$n in attesa di internet")
    val allRecordings get() = t("All recordings", "Tutte le registrazioni")
    val empty get() = t("Nothing recorded yet", "Ancora nessuna registrazione")

    val settings get() = t("Settings", "Impostazioni")
    val yourDeviceId get() = t("Your Device ID", "Il tuo ID dispositivo")
    val deviceIdSub get() = t("Unique to this phone and locked in its secure chip. It appears in every recording so a court can tell which device made it. It contains no personal data. The complete 64-character value is printed in every report.", "Unico per questo telefono e custodito nel chip sicuro. Compare in ogni registrazione così il giudice sa quale dispositivo l'ha prodotta. Non contiene dati personali. Il valore completo di 64 caratteri è stampato in ogni relazione.")
    val copied get() = t("Device ID copied", "ID dispositivo copiato")
    val appearance get() = t("Appearance", "Aspetto")
    val theme get() = t("Theme", "Tema")
    val system get() = t("System", "Sistema")
    val light get() = t("Light", "Chiaro")
    val dark get() = t("Dark", "Scuro")
    val language get() = t("Language", "Lingua")
    val uninterrupted get() = t("Uninterrupted recording", "Registrazione continua")
    val uninterruptedOn get() = t("Battery limits removed for this app", "Limiti di batteria rimossi per questa app")
    val uninterruptedOff get() = t("The phone may stop a long recording. Tap to fix.", "Il telefono potrebbe fermare una registrazione lunga. Tocca per risolvere.")
    val active get() = t("Active", "Attiva")
    val liveWave get() = t("Live waveform", "Onda sonora dal vivo")
    val liveWaveSub get() = t("Show the sound while recording", "Mostra il suono durante la registrazione")
    val keepOn get() = t("Keep screen on", "Schermo sempre acceso")
    val keepOnSub get() = t("While recording", "Durante la registrazione")
    val quality get() = t("Audio quality", "Qualità audio")
    val qualitySub get() = t("Lossless, always. Cannot be lowered.", "Senza perdita, sempre. Non si può ridurre.")
    val security get() = t("Security", "Sicurezza")
    val certTime get() = t("Certified time", "Ora certificata")
    val certTimeSub get() = t("Automatic · free · EU-qualified", "Automatica · gratuita · qualificata UE")
    val certTimeBody get() = t(
        "When you start and when you stop, the app asks independent EU time authorities to certify the moment. Only a fingerprint of the recording is sent, never the audio.\n\nProviders, in order:",
        "All'inizio e alla fine, l'app chiede a autorità di marcatura temporale UE indipendenti di certificare il momento. Viene inviata solo un'impronta della registrazione, mai l'audio.\n\nFornitori, in ordine:")
    val about get() = t("About", "Informazioni")
    val howSeal get() = t("How the seal works", "Come funziona il sigillo")
    val howSealBody get() = t(
        "While you record, every second of sound gets a mathematical fingerprint. Each fingerprint is chained to the ones before it, so nothing can be changed, removed or inserted later without breaking the chain.\n\nA key locked inside your phone's secure chip signs the chain every ten seconds. The key cannot be copied out of the phone.\n\nAn independent, EU-qualified time authority certifies when the recording started and when it ended.\n\nAll of this proof is stored inside the audio file itself. Anyone can check it with free, standard tools, without this app.",
        "Mentre registri, ogni secondo di suono riceve un'impronta matematica. Ogni impronta è concatenata alle precedenti, quindi nulla può essere cambiato, tolto o inserito in seguito senza rompere la catena.\n\nUna chiave custodita nel chip sicuro del telefono firma la catena ogni dieci secondi. La chiave non può essere copiata fuori dal telefono.\n\nUn'autorità di marcatura temporale indipendente, qualificata UE, certifica quando la registrazione è iniziata e quando è finita.\n\nTutta questa prova è conservata dentro il file audio stesso. Chiunque può controllarla con strumenti standard gratuiti, senza questa app.")
    val source get() = t("Source code and licence", "Codice sorgente e licenza")
    val sourceSub get() = t("Public and auditable", "Pubblico e verificabile")
    val sourceBody get() = t("The complete source code is public so that anyone, including a court expert, can check exactly what this app does. Free software under the GNU GPL, version 3 or later.", "Il codice sorgente completo è pubblico, così chiunque, anche un perito, può controllare esattamente cosa fa questa app. Software libero con licenza GNU GPL, versione 3 o successiva.")

    val verifyTitle get() = t("Check a recording", "Controlla una registrazione")
    val verifyIntro get() = t("Received a recording? Open it here to see if it is original. Works offline.", "Hai ricevuto una registrazione? Aprila qui per sapere se è originale. Funziona offline.")
    val chooseFile get() = t("Choose a file", "Scegli un file")
    val original get() = t("Original", "Originale")
    val notOriginal get() = t("Not valid", "Non valida")
    val verifyOk get() = t("This recording has not been altered", "Questa registrazione non è stata alterata")
    val fromDevice get() = t("from device", "dal dispositivo")
    val verifyFoot get() = t("The same check can be done on any computer with free standard tools. Instructions are inside every package.", "Lo stesso controllo si può fare su qualsiasi computer con strumenti standard gratuiti. Le istruzioni sono in ogni pacchetto.")

    val navRecord get() = t("Record", "Registra")
    val exportTitle get() = t("Prepare evidence", "Prepara la prova")
    val exportSub get() = t("The proof travels inside the file. Copying it to a PC or USB stick cannot break it.", "La prova viaggia dentro il file. Copiarlo su PC o chiavetta USB non può romperla.")
    val exFull get() = t("Complete package", "Pacchetto completo")
    val exFullSub get() = t("Audio, proof and report in one file", "Audio, prova e relazione in un solo file")
    val exSplit get() = t("Split for online court filing", "Diviso per il deposito telematico")
    val exSplitSub get() = t("Parts under 25 MB, each one verifiable", "Parti sotto i 25 MB, ognuna verificabile")
    val exPdf get() = t("Includes a PDF report in Italian and English", "Include una relazione PDF in italiano e inglese")
    val share get() = t("Share", "Condividi")
    val preparing get() = t("Preparing…", "Preparazione…")
    val saved get() = t("Saved", "Salvato")
    val failed get() = t("Something went wrong", "Qualcosa è andato storto")
    val needMic get() = t("Microphone permission is needed to record.", "Serve il permesso del microfono per registrare.")
    val lowSpace get() = t("Not enough free space to record.", "Spazio libero insufficiente per registrare.")

    val unlockTitle get() = t("Unlock to continue", "Sblocca per continuare")
    val unlock get() = t("Unlock", "Sblocca")
    val lockedSub get() = t("The app is locked. A recording in progress keeps running.", "L'app è bloccata. Una registrazione in corso continua.")
    val appLock get() = t("App lock", "Blocco app")
    val appLockSub get() = t("Fingerprint, face or phone PIN to open. Also hides the app from screenshots.", "Impronta, volto o PIN del telefono per aprire. Nasconde anche l'app dagli screenshot.")
    val appLockNeedsPin get() = t("Set a screen lock on the phone first.", "Imposta prima un blocco schermo sul telefono.")
    val discreet get() = t("Discreet screen", "Schermo discreto")
    val discreetSub get() = t("Black screen while recording. Double tap to show the controls.", "Schermo nero durante la registrazione. Doppio tocco per mostrare i comandi.")
    val discreetHint get() = t("double tap", "doppio tocco")
    val stereo get() = t("Stereo", "Stereo")
    val stereoSub get() = t("Two channels if the phone has two microphones. Files are about twice as large.", "Due canali se il telefono ha due microfoni. I file sono grandi circa il doppio.")
    val location get() = t("Add location", "Aggiungi posizione")
    val locationSub get() = t("Writes where the phone says it is into the proof, at start and stop. Declared by the phone, not certified.", "Scrive nella prova dove il telefono dice di trovarsi, all'inizio e alla fine. Dichiarata dal telefono, non certificata.")
    val locationDenied get() = t("Location permission is needed for this.", "Per questo serve il permesso di posizione.")
    val position get() = t("Position (declared by phone)", "Posizione (dichiarata dal telefono)")
    val positionNone get() = t("Location was on, but the phone gave no position.", "La posizione era attiva, ma il telefono non ha fornito alcuna posizione.")
    val channelsLabel get() = t("Channels", "Canali")
    val saveFolder get() = t("Save folder", "Cartella di salvataggio")
    val askEveryTime get() = t("Ask every time", "Chiedi ogni volta")
    fun savedIn(folder: String) = t("Saved in $folder", "Salvato in $folder")
    val rename get() = t("Name this recording", "Dai un nome alla registrazione")
    val renameHint get() = t("E.g. Meeting with manager", "Es. Riunione con il responsabile")
    val renameSub get() = t("The name is only a label in this app. It is not part of the sealed proof.", "Il nome è solo un'etichetta in questa app. Non fa parte della prova sigillata.")
    val save get() = t("Save", "Salva")
    val micBluetoothSub get() = t("Bluetooth · lower sound quality", "Bluetooth · qualità audio inferiore")
    fun midStamps(n: Int) = t("$n certified along the way", "$n certificazioni durante la registrazione")
    val batteryGuide get() = t("Battery settings for this phone", "Impostazioni batteria per questo telefono")
    fun batteryGuideCard(brand: String) = t("One more step on $brand phones", "Un altro passaggio sui telefoni $brand")
    val batteryGuideCardSub get() = t("So long recordings are never stopped.", "Così le registrazioni lunghe non vengono mai fermate.")
    val show get() = t("Show", "Mostra")
    val openAppSettings get() = t("Open app settings", "Apri impostazioni app")
    val done get() = t("Done", "Fatto")
    val guideFoot get() = t("Menu names can differ slightly between versions.", "I nomi dei menu possono variare leggermente tra le versioni.")
    val guideSamsung get() = t(
        "Samsung phones can put apps to sleep even when battery use is unrestricted.\n\n1. Settings → Apps → WitnessClip → Battery → choose Unrestricted.\n\n2. Settings → Battery → Background usage limits → Never sleeping apps → add this app.\n\n3. Open the recent apps screen, tap this app's icon at the top of its card and choose Keep open.",
        "I telefoni Samsung possono sospendere le app anche con uso batteria senza restrizioni.\n\n1. Impostazioni → Applicazioni → WitnessClip → Batteria → scegli Senza restrizioni.\n\n2. Impostazioni → Batteria → Limiti utilizzo in background → App mai sospese → aggiungi questa app.\n\n3. Apri la schermata delle app recenti, tocca l'icona di questa app in cima alla sua scheda e scegli Mantieni aperta.")
    val guideOppo get() = t(
        "OPPO and OnePlus phones close background apps aggressively.\n\n1. Settings → Apps → App management → WitnessClip → Battery usage → turn on Allow background activity.\n\n2. On the same page, turn on Allow auto-launch.\n\n3. Settings → Battery → More settings → Optimise battery use → this app → Don't optimise.\n\n4. Open the recent apps screen, tap the menu (⋮) on this app's card and choose Lock.",
        "I telefoni OPPO e OnePlus chiudono le app in background in modo aggressivo.\n\n1. Impostazioni → App → Gestione app → WitnessClip → Utilizzo batteria → attiva Consenti attività in background.\n\n2. Nella stessa pagina, attiva Consenti avvio automatico.\n\n3. Impostazioni → Batteria → Altre impostazioni → Ottimizza uso batteria → questa app → Non ottimizzare.\n\n4. Apri la schermata delle app recenti, tocca il menu (⋮) sulla scheda di questa app e scegli Blocca.")

    val chRecording get() = t("Recording in progress", "Registrazione in corso")
    val chAlerts get() = t("Recording alerts", "Avvisi di registrazione")
    val notifTitle get() = t("Recording", "Registrazione in corso")
    val notifText get() = t("Sealing every second. Open the app to stop.", "Sigillo ogni secondo. Apri l'app per fermare.")
    val alertSilentTitle get() = t("Microphone blocked", "Microfono bloccato")
    val alertSilentText get() = t("Another app or a call is using the microphone. The recording stays open and continues automatically.", "Un'altra app o una chiamata sta usando il microfono. La registrazione resta aperta e riprende da sola.")
    val alertFailTitle get() = t("Recording ended", "Registrazione terminata")
    val alertFailText get() = t("The phone could not keep saving the audio. Everything recorded so far has been kept and sealed.", "Il telefono non riusciva più a salvare l'audio. Tutto ciò che è stato registrato finora è conservato e sigillato.")
    val alertSpaceTitle get() = t("Storage almost full", "Memoria quasi piena")
    val alertSpaceText get() = t("Free some space or the recording will have to end.", "Libera spazio o la registrazione dovrà terminare.")

    companion object {
        fun current(ctx: Context): Str {
            val pref = (ctx.applicationContext as App).prefs.lang
            return Str(if (pref == "system") Locale.getDefault().language == "it" else pref == "it")
        }
    }
}
