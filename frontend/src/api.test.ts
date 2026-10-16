import { test, mock } from 'node:test';
import assert from 'node:assert/strict';

function browser(t: { after: (cleanup: () => void) => void }, hash: string) {
  const names = ['location', 'history'] as const;
  const previous = names.map((name) => Object.getOwnPropertyDescriptor(globalThis, name));
  const location = { hash, pathname: '/', search: '' };
  Object.defineProperty(globalThis, 'location', { configurable: true, value: location });
  Object.defineProperty(globalThis, 'history', {
    configurable: true,
    value: {
      replaceState(_data: unknown, _title: string, url: string) {
        location.hash = new URL(url, 'http://localhost').hash;
      },
    },
  });
  t.after(() => {
    mock.restoreAll();
    names.forEach((name, index) => {
      const descriptor = previous[index];
      if (descriptor) Object.defineProperty(globalThis, name, descriptor);
      else Reflect.deleteProperty(globalThis, name);
    });
  });
  return location;
}

test('il collegamento conserva accesso API anche dopo il reload senza memoria persistente', async (t) => {
  const location = browser(t, '#token=token-locale-di-test');
  const requests: { path: string; token: string | null }[] = [];
  mock.method(globalThis, 'fetch', async (path: string, options: RequestInit) => {
    requests.push({ path, token: new Headers(options.headers).get('X-Workbench-Token') });
    return new Response('{"toolVersion":"0.2.0"}', { status: 200 });
  });
  const first = await import('./api.ts?load=first');
  await first.api('bootstrap');
  const reloaded = await import('./api.ts?load=reloaded');
  await reloaded.api('bootstrap');
  assert.equal(location.hash, '#token=token-locale-di-test');
  assert.deepEqual(requests, [
    { path: '/api/bootstrap', token: 'token-locale-di-test' },
    { path: '/api/bootstrap', token: 'token-locale-di-test' },
  ]);
});

test('errori del servizio leggibili e risposte non JSON rifiutate', async (t) => {
  browser(t, '#token=error-test');
  const fetch = mock.method(
    globalThis,
    'fetch',
    async () => new Response('{"error":"Cartella non trovata: C:/assente"}', { status: 400 }),
  );
  const client = await import('./api.ts?load=errors');
  await assert.rejects(client.api('inspect', { root: 'C:/assente' }), {
    message: 'Cartella non trovata: C:/assente',
  });
  fetch.mock.mockImplementation(async () => new Response('<html>proxy</html>', { status: 502 }));
  await assert.rejects(client.api('bootstrap'), {
    message: 'Il servizio locale ha restituito una risposta non leggibile.',
  });
});
