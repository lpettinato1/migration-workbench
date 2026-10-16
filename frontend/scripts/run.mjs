import { spawn } from 'node:child_process';
import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';

const frontend = fileURLToPath(new URL('..', import.meta.url));
const sdk = resolve(
  frontend,
  'node_modules/node/bin',
  process.platform === 'win32' ? 'node.exe' : 'node',
);
const [tool, ...args] = process.argv.slice(2);
const commands = {
  angular: [resolve(frontend, 'node_modules/@angular/cli/bin/ng.js'), ...args],
  test: ['--import', 'tsx', '--test', 'src/*.test.ts'],
};
if (!Object.hasOwn(commands, tool)) throw new Error(`Comando di sviluppo sconosciuto: ${tool}`);
const child = spawn(existsSync(sdk) ? sdk : process.execPath, commands[tool], {
  cwd: frontend,
  stdio: 'inherit',
});
child.on('error', (error) => {
  console.error(error.message);
  process.exitCode = 1;
});
child.on('exit', (code) => {
  process.exitCode = code ?? 1;
});
