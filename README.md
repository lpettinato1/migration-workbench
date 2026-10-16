# Migration Workbench 0.3.0

Interfaccia Angular 22 e CodeMirror 6 con OpenRewrite incorporato. GUI e CLI usano lo stesso motore per anteprima, applicazione, commit e push.

## Avvio e stato del contenitore desktop

L'artefatto corrente è `target/migration-workbench.jar`: avviarlo da IntelliJ oppure con `java -jar target/migration-workbench.jar`. Le librerie e il frontend sono inclusi, senza download a runtime. Per Git si usa l'installazione della macchina, con configurazione e credenziali esistenti. Non servono Node, npm, Maven o Gradle sulla VDI.

Lo sviluppo e le verifiche usano JDK 26; il bytecode rimane compatibile con Java 21. Quando si riprenderà il confezionamento desktop, il pacchetto includerà JVM 26 e un avvio con un clic. Git portabile è escluso. Il confezionamento è attualmente rinviato.

Il prodotto concordato prevede una finestra con browser incorporato. Il launcher attuale apre ancora il browser di sistema: e un adattatore provvisorio di collaudo e non completa quel requisito. Non e stato introdotto Spring Boot. La logica della migrazione e separata dal trasporto HTTP; la scelta del contenitore desktop e del suo collegamento con Java resta aperta.

Il launcher Java avvia motore e adattatore locale, quindi apre il browser. Non e il browser ad avviare il motore. `java -jar target/migration-workbench.jar ui --no-browser` mostra soltanto il collegamento. Chiudere il launcher termina il servizio.

## Flusso disponibile

1. Scegliere la cartella di lavoro unica e avviare la migrazione. Aggiungere poi i repository locali da elaborare. Il gruppo facoltativo organizza la verifica, senza assegnare valori o ricette differenti. Cambiare un'etichetta non richiede di rigenerare l'anteprima.
2. Per ciascun repository, aprire **Branch e cartella di lavoro**, scegliere il riferimento di partenza, il nuovo branch e una cartella esterna nuova. Confermare la preparazione: la root selezionata viene sostituita con il worktree, mentre il checkout originale mantiene branch e contenuti. I riferimenti disponibili sono quelli presenti in locale, senza fetch automatico.
3. Caricare o scrivere le ricette YAML, scegliere la composizione e impostare eventuali parametri `migration.*`. Gli ambienti sono arbitrari. Eseguire l'anteprima senza riscrivere gli input.
4. Controllare diff e contenuti completi. Segnare esplicitamente tutti i file modificati del repository, poi confermare la verifica dal riepilogo. I segni di verifica rimangono passando alle ricette e tornando allo stesso risultato.
5. Confermare **Applica le modifiche**: il motore ricontrolla inventario, contenuti, branch e HEAD prima di scrivere. Un'anteprima obsoleta viene rifiutata.
6. Specificare il messaggio e confermare **Crea il commit**. Vengono inclusi solo i percorsi della migrazione; staging preesistente e modifiche estranee sono rifiutati. Per una cartella senza Git il flusso termina con l'applicazione.
7. Se si vuole pubblicare, scegliere un remote configurato e confermare **Esegui il push**, dopo aver letto branch e SHA nel riepilogo. Il push e separato dal commit e non usa force.

Ogni repository mantiene il proprio stato. Le operazioni non vengono concatenate automaticamente. Dopo un errore di comunicazione la GUI rilegge lo stato prima di proporre altre azioni; se non puo verificarlo, richiede **Aggiorna stato**. Un commit gia concluso non viene ripetuto automaticamente.

Una migrazione Git applicata deve completare il commit prima di avviare altre anteprime. I controlli UI bloccano reset e modifiche della configurazione durante questa fase e chiedono conferma al browser prima di un ricaricamento involontario. Non esiste un ripristino automatico delle sessioni: chiudere il processo perde gli stati in memoria, mentre file e commit rimangono in Git. I job conservati sono gli ultimi tre; un commit precedente resta utilizzabile dalla CLI anche dopo la rimozione del job dalla memoria.

La GUI consente sempre di esportare esplicitamente ricette e report. Il report di un singolo repository e compatibile con la CLI; la review complessiva ha un formato distinto. Il temporaneo delle ricette e esterno ai target ed eliminato a fine esecuzione.

Restano aperti: contenitore desktop incorporato, override per gruppo/repository/ambiente, correzioni manuali della diff, ricalcolo incrementale, nuove classi Java a runtime, autocomplete Java, Bitbucket e parallelismo delle migrazioni.

## CLI del motore

```powershell
java -jar target/migration-workbench.jar preview `
  --root examples/repository `
  --rules examples/migration.yml `
  --recipe it.migration.demo.Configurazioni `
  --param migration.endpoint=nuovo.servizio.svc.cluster.local `
  --report target/preview.json --diff

java -jar target/migration-workbench.jar apply --report target/preview.json
```

Le anteprime diventano obsolete dopo cambiamenti agli YAML di contesto o a branch/HEAD. L'applicazione richiede un worktree Git pulito. Il report del singolo repository esportato dalla UI puo essere usato dalla CLI sullo stesso percorso e snapshot; la review complessiva ha un formato distinto.

La preparazione Git crea un nuovo branch in un worktree separato; il checkout originale deve essere pulito e branch/cartella devono essere nuovi:

```powershell
java -jar target/migration-workbench.jar prepare --repository C:/workspace/repo --source develop --branch migration/configurazioni --worktree C:/migrazioni/repo
java -jar target/migration-workbench.jar commit --report C:/migrazioni/repo-preview.json --message "Migrazione configurazioni"
java -jar target/migration-workbench.jar push --root C:/migrazioni/repo --remote origin --branch migration/configurazioni --commit SHA_DEL_COMMIT
```

Il commit aggiunge solo i percorsi previsti dal report, rifiutando staging preesistente e modifiche estranee. Il push richiede il commit esatto e non usa force. Gli esempi funzionano anche da PowerShell 5.1.

## Sviluppo

```powershell
cd frontend
npm ci
npm test
npm run build
cd ..
.\mvnw.cmd verify
```

Il frontend usa un runtime Node locale dichiarato nel suo `package.json`, senza modificare quello globale. Angular produce `frontend/dist`; Maven incorpora direttamente questi asset nel JAR. Gli asset generati non sono sorgenti e non sono versionati. Eseguire la build frontend prima della prima build Maven e dopo modifiche alla UI. `npm run format` applica la formattazione condivisa.

Le versioni dirette sono fissate e sono state verificate sui registri pubblici il 16 ottobre 2026. `frontend/package-lock.json` fissa la risoluzione npm. TypeScript usa la patch 6.0 compatibile con Angular 22; non si forza una versione incompatibile per avere un numero più alto.

I test confrontano contenuti attesi espliciti, condizioni fra file, YAML complessi e immutabilità degli input. Le integrazioni avviano il JAR fuori dal progetto ed eseguono preparazione, anteprima, applicazione, commit e push verso remoti Git locali. Non verificano autenticazione Bitbucket, prestazioni su grandi volumi o layout del browser.

## Confini e compatibilità

- Target YAML UTF-8, con o senza BOM, fino a 8 MiB per file. Nessun attraversamento di link simbolici o repository Git annidati.
- Template Helm non quotati: file ignorato con avviso; nessun rendering Helm. La rilevazione conservativa può ignorare anche blocchi letterali con delimitatori non quotati.
- Errori di parsing, alias non definiti o output non valido bloccano il target. Chiavi numeriche e riferimenti YAML sono conservati nei casi verificati; le chiavi complesse non sono riconosciute dal parser OpenRewrite usato.
- `DeleteProperty` nativa non elimina la proprietà nel documento con alias collaudato; l'esempio usa `DeleteKey`. Un output con quoting non valido viene rifiutato. Nessuna sostituzione silenziosa di ricette esterne.
- Validatore target: profondità 300 e 5.000 alias di collezioni. Configurazione ricette: 8 MiB, profondità 200, 500 alias e chiavi di opzione testuali. Questi limiti non espandono gli alias dei target.
- Creazioni, eliminazioni e rinomine senza collisioni sono supportate dal motore; non gli scambi di nomi. L'applicazione usa temporanei nella stessa cartella e recupero best effort dei contenuti dopo errori I/O; nessuna garanzia di transazione resistente a crash o di conservazione completa di ACL/metadati.
- Il servizio UI ascolta solo su loopback e richiede il token del collegamento per le API. La ricetta Java caricata deve essere fidata. Il browser deve aprire il collegamento completo mostrato dal launcher.
- Git usa configurazione, hook e credenziali disponibili. Nessuna autenticazione Bitbucket o risoluzione automatica di conflitti. Timeout per comando Git: 60 secondi.

Il controllo visivo completo resta aperto: il browser di collaudo non raggiunge il servizio loopback, mentre le prove HTTP fra processi Java funzionano. In particolare, il dialogo cartelle viene portato in primo piano e la UI blocca richieste duplicate, ma la sua comparsa da IntelliJ deve essere confermata sulla macchina dell'utente.

Git conserva lo storico delle decisioni e delle verifiche; non si mantengono copie del codice o resoconti per ogni iterazione. Leggere anche [AGENTS.md](AGENTS.md) prima di contribuire.
