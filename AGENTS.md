# Jot2Press: notes for agents

Jot2Press is two things that ship together:

- **The app** (`android/`, Kotlin with Jetpack Compose) queues short journal entries
  and photos.
- **The plugin** (`plugin/jot2press/jot2press.php`, a single-file WordPress plugin)
  turns them into one post per day.

`project.json` holds the product names, and `plugin/apply-name.mjs` writes them into
the plugin header. Each side's README has the full setup.

## Commands

- **App tests:** `cd android && ./gradlew testDebugUnitTest`.
  - Use Android Studio's bundled JBR or any JDK 17+.
  - The output is long: redirect it to a file and grep `FAILED|BUILD|e: `.
  - Count results from `app/build/test-results/testDebugUnitTest/*.xml`. A run that is
    quick because Gradle cached it is not a failure.
- **Credential encryption tests:** these need the Android Keystore, so they run with
  `connectedDebugAndroidTest` on an emulator only. A connected run can remove the
  installed app and its data, so never point it at a real phone.
- **App release:** `./gradlew :app:assembleRelease`, with R8, minify and
  shrinkResources on.
  - It needs the signing properties described in `android/README.md`. The keystore
    lives outside the repo.
- **Plugin tests:** `cd plugin && npm run env:start && npm test`.
  - They need Docker and wp-env, and take 2 to 3 minutes.
  - Some lock tests depend on timing, so don't run heavy builds at the same time.
- **Plugin package:** `npm run build` writes `jot2press.zip`.

## Workflow

- **TDD:** write the failing test first and watch it fail.
- **Branches:** one branch per change, cut from an up-to-date `main` and named after
  the change (`fix/photo-type-retry`, `feat/…`, `refactor/…`). Merge through a pull
  request; don't commit straight to `main`.
- **Tests before the PR:** run the whole suite of the side you touched, and both
  suites when you touch the contract between them. There is no CI, so say in the PR
  which suites you ran and their counts.
- **Commits:** one line, Conventional Commits (`refactor(app): …`, `fix(plugin): …`),
  with no AI or co-author trailers. Each commit should build and pass the tests by
  itself.
- **Staging:** stage files by name. Never `git add -A`, `git add .` or `commit -a`.
- **History:** never rewrite history on `main` or on a branch someone else works on.
  On your own branch, use `--force-with-lease` and never plain `--force`.
- **Agents** don't push, open pull requests or merge unless asked.

## Code style

- All code, UI text, comments, commits and READMEs are in English.
- Readability comes before cleverness and before saving lines.
- KDoc and PHP comments say *why*, in plain declarative sentences. Match the comment
  density of the code around you.
- Test names are backtick sentences that describe behavior, for example
  `` `the badge stops counting at 99` ``.
- Delete code that only tests use. Keep test seams that make testing possible without
  being a feature: injectable constructor parameters, `Jot2PressApp.install()`,
  `Locator.handled`.

## Contract between app and plugin

These values are shared by both sides. Change both in the same change, with tests on
both sides.

- **Error codes:** `unknown_photo`, `photo_type_not_allowed`, `cannot_upload_photos`,
  `cannot_edit_day_post`, `rest_no_route`.
- **Photo limits:** `MAX_PHOTOS = 10`. The photo types are JPEG, PNG and WebP.
- **Plugin header:** `apply-name.mjs` and `tests/name.test.mjs` rewrite only the
  header of `jot2press.php`. Keep its format.

## Never change without a migration

- **Room:** a schema change needs a migration and the new exported schema in
  `android/app/schemas/`.
- **DataStore:** the store name (`SettingsStore.NAME`) and its preference keys. Apps
  already installed read them.
- **Package names:** `applicationId` and `namespace`.

## How the app is built

- **`Trouble`** is the only source of both the widget badge and the capture notice.
  Don't work either one out anywhere else.
- **Credentials** carry a version (`credentialsVersion`). A rejection is recorded only
  if the version is still the one the request was sent with. That check stays inside
  the same DataStore edit as the write.

## Testing gotchas

- **Robolectric:** anything that touches `Bitmap` or `ImageBitmap` needs
  `@GraphicsMode(GraphicsMode.Mode.NATIVE)`.
- **MockWebServer:** check `requestCount` before calling `takeRequest()`, which can
  hang.
- **Helpers:** `TestWorld` wires up the whole app. `TestStores.kt` has `tempStore`,
  `FakeBox` and `rejectSignIn()`. Reuse them before writing new helpers.

## Visual identity (Ballpoint)

- **Fonts:** Shantell Sans and Atkinson Hyperlegible.
- **Ink:** Azure, `#0066FF` in light mode and `#7AB0FF` in dark mode.
- **Yellow:** only in the logo and in text selection.
- **Avoid** muted teal, forest, rust or indigo palettes.

## Safety

- Never use or ask for the credentials of a real site. Tests run against wp-env.
- Never print secrets: `.env` files, the keystore or its password,
  `plugin/tests/.credentials.json`.
- Don't control a connected phone. Use adb only to read state or install a build, and
  only when asked.
