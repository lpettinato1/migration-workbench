const token = new URLSearchParams(location.hash.slice(1)).get('token') || '';
// Conserva il token nel frammento per consentire il reload senza persistenza della sessione.
export async function api<T>(path: string, body?: unknown, signal?: AbortSignal): Promise<T> {
  const response = await fetch(`/api/${path}`, {
    method: body === undefined ? 'GET' : 'POST',
    headers: {
      'X-Workbench-Token': token,
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal,
    cache: 'no-store',
  });
  let data: unknown;
  try {
    data = await response.json();
  } catch {
    throw new Error('Il servizio locale ha restituito una risposta non leggibile.');
  }
  if (!response.ok) {
    const error = data as { error?: string; message?: string };
    throw new Error(
      error.error || error.message || `Operazione non riuscita (${response.status}).`,
    );
  }
  return data as T;
}
export function download(name: string, text: string, type = 'application/json') {
  const url = URL.createObjectURL(new Blob([text], { type }));
  const link = document.createElement('a');
  link.href = url;
  link.download = name;
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
