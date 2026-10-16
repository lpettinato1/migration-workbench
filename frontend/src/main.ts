import { bootstrapApplication } from '@angular/platform-browser';
import { provideZonelessChangeDetection } from '@angular/core';
import { AppComponent } from './app.component';
bootstrapApplication(AppComponent, { providers: [provideZonelessChangeDetection()] }).catch(
  (error) => {
    document.body.textContent =
      'Impossibile avviare l’interfaccia. Chiudi questa scheda e riapri Migration Workbench.';
    console.error(error);
  },
);
