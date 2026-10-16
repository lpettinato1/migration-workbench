import {
  type AfterViewInit, ChangeDetectionStrategy, Component, ElementRef, EventEmitter, Input,
  type OnChanges, type OnDestroy, Output, type SimpleChanges, ViewChild} from '@angular/core';
import {EditorState} from '@codemirror/state';
import {EditorView,keymap,lineNumbers,highlightActiveLineGutter,highlightSpecialChars,drawSelection,highlightActiveLine} from '@codemirror/view';
import {defaultKeymap,history,historyKeymap,indentWithTab} from '@codemirror/commands';
import {syntaxHighlighting,HighlightStyle,bracketMatching,indentOnInput,foldGutter} from '@codemirror/language';
import {yaml} from '@codemirror/lang-yaml';
import {autocompletion} from '@codemirror/autocomplete';
import type {CompletionContext, Completion} from '@codemirror/autocomplete';
import {MergeView} from '@codemirror/merge';
import {tags} from '@lezer/highlight';
import type {Descriptor} from './model';

const theme=EditorView.theme({
  '&':{color:'#e0e3e9',backgroundColor:'#252629',height:'100%',fontSize:'13px'},
  '.cm-content':{fontFamily:'Consolas, "Cascadia Code", monospace',padding:'12px 0',caretColor:'#e0e3e9'},
  '.cm-scroller':{overflow:'auto',fontFamily:'Consolas, "Cascadia Code", monospace',lineHeight:'1.7'},
  '.cm-gutters':{backgroundColor:'#252629',color:'#858c99',border:'none',paddingRight:'10px'},
  '.cm-activeLine,.cm-activeLineGutter':{backgroundColor:'#ffffff06'},
  '&.cm-focused .cm-selectionBackground,.cm-selectionBackground,.cm-content ::selection':{backgroundColor:'#38547a'},
  '.cm-cursor':{borderLeftColor:'#e0e3e9'},
  '.cm-tooltip':{backgroundColor:'#313438',border:'1px solid #4a4e57',color:'#e0e3e9'},
  '.cm-tooltip-autocomplete ul li[aria-selected]':{backgroundColor:'#354a6d',color:'#fff'},
  '.cm-foldGutter':{width:'14px'},
},{dark:true});
const highlight=syntaxHighlighting(HighlightStyle.define([
  {tag:tags.propertyName,color:'#ddb785'}, {tag:tags.string,color:'#a5c69a'},
  {tag:[tags.bool,tags.null,tags.number],color:'#b3a0dc'}, {tag:tags.comment,color:'#8a929d',fontStyle:'italic'},
  {tag:tags.meta,color:'#7da7ff'}, {tag:tags.punctuation,color:'#c3c6ce'},
]));
const base=()=>[theme,highlight,yaml(),lineNumbers(),highlightSpecialChars(),drawSelection(),bracketMatching(),foldGutter()];

@Component({selector:'mw-editor',standalone:true,changeDetection:ChangeDetectionStrategy.OnPush,template:'<div class="code-editor" #host></div>'})
export class EditorComponent implements AfterViewInit, OnChanges, OnDestroy {
  @ViewChild('host',{static:true}) host!: ElementRef<HTMLDivElement>;
  @Input() value='';
  @Input() label='Editor YAML';
  @Input() readOnly=false;
  @Input() catalog: Descriptor|null=null;
  @Output() valueChange=new EventEmitter<string>();
  private editor?:EditorView;
  private changing=false;
  ngAfterViewInit() {
    const completion=(context:CompletionContext)=>{
      const word=context.matchBefore(/[\w.]+/);
      if(!word && !context.explicit) return null;
      const options:Completion[]=[{label:'recipeList',type:'property',detail:'Passaggi eseguiti in ordine',apply:'recipeList:\n  - '},{label:'preconditions',type:'property',detail:'Condizioni per selezionare i file',apply:'preconditions:\n  - '},{label:'name',type:'property',detail:'Identificativo univoco',apply:'name: '},{label:'displayName',type:'property',detail:'Nome leggibile',apply:'displayName: '},{label:'description',type:'property',apply:'description: '}];
      const visit=(item:Descriptor)=>{options.push({label:item.name,type:'class',detail:item.displayName,info:item.description}); for(const option of item.options||[]) options.push({label:option.name,type:'property',detail:option.displayName||option.type,apply:`${option.name}: `,info:option.description}); item.recipeList?.forEach(visit);};
      if(this.catalog) visit(this.catalog);
      return {from:word?.from ?? context.pos,options:[...new Map(options.map(item=>[item.label,item])).values()]};
    };
    this.editor=new EditorView({parent:this.host.nativeElement,state:EditorState.create({doc:this.value,extensions:[...base(),EditorView.contentAttributes.of({'aria-label':this.label}),EditorState.readOnly.of(this.readOnly),EditorView.editable.of(!this.readOnly),...(this.readOnly?[]:[history(),indentOnInput(),highlightActiveLine(),highlightActiveLineGutter(),keymap.of([indentWithTab,...defaultKeymap,...historyKeymap]),autocompletion({override:[completion]}),EditorView.updateListener.of(update=>{if(update.docChanged && !this.changing) this.valueChange.emit(update.state.doc.toString());})])]})});
  }
  ngOnChanges(changes:SimpleChanges) {
    if(changes['value'] && this.editor && this.editor.state.doc.toString()!==this.value) {
      this.changing=true;
      this.editor.dispatch({changes:{from:0,to:this.editor.state.doc.length,insert:this.value}});
      this.changing=false;
    }
  }
  ngOnDestroy(){this.editor?.destroy();}
}

@Component({selector:'mw-diff',standalone:true,changeDetection:ChangeDetectionStrategy.OnPush,template:'<div class="diff-editor" #host></div>'})
export class DiffComponent implements AfterViewInit, OnChanges, OnDestroy {
  @ViewChild('host',{static:true}) host!:ElementRef<HTMLDivElement>;
  @Input() before=''; @Input() after='';
  private view?:MergeView;
  ngAfterViewInit(){this.render();}
  ngOnChanges(){if(this.view) this.render();}
  private render(){
    this.view?.destroy();
    this.view=new MergeView({parent:this.host.nativeElement,a:{doc:this.before,extensions:[...base(),EditorState.readOnly.of(true),EditorView.editable.of(false),EditorView.contentAttributes.of({'aria-label':'YAML prima della migrazione'})]},b:{doc:this.after,extensions:[...base(),EditorState.readOnly.of(true),EditorView.editable.of(false),EditorView.contentAttributes.of({'aria-label':'YAML dopo la migrazione'})]},highlightChanges:true,gutter:true,collapseUnchanged:{margin:4,minSize:8}});
  }
  ngOnDestroy(){this.view?.destroy();}
}
