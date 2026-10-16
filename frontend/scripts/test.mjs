import {spawn} from 'node:child_process';
import {existsSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
import {resolve,dirname} from 'node:path';
const frontend=resolve(dirname(fileURLToPath(import.meta.url)),'..');
const sdk=resolve(frontend,'node_modules/node/bin',process.platform==='win32'?'node.exe':'node');
const child=spawn(existsSync(sdk)?sdk:process.execPath,['--import','tsx','--test','src/model.test.ts','src/api.test.ts','src/workflow.test.ts'],{cwd:frontend,stdio:'inherit',env:process.env});
child.on('error',error=>{console.error(error.message);process.exitCode=1;});
child.on('exit',code=>{process.exitCode=code??1;});