# Verifiche del primo incremento

Ambiente: Windows 11, Eclipse Temurin 21.0.9, Git for Windows, Maven 3.9.9 tramite wrapper. Data: 16 ottobre 2026. OpenRewrite Core/YAML 8.89.0.

## Verifiche automatiche

La build `mvnw.cmd verify` esegue test del servizio, dell'applicazione dei file e di Git; poi confeziona il JAR ed esegue test che lo avviano in processi Java separati.

Esito finale: **41 test superati, zero fallimenti, zero errori, zero test saltati**. La build completa è terminata in circa 2 minuti e 4 secondi.

| Suite | Test |
| --- | ---: |
| Motore e compatibilità YAML | 25 |
| Applicazione e recupero dopo errori I/O | 8 |
| Controlli Git | 6 |
| JAR standalone e flusso completo sui tre repository | 2 |

Il confronto principale usa due file scritti esplicitamente: `src/test/resources/golden/complex-before.yaml` e `complex-after.yaml`. Il risultato atteso non viene prodotto dal motore sotto test. Comprende documenti multipli, direttiva YAML 1.1, mappe e array annidati, commenti, stringhe quotate e multilinea, anchor, alias, merge, chiavi numeriche, timestamp, binari e valori che devono rimanere identici.

Le altre prove coprono:

- Condizione vera, falsa e assente in un altro file; il file della condizione rimane nel contesto anche se fuori dal filtro dei file modificabili. Nessuna contaminazione fra repository.
- Ordine delle ricette, idempotenza, UTF-8 con BOM e CRLF dopo un'applicazione reale, riferimenti circolari e `!!set` nei casi collaudati.
- Template Helm ignorati con avviso; delimitatori quotati e commentati conservati. UTF-16 segnalato come non supportato.
- YAML malformato, alias non definito, eliminazione di un anchor ancora referenziato e output non valido: errore bloccante, input intatti.
- Creazioni, eliminazioni e rinomine reali; percorso che esce dalla root e contenuto del report con hash incoerente rifiutati prima di scrivere.
- Creazione o rinomina verso un file già esistente e ignorato perché Helm: errore rilevato durante la preview, senza perdita del file dall'inventario.
- Invalidazione dell'anteprima dopo modifica di un file di sola lettura, aggiunta/rimozione di YAML, cambio branch o HEAD e modifiche Git estranee.
- Guasto I/O simulato prima e dopo la seconda scrittura: ripristino dei contenuti originali verificato per hash.
- Checkout di origine pulito e separato dal worktree della migrazione; branch già esistente rifiutato. Commit senza staging preesistente o file estranei. Push con commit inatteso rifiutato.
- Serializzazione del catalogo dal JAR confezionato e codici di uscita della CLI nei casi di errore.

## Prova completa del JAR

Il test `StandaloneIT` copia solo JAR e ricette in una cartella senza POM o wrapper. Rimuove dal processo le variabili Maven e CLASSPATH e imposta un proxy Java non disponibile: il runner usa le dipendenze già contenute nel JAR.

Campione: tre repository sintetici (`ordine-service`, `pagamento-service`, `rendiconto-batch`), con branch di partenza `develop`, `release/2026.10` e `main`. Ogni repository contiene cinque YAML: una configurazione di condizione, tre values negli ambienti arbitrari `svil`, `uat-eu`, `prd2` e un template Helm.

La sequenza verifica preparazione del worktree, dry run senza scritture, tre file modificati e un template ignorato per repository, applicazione, secondo dry run senza modifiche, commit e push. SHA e contenuto vengono letti dal remote bare per verificare l'invio; branch, HEAD e file del checkout originale rimangono invariati. Le misure e gli SHA della prova completata sono esportati in `target/verification/standalone-results.json`.

I remote sono esclusivamente locali. Il test usa il trasporto nativo `ext::git receive-pack` con abilitazione circoscritta alla configurazione dei repository sintetici: evita shell MSYS e connessioni loopback, limitate dalla sandbox Windows di questa sessione. Il runner distribuito non abilita quel protocollo e non modifica la configurazione Git globale. Questo collaudo non dimostra l'autenticazione o la connettività HTTPS verso Bitbucket aziendale.

I tempi registrati distinguono parsing, esecuzione delle ricette e totale del motore; non includono tutto il flusso Git. Il campione serve a verificare il comportamento, non a stimare prestazioni su migliaia di repository.

| Repository | Parsing | Ricette | Totale motore |
| --- | ---: | ---: | ---: |
| ordine-service | 102 ms | 176 ms | 723 ms |
| pagamento-service | 103 ms | 198 ms | 796 ms |
| rendiconto-batch | 103 ms | 170 ms | 698 ms |

## Controllo intenzionale dei fallimenti

Tre copie delle ricette, fuori dagli esempi originali, vengono alterate separatamente:

| Alterazione | Difetto che il confronto deve rilevare |
| --- | --- |
| `app.endpoint` diventa `resources.requests.cpu` | Il valore da migrare resta errato. |
| `deploy/**/values.yaml` diventa `**/*.yaml` | Cambiano anche file esterni all'ambito previsto. |
| La condizione attesa passa da `true` a `false` | La modifica condizionata non viene applicata. |

Per ciascuna copia viene eseguito il test golden con `-Dtest=EngineTest#complexGoldenAndDryRunDoNotChangeInputs -Dworkbench.test.rules=PERCORSO_ASSOLUTO_COPIA`. Il controllo richiede un fallimento di confronto e zero errori di esecuzione: una ricetta non caricabile non vale come prova. Gli esiti e i log della sessione sono in `.build/mutants/`. La build finale viene rieseguita con le ricette originali.

Esito osservato: per tutte e tre le alterazioni, un test eseguito, un fallimento di confronto, zero errori di esecuzione. Per il filtro alterato è stata mantenuta una stringa YAML valida e quotata (`'**/*.yaml'`).

## Limiti emersi e correzioni

- La ricetta nativa `DeleteProperty` 8.89.0 non elimina la proprietà nel documento con alias usato dal test. Il comportamento è caratterizzato esplicitamente; l'esempio usa `DeleteKey`, mantenendo invariato il risultato atteso del confronto.
- Le chiavi YAML complesse, come una sequenza usata come chiave, non sono riconosciute dal parser OpenRewrite 8.89.0. Il test registra questo limite e verifica il blocco senza scritture. Le chiavi numeriche sono un caso distinto e vengono conservate.
- Un valore con virgolette non gestite dalla ricetta nativa può produrre YAML non valido: il validatore dell'output impedisce di applicarlo. Non è stata aggiunta una sostituzione silenziosa delle ricette native.
- Gli errori emersi nel confezionamento (diff con percorsi Windows e serializzazione dei metadati temporali) sono stati corretti nel codice del runner.
- Per i test Windows i temporanei si trovano sotto `target/test-work`, con una configurazione Git solo di test. Il wrapper è stato avviato anche dopo un download reale di Maven, usando un temporaneo nel workspace per rispettare i vincoli della sandbox.

Non risultano verificati in questo incremento: VDI aziendale, HTTPS e credenziali Bitbucket, grandi volumi, cancellazione, parallelismo, editor/UI e conservazione di correzioni manuali dopo una riesecuzione. Il JAR è pronto per provare questo primo flusso locale; non costituisce il collaudo finale dell'intero prodotto.
