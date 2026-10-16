import {test} from 'node:test';
import assert from 'node:assert/strict';
import {parseRecipes,updateStep,parametersMap,groupFiles,fileKey,changed,reviewedPaths} from './model';
import type {FileResult} from './model';
const yaml=`name: it.Root
recipeList:
  - it.Values
  - it.Custom:
      enabled: true
---
name: it.Values
preconditions:
  - org.openrewrite.FindSourceFiles:
      filePattern: 'deploy/**/values.yaml'
recipeList:
  - org.openrewrite.yaml.ChangePropertyValue:
      propertyKey: app.endpoint
      newValue: '\${migration.endpoint:default}'
  - org.openrewrite.yaml.DeleteKey:
      keyPath: $.app.old
`;
test('composizione multidocumento conserva ordine e opzioni dei riferimenti',()=>{
 const parsed=parseRecipes(yaml);assert.equal(parsed.error,'');assert.deepEqual(parsed.names,['it.Root','it.Values']);
 assert.deepEqual(parsed.steps('it.Root').map(s=>s.name),['org.openrewrite.yaml.ChangePropertyValue','org.openrewrite.yaml.DeleteKey','it.Custom']);
 assert.equal(parsed.steps('it.Root')[0].values.newValue,'${migration.endpoint:default}');
});
test('modifica guidata aggiorna solo il passaggio selezionato e conserva precondizioni',()=>{
 const step=parseRecipes(yaml).steps('it.Root')[0];
 const result=updateStep(yaml,step,'newValue','host: "quoted"\nnext');
 const parsed=parseRecipes(result);assert.equal(parsed.error,'');
 assert.equal(parsed.steps('it.Root')[0].values.newValue,'host: "quoted"\nnext');
 assert.equal(parsed.steps('it.Root')[1].values.keyPath,'$.app.old');
 assert.equal(parsed.steps('it.Root')[2].values.enabled,true);
 assert.ok(result.includes('filePattern:'));
});
test('chiavi duplicate e YAML malformato hanno diagnostica invece di un piano parziale',()=>{
 assert.notEqual(parseRecipes('name: a\nname: b\n').error,'');
 assert.notEqual(parseRecipes('recipeList: [broken').error,'');
 assert.throws(()=>updateStep('recipeList: [broken',parseRecipes(yaml).steps('it.Root')[0],'x','y'));
});
test('parametri duplicati e valori senza nome vengono rifiutati',()=>{
 assert.throws(()=>parametersMap([{name:'migration.endpoint',value:'a'},{name:'migration.endpoint',value:'b'}]));
 assert.throws(()=>parametersMap([{name:'',value:'a'}]));
 assert.throws(()=>parametersMap([{name:'endpoint',value:'ignored-default'}]));
 assert.equal(parametersMap([{name:'migration.endpoint',value:'a=b\n"quoted"'}])['migration.endpoint'],'a=b\n"quoted"');
});
const file=(path:string,status:FileResult['status']='MODIFIED'):FileResult=>({beforePath:path,afterPath:path,status,before:'old',after:'new',recipes:[],diff:''});
test('cartelle con ambienti arbitrari restano distinte e una rinomina usa il nuovo percorso',()=>{
 const a=file('deploy/Blue_42/values.yaml'),b=file('deploy/UAT-EU/values.yaml');
 const renamed={...file('old.yaml','RENAMED'),afterPath:'elsewhere/new.yaml'};
 assert.deepEqual([...groupFiles([a,b,renamed]).keys()],['deploy/Blue_42','deploy/UAT-EU','elsewhere']);
 assert.notEqual(fileKey('/repo-a',a),fileKey('/repo-b',a));
 assert.notEqual(fileKey('/repo-a',renamed),fileKey('/repo-a',a));
});
test('stati ignorati ed errori non sono conteggiati come modifiche',()=>{
 assert.equal(changed(file('helm.yaml','SKIPPED')),false);assert.equal(changed(file('bad.yaml','ERROR')),false);
 for(const status of ['MODIFIED','CREATED','DELETED','RENAMED'] as const)assert.equal(changed(file('a.yaml',status)),true);
});

test('la review richiede tutti i file modificati della root corretta, ma non quelli ignorati',()=>{
 const files=[file('deploy/Blue_42/values.yaml'),file('helm/template.yaml','SKIPPED'),{...file('old.yaml','RENAMED'),afterPath:'new.yaml'},{...file('remove.yaml','DELETED'),afterPath:null}];
 const report={root:'/repo-a',files,diagnostics:[],schemaVersion:1,recipe:'it.Migration',resolvedRecipe:'',createdAt:'',git:null,metrics:{discoveredFiles:4,totalMillis:1}};
 const marks=new Set<string>();
 assert.throws(()=>reviewedPaths(report,marks),/tutti i file modificati/);
 files.filter(changed).forEach(item=>marks.add(fileKey('/repo-b',item)));
 assert.throws(()=>reviewedPaths(report,marks),/tutti i file modificati/);
 files.filter(changed).forEach(item=>marks.add(fileKey('/repo-a',item)));
 assert.deepEqual(reviewedPaths(report,marks),['deploy/Blue_42/values.yaml','new.yaml','remove.yaml']);
});

test('errori e assenza di modifiche impediscono di approvare la review',()=>{
 const modified=file('config.yaml');
 const report={root:'/repo',files:[modified],diagnostics:[],schemaVersion:1,recipe:'it.Migration',resolvedRecipe:'',createdAt:'',git:null,metrics:{discoveredFiles:1,totalMillis:1}};
 const marks=new Set([fileKey('/repo',modified)]);
 assert.throws(()=>reviewedPaths({...report,files:[file('config.yaml','UNCHANGED')]},marks),/non contiene modifiche/);
 assert.throws(()=>reviewedPaths({...report,files:[modified,file('bad.yaml','ERROR')]},marks),/Correggi gli errori/);
 assert.throws(()=>reviewedPaths({...report,diagnostics:[{severity:'ERROR',code:'E',path:null,message:'input non valido'}]},marks),/Correggi gli errori/);
});