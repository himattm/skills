# Design: `new-android-app` skill

## Summary

A skill that scaffolds an opinionated, single-module Android app using the
`android` CLI as the entry point and a set of reference templates as the
source of truth for build configuration and source layout.

The generated project commits to one library per concern: Metro for DI,
Jetpack Compose for UI, Circuit (Slack) for state and architecture with
Circuit-native navigation, Material 3 with Material You dynamic color for
theming, Coil 3 for images, kermit for logging, Ktor for networking, and
Firebase Crashlytics for crash reporting.

The skill produces an empty scaffold — it builds, deploys, and launches a
blank screen. No demo features. The first feature the user adds is the
first Circuit screen.

## Goals

- One command path from "I want a new Android app" to a green build with a
  blank app on a device.
- Lock in architectural decisions so AI agents working in the project later
  do not have to re-derive them. `AGENTS.md` (real file) plus a `CLAUDE.md`
  symlink to it carry that context.
- Encode ergonomic Gradle entry points: `runDebug`, `runRelease`,
  `runDebugAll`, `runReleaseAll` for assemble + install + launch.
- Make Crashlytics a one-step add: drop a `google-services.json` file in
  and rebuild. Until then, the project must still build.

## Non-goals

- Multi-module starter. Single `:app` only; convention plugins are scaffolded
  so adding a second module is cheap.
- Demo screens or a `hello world` feature.
- KMP. The stack leans KMP-friendly (kermit, Ktor, Coil 3) but this skill
  scaffolds Android-only.
- Contributing a `circuit-app` template to the `android` CLI's template
  registry. Considered (Approach C) and rejected for now in favor of
  faster iteration on a markdown skill.

## Approach

The skill is markdown-only. Canonical build files and source templates
live alongside SKILL.md in `references/` as real, lintable files with
placeholder tokens. The workflow copies them out and substitutes.

Rejected alternatives:

- **Pure-instruction skill** (file contents inlined in SKILL.md). Rejected:
  too much inline content, version bumps become diffs against fenced
  blocks instead of real files.
- **`android create` template contribution.** Rejected for now: requires
  changes to the `android` CLI itself; iteration cost is higher than for a
  markdown skill in this repo.

## Skill location

```
skills/new-android-app/
├── SKILL.md
└── references/
    ├── AGENTS.md.tmpl
    ├── settings.gradle.kts.tmpl
    ├── root.build.gradle.kts
    ├── app.build.gradle.kts.tmpl
    ├── libs.versions.toml
    ├── run-tasks.gradle.kts
    ├── build-logic/
    │   ├── settings.gradle.kts
    │   └── convention/
    │       ├── build.gradle.kts
    │       └── src/main/kotlin/
    │           ├── AndroidApplicationConventionPlugin.kt
    │           ├── ComposeConventionPlugin.kt
    │           ├── CircuitConventionPlugin.kt
    │           ├── MetroConventionPlugin.kt
    │           ├── TestingConventionPlugin.kt
    │           └── QualityConventionPlugin.kt
    ├── App.kt.tmpl
    ├── MainActivity.kt.tmpl
    ├── AppGraph.kt.tmpl
    ├── CircuitConfig.kt.tmpl
    ├── RootScreen.kt.tmpl
    ├── Theme.kt.tmpl
    ├── Color.kt.tmpl
    ├── Type.kt.tmpl
    ├── ExampleTest.kt.tmpl
    ├── RootPresenterTest.kt.tmpl
    ├── ThemeScreenshotTest.kt.tmpl
    ├── google-services.json.example
    ├── ci.yml
    ├── detekt.yml
    ├── editorconfig
    └── gitignore
```

Files with `.tmpl` get placeholder substitution. Non-templated files copy
verbatim.

Placeholders:
- `{{appName}}` — display name, e.g. `Halogen`
- `{{appNameSnake}}` — snake_case form, e.g. `halogen`
- `{{packageName}}` — Java package, e.g. `me.mmckenna.halogen`
- `{{packagePath}}` — same with `/`, e.g. `me/mmckenna/halogen`
- `{{minSdk}}` — default 26
- `{{targetSdk}}` — default to current platform from `android sdk list --installed`

## Workflow (what SKILL.md tells Claude to do)

1. **Collect inputs** — prompt for app display name, package name (validated
   `[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+`), output directory (default cwd),
   min SDK (default 26), and whether to init git (default yes).
2. **Verify environment** — `android` CLI present, JDK 21 available, run
   `android sdk list --installed` and pick a current platform / build-tools.
3. **Scaffold base via `android create`** with the simplest available
   Compose template, into `<output>/<appNameSnake>/`. This produces a
   buildable starting point that step 4 then heavily overwrites.
4. **Apply opinionated stack** — copy every file from `references/` to its
   target path, substituting placeholders for `.tmpl` files. This includes
   build configuration, convention plugins, source files, tests, CI
   workflow, `AGENTS.md`, `.gitignore`, `.editorconfig`, the
   `google-services.json.example` placeholder, and `runDebug`/`runRelease`
   Gradle tasks.
5. **Create CLAUDE.md symlink** — `ln -s AGENTS.md CLAUDE.md` at project
   root.
6. **Sanity-build** — run, in order, with `-q --console=plain`:
   - `./gradlew :app:assembleDebug`
   - `./gradlew :app:testDebugUnitTest`
   - `./gradlew :app:detekt :app:lintDebug`
   On failure: surface error, stop, do not commit.
7. **Initialize git** (if requested) — `git init && git add . && git commit`
   with a fixed initial-scaffold message.
8. **Report next steps** — how to set up Firebase + Crashlytics, how to add
   a first Circuit screen, how to invoke `runDebug` / `runDebugAll`.

## Generated project — end state

- Single-module app at `<output>/<appNameSnake>/`.
- `:app` module + `build-logic` included build for convention plugins.
- Compose-only, Circuit-native nav, no demo screens. Start destination is
  a `RootScreen` whose UI is `Box {}` with a `// TODO` comment. Builds and
  runs to a blank app.
- Metro DI graph (`AppGraph`) referenced from `App: Application`.
- Material 3 theme with Material You dynamic color on Android 12+, fallback
  palette below.
- Edge-to-edge enabled (`enableEdgeToEdge()` in `MainActivity`).
- Core SplashScreen API (`installSplashScreen()`).
- Predictive back enabled in manifest.
- Coil 3 `ImageLoader` provided through Metro.
- kermit `Logger` initialized in `Application.onCreate`.
- Ktor client provided through Metro (no real endpoints — just the
  configured `HttpClient`).
- DataStore (preferences) configured.
- Crashlytics + google-services Gradle plugins applied conditionally on
  `app/google-services.json` existing. `App.onCreate` initializes Crashlytics
  only when present. `app/google-services.json.example` ships in the repo;
  the real file is gitignored.
- LeakCanary in debug only.
- detekt + ktlint via convention plugin; `config/detekt/detekt.yml`
  shipped; lint baseline placeholder.
- Tests: JUnit5, Kotest assertions, Turbine, `circuit-test`, Robolectric,
  Compose UI test, Roborazzi screenshot tests.
- `.github/workflows/ci.yml` runs `assembleDebug`, `testDebugUnitTest`,
  `detekt`, `lintDebug`.
- `AGENTS.md` real file at root; `CLAUDE.md` is a symlink to it.
- `runDebug`, `runRelease`, `runDebugAll`, `runReleaseAll` Gradle tasks.

## `runDebug` / `runRelease` Gradle tasks

Registered from `run-tasks.gradle.kts`, applied by the application
convention plugin:

- `runDebug` / `runRelease` — depend on `installDebug` / `installRelease`.
  After install, query `adb devices` for connected devices. If exactly one
  is connected, launch the main activity via
  `adb -s <serial> shell am start -n <packageName>/.MainActivity`. If none
  or multiple are connected, fail with a clear message listing devices and
  pointing to the `*All` variant.
- `runDebugAll` / `runReleaseAll` — same, but iterate every connected
  device/emulator and launch on each.
- All four tasks log to stdout which device(s) they targeted.

The task implementation uses `Exec`-style task types and reads `adb`
output; no Kotlin coroutines or third-party plugins required.

## Crashlytics

The scaffold pre-wires Crashlytics so activation is a one-step add:

- `libs.versions.toml` includes `googleServices` and `crashlytics` plugin
  versions and the `firebase-crashlytics-ktx` library.
- `app/build.gradle.kts` only applies the `com.google.gms.google-services`
  and `com.google.firebase.crashlytics` plugins when
  `rootProject.file("app/google-services.json").exists()`.
- `App.kt` only calls `FirebaseApp.initializeApp(this)` and configures
  Crashlytics when the same file exists, falling back to a kermit warning
  log otherwise.
- `app/google-services.json` is gitignored. `app/google-services.json.example`
  is committed as a stub.
- `AGENTS.md` documents how to create a Firebase project and place the
  real file.

## `AGENTS.md` content

Terse, scannable, agent-readable. Sections:

- **Stack** — one-liner per concern with the locked-in choice and a "do not
  introduce X" note where another popular alternative exists.
- **Adding a feature** — five-step recipe: define `Screen`/`State`/`Event`,
  write `Presenter`, write composable, register in `CircuitConfig`, add
  presenter test using `circuit-test`.
- **Running** — `runDebug` / `runDebugAll` / Release variants.
- **Testing** — JUnit5 + Kotest + Turbine; `circuit-test`; Compose UI test;
  Roborazzi (`recordRoborazziDebug` to update goldens).
- **What not to do** — explicit anti-list (no second DI / nav / image
  loader / logger; no Metro bypass; no business logic in composables).

`CLAUDE.md` is created as a symlink to `AGENTS.md` via `ln -s`.

## Composes with

- **`android-cli`** — used by step 2 and step 3 of the workflow, and
  surfaced as the recommended path in next-step advice.
- **`edge-to-edge`** — referenced in `AGENTS.md` and SKILL.md as the
  canonical guide for inset / system-bar issues that come up when real
  screens are added. The scaffold enables edge-to-edge, but real screens
  may need more work.
- **`superpowers:test-driven-development`** — referenced in `AGENTS.md` as
  the recipe for adding features (TDD-first via `circuit-test`).
- **`superpowers:verification-before-completion`** — the workflow's step 6
  is a verification gate; this skill governs how it runs.
- **`superpowers:writing-plans`** — invoked next to turn this spec into an
  implementation plan for the skill.

## Failure handling

- `android create` failure: surface stderr, suggest
  `android sdk list --installed` / `android init`, stop.
- Gradle failure in step 6: print failing task + last ~50 lines of output,
  stop without committing. Leave the partial scaffold for inspection.
- `git init` failure: scaffold is complete; warn but do not roll back.
- Placeholder substitution: validate package name with the regex above
  before any file is written. If the regex fails, prompt again rather than
  generating a half-broken project.

## Open questions

None at design time. Library version pins live in
`references/libs.versions.toml` and are the single point of truth; bumps
are a maintenance task on the skill, not a design decision.
