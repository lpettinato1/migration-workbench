import '@angular/compiler';
import { test, mock } from 'node:test';
import assert from 'node:assert/strict';
import { setImmediate as nextTurn } from 'node:timers/promises';
import type { OperationState, Report } from './model';
Object.defineProperty(globalThis, 'location', {
  configurable: true,
  value: { hash: '#token=workflow-test' },
});
const { ReviewComponent } = await import('./review.component');
const { AppComponent } = await import('./app.component');
const { GitPreparationComponent } = await import('./git-preparation.component');
const root = 'C:/repos/ordine-migration';
const file = {
  beforePath: 'deploy/Blue_42/values.yaml',
  afterPath: 'deploy/Blue_42/values.yaml',
  status: 'MODIFIED' as const,
  before: 'app: old',
  after: 'app: new',
  recipes: [],
  diff: '-old\n+new',
};
const report: Report = {
  root,
  schemaVersion: 1,
  recipe: 'it.Migration',
  resolvedRecipe: '',
  createdAt: '',
  files: [file],
  diagnostics: [],
  git: { head: 'a'.repeat(40), branch: 'migration/configurazioni', status: '' },
  metrics: { discoveredFiles: 1, totalMillis: 1 },
};
function view(stage: OperationState['stage']) {
  const component = new ReviewComponent();
  component.jobId = 'job-server-owned';
  component.reports = [report];
  component.operationsLoaded.set(true);
  component.operationStates.set([{ root, stage, changedFiles: 1, branch: report.git!.branch }]);
  component.remoteOptions.set({ [root]: ['origin'] });
  return component;
}

test('la GUI invia la review solo dopo tutti i controlli e una conferma esplicita', async (t) => {
  t.after(() => mock.restoreAll());
  const requests: { path: string; body: unknown }[] = [];
  mock.method(globalThis, 'fetch', async (path: string, options: RequestInit) => {
    requests.push({ path, body: JSON.parse(options.body as string) });
    return new Response(
      JSON.stringify({ root, stage: 'REVIEWED', changedFiles: 1, branch: report.git!.branch }),
    );
  });
  const component = view('PREVIEW');
  assert.equal(component.canOperate('review', report), false);
  assert.equal(component.canOperate('apply', report), false);
  component.mark(report, file);
  assert.equal(component.canOperate('review', report), true);
  await component.performConfirmed();
  assert.equal(requests.length, 0);
  component.confirmation.set({ kind: 'review', root });
  await component.performConfirmed();
  assert.deepEqual(requests, [
    {
      path: '/api/jobs/job-server-owned/review',
      body: { root, confirmed: true, reviewedPaths: ['deploy/Blue_42/values.yaml'] },
    },
  ]);
  assert.equal(component.operation(report)?.stage, 'REVIEWED');
  assert.equal(component.canOperate('apply', report), true);
  component.mark(report, file);
  assert.equal(component.isReviewed(report), true);
});

test('un commit riuscito con risposta persa viene recuperato senza ripetere commit o push', async (t) => {
  t.after(() => mock.restoreAll());
  const paths: string[] = [];
  const commit = 'b'.repeat(40);
  mock.method(globalThis, 'fetch', async (path: string) => {
    paths.push(path);
    if (path.endsWith('/commit')) throw new TypeError('Risposta interrotta');
    return new Response(
      JSON.stringify({
        operations: [
          { root, stage: 'COMMITTED', changedFiles: 1, branch: report.git!.branch, commit },
        ],
      }),
    );
  });
  const component = view('APPLIED');
  component.confirmation.set({ kind: 'commit', root });
  await component.performConfirmed();
  assert.deepEqual(paths, [
    '/api/jobs/job-server-owned/commit',
    '/api/jobs/job-server-owned/operations',
  ]);
  assert.equal(component.operation(report)?.commit, commit);
  assert.equal(component.operationError(), '');
  assert.ok(component.operationMessage().includes(commit));
  assert.equal(component.canOperate('commit', report), false);
  assert.equal(component.confirmation(), null);
});

test('se non si conosce esito della scrittura tutte le azioni restano bloccate', async (t) => {
  t.after(() => mock.restoreAll());
  mock.method(globalThis, 'fetch', async () => {
    throw new TypeError('Servizio non raggiungibile');
  });
  const component = view('REVIEWED');
  component.confirmation.set({ kind: 'apply', root });
  await component.performConfirmed();
  assert.equal(component.uncertainMutation(), true);
  assert.equal(component.configurationLocked(), true);
  for (const kind of ['review', 'apply', 'commit', 'push'] as const)
    assert.equal(component.canOperate(kind, report), false);
  const directory = view('APPLIED');
  directory.reports = [{ ...report, git: null }];
  assert.equal(directory.configurationLocked(), false);
  assert.equal(directory.canOperate('commit', directory.reports[0]), false);
});

test('un commit ancora da completare impedisce reset e ricaricamento involontari', (t) => {
  t.after(() => mock.restoreAll());
  mock.method(
    globalThis,
    'fetch',
    async () =>
      new Response(
        JSON.stringify({
          toolVersion: 'test',
          engineVersion: 'test',
          baseDirectory: 'C:/repos',
        }),
      ),
  );
  const component = new AppComponent();
  component.stage.set('review');
  component.pendingCommit.set(true);
  component.newSession();
  assert.equal(component.stage(), 'review');
  let prevented = false;
  const event = {
    preventDefault() {
      prevented = true;
    },
    returnValue: 'original',
  } as unknown as BeforeUnloadEvent;
  component.protectPendingOperation(event);
  assert.equal(prevented, true);
  assert.equal(event.returnValue, '');
  component.pendingCommit.set(false);
  component.newSession();
  assert.equal(component.stage(), 'welcome');
});
test('una risposta persa non sblocca la configurazione se il motore sta ancora scrivendo', async (t) => {
  t.after(() => mock.restoreAll());
  mock.method(globalThis, 'fetch', async (path: string) => {
    if (path.endsWith('/apply')) throw new TypeError('Connessione interrotta');
    return new Response(
      JSON.stringify({
        busy: true,
        operations: [{ root, stage: 'REVIEWED', changedFiles: 1, branch: report.git!.branch }],
      }),
    );
  });
  const component = view('REVIEWED');
  component.confirmation.set({ kind: 'apply', root });
  await component.performConfirmed();
  assert.equal(component.uncertainMutation(), true);
  assert.equal(component.configurationLocked(), true);
  assert.equal(component.canOperate('apply', report), false);
});

test('un GET precedente al commit non sovrascrive il commit appena completato', async (t) => {
  t.after(() => mock.restoreAll());
  const commit = 'c'.repeat(40);
  let completeRead!: (response: Response) => void;
  const previousRead = new Promise<Response>((resolve) => {
    completeRead = resolve;
  });
  mock.method(globalThis, 'fetch', async (path: string) => {
    if (path.endsWith('/operations')) return previousRead;
    assert.ok(path.endsWith('/commit'));
    return new Response(
      JSON.stringify({
        root,
        stage: 'COMMITTED',
        changedFiles: 1,
        branch: report.git!.branch,
        commit,
      }),
    );
  });
  const component = view('APPLIED');
  const pending = component.refreshOperations();
  component.confirmation.set({ kind: 'commit', root });
  await component.performConfirmed();
  assert.equal(component.operation(report)?.commit, commit);
  completeRead(
    new Response(
      JSON.stringify({
        operations: [{ root, stage: 'APPLIED', changedFiles: 1, branch: report.git!.branch }],
      }),
    ),
  );
  assert.equal(await pending, false);
  assert.equal(component.operation(report)?.stage, 'COMMITTED');
  assert.equal(component.operation(report)?.commit, commit);
  assert.equal(component.configurationLocked(), false);
  assert.equal(component.canOperate('commit', report), false);
});

test('un errore GET tardivo non invalida lo stato aggiornato dopo apply', async (t) => {
  t.after(() => mock.restoreAll());
  let failRead!: (error: Error) => void;
  const previousRead = new Promise<Response>((_, reject) => {
    failRead = reject;
  });
  mock.method(globalThis, 'fetch', async (path: string) => {
    if (path.endsWith('/operations')) return previousRead;
    assert.ok(path.endsWith('/apply'));
    return new Response(
      JSON.stringify({ root, stage: 'APPLIED', changedFiles: 1, branch: report.git!.branch }),
    );
  });
  const component = view('REVIEWED');
  const pending = component.refreshOperations();
  component.confirmation.set({ kind: 'apply', root });
  await component.performConfirmed();
  failRead(new TypeError('Errore di una lettura precedente alla scrittura'));
  assert.equal(await pending, false);
  assert.equal(component.operation(report)?.stage, 'APPLIED');
  assert.equal(component.operationsLoaded(), true);
  assert.equal(component.operationError(), '');
  assert.equal(component.canOperate('commit', report), true);
  assert.equal(component.configurationLocked(), true);
});

test('il selettore apre una sola richiesta e la cartella di lavoro non diventa un repository', async (t) => {
  t.after(() => mock.restoreAll());
  const requests: string[] = [];
  let choose!: (response: Response) => void;
  mock.method(globalThis, 'fetch', async (path: string) => {
    requests.push(path);
    if (path.endsWith('/pick'))
      return new Promise<Response>((resolve) => {
        choose = resolve;
      });
    if (path.endsWith('/workspace')) return new Response(JSON.stringify({ path: 'C:/migrazioni' }));
    return new Response(
      JSON.stringify({ toolVersion: 'test', engineVersion: 'test', baseDirectory: 'C:/repos' }),
    );
  });
  const component = new AppComponent();
  const first = component.pick('workspace');
  await component.pick('workspace');
  assert.equal(component.picking(), true);
  assert.equal(requests.filter((path) => path.endsWith('/pick')).length, 1);
  choose(new Response(JSON.stringify({ path: 'C:/migrazioni' })));
  await first;
  assert.equal(component.picking(), false);
  assert.equal(component.workPath(), 'C:/migrazioni');
  assert.deepEqual(component.roots(), []);
  await component.begin();
  assert.equal(component.workDirectory(), 'C:/migrazioni');
  assert.equal(component.stage(), 'configure');
  component.newSession();
  assert.equal(component.workDirectory(), '');
});

test('annullare o fallire la selezione permette un nuovo tentativo', async (t) => {
  t.after(() => mock.restoreAll());
  let attempts = 0;
  mock.method(globalThis, 'fetch', async (path: string) => {
    if (path.endsWith('/bootstrap')) return new Response('{}');
    attempts++;
    if (attempts === 1) throw new TypeError('Errore del selettore');
    return new Response(JSON.stringify({ path: null }));
  });
  const component = new AppComponent();
  await component.pick('workspace');
  assert.equal(component.picking(), false);
  assert.equal(component.error(), 'Errore del selettore');
  await component.pick('workspace');
  assert.equal(attempts, 2);
  assert.equal(component.picking(), false);
  assert.equal(component.error(), '');
  assert.equal(component.workPath(), '');
});

test('la modifica temporaneamente invalida del YAML conserva la ricetta selezionata', (t) => {
  t.after(() => mock.restoreAll());
  mock.method(globalThis, 'fetch', async () => new Response('{}'));
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const component = new AppComponent();
  component.recipe.set('it.Second');
  component.setYaml('name: it.Second\nrecipeList: [');
  assert.ok(component.parsed().error);
  assert.equal(component.recipe(), 'it.Second');
  component.setYaml('name: it.First\n---\nname: it.Second\n');
  assert.equal(component.recipe(), 'it.Second');
  component.setYaml('name: it.First\n');
  assert.equal(component.recipe(), '');
});

test('modificare il gruppo conserva i campi Git già compilati e l’anteprima', (t) => {
  t.after(() => mock.restoreAll());
  mock.method(globalThis, 'fetch', async () => new Response('{}'));
  const info = { root, name: 'ordine', group: '', git: report.git, yamlFiles: [] };
  const component = new GitPreparationComponent();
  component.root = info;
  component.workDirectory = 'C:/migrazioni';
  component.ngOnChanges({
    root: {
      previousValue: undefined,
      currentValue: info,
      firstChange: true,
      isFirstChange: () => true,
    },
  });
  assert.equal(component.worktree, 'C:/migrazioni/ordine-migration');
  component.source = 'release/2026';
  component.worktree = 'C:/migrazioni/scelta-personale';
  component.root = { ...info, group: 'Pagamenti' };
  component.ngOnChanges({
    root: {
      previousValue: info,
      currentValue: component.root,
      firstChange: false,
      isFirstChange: () => false,
    },
  });
  assert.equal(component.source, 'release/2026');
  assert.equal(component.worktree, 'C:/migrazioni/scelta-personale');
  const app = new AppComponent();
  app.roots.set([info]);
  app.job.set({ id: 'anteprima', status: 'COMPLETED', reports: [report] });
  app.changeGroup(root, 'Pagamenti');
  assert.equal(app.roots()[0].group, 'Pagamenti');
  assert.equal(app.job()?.id, 'anteprima');
});

test('Invio non avvia una seconda ispezione mentre una cartella è in caricamento', async (t) => {
  t.after(() => mock.restoreAll());
  let inspections = 0;
  let inspect!: (response: Response) => void;
  mock.method(globalThis, 'fetch', async (path: string) => {
    if (path.endsWith('/bootstrap')) return new Response('{}');
    inspections++;
    return new Promise<Response>((resolve) => {
      inspect = resolve;
    });
  });
  const app = new AppComponent();
  const first = app.addRoot(root);
  assert.equal(await app.addRoot('C:/altro-repository'), false);
  assert.equal(inspections, 1);
  inspect(new Response(JSON.stringify({ root, name: 'ordine', git: report.git, yamlFiles: [] })));
  assert.equal(await first, true);
  assert.deepEqual(
    app.roots().map((item) => item.root),
    [root],
  );
  assert.equal(app.loading(), false);
});

test('scegliere un altro repository rimuove messaggi e SHA senza perdere lo stato del commit', async (t) => {
  t.after(() => mock.restoreAll());
  const second = { ...report, root: 'C:/repos/pagamenti-migration' };
  const commit = 'd'.repeat(40);
  mock.method(globalThis, 'fetch', async (path: string) => {
    assert.ok(path.endsWith('/commit'));
    return new Response(
      JSON.stringify({
        root,
        stage: 'COMMITTED',
        changedFiles: 1,
        branch: report.git!.branch,
        commit,
      }),
    );
  });
  const component = view('APPLIED');
  component.reports = [report, second];
  component.remoteOptions.set({ [root]: ['origin'], [second.root]: ['origin'] });
  component.confirmation.set({ kind: 'commit', root });
  await component.performConfirmed();
  assert.ok(component.operationMessage().includes(commit));
  component.operationError.set('Messaggio del repository precedente');
  component.selectReport(second);
  assert.equal(component.activeReport().root, second.root);
  assert.equal(component.operationMessage(), '');
  assert.equal(component.operationError(), '');
  assert.equal(component.operation(report)?.stage, 'COMMITTED');
  assert.equal(component.operation(report)?.commit, commit);
});

test('un errore Git tardivo di A non modifica errore e caricamento del repository B', async (t) => {
  t.after(() => mock.restoreAll());
  const second = { ...report, root: 'C:/repos/pagamenti-migration' };
  for (const cached of [false, true]) {
    const requests: { resolve: (response: Response) => void; reject: (error: Error) => void }[] =
      [];
    mock.method(globalThis, 'fetch', (path: string) => {
      assert.equal(path, '/api/git/details');
      return new Promise<Response>((resolve, reject) => requests.push({ resolve, reject }));
    });
    const component = view('COMMITTED');
    component.reports = [report, second];
    component.remoteOptions.set(cached ? { [second.root]: ['origin-b'] } : {});
    component.selectReport(report);
    component.selectReport(second);
    assert.equal(requests.length, cached ? 1 : 2);
    assert.equal(component.gitDetailsLoading(), !cached);
    requests[0].reject(new TypeError('Errore tardivo del repository A'));
    await nextTurn();
    assert.equal(component.operationError(), '');
    assert.equal(component.gitDetailsLoading(), !cached);
    if (!cached) {
      requests[1].resolve(new Response(JSON.stringify({ remotes: ['origin-b'] })));
      await nextTurn();
    }
    assert.equal(component.gitDetailsLoading(), false);
    assert.deepEqual(component.remoteOptions()[second.root], ['origin-b']);
  }
});

test('tornare rapidamente da A a B e ad A ignora la prima lettura di A', async (t) => {
  t.after(() => mock.restoreAll());
  const second = { ...report, root: 'C:/repos/pagamenti-migration' };
  const requests: {
    root: string;
    resolve: (response: Response) => void;
    reject: (error: Error) => void;
  }[] = [];
  mock.method(globalThis, 'fetch', (path: string, options: RequestInit) => {
    assert.equal(path, '/api/git/details');
    return new Promise<Response>((resolve, reject) => {
      requests.push({ root: JSON.parse(options.body as string).root, resolve, reject });
    });
  });
  const component = view('COMMITTED');
  component.reports = [report, second];
  component.remoteOptions.set({});
  component.selectReport(report);
  component.selectReport(second);
  component.selectReport(report);
  assert.deepEqual(
    requests.map((request) => request.root),
    [root, second.root, root],
  );
  requests[0].reject(new TypeError('Errore della prima lettura di A'));
  await nextTurn();
  assert.equal(component.operationError(), '');
  assert.equal(component.gitDetailsLoading(), true);
  requests[1].resolve(new Response(JSON.stringify({ remotes: ['origin-b'] })));
  await nextTurn();
  assert.deepEqual(component.remoteOptions()[second.root], ['origin-b']);
  assert.equal(component.gitDetailsLoading(), true);
  requests[2].resolve(new Response(JSON.stringify({ remotes: ['origin-a'] })));
  await nextTurn();
  assert.equal(component.gitDetailsLoading(), false);
  assert.equal(component.operationError(), '');
  assert.deepEqual(component.remoteOptions()[root], ['origin-a']);
});
