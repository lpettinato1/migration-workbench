# Piano di implementazione

Stato 0.3.0 al 16 ottobre 2026: runner e GUI Angular includono preparazione branch/worktree, review confermata, applicazione, commit e push distinti per repository. Il workflow Git e separato dal trasporto HTTP. I gruppi restano etichette, senza override.

Il browser incorporato e un requisito concordato ancora aperto. La scelta provvisoria del browser di sistema non lo soddisfa e non e confermata come architettura finale. Non si introduce Spring Boot prima di definire contenitore desktop e collegamento con Java. La verifica visiva resta aperta per il problema di raggiungibilita loopback del browser di collaudo. Dettagli operativi e verifiche in [git-ui.md](git-ui.md).

## Direzione

Partire da una piccola esecuzione reale di OpenRewrite, poi collegare una prima interfaccia. Non costruire prima tutto il motore o tutta la UI: ogni incremento deve produrre un comportamento verificabile.

- Runtime concordato: Java 21, disponibile anche sulla VDI. Codice compilato e distribuito come JAR; JShell non previsto.
- OpenRewrite incorporato come libreria nel nostro motore. Nessun Maven/Gradle richiesto nei repository da migrare o sulla VDI.
- Build e test nel workspace di sviluppo. Build riproducibile tramite wrapper; dipendenze fissate a versioni esplicite.
- Il JAR 0.3.0 include le dipendenze necessarie all'esecuzione YAML e gli asset frontend. Avvio tramite `Avvia.cmd` o `java -jar migration-workbench.jar`; nessun Node/npm necessario sulla VDI. Le ricette dichiarative possono provenire da un file esterno o essere modificate nell'editor.
- Nessuna dipendenza dalla piattaforma Moderne o da servizi AI per eseguire le migrazioni.
- Primo backend come singolo modulo, con responsabilità separate per piano, esecuzione OpenRewrite e adattatori. Nessuna necessità iniziale di microservizi o molti moduli.

L'integrazione standalone è documentata da [OpenRewrite](https://docs.openrewrite.org/running-recipes/running-rewrite-on-an-infrastructure-as-code-project#option-3-standalone-runner). Isolare le API di parsing ed esecuzione in un adattatore: la documentazione ne segnala la possibile evoluzione fra versioni.

## Vincoli di prodotto già emersi

- L'utente avvia esplicitamente una nuova migrazione. Nessuna memoria delle sessioni, ripristino automatico o configurazione obbligatoria da gestire.
- Gruppi logici definiti a partire dalla selezione dei repository e mantenuti nel builder e nella review.
- Valori comuni alla migrazione, valori di gruppo ed eccezioni puntuali. Ambienti arbitrari, con eventuali valori specifici per ambiente al livello pertinente.
- Ricette e parametri condivisi sono distinti: lo stesso valore può essere usato in proprietà e file differenti.
- Repository, coppia di branch, ambienti e file devono rimanere identificabili. I gruppi non devono nascondere branch ed eccezioni.
- Builder con catalogo, composizione ordinata, precondizioni, opzioni e documentazione; niente grafo centrale.
- Review con panoramica, navigazione dei file, diff, contenuto completo e stato di controllo esplicito.
- Correzioni ripetibili tramite parametri/eccezioni; eventuali correzioni manuali distinte dal risultato delle ricette.
- Distribuzione finale avviabile dalla VDI senza build. Nessuna configurazione di GitHub Actions richiesta adesso.

## Incremento 1 — Runner OpenRewrite verificabile: realizzato

Obiettivo: una cartella locale e una ricetta YAML producono un'anteprima reale, utilizzabile successivamente dalla UI.

Disponibile:

1. Progetto Java 21, build tramite wrapper, dipendenze OpenRewrite coerenti e JAR eseguibile con dipendenze.
2. Contratto di richiesta/risultato indipendente dalla UI. Identità del target, input, ricetta e parametri; per ogni file, percorso, contenuti prima/dopo, stato e diagnostica.
3. Identificazione dello snapshot di input e della configurazione eseguita, per poter riconoscere risultati non più aggiornati.
4. Lettura YAML, caricamento di ricette dichiarative esterne ed esecuzione senza scrivere sui file originali.
5. Piccolo catalogo dimostrativo: modifica proprietà, rimozione proprietà, composizione e precondizione per percorso.
6. Una ricetta Java dimostrativa che legge una configurazione e condiziona una modifica su un altro file dello stesso target. Serve a verificare presto l'estensibilità effettiva.
7. Diagnostica comprensibile, con repository, percorso, fase e causa. Un file ignorato deve essere distinguibile da uno invariato o da un errore.
8. CLI minima per sviluppo e test, che chiama lo stesso servizio applicativo previsto per la GUI. Niente logica centrale nello script chiamante.

Verifiche del runner:

- Fixture realistiche con più cartelle, ambienti arbitrari, mappe/array annidati, documenti multipli, stringhe quotate e multilinea, commenti e terminatori Windows.
- Casi espliciti per anchor, alias, merge e costrutti YAML particolari: verificarne il comportamento effettivo prima di dichiararli supportati. Limiti o incompatibilità rimangono aperti e documentati.
- Template Helm e file non elaborabili non sono riscritti o trasformati silenziosamente; ricevono uno stato riconoscibile.
- Verifica di ciò che deve cambiare e di ciò che deve rimanere intatto, ordine delle ricette, condizioni vere/false e idempotenza.
- Nessuna modifica ai contenuti originali durante il dry run, anche in caso di errore.
- Il JAR funziona fuori dalla cartella del progetto, in una cartella priva di Maven/Gradle e di file di build del repository target, senza download a runtime per gli esempi inclusi.
- Registrare tempi di parsing/esecuzione e dimensioni del campione, senza promettere prestazioni non misurate.

## Incremento 2 — Primo flusso grafico reale: implementato, verifica visiva aperta

Obiettivo realizzato nel codice: collegare il runner a un flusso grafico locale, prima di ampliare catalogo e automazioni.

- Nuova migrazione avviata esplicitamente, senza salvataggio o ripristino automatico della sessione.
- Selezione di più cartelle locali, con percorso, branch/HEAD Git quando presenti e file YAML individuati. La UI mostra il branch corrente e consente di preparare un nuovo branch da un riferimento locale disponibile.
- Caricamento di ricette dichiarative, scelta della ricetta, modifica del YAML completo e vista guidata delle opzioni con descrizioni dai metadati OpenRewrite.
- Parametri comuni `migration.*`, con `${migration.nome}` e `${migration.nome:default}`; nessun insieme fisso di ambienti.
- Esecuzione delle root in sequenza. Frontend e CLI consumano lo stesso modello; progressi, errori e risultati sono dati strutturati.
- Review per gruppo, repository, cartella e file; diff CodeMirror, patch, YAML originale/risultante completo e ricetta risolta. Stati distinti per modificato, invariato, ignorato ed errore. Aprire un file non equivale a segnarlo come controllato.
- Esportazione esplicita delle ricette, del report del singolo repository e della review complessiva. Gli export non sono un sistema di ripristino della sessione.
- Frontend Angular 22 con CodeMirror 6 incluso nel JAR. Le API ascoltano soltanto su loopback e richiedono il token della sessione.

Verifica tecnica completata: richieste HTTP reali, isolamento delle sessioni, errori, anteprima multi-root con confronti attesi indipendenti e preservazione degli input; avvio del JAR in un processo separato, caricamento di HTML/JavaScript inclusi e preview attraverso le API.

Accettazione ancora aperta: percorrere il flusso nel browser, controllare leggibilità, layout, navigazione, uso da tastiera e diff su campioni realistici. Il blocco di raggiungibilità loopback nel browser di collaudo impedisce di dichiarare conclusa questa verifica.

Confini attuali: stessa ricetta e stessi parametri per tutte le root; gruppi usati soltanto nella review; risultati in sola lettura. Una modifica alla configurazione richiede una nuova anteprima. **Interrompi attesa** ferma il polling del client, senza cancellare il lavoro Java già avviato. Applicazione, commit e push sono ora richiamati dalla UI con conferme distinte e stato per repository.

## Incremento 3 — Gruppi, parametri e builder: basi presenti, estensioni aperte

Già presenti: etichette di gruppo nella selezione e nella review, parametri comuni, composizione ordinata letta dal YAML, opzioni/documentazione dai metadati e ricetta risolta consultabile. Queste basi non implementano ancora assegnazioni o override per gruppo, repository o ambiente.

Da implementare:

- Gruppi logici e assegnazione delle ricette; valori comuni ed eccezioni con origine del valore visibile.
- Estendere risoluzione dei parametri e generazione della ricetta effettiva ai valori di gruppo e alle eccezioni puntuali, mantenendo il trattamento corretto di virgolette, caratteri speciali e valori YAML.
- Estendere il catalogo oltre i passaggi già caricati: ricerca di nuove ricette, inserimento, esclusioni e riordinamento guidato. Le opzioni continuano a provenire dai metadati.
- Distinzione fra filtri sui file modificabili e file necessari per leggere condizioni. Una condizione fra file deve ricevere il contesto necessario.
- Unità di esecuzione isolata per target/repository. Ricalcolare soltanto i target interessati quando l'ambito è noto.
- Parallelismo limitato fra target indipendenti, con progressi e cancellazione. Esecuzione contemporanea sullo stesso checkout da evitare.
- Ottimizzazione al singolo file soltanto per trasformazioni di cui siano note le dipendenze. Per le altre, riesecuzione del piano del repository sullo snapshot originale.

Accettazione: un parametro comune e un override di gruppo/repository/ambiente producono risultati corretti; nessuna contaminazione fra esecuzioni o ricette fuori ordine.

## Incremento 4 — Git locale e applicazione delle modifiche: flusso collegato alla GUI

Implementati: selezione del riferimento di partenza, nuovo branch e worktree esterno per repository; review completa con conferma esplicita; applicazione del report conservato dal backend; commit dei soli percorsi previsti; push del commit esatto su un remote configurato. I riepiloghi mostrano cartella, branch, file coinvolti e, per il push, SHA e remote. Nessuna concatenazione automatica di operazioni.

Rifiuti verificati: conferme mancanti, review incompleta, root estranea al job, azioni fuori ordine o ripetute, input obsoleti, staging o modifiche estranee, branch/HEAD diversi prima del push. La GUI recupera lo stato dopo errori di comunicazione senza ripetere automaticamente operazioni dall'esito incerto. Una migrazione Git applicata blocca reset e nuove anteprime fino al commit; una cartella senza Git termina legittimamente dopo apply.

Restano aperti: correzioni manuali della diff, riconciliazione delle correzioni durante nuove esecuzioni, automazioni su insiemi di repository e collaudo visivo del flusso. Il browser incorporato riguarda il contenitore dell'app e non e completato da questi endpoint. Test HTTP e test dei componenti non sostituiscono una prova visiva.

## Incremento 5 — Automazione e Bitbucket: non implementato

- Default Git, template per i branch, criteri su insiemi di repository ed eccezioni puntuali.
- Piano risolto prima dell'esecuzione, con origine delle scelte e criteri incompatibili evidenziati.
- Catalogo/discovery Bitbucket separato dalle operazioni Git. Clone, branch, commit e push restano riutilizzabili anche con cartelle locali.
- Gestione dell'autenticazione in base al servizio aziendale effettivo, senza introdurre dipendenze SSO non richieste.
- I criteri Git possono attraversare più gruppi di ricette; l'identità del target evita esecuzioni duplicate.

## Distribuzione e funzionalità successive

Il JAR standalone e gli asset UI incorporati sono verificati automaticamente. Il pacchetto 0.3.0 comprende launcher Windows ed esempi. Rimane da verificare il flusso grafico effettivo sulla VDI Java 21, incluso l'accesso del browser al servizio locale.

Restano da introdurre quando servono: ulteriori modalità di distribuzione, caricamento di nuove ricette Java/Groovy senza build sulla VDI, completamento Java tramite language server, caching avanzato e staging per singole porzioni di diff. Le classi Java eseguibili in 0.3.0 sono quelle incluse nel JAR; l'editor YAML non costituisce un ambiente di compilazione Java.

GraalVM Native Image non è il punto di partenza del piano. La modalità di distribuzione dovrà essere compatibile con le estensioni effettivamente richieste e verificate.

## Prossimo passo

Definire e implementare il contenitore con browser incorporato e il collegamento con Java, mantenendo il workflow indipendente dal trasporto. Completare quindi la verifica visiva. Override, correzioni manuali, parallelismo e Bitbucket restano gli incrementi successivi.
