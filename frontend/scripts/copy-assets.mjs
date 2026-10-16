import {cp, mkdir, readdir, rm, readFile, writeFile} from 'node:fs/promises';
import {resolve, dirname, relative, sep} from 'node:path';
import {fileURLToPath} from 'node:url';
const frontend=resolve(dirname(fileURLToPath(import.meta.url)),'..');
const project=resolve(frontend,'..');
const destination=resolve(project,'src/main/resources/ui');
const expected=relative(project,destination);
if(expected!==['src','main','resources','ui'].join(sep))throw new Error('Destinazione asset non consentita.');
await mkdir(destination,{recursive:true});
for(const entry of await readdir(destination)) {
  const target=resolve(destination,entry);
  if(dirname(target)!==destination)throw new Error('Percorso asset non valido.');
  await rm(target,{recursive:true,force:true});
}
await cp(resolve(frontend,'dist/browser'),destination,{recursive:true});
await cp(resolve(frontend,'dist/3rdpartylicenses.txt'),resolve(destination,'3rdpartylicenses.txt'));
const index=resolve(destination,'index.html');
const html=await readFile(index,'utf8');
await writeFile(index,html.replace(/<base\b[^>]*>/gi,'').replace(/(src|href)="(?!\/|data:|https?:)([^"]+\.(?:js|css))"/g,'$1="/ui/$2"'));
