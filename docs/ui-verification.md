# Verifica dell'incremento UI 0.2.0

Documento storico. Per le funzionalita Git e le verifiche 0.3.0 vedere [git-ui.md](git-ui.md).

Verifiche eseguite il 16 ottobre 2026 su Windows con Java 21.0.9. Questa pagina distingue i controlli automatici completati dal controllo visivo ancora aperto.

## Esito

- Build Angular 22.2.3 di produzione con compilazione AOT e template rigorosi completata.
- Otto test frontend passati: composizione YAML multidocumento e ordine, modifica guidata e precondizioni, diagnostica YAML invalido/duplicato, parametri duplicati o fuori namespace, cartelle con ambienti arbitrari e rinomine, stati delle modifiche, autenticazione dopo reload, messaggi API e risposte non JSON.
- Suite Java completa: 54 test, zero fallimenti, errori o test ignorati. Include 51 test unitari e tre prove di integrazione sul JAR.
- Dopo la correzione del reload e la rigenerazione degli asset, ripetute le verifiche pertinenti: UiServerTest e UiStandaloneIT sul JAR finale.

I test hanno aspettative esplicite sui contenuti prima/dopo, sui percorsi e sui file che devono restare intatti. Non si limitano a verificare che il processo termini senza eccezioni.

## Cosa viene verificato

`UiServerTest` copre accesso con token, restrizioni di origine e host, asset inclusi, errori leggibili, richieste invalide, catalogo, preview reale, isolamento dei risultati e gestione dei job.

`UiStandaloneIT` copia il JAR in una cartella senza Node, Maven o file di build, lo avvia come processo separato e controlla gli asset Angular tramite HTTP. Esegue una preview su due ambienti arbitrari, confronta il contenuto atteso delle modifiche, verifica il template Helm ignorato e l'inventario originale invariato. Non esegue il JavaScript in un browser.

`StandaloneIT` verifica anche tre repository Git sintetici, con branch di partenza diversi e tre ambienti per repository: preparazione del worktree, preview, applicazione, commit e push su remoti locali. Non sono usati servizi remoti reali. Le misure del campione sono in `standalone-results.json`; non costituiscono un benchmark di produzione.

Le verifiche storiche delle ricette alterate intenzionalmente, documentate in [verification.md](verification.md), restano riferite all'incremento del motore 0.1. Non sono presentate come nuovi mutation test della UI 0.2.

## Dimensioni e dipendenze

Il frontend iniziale comprende circa 744 KB non compressi di JavaScript e CSS, circa 205 KB nella stima di trasferimento compressa della build Angular. Non sono misure del consumo RAM del browser o del tempo di avvio su VDI.

Il JAR comprende frontend, motore e dipendenze. Gli avvisi di licenza del frontend sono inclusi nel JAR e nel pacchetto; non si usano CDN. Le versioni dirette sono fissate in `pom.xml` e `frontend/package.json`, con lock npm. TypeScript 6.0.3 è la versione compatibile con Angular 22; non si forza TypeScript 7 fuori dall'intervallo supportato.

## Controllo visivo ancora aperto

Il browser disponibile in questa sessione non raggiunge la porta loopback del servizio avviato nell'ambiente di esecuzione: restituisce `ERR_CONNECTION_TIMED_OUT`. I test HTTP Java, anche fra processi distinti, passano. Non sono state modificate impostazioni di rete o firewall per aggirare questo limite.

La navigazione grafica completa e la resa su schermo non sono quindi dichiarate verificate. Nel pacchetto pronto, avviare `Avvia.cmd`, scegliere l'esempio incluso, eseguire l'anteprima e controllare navigazione, diff, contenuto completo, stato di revisione ed esportazione. Provare anche F5: il collegamento conserva il token locale, mentre una nuova pagina riparte senza ripristinare la sessione di migrazione.

## Ambito consegnato

Selezione cartelle locali, informazioni Git, ricette YAML e parametri arbitrari `migration.*`, opzioni e descrizioni delle ricette caricate, preview reale e progressi, review per gruppo/repository/cartella/file, diff di sola lettura, YAML completo, controllo esplicito ed esportazione.

I gruppi di questa versione organizzano la review; non applicano override di configurazione. Le migrazioni vengono elaborate in sequenza. La UI non applica file, non effettua commit/push e non seleziona repository Bitbucket. I comandi CLI esistenti restano disponibili. Nessun salvataggio o ripristino automatico di sessioni.