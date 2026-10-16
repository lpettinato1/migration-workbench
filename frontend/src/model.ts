import { parseAllDocuments } from 'yaml';

export type FileStatus = 'UNCHANGED' | 'MODIFIED' | 'CREATED' | 'DELETED' | 'RENAMED' | 'SKIPPED' | 'ERROR';
export interface GitSnapshot { head: string; branch: string; status: string }
export interface RootInfo { root: string; name: string; git: GitSnapshot | null; yamlFiles: string[]; group: string }
export interface FileResult { beforePath: string | null; afterPath: string | null; status: FileStatus; before: string | null; after: string | null; recipes: string[]; diff: string | null }
export interface Report { schemaVersion: number; root: string; recipe: string; resolvedRecipe: string; createdAt: string; git: GitSnapshot | null; files: FileResult[]; diagnostics: {severity: 'INFO'|'WARNING'|'ERROR'; code: string; path: string | null; message: string}[]; metrics: {discoveredFiles: number; totalMillis: number} }
export interface Bootstrap {toolVersion: string; engineVersion: string; baseDirectory: string; exampleRoot: string; exampleRules: string; exampleRecipe: string}
export interface Option {name: string; displayName?: string; description?: string; type?: string; required?: boolean; valid?: string[]; example?: string}
export interface Descriptor {name: string; displayName: string; description: string; options?: Option[]; recipeList?: Descriptor[]}
export interface Job {id: string; status: 'QUEUED'|'RUNNING'|'COMPLETED'|'FAILED'; progress?: {root: string; index: number; total: number; phase: string; path: string; message: string}; reports: Report[]; error?: string}
export interface Step {id: string; name: string; values: Record<string, unknown>; document: number; path: (string|number)[]}
export const changed = (file: FileResult) => ['MODIFIED','CREATED','DELETED','RENAMED'].includes(file.status);
export const statusLabel: Record<FileStatus,string> = {UNCHANGED:'Invariato',MODIFIED:'Modificato',CREATED:'Creato',DELETED:'Eliminato',RENAMED:'Rinominato',SKIPPED:'Ignorato',ERROR:'Errore'};
export const filePath = (file: FileResult) => file.afterPath || file.beforePath || '(senza percorso)';
export const fileKey = (root: string, file: FileResult) => JSON.stringify([root,file.beforePath,file.afterPath]);
export function parseRecipes(source: string): {names: string[]; error: string; steps: (recipe: string) => Step[]} {
  const documents = parseAllDocuments(source, { uniqueKeys: true });
  const error = documents.flatMap(d => d.errors.map(e => e.message)).join('\n');
  if(error) return {names:[],error,steps:()=>[]};
  try {
    const data = documents.map(d => d.toJS({maxAliasCount:500}));
    const names = data.map(d => typeof d?.name === 'string' ? d.name : '').filter(Boolean);
    return {names,error:'',steps:recipe=>{
      const steps: Step[] = [];
      const visit = (name: string, trail: string[]) => {
        if(trail.includes(name)) return;
        const document = data.findIndex(d => d?.name === name);
        const rules = data[document]?.recipeList;
        if(!Array.isArray(rules)) return;
        rules.forEach((item: unknown, index: number) => {
          if(typeof item==='string') {
            if(data.some(d => d?.name===item)) visit(item,[...trail,name]);
            else steps.push({id:`${document}:${index}`,name:item,values:{},document,path:['recipeList',index]});
          } else if(item && typeof item==='object' && !Array.isArray(item)) {
            const [operation, values] = Object.entries(item)[0] || [];
            if(operation) steps.push({id:`${document}:${index}`,name:operation,values:values && typeof values==='object' ? values as Record<string,unknown> : {},document,path:['recipeList',index,operation]});
          }
        });
      };
      visit(recipe,[]);
      return steps;
    }};
  } catch(error) {return {names:[],error: error instanceof Error ? error.message : 'YAML non leggibile.',steps:()=>[]};}
}
export function updateStep(source: string, step: Step, key: string, value: unknown): string {
  const documents = parseAllDocuments(source);
  if(documents.some(d=>d.errors.length)) throw new Error('Correggi il documento YAML prima di modificare il passaggio.');
  const document=documents[step.document];
  if(!document) throw new Error('Il passaggio non è più disponibile.');
  // Conversione di una ricetta senza opzioni in una mappa, solo quando modificata.
  const path=step.path.length===2 ? [...step.path,step.name,key] : [...step.path,key];
  if(step.path.length===2) document.setIn(step.path,{[step.name]:{}});
  document.setIn(path,value);
  return documents.map((doc,index)=>`${index ? '---\n' : ''}${doc.toString()}`).join('');
}
export function descriptorIndex(root: Descriptor | null): Map<string,Descriptor> {
  const index = new Map<string,Descriptor>();
  const visit=(item: Descriptor)=>{index.set(item.name,item); item.recipeList?.forEach(visit);};
  if(root) visit(root);
  return index;
}
export function parametersMap(rows: {name:string;value:string}[]): Record<string,string> {
  const result: Record<string,string>=Object.create(null);
  for(const row of rows) {
    if(!row.name.trim()) {if(row.value) throw new Error('Assegna un nome al parametro compilato.'); continue;}
    const name=row.name.trim();
    if(!/^migration\.[A-Za-z0-9_.-]+$/.test(name))throw new Error(`Il parametro «${name}» deve iniziare con migration., ad esempio migration.endpoint.`);
    if(Object.hasOwn(result,name)) throw new Error(`Il parametro «${name}» è ripetuto.`);
    result[name]=row.value;
  }
  return result;
}
export function groupFiles(files: FileResult[]): Map<string,FileResult[]> {
  const groups = new Map<string,FileResult[]>();
  for(const file of files) {
    const path=filePath(file).replaceAll('\\','/');
    const directory=path.includes('/') ? path.slice(0,path.lastIndexOf('/')) : 'Cartella principale';
    groups.set(directory,[...(groups.get(directory)||[]),file]);
  }
  return new Map([...groups.entries()].sort(([a],[b])=>a.localeCompare(b)));
}

export interface GitDetails { branches: string[]; remotes: string[]; git: GitSnapshot }
export interface OperationState { root: string; stage: 'PREVIEW'|'REVIEWED'|'APPLIED'|'COMMITTED'|'PUSHED'; changedFiles: number; commit?: string; branch?: string; remote?: string }
export function reviewedPaths(report: Report, reviewed: ReadonlySet<string>): string[] {
  if(report.diagnostics.some(item=>item.severity==='ERROR') || report.files.some(file=>file.status==='ERROR'))
    throw new Error('Correggi gli errori e genera una nuova anteprima prima di confermare la review.');
  const files=report.files.filter(changed);
  if(!files.length) throw new Error('Il repository non contiene modifiche da applicare.');
  if(files.some(file=>!reviewed.has(fileKey(report.root,file))))
    throw new Error('Controlla e segna come verificati tutti i file modificati prima di confermare la review.');
  return files.map(file=>filePath(file));
}
