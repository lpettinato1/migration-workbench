# Working agreements

Read `git status`, `git log -8 --oneline` and the current README before editing. Git history is the progress record; do not add handoff reports, dated plans, versioned source copies or release notes for each iteration.

- This is a product, not another prototype. Fix the real flow and remove obsolete code before adding features.
- Prefer existing library/platform capabilities. Keep the amount of custom code small and readable; do not minify source or extract single-use helpers without a clear responsibility.
- Reduce duplicated decisions, nesting and state. Do not lower a complexity score by scattering one operation across more helpers, services or layers.
- Preserve Angular. UI text is consistent Italian; retain Git terms where they have a specific meaning.
- Refine the existing app directly. Use neutral dark surfaces, restrained borders and consistent typography; no new prototypes. Keep engine versions and implementation metadata out of the UI. Preserve paths, branches and commit information where users need them to review an operation.
- Keep the current composition unless the user approves a UX change. Ask when behavior is unclear. No invented defaults or changes of architecture.
- A migration starts explicitly with one working directory. It is the base for Git working folders. Existing repository selection is a separate operation.
- The final app has an embedded browser and a clickable Windows launcher. Packaging is deferred. Bundle JVM 26 when packaging resumes, and use the machine's installed Git and its configuration. Do not bundle Git or force authentication environment variables.
- No automatic session persistence/restore.
- Keep input/HEAD checks that protect files and Git operations. Remove duplicate state, unused code and restrictions unrelated to correctness.
- Tests must cover observed failures and expected content, not just successful process exit. A passing build is not visual verification.
- Do not claim the app is production-ready until its actual UI flow has been exercised.

## Development

Frontend: `cd frontend`, `npm ci`, `npm test`, `npm run build`. The build generates the UI assets used by Maven.
Backend: use the Maven wrapper and project-local `.build/m2` and `.build/wrapper-cache`. Use the available JDK 26 for development checks; do not change global Java/Git settings.

Make small local commits with the reason and relevant validation in the message. Commit only this task's files. Do not push unless requested. Separate formatting/cleanup from behavior changes where practical.
