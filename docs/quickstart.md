# Migration Workbench 0.3.0

Estrarre il pacchetto e avviare `Avvia.cmd`. Servono Java 21 e Git nel PATH; nessun tool di build sulla VDI. La versione di collaudo apre il browser di sistema. La finestra con browser incorporato, prevista dal prodotto concordato, non e ancora implementata in questo pacchetto.

## Migrazione completa

1. Scegliere **Nuova migrazione** e aggiungere le cartelle locali. Per una prova senza Git si puo caricare **Apri l'esempio locale**.
2. In ogni repository Git, aprire **Branch e cartella di lavoro**. Impostare riferimento di partenza, nuovo branch e nuova cartella esterna. Leggere il riepilogo e confermare: la migrazione usera il worktree, senza cambiare il checkout originale.
3. Caricare le ricette, scegliere quella da eseguire e compilare eventuali parametri `migration.nome`. Le ricette possono usare `${migration.nome}` oppure `${migration.nome:default}`. Non esiste un insieme fisso di ambienti.
4. Generare l'anteprima e controllare le differenze. Ogni file modificato va segnato esplicitamente come verificato; aprirlo non basta.
5. Confermare la review del repository e poi l'applicazione, leggendo i riepiloghi. Se input o stato Git non corrispondono piu all'anteprima, il motore rifiuta la scrittura.
6. Per Git, impostare il messaggio e confermare il commit. Vengono committati solo i file della migrazione. Il commit e locale e non esegue push.
7. Se desiderato, selezionare il remote e confermare il push del branch e dello SHA mostrati. Non viene usato force. Il push usa le credenziali Git gia configurate.

La preparazione richiede un checkout pulito, un branch nuovo e una destinazione nuova. Non fa fetch automatici. I gruppi sono etichette della review, senza override. Una cartella senza Git puo ricevere le modifiche, ma non ha commit o push.

Finche una migrazione Git applicata non e committata, nuove anteprime e reset sono bloccati. In caso di errore di comunicazione usare **Aggiorna stato**: l'app non ripete automaticamente una scrittura o un commit dall'esito incerto. Gli errori di hook o configurazione Git possono richiedere di verificare il repository prima di riprovare.

La sessione non viene salvata o ripristinata. Chiudere il processo non annulla modifiche o commit. I report esportati contengono anche i valori delle configurazioni originali e risultanti.

## Avvio da terminale e comandi esistenti

```powershell
java -jar migration-workbench.jar
java -jar migration-workbench.jar ui --no-browser
java -jar migration-workbench.jar --help
```

Il launcher avvia Java, il motore e l'adattatore locale; il browser e il client della UI. Spring Boot non e richiesto. Anche i comandi CLI `prepare`, `preview`, `apply`, `commit`, `push` e `describe` restano disponibili. Sono compatibili con PowerShell 5.1.

Per usare un export con `apply`, scegliere il report del singolo repository; la review complessiva ha un formato diverso. Percorso e snapshot devono ancora coincidere con quelli del report.

Il [flusso Git e le verifiche](git-ui.md), il [piano](implementation-plan.md) e i [limiti del motore](verification.md) distinguono funzioni pronte e parti ancora aperte. La verifica grafica completa nel browser non e dichiarata conclusa: il browser dell'ambiente di collaudo non raggiunge il servizio locale, mentre i test HTTP passano.