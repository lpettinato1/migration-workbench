# Flusso Git dalla GUI — 0.3.0

L'incremento collega alla GUI le operazioni del motore esistente. La scelta della finestra desktop con browser incorporato rimane aperta: il launcher di collaudo usa ancora il browser di sistema. Il workflow in `UiGitWorkflow` non dipende da HTTP o JSON; Spring Boot non e stato introdotto.

## Percorso operativo

In **Repository**, aprire **Branch e cartella di lavoro**. Il riferimento di partenza puo essere un branch locale o un riferimento remoto gia disponibile; non viene eseguito fetch. Il branch e il worktree di destinazione devono essere nuovi. La preparazione richiede un checkout di origine pulito e lascia quel checkout sul branch e sui contenuti originali.

Dopo la preparazione, la selezione usa il nuovo worktree. Generare l'anteprima e controllare tutti i file modificati. Le azioni della review mostrano un riepilogo prima di confermare l'operazione sul repository selezionato.

| Stato | Azione successiva |
|---|---|
| Anteprima | Segnare tutti i file modificati, poi confermare la review |
| Review confermata | Confermare l'applicazione dei file |
| Modifiche applicate | Per Git, confermare il commit con il proprio messaggio |
| Commit creato | Facoltativamente, scegliere un remote e confermare il push dello SHA mostrato |
| Push completato | Nessuna ulteriore scrittura per quel risultato |

Per una cartella senza Git l'applicazione conclude il lavoro. Le operazioni non sono concatenate automaticamente. I gruppi organizzano la review, senza introdurre override o commit di gruppo.

## Coerenza e comunicazione

Il backend applica esclusivamente i report che conserva in memoria, identificati dal job e dalla root. Il client non puo sostituire il report con uno modificato. La conferma della review richiede tutti e soli i percorsi modificati; errori o assenza di modifiche impediscono la finalizzazione.

Applicazione e commit usano i controlli gia presenti nel motore: input, inventario YAML, branch e HEAD coerenti, staging non preesistente e assenza di modifiche estranee al commit. Il push usa il remote configurato, il branch e lo SHA conservati dopo il commit, senza force.

Il motore esegue una mutazione alla volta e mantiene disponibile la lettura dello stato. La risposta dello stato distingue una mutazione ancora in corso. Se una risposta si perde, la GUI verifica il risultato, senza ripetere automaticamente apply, commit o push. Se non puo stabilire l'esito, blocca le azioni finche **Aggiorna stato** non riesce.

Una migrazione Git applicata blocca nuove anteprime finche non viene committata. Il push non e obbligatorio per iniziare un altro lavoro. I job mantenuti in memoria sono gli ultimi tre; i commit di job rimossi restano in Git e si possono gestire dalla CLI. I risultati non sono salvati o ripristinati automaticamente. Chiudere il processo non esegue rollback dei file o dei commit.

## Verifiche

I test Java attraversano le API HTTP reali. La prova completa prepara un worktree con spazi nel percorso, modifica due ambienti arbitrari, segnala un template Helm ignorato, applica i risultati attesi, crea il commit e pubblica il branch su un repository bare locale. Sono verificati contenuti e SHA del remoto e integrita del checkout originale.

I casi negativi includono conferme assenti o false, review incompleta, root estranea al report, operazioni fuori ordine o ripetute, preparazione su checkout sporco o branch esistente, modifica degli input dopo la review, contenuti estranei o alterati prima del commit, branch o SHA cambiati prima del push. Un commit in corso verifica che il polling esponga lo stato in corso e che una seconda mutazione sia rifiutata.

I test frontend esercitano anche le classi effettive dei componenti: nessuna richiesta prima della conferma, gate della review, risposta del commit persa ma successo recuperato, stato non verificabile o ancora in mutazione, blocco di reset/ricaricamento quando manca il commit. Questi test non renderizzano il DOM e non verificano il layout.

Le suite del motore e le prove del JAR standalone restano incluse. Gli esiti aggregati sono nei JSON della cartella `verification` del pacchetto; i controlli storici di mutazione 0.1 rimangono identificati come tali in [verification.md](verification.md).


Esito del 16 ottobre 2026: **61 test Java e 15 test frontend superati**, senza errori o test ignorati. Build Angular AOT di produzione e verifica del JAR finale completate. I test Java sono stati eseguiti in piu passaggi: suite completa, integrazioni standalone, poi suite Git aggiornata e integrazione UI sul JAR finale. Il bundle JavaScript e CSS iniziale misura 776.055 byte non compressi; non e una misura del consumo RAM o delle prestazioni sulla VDI.

## Parti ancora aperte

Il collaudo visivo completo resta aperto: il browser dell'ambiente di esecuzione non raggiunge il servizio loopback, mentre i test HTTP Java passano anche fra processi distinti. Non si dichiara completata la verifica della finestra incorporata o del layout.

Restano da implementare contenitore desktop e relativo collegamento con Java, automazioni Bitbucket, override, correzioni manuali della diff e parallelismo delle migrazioni. Le credenziali Git devono essere gia configurate; eventuali errori di autenticazione, hook o conflitti sono riportati senza forzare l'operazione.