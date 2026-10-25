# Informativa sulla privacy — Read Files Tech

_Ultimo aggiornamento: 25 ottobre 2026 (versione 3.0.0)_

## In una frase

Read Files Tech non raccoglie, non trasmette e non conserva alcun dato su un server: l'app non ha l'autorizzazione ad accedere a Internet, e i Suoi file lasciano il telefono solo quando è Lei ad affidarli a un'altra app.

## Chi è il responsabile

Read Files Tech è pubblicata da Patrice Haltaya (Files Tech), a titolo personale. Contatto: **contact@files-tech.com**. Il codice sorgente è pubblico: https://github.com/gitubpatrice/READ-FILES-TECH.

Lo sviluppatore non riceve alcun dato dall'app: non ha quindi nulla da conservare, consultare o cancellare che La riguardi. Gli arriva solo un'e-mail che Lei sceglie di scrivergli — «Segnalare un bug» vi aggiunge le versioni dell'app e di Android. Questa e-mail serve solo a risponderLe; è conservata per il tempo dello scambio, poi cancellata. Può chiederne l'accesso o la cancellazione allo stesso indirizzo, e rivolgersi alla CNIL, l'autorità francese per la protezione dei dati (www.cnil.fr), se ritiene che i Suoi diritti non siano rispettati.

## Ciò che l'app non fa

- Nessuna pubblicità, nessun tracker, nessuna misurazione del pubblico, nessun rapporto di arresto anomalo inviato.
- Nessun account, nessuna registrazione.
- **Nessuna autorizzazione Internet**: `INTERNET` e `ACCESS_NETWORK_STATE` sono rimosse dal manifest dell'app, e la sua compilazione rifiuta qualsiasi versione che ne contenga una. L'app non può raggiungere alcun server.
- **Nessun servizio Google**: né servizi Google Play, né ML Kit, né Firebase. Il riconoscimento del testo (OCR) avviene sul telefono, con Tesseract. La WebView che mostra le pagine HTML è impostata per non inviare statistiche d'uso al suo produttore, e l'app non chiede a un servizio esterno il suo carattere per le emoji.
- L'app non stila l'elenco delle Sue app. Verifica soltanto se quattro app sono installate, per proporLe di inviare loro un file: PDF Tech, kDrive, Proton Drive e Google Drive.

## Ciò che l'app legge, e perché

Read Files Tech è un esploratore e visualizzatore di file. Con il Suo consenso — «Accesso a tutti i file» da Android 11, l'autorizzazione alla memoria prima — può leggere e modificare tutti i file della memoria condivisa del telefono, scheda SD compresa. Lo fa solo per ciò che Lei chiede: sfogliare una cartella, aprire un file, convertirlo, cercare, trovare i duplicati… Non affida mai a un'altra app un file delle cartelle `Android/data` o `Android/obb` delle altre app.

## Ciò che viene conservato sul Suo telefono

- **File recenti (percorso, nome, dimensione, data, preferito), file tolti da questo elenco, tema, cartella di destinazione, condivisione automatica** — preferenze private dell'app, in chiaro.
- **Impostazioni della cassaforte (sale, parametri di derivazione della chiave), contatori di errori** — preferenze private dell'app, in chiaro; nessuna password.
- **Cronologia degli editor: le ultime 10 versioni di ogni file modificato nell'editor di codice o nell'editor CSV, con il suo percorso** — memoria privata dell'app, **in chiaro**.
- **File della cassaforte** — memoria privata dell'app, cifrati (vedi sotto).
- **Modelli dell'OCR** — memoria privata dell'app, forniti con l'app.
- **Copie temporanee: un file della cassaforte aperto o condiviso, un PDF protetto sbloccato, un elemento d'archivio condiviso, le foto dello scanner** — cache privata dell'app, in chiaro; cancellate quando non servono più o, al più tardi, all'avvio successivo.
- **File creati dagli strumenti: scansioni, conversioni, compressioni, immagini senza EXIF, testi dell'OCR, backup della cassaforte, estrazioni** — cartella `Files Tech` della memoria condivisa, o quella che sceglie nelle Impostazioni, in chiaro, tranne i backup della cassaforte.
- **Cestino: i file eliminati e la loro descrizione (nome, percorso d'origine, dimensione, data)** — cartella nascosta `.RFT_Corbeille` alla radice di ogni volume, in chiaro; **mai svuotato automaticamente**.

I file della memoria condivisa — la cartella `Files Tech`, il cestino — sono leggibili da qualsiasi altra app a cui Lei abbia dato accesso ai Suoi file.

Se la cartella dei file creati rifiuta la scrittura («Accesso a tutti i file» negato, scheda rimossa), l'app scrive nella propria cartella della memoria condivisa (`Android/data/…/files/Files Tech`), poi nella sua memoria privata; le Impostazioni lo segnalano, così come la finestra di un backup della cassaforte. Queste cartelle vengono cancellate con l'app, e la modalità panico non cancella ciò che contengono.

## La cassaforte

- Ogni file è cifrato con **AES-256-GCM**, con una chiave derivata dalla Sua password tramite **Argon2id**. Una cassaforte creata con una versione precedente mantiene la derivazione con cui è stata creata (PBKDF2, o un Argon2id più leggero). La password non viene mai salvata.
- **Non sono cifrati**: il **nome** dei file della cassaforte, la loro **dimensione**, il loro numero e le loro date, nonché le impostazioni di derivazione della chiave.
- La cassaforte si blocca 30 secondi dopo che Lei ha lasciato l'app — con il telefono in sospensione profonda, al suo risveglio, e sempre prima che la cassaforte ricompaia —, dopo 3 minuti senza toccare lo schermo, e quando Lei lascia la sua schermata. Dopo 5 password errate, ogni tentativo attende più a lungo, fino a 30 minuti.
- Le schermate della cassaforte, e i file della cassaforte aperti nell'app, non possono essere catturati e non compaiono nell'anteprima delle app recenti.
- Un **backup** `.rftvault` è cifrato per intero, nomi compresi, con una password di backup distinta; viene scritto nella cartella dei backup della cassaforte, che la finestra del backup mostra prima: nella memoria condivisa o, se la cartella di destinazione rifiuta la scrittura, in una cartella dell'app, cancellata con essa.
- **Condividi (decifrato)** crea una copia in chiaro temporanea, cancellata al blocco e all'avvio successivo. **Esporta** scrive una copia in chiaro permanente nella cartella che Lei sceglie. Ciò che fa l'app che riceve un file non dipende più da Read Files Tech.

## Quando qualcosa lascia il Suo telefono

Sempre su una Sua azione, e verso l'app che Lei sceglie:

- **Condividi**, **Apri con**: il file è affidato all'app scelta, in sola lettura, per quella volta.
- **Invia** a kDrive, Google Drive o Proton Drive, se installati.
- **Modifica in PDF Tech**: l'accesso in scrittura è concesso solo se l'app installata porta la firma di PDF Tech.
- **Condividere automaticamente dopo la creazione**, se attiva questa impostazione (disattivata per impostazione predefinita).
- **Scattare una foto** per lo scanner o l'OCR: l'app fotocamera del telefono scrive la foto in un file che Read Files Tech le apre solo a questo scopo.
- **Link** — pagina delle versioni, sito, e-mail —: aperti nel Suo browser o nella Sua app di posta, che si connettono da soli. «Segnalare un bug» prepara un'e-mail che Lei rilegge e invia personalmente.
- **Link di un documento**: un link che Lei tocca in una pagina HTML o in un file Markdown si apre nel Suo browser, che si connette da solo al sito.

Il visualizzatore HTML mostra una pagina senza JavaScript e senza alcuna connessione: vengono caricati solo i file della cartella della pagina.

## Modalità panico

Dalle Impostazioni, dopo una conferma, la modalità panico cancella subito: la cassaforte e le sue impostazioni, tutte le preferenze (file recenti compresi), tutta la cache, la cronologia degli editor, le vecchie estrazioni e **il cestino** su tutti i volumi. Non cancella: i Suoi file, la cartella `Files Tech` (backup della cassaforte compresi), i file esportati dalla cassaforte, le copie già affidate ad altre app, né gli appunti. La cancellazione elimina i file senza sovrascriverne il contenuto.

## Backup di Android

Il backup di Android — nel cloud o da un telefono all'altro — è disattivato per i dati dell'app. I file della memoria condivisa non ne fanno parte: uno strumento che copia la Sua memoria può portarli con sé.

## Autorizzazioni Android

| Autorizzazione | Perché |
|---|---|
| `MANAGE_EXTERNAL_STORAGE` (da Android 11) | L'accesso a tutti i file: Esplora file |
| `READ_EXTERNAL_STORAGE` (fino ad Android 12), `WRITE_EXTERNAL_STORAGE` (fino ad Android 10) | La stessa cosa sulle versioni precedenti |
| `REQUEST_INSTALL_PACKAGES` | Affidare un APK toccato in Esplora file al programma di installazione di Android, che chiede la propria conferma |
| `…DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | Aggiunta da una libreria di Android, interna all'app |

Né fotocamera, né posizione, né contatti, né microfono, né notifiche, né Internet.

## Registro di sistema

Quando un'operazione non riesce, l'app scrive una sola riga nel registro di sistema: il tipo dell'errore, senza nome di file né contenuto. La libreria che legge i PDF può aggiungervi i propri avvisi, e Android vi registra un eventuale arresto anomalo dell'app; l'app non invia questo registro da nessuna parte.

## Dati delle versioni precedenti

La versione 3.0.0 riprende sul posto i dati delle versioni 2.x — preferenze, cassaforte, cestino, cronologia —: nulla viene inviato né copiato altrove, tranne le vecchie estrazioni, che l'app propone una volta di spostare nella cartella `Extractions` dei file che crea, o di cancellare: nulla viene spostato né cancellato senza il Suo consenso.

## I Suoi diritti

I Suoi dati sono sul Suo telefono, sotto il Suo controllo: può cancellarli dall'app (file, cestino, cassaforte, modalità panico) o disinstallandola. La disinstallazione cancella i dati privati dell'app, ma non la cartella `Files Tech` né il cestino, che si trovano nella memoria condivisa. Per qualsiasi domanda: **contact@files-tech.com**.

## Minori

L'app non si rivolge in particolare ai minori; non contiene pubblicità né profilazione.

## Modifiche

Questa informativa può evolvere con l'app; la data in alto ne indica la versione.

## Lingua

Questa informativa è stata redatta in francese; le versioni in altre lingue ne sono traduzioni. In caso di differenza, **fa fede la versione francese**.

---

Read Files Tech fa parte della suite **Files Tech**, pubblicata da **Patrice Haltaya**.
