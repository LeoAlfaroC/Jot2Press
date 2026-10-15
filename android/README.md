# Android app

The capture app. A widget on the home screen opens a small dialog over the launcher, and what is typed there is sent to the journal plugin in this repository, which adds it to the day's post.

This version sends text entries. Photos and location are not built yet.

## Requirements

- A site with the plugin from `plugin/` installed and active, served over HTTPS.
- An application password for the WordPress user that keeps the journal (Users → Profile → Application Passwords).
- A phone or an emulator with Android 10 or later.
- Android Studio, whose own JDK is enough, or the Android SDK and a JDK.

## Build and install

From the repository root, with a phone connected and USB debugging on:

```bash
echo "sdk.dir=$HOME/Library/Android/sdk" > android/local.properties   # once: the path of the Android SDK
android/gradlew -p android :app:installDebug
```

On a machine with no Java of its own, put the one inside Android Studio in front of the command: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.

This is a debug build, signed with the debug key of the machine that built it. A build from another machine cannot be installed over it: the app has to be uninstalled first, and its queue and settings go with it.

## Set up

1. Open the app. It opens on settings.
2. Under Connection, enter the site URL (`https://…`), the username and the application password, and tap `Test connection`. When it works, it says which user it connected as and the site's upload limit.
3. Add the widget: long-press the home screen → Widgets → the app's name.

Until `Test connection` has worked once, the widget's write button opens settings.

## Use

- The write button on the widget opens the capture dialog. `Send` stores the entry and closes the dialog.
- An entry waits for the grace window, 60 seconds unless changed, before it is sent. During that time the dialog shows it at the top, with `Edit` and `Delete`.
- Closing the dialog with text in it keeps the text as a draft.
- The number on the widget is how many entries are still on the phone. Tapping it opens settings, whose first row leads to the queue.

## Rename

The name on the launcher, on the settings screen and in the list of widgets is `appName` in `project.json` at the repository root. Change it there, then build and install again. The queue, the draft and the settings stay, because Android recognises the app by its id, `io.github.leoalfaroc.journal`, which never changes.

## Tests

```bash
android/gradlew -p android :app:testDebugUnitTest           # on the computer, no device needed
android/gradlew -p android :app:connectedDebugAndroidTest   # on a connected phone or a running emulator
```

The first command tests everything except the encryption of the credentials, which needs the Android Keystore and therefore a device. The second tests that.

## Privacy

- The site URL, the username and the application password are encrypted with a key held in the Android Keystore.
- The app talks only to the site it was given, over HTTPS. Plain HTTP is turned off.
- App data is left out of device backups and transfers. An unsent entry exists only on the phone, and is deleted from it once the site confirms it.

## Licences

The day number in the capture dialog is set in Schibsted Grotesk, used under the SIL Open Font License 1.1. The licence is in `app/src/main/assets/licenses/`.
