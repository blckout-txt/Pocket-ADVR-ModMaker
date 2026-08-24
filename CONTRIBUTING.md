# Contributing

Thanks for taking a look. Bug reports and pull requests are welcome.

## Getting set up

You need JDK 17 and an Android SDK with platform 36. The build finds the SDK through `$ANDROID_HOME`
(or `$ANDROID_SDK_ROOT`); if you would rather point at it explicitly, create a `local.properties`:

```properties
sdk.dir=/path/to/Android/Sdk
```

That file is deliberately not tracked.

If the build stops with *"Several environment variables and/or system properties contain different
paths to the SDK"*, you have `ANDROID_HOME` and `ANDROID_SDK_ROOT` pointing at two different
installs. Unset one of them.

```sh
./gradlew testDebugUnitTest    # unit tests
./gradlew assembleDebug        # APK at app/build/outputs/apk/debug/
./gradlew installDebug         # build and push to a connected device
```

## Where things live

The README has a table of the source layout. Two areas are worth knowing before you change them:

- **`lua/`** is plain Kotlin with no Android dependencies, which is why it is covered by fast JVM unit
  tests. Keep it that way — if a change there needs a `Context`, the design has drifted.
- **`app/src/main/assets/advr_api.txt` is generated.** Never edit it by hand. Regenerate it with
  `python3 tools/gen_api_index.py` after installing a newer ADVR Modding Tools extension; the script
  finds the extension itself.

## Tests

The tests run against the real generated index rather than fixtures, so they also catch regressions
in the stub parsing. Anything touching completion, inference or diagnostics should come with a case
in `app/src/test/java/com/advr/luaeditor/`.

Please run `./gradlew testDebugUnitTest` before opening a pull request; CI runs the same thing.

## Style

Follow the surrounding code: official Kotlin style, 4-space indent, 120 columns
(there is an `.editorconfig`). Comments should explain *why* something is the way it is —
the ADVR-specific rules encoded here are rarely self-evident.
