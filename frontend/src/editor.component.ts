import {
  type AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  EventEmitter,
  Input,
  type OnChanges,
  type OnDestroy,
  Output,
  type SimpleChanges,
  ViewChild,
} from '@angular/core';
import { EditorState } from '@codemirror/state';
import {
  EditorView,
  keymap,
  lineNumbers,
  highlightActiveLineGutter,
  highlightSpecialChars,
  drawSelection,
  highlightActiveLine,
} from '@codemirror/view';
import { defaultKeymap, history, historyKeymap, indentWithTab } from '@codemirror/commands';
import {
  syntaxHighlighting,
  HighlightStyle,
  bracketMatching,
  indentOnInput,
  foldGutter,
} from '@codemirror/language';
import { yaml } from '@codemirror/lang-yaml';
import { autocompletion } from '@codemirror/autocomplete';
import type { CompletionContext, Completion } from '@codemirror/autocomplete';
import { MergeView } from '@codemirror/merge';
import { tags } from '@lezer/highlight';
import type { Descriptor } from './model';
import { descriptorIndex } from './model';

const theme = EditorView.theme(
  {
    '&': { color: 'var(--text)', backgroundColor: 'var(--bg)', height: '100%', fontSize: '13px' },
    '.cm-content': {
      fontFamily: 'Consolas, "Cascadia Code", monospace',
      padding: '12px 0',
      caretColor: 'var(--text)',
    },
    '.cm-scroller': {
      overflow: 'auto',
      fontFamily: 'Consolas, "Cascadia Code", monospace',
      lineHeight: '1.7',
    },
    '.cm-gutters': {
      backgroundColor: 'var(--bg)',
      color: 'var(--muted)',
      border: 'none',
      paddingRight: '10px',
    },
    '.cm-activeLine,.cm-activeLineGutter': { backgroundColor: 'var(--surface)' },
    '&.cm-focused .cm-selectionBackground,.cm-selectionBackground,.cm-content ::selection': {
      backgroundColor: 'var(--selected)',
    },
    '.cm-cursor': { borderLeftColor: 'var(--accent)' },
    '.cm-tooltip': {
      backgroundColor: 'var(--surface)',
      border: '1px solid var(--line)',
      color: 'var(--text)',
    },
    '.cm-tooltip-autocomplete ul li[aria-selected]': {
      backgroundColor: 'var(--selected)',
      color: 'var(--text)',
    },
    '.cm-foldGutter': { width: '14px' },
  },
  { dark: true },
);
const highlight = syntaxHighlighting(
  HighlightStyle.define([
    { tag: tags.propertyName, color: '#cbb997' },
    { tag: tags.string, color: '#b7c2aa' },
    { tag: [tags.bool, tags.null, tags.number], color: '#c6b8ca' },
    { tag: tags.comment, color: 'var(--muted)', fontStyle: 'italic' },
    { tag: tags.meta, color: '#c4bbac' },
    { tag: tags.punctuation, color: 'var(--muted)' },
  ]),
);
const base = () => [
  theme,
  highlight,
  yaml(),
  lineNumbers(),
  highlightSpecialChars(),
  drawSelection(),
  bracketMatching(),
  foldGutter(),
];

@Component({
  selector: 'mw-editor',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: '<div class="code-editor" #host></div>',
})
export class EditorComponent implements AfterViewInit, OnChanges, OnDestroy {
  @ViewChild('host', { static: true }) host!: ElementRef<HTMLDivElement>;
  @Input() value = '';
  @Input() label = 'Editor YAML';
  @Input() readOnly = false;
  @Input() catalog: Descriptor | null = null;
  @Output() valueChange = new EventEmitter<string>();
  private editor?: EditorView;
  private changing = false;
  ngAfterViewInit() {
    const completion = (context: CompletionContext) => {
      const word = context.matchBefore(/[\w.]+/);
      if (!word && !context.explicit) return null;
      const options: Completion[] = [
        {
          label: 'recipeList',
          type: 'property',
          detail: 'Passaggi eseguiti in ordine',
          apply: 'recipeList:\n  - ',
        },
        {
          label: 'preconditions',
          type: 'property',
          detail: 'Condizioni per selezionare i file',
          apply: 'preconditions:\n  - ',
        },
        { label: 'name', type: 'property', detail: 'Identificativo univoco', apply: 'name: ' },
        {
          label: 'displayName',
          type: 'property',
          detail: 'Nome leggibile',
          apply: 'displayName: ',
        },
        { label: 'description', type: 'property', apply: 'description: ' },
      ];
      for (const item of descriptorIndex(this.catalog).values()) {
        options.push({
          label: item.name,
          type: 'class',
          detail: item.displayName,
          info: item.description,
        });
        for (const option of item.options || [])
          options.push({
            label: option.name,
            type: 'property',
            detail: option.displayName || option.type,
            apply: `${option.name}: `,
            info: option.description,
          });
      }
      return {
        from: word?.from ?? context.pos,
        options: [...new Map(options.map((item) => [item.label, item])).values()],
      };
    };
    this.editor = new EditorView({
      parent: this.host.nativeElement,
      state: EditorState.create({
        doc: this.value,
        extensions: [
          ...base(),
          EditorView.contentAttributes.of({ 'aria-label': this.label }),
          EditorState.readOnly.of(this.readOnly),
          EditorView.editable.of(!this.readOnly),
          ...(this.readOnly
            ? []
            : [
                history(),
                indentOnInput(),
                highlightActiveLine(),
                highlightActiveLineGutter(),
                keymap.of([indentWithTab, ...defaultKeymap, ...historyKeymap]),
                autocompletion({ override: [completion] }),
                EditorView.updateListener.of((update) => {
                  if (update.docChanged && !this.changing)
                    this.valueChange.emit(update.state.doc.toString());
                }),
              ]),
        ],
      }),
    });
  }
  ngOnChanges(changes: SimpleChanges) {
    if (changes['value'] && this.editor && this.editor.state.doc.toString() !== this.value) {
      this.changing = true;
      this.editor.dispatch({
        changes: { from: 0, to: this.editor.state.doc.length, insert: this.value },
      });
      this.changing = false;
    }
  }
  ngOnDestroy() {
    this.editor?.destroy();
  }
}

@Component({
  selector: 'mw-diff',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: '<div class="diff-editor" #host></div>',
})
export class DiffComponent implements AfterViewInit, OnChanges, OnDestroy {
  @ViewChild('host', { static: true }) host!: ElementRef<HTMLDivElement>;
  @Input() before = '';
  @Input() after = '';
  private view?: MergeView;
  ngAfterViewInit() {
    this.render();
  }
  ngOnChanges() {
    if (this.view) this.render();
  }
  private render() {
    this.view?.destroy();
    this.view = new MergeView({
      parent: this.host.nativeElement,
      a: {
        doc: this.before,
        extensions: [
          ...base(),
          EditorState.readOnly.of(true),
          EditorView.editable.of(false),
          EditorView.contentAttributes.of({ 'aria-label': 'YAML prima della migrazione' }),
        ],
      },
      b: {
        doc: this.after,
        extensions: [
          ...base(),
          EditorState.readOnly.of(true),
          EditorView.editable.of(false),
          EditorView.contentAttributes.of({ 'aria-label': 'YAML dopo la migrazione' }),
        ],
      },
      highlightChanges: true,
      gutter: true,
      collapseUnchanged: { margin: 4, minSize: 8 },
    });
  }
  ngOnDestroy() {
    this.view?.destroy();
  }
}
