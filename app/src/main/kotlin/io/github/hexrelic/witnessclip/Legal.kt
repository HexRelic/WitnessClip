// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 HexRelic

package io.github.hexrelic.witnessclip

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hexrelic.witnessclip.ui.*

/** Publisher details used in the legal pages. */
object Project {
    const val PUBLISHER = "HexRelic"
    const val CONTACT = "roundish-old-comic@duck.com"
    const val REPO = "https://github.com/HexRelic/WitnessClip"
    /** Date the privacy policy and terms were last changed. */
    const val UPDATED_EN = "18 November 2026"
    const val UPDATED_IT = "18 novembre 2026"
    /** Date the third-party licences were last checked (see docs/DEPENDENCIES.md). */
    const val CHECKED_EN = "15 November 2026"
    const val CHECKED_IT = "15 novembre 2026"
}

enum class Doc { THIRD_PARTY, TERMS, PRIVACY, LICENCE, AI_USE }

private const val BC_NOTICE = """Copyright (c) 2000-2024 The Legion of the Bouncy Castle Inc. (https://www.bouncycastle.org).

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sub license, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions: The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE."""

/** Lines starting with "## " are headings, "@asset:name" inserts a bundled text file, the rest are paragraphs. */
object Legal {
    fun title(doc: Doc, it: Boolean) = when (doc) {
        Doc.THIRD_PARTY -> if (it) "Software di terze parti" else "Third-party software"
        Doc.TERMS -> if (it) "Condizioni d'uso" else "Terms of use"
        Doc.PRIVACY -> if (it) "Informativa sulla privacy" else "Privacy policy"
        Doc.LICENCE -> if (it) "Licenza" else "Licence"
        Doc.AI_USE -> if (it) "Uso dell'intelligenza artificiale" else "Use of AI"
    }

    fun text(doc: Doc, it: Boolean): String = when (doc) {
        Doc.PRIVACY -> if (it) PRIVACY_IT else PRIVACY_EN
        Doc.TERMS -> if (it) TERMS_IT else TERMS_EN
        Doc.LICENCE -> if (it) LICENCE_IT else LICENCE_EN
        Doc.THIRD_PARTY -> if (it) THIRD_IT else THIRD_EN
        Doc.AI_USE -> if (it) AI_IT else AI_EN
    }

    private val PRIVACY_EN = """
Last updated: ${Project.UPDATED_EN}

## In short
This app has no account, no server of ours, no advertising, no analytics and no crash reporting. Your recordings stay on your phone. We never receive them and cannot access them.

## Who is responsible
Publisher of the app: ${Project.PUBLISHER}
Contact for privacy questions and requests: ${Project.CONTACT}

## What is stored on your phone
• Your recordings, with the notes and marks you add.
• The proof attached to each recording: time, duration, microphone used, phone make and model, Android version, and the Device ID.
• Your settings (theme, language, microphone, save folder).
• Only if you switch on "Add location": the position your phone reports when a recording starts and stops, written into that recording's proof. It is off unless you turn it on.
All of this is kept in the app's private storage, is not included in Android backups, and is removed when you delete a recording or uninstall the app.

## The Device ID
It is the fingerprint of a random key created inside your phone when the app is first used. It is not your phone number, IMEI or any hardware serial number, and it contains no personal data. It changes if you reinstall the app. It is written into every recording so that a court can tell which device made it.

## What leaves your phone
Only one thing, and only to certify the date and time: a short mathematical fingerprint (SHA-256) of the recording's proof, sent when you start, when you stop and, for long recordings, about every five minutes in between. The audio never leaves your phone, and the fingerprint cannot be turned back into audio.
The fingerprint is sent to independent time authorities in the European Union:
• Sectigo (Europe) SL, Spain: https://www.sectigo.com
• ACCV, Spain: https://www.accv.es
• Izenpe S.A., Spain: https://www.izenpe.eus
Like any internet service, they see the IP address the request comes from and the time. They act as independent controllers under their own privacy policies. If you record without internet, nothing is sent until the connection returns.

## Permissions
• Microphone: to record.
• Notifications: to show that a recording is in progress and to alert you of problems.
• Running in the foreground and unrestricted battery use: so a recording is not stopped when the screen is off.
• Internet: only for the time certification described above.
• Location (optional): only if you switch on "Add location". The position stays inside the recording on your phone; it is never sent to us or to the time authorities.
• Bluetooth and audio settings: only to use a Bluetooth headset as microphone, if you choose one.
• Fingerprint, face or PIN (optional app lock): checked by your phone itself. The app never sees or stores them.

## When you export or share
You decide who receives a file. From that moment the recipient is responsible for it.

## Other people's voices
A recording contains the voices of the people you speak with, which is their personal data. You are responsible for how you use it. Record only conversations you take part in, and use the recording only to assert or defend your rights, with your lawyer or before an authority. Do not publish it or pass it around.

## Your rights
Under the GDPR (articles 15 to 22) you can ask for access, correction, deletion, restriction, portability and you can object. Because we hold none of your data, you exercise these rights directly on your phone by deleting recordings or the app. You can still write to ${Project.CONTACT} for any question. You may also lodge a complaint with the Italian data protection authority: https://www.garanteprivacy.it

## Children
The app is not intended for children under 14.

## Changes
If this policy changes, the new version will be published in the app and in the source repository: ${Project.REPO}
""".trim()

    private val PRIVACY_IT = """
Ultimo aggiornamento: ${Project.UPDATED_IT}

## In breve
Questa app non ha account, non ha nostri server, non ha pubblicità, statistiche d'uso o segnalazioni automatiche di errori. Le registrazioni restano sul tuo telefono. Noi non le riceviamo mai e non possiamo accedervi.

## Chi è il responsabile
Editore dell'app: ${Project.PUBLISHER}
Contatto per domande e richieste sulla privacy: ${Project.CONTACT}

## Cosa viene conservato sul telefono
• Le tue registrazioni, con le note e i segni che aggiungi.
• La prova allegata a ogni registrazione: ora, durata, microfono usato, marca e modello del telefono, versione di Android e ID dispositivo.
• Le tue impostazioni (tema, lingua, microfono, cartella di salvataggio).
• Solo se attivi «Aggiungi posizione»: la posizione che il telefono indica all'inizio e alla fine di una registrazione, scritta nella prova di quella registrazione. È disattivata finché non la attivi tu.
Tutto questo resta nella memoria privata dell'app, non è incluso nei backup di Android e viene rimosso quando elimini una registrazione o disinstalli l'app.

## L'ID dispositivo
È l'impronta di una chiave casuale creata dentro il telefono al primo utilizzo dell'app. Non è il tuo numero di telefono, l'IMEI né un numero di serie, e non contiene dati personali. Cambia se reinstalli l'app. Viene scritto in ogni registrazione perché il giudice possa sapere quale dispositivo l'ha prodotta.

## Cosa esce dal telefono
Una sola cosa, e solo per certificare data e ora: una breve impronta matematica (SHA-256) della prova della registrazione, inviata all'inizio, alla fine e, nelle registrazioni lunghe, circa ogni cinque minuti. L'audio non lascia mai il telefono e dall'impronta non è possibile ricostruire l'audio.
L'impronta è inviata ad autorità di marcatura temporale indipendenti nell'Unione europea:
• Sectigo (Europe) SL, Spagna: https://www.sectigo.com
• ACCV, Spagna: https://www.accv.es
• Izenpe S.A., Spagna: https://www.izenpe.eus
Come ogni servizio internet, vedono l'indirizzo IP da cui parte la richiesta e l'ora. Agiscono come titolari autonomi secondo le proprie informative. Se registri senza internet, nulla viene inviato finché la connessione non ritorna.

## Permessi
• Microfono: per registrare.
• Notifiche: per mostrare che una registrazione è in corso e avvisarti di problemi.
• Esecuzione in primo piano e uso della batteria senza restrizioni: perché la registrazione non venga fermata a schermo spento.
• Internet: solo per la certificazione dell'ora descritta sopra.
• Posizione (facoltativa): solo se attivi «Aggiungi posizione». La posizione resta dentro la registrazione sul tuo telefono; non viene mai inviata a noi né alle autorità di marcatura temporale.
• Bluetooth e impostazioni audio: solo per usare un auricolare Bluetooth come microfono, se ne scegli uno.
• Impronta, volto o PIN (blocco app facoltativo): verificati dal telefono stesso. L'app non li vede e non li conserva.

## Quando esporti o condividi
Sei tu a decidere chi riceve un file. Da quel momento ne risponde chi lo riceve.

## Le voci delle altre persone
Una registrazione contiene le voci delle persone con cui parli, che sono loro dati personali. Sei responsabile di come la usi. Registra solo conversazioni a cui partecipi e usa la registrazione solo per far valere o difendere i tuoi diritti, con il tuo avvocato o davanti a un'autorità. Non pubblicarla e non diffonderla.

## I tuoi diritti
In base al GDPR (articoli da 15 a 22) puoi chiedere accesso, rettifica, cancellazione, limitazione, portabilità e puoi opporti. Poiché non deteniamo alcun tuo dato, eserciti questi diritti direttamente sul telefono, eliminando le registrazioni o l'app. Puoi comunque scrivere a ${Project.CONTACT} per qualsiasi domanda. Puoi anche presentare reclamo al Garante per la protezione dei dati personali: https://www.garanteprivacy.it

## Minori
L'app non è destinata a minori di 14 anni.

## Modifiche
Se questa informativa cambia, la nuova versione sarà pubblicata nell'app e nel repository del codice sorgente: ${Project.REPO}
""".trim()

    private val TERMS_EN = """
Last updated: ${Project.UPDATED_EN}

## What the app is for
The app records conversations you take part in and seals them so that anyone can later check that the file has not been altered.

## Rules of use
• Record only conversations in which you are present and taking part.
• Never leave the phone recording where you are not present.
• Do not publish the recordings or pass them to people who have no reason to receive them. Use them only to assert or defend your rights, with your lawyer or before an authority.
• You are responsible for making sure your use of the app complies with the law that applies to you.
• Do not use the app to harass, threaten or control anyone.

## What the app does not promise
• It proves that a file is original. It does not prove that what was said is true.
• It does not guarantee that a court will admit or give weight to a recording. That decision belongs to the judge.
• The app gives no legal advice. Ask a lawyer about your case.

## Your recordings are in your hands
Recordings exist only on your phone. If you lose the phone, reset it or uninstall the app, they are gone. Save a copy of anything important with "Save to phone" and keep it somewhere safe.

## No warranty
The app is provided free of charge, as it is, without warranties of any kind, to the extent permitted by law. Rights that the law grants to consumers and cannot be excluded remain unaffected.

## Licence
WitnessClip is free software under the GNU General Public License, version 3 or later. Its source code is public. The name and icon are trademarks. See the Licence page.

## Contact
${Project.CONTACT}
Source code: ${Project.REPO}
""".trim()

    private val TERMS_IT = """
Ultimo aggiornamento: ${Project.UPDATED_IT}

## A cosa serve l'app
L'app registra le conversazioni a cui partecipi e le sigilla, in modo che chiunque possa poi controllare che il file non sia stato alterato.

## Regole d'uso
• Registra solo conversazioni in cui sei presente e a cui partecipi.
• Non lasciare mai il telefono a registrare dove non sei presente.
• Non pubblicare le registrazioni e non consegnarle a chi non ha motivo di riceverle. Usale solo per far valere o difendere i tuoi diritti, con il tuo avvocato o davanti a un'autorità.
• Sei responsabile di verificare che il tuo uso dell'app rispetti la legge che si applica a te.
• Non usare l'app per molestare, minacciare o controllare altre persone.

## Cosa l'app non promette
• Dimostra che un file è originale. Non dimostra che quanto detto sia vero.
• Non garantisce che un giudice ammetta la registrazione o le dia peso. La decisione spetta al giudice.
• L'app non fornisce consulenza legale. Per il tuo caso rivolgiti a un avvocato.

## Le registrazioni sono nelle tue mani
Le registrazioni esistono solo sul tuo telefono. Se perdi il telefono, lo ripristini o disinstalli l'app, vanno perse. Salva una copia di ciò che è importante con «Salva sul telefono» e conservala in un luogo sicuro.

## Nessuna garanzia
L'app è fornita gratuitamente, così com'è, senza garanzie di alcun tipo, nei limiti consentiti dalla legge. Restano salvi i diritti che la legge riconosce ai consumatori e che non possono essere esclusi.

## Licenza
WitnessClip è software libero, con licenza GNU General Public License versione 3 o successiva. Il codice sorgente è pubblico. Nome e icona sono marchi. Vedi la pagina Licenza.

## Contatti
${Project.CONTACT}
Codice sorgente: ${Project.REPO}
""".trim()

    private val LICENCE_EN = """
## In plain words
• WitnessClip is free software. Anyone may use it, for any purpose.
• The complete source code is public, so that anyone, including a court-appointed expert, can check exactly what the app does.
• You may study, modify and share it. If you distribute a modified version, you must publish its source code under the same licence.
• The name WitnessClip and its icon are trademarks: a modified version must use a different name and icon.
• The software comes with no warranty.

Source code: ${Project.REPO}
Official licence page: https://www.gnu.org/licenses/gpl-3.0.html

Copyright © 2026 ${Project.PUBLISHER}. This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version. This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.

The text below is the licence itself. It is published in English only; the summary above has no legal effect.

## GNU General Public License, version 3
@asset:gpl-3.0.txt
""".trim()

    private val LICENCE_IT = """
## In parole semplici
• WitnessClip è software libero. Chiunque può usarlo, per qualsiasi scopo.
• Il codice sorgente completo è pubblico, così chiunque, anche un consulente nominato dal giudice, può controllare esattamente cosa fa l'app.
• Puoi studiarlo, modificarlo e condividerlo. Se distribuisci una versione modificata, devi pubblicarne il codice sorgente con la stessa licenza.
• Il nome WitnessClip e la sua icona sono marchi: una versione modificata deve usare un nome e un'icona diversi.
• Il software è fornito senza garanzia.

Codice sorgente: ${Project.REPO}
Pagina ufficiale della licenza: https://www.gnu.org/licenses/gpl-3.0.html

Copyright © 2026 ${Project.PUBLISHER}. Questo programma è software libero: puoi ridistribuirlo e/o modificarlo secondo i termini della GNU General Public License pubblicata dalla Free Software Foundation, versione 3 o (a tua scelta) qualsiasi versione successiva. Il programma è distribuito nella speranza che sia utile, ma SENZA ALCUNA GARANZIA.

Il testo qui sotto è la licenza vera e propria. È pubblicata solo in inglese; il riassunto qui sopra non ha valore legale.

## GNU General Public License, versione 3 (testo originale)
@asset:gpl-3.0.txt
""".trim()

    private val AI_EN = """
The app has been developed by me, ${Project.PUBLISHER} (a human), with the aid of frontier large language models (LLMs) to speed up development, testing and patching.

## No AI inside the app
The app contains no artificial intelligence and none is used on your recordings. The audio is never analysed, transcribed, enhanced or altered: what the microphone captures is exactly what gets sealed.

The source code is public, so anyone can check this: ${Project.REPO}
""".trim()

    private val AI_IT = """
L'app è stata sviluppata da me, ${Project.PUBLISHER} (un essere umano), con l'aiuto di modelli linguistici di frontiera (LLM) per velocizzare sviluppo, test e correzioni.

## Nessuna IA dentro l'app
L'app non contiene intelligenza artificiale e nessuna viene usata sulle tue registrazioni. L'audio non viene mai analizzato, trascritto, migliorato o alterato: ciò che il microfono cattura è esattamente ciò che viene sigillato.

Il codice sorgente è pubblico, quindi chiunque può verificarlo: ${Project.REPO}
""".trim()

    private val COMPONENTS = """
• AndroidX and Jetpack Compose (The Android Open Source Project): https://developer.android.com/jetpack · Apache License 2.0
• Kotlin standard library, kotlinx.coroutines, kotlinx.serialization (JetBrains): https://kotlinlang.org · Apache License 2.0
• Bouncy Castle cryptography library (The Legion of the Bouncy Castle): https://www.bouncycastle.org · Bouncy Castle Licence (MIT)
• JSpecify, JetBrains annotations, Guava ListenableFuture: Apache License 2.0
• Liberation Sans font, used only inside the PDF reports (Google, Red Hat): https://github.com/liberationfonts · SIL Open Font License 1.1
"""

    private val THIRD_EN = """
Checked on ${Project.CHECKED_EN}.

## What is inside the app
The app includes only free and open-source components, all compatible with its own licence (GPL-3.0-or-later). No advertising, analytics or proprietary library is included.
$COMPONENTS
The complete list, with the version and licence of every single component and how it was checked, is in the file docs/DEPENDENCIES.md of the source code: ${Project.REPO}

## What is not from third parties
The interface icons and the audio sealing code were written for this app. On screen the app uses the fonts already on your phone. The audio format is FLAC, an open and royalty-free format.

## Bouncy Castle notice
$BC_NOTICE

## SIL Open Font License 1.1 (Liberation Sans)
@asset:ofl-1.1-liberation.txt

## Apache License 2.0
@asset:apache-2.0.txt
""".trim()

    private val THIRD_IT = """
Verificato il ${Project.CHECKED_IT}.

## Cosa c'è dentro l'app
L'app include solo componenti liberi e open source, tutti compatibili con la sua licenza (GPL-3.0 o successiva). Non contiene librerie pubblicitarie, di statistiche o proprietarie.
$COMPONENTS
L'elenco completo, con versione e licenza di ogni singolo componente e il metodo di verifica, è nel file docs/DEPENDENCIES.md del codice sorgente: ${Project.REPO}

## Cosa non è di terze parti
Le icone dell'interfaccia e il codice che sigilla l'audio sono stati scritti per questa app. Sullo schermo l'app usa i font già presenti sul telefono. Il formato audio è FLAC, un formato aperto e libero da royalty.

## Avviso Bouncy Castle (testo originale)
$BC_NOTICE

## SIL Open Font License 1.1 (Liberation Sans, testo originale)
@asset:ofl-1.1-liberation.txt

## Apache License 2.0 (testo originale)
@asset:apache-2.0.txt
""".trim()
}

private val LINK = Regex("""https?://[^\s)]+[^\s).,;]|[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[a-z]{2,}""")

@Composable
private fun Paragraph(text: String, small: Boolean = false) {
    val color = P.seal
    val annotated = remember(text, color) {
        buildAnnotatedString {
            var last = 0
            for (m in LINK.findAll(text)) {
                append(text.substring(last, m.range.first))
                val target = if (m.value.contains("://")) m.value else "mailto:${m.value}"
                withLink(LinkAnnotation.Url(target, TextLinkStyles(SpanStyle(color = color, textDecoration = TextDecoration.Underline)))) { append(m.value) }
                last = m.range.last + 1
            }
            append(text.substring(last))
        }
    }
    Text(annotated, Modifier.padding(bottom = 10.dp), color = if (small) P.muted else P.text, fontSize = if (small) 12.sp else 14.5.sp,
        lineHeight = if (small) 17.sp else 22.sp, fontFamily = if (small) FontFamily.Monospace else null)
}

@Composable
fun DocScreen(doc: Doc, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val italian = S.italian
    val blocks = remember(doc, italian) { Legal.text(doc, italian).split("\n").filter { it.isNotBlank() } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
        Row(Modifier.height(60.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(Icons.back, onClick = onBack)
            T(Legal.title(doc, italian), Modifier.padding(start = 4.dp), size = 17.sp, weight = FontWeight.SemiBold, maxLines = 1)
        }
        for (b in blocks) when {
            b.startsWith("## ") -> T(b.removePrefix("## "), Modifier.padding(top = 16.dp, bottom = 8.dp), size = 18.sp, weight = FontWeight.Bold)
            b.startsWith("@asset:") -> {
                val body = remember(b) { ctx.assets.open(b.removePrefix("@asset:")).use { it.readBytes().toString(Charsets.UTF_8) } }
                Paragraph(body, small = true)
            }
            else -> Paragraph(b)
        }
    }
}
