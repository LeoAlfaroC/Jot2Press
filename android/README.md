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
2. Under Connection, enter the site URL (`https://…`), the username and the application password. `Test connection` checks them with the site and says which user it connected as and the site's upload limit; it stores nothing. `Save` stores them, tested or not.
3. The categories to choose from come from a successful `Test connection` of the credentials that are saved, before or after `Save`.
4. Add the widget: long-press the home screen → Widgets → the app's name.

Until credentials are saved, the widget's write button opens settings. Once saved they show as text; `Edit` opens them again, and `Cancel` drops what was typed.

## Use

- The write button on the widget opens the capture dialog. `Send` stores the entry and closes the dialog.
- An entry waits for the grace window, 60 seconds unless changed, before it is sent. During that time the dialog shows it at the top, with `Edit` and `Delete`.
- Closing the dialog with text in it keeps the text as a draft.
- The widget is one square. A red badge appears on its corner only when something needs fixing: a number for entries that failed to send, `!` when the site rejected the sign-in. Tapping the badge opens settings, whose first row leads to the queue. Entries waiting to be sent and a kept draft show nothing.
- A widget added when it was two squares wide keeps that size after an update: remove it and add it again.

## Rename

The name on the launcher, on the settings screen and in the list of widgets is `appName` in `project.json` at the repository root. Change it there, then build and install again. The queue, the draft and the settings stay, because Android recognises the app by its id, `io.github.leoalfaroc.journal`, which never changes.

## Tests

```bash
android/gradlew -p android :app:testDebugUnitTest           # on the computer, no device needed
android/gradlew -p android :app:connectedDebugAndroidTest   # on a running emulator, not on the phone you journal on
```

The first command tests everything except the encryption of the credentials, which needs the Android Keystore and therefore a device. The second tests that. Run it on an emulator: the debug and test APKs replace the app that is installed there, and a connected run can remove the app and its data.

## Privacy

- The site URL, the username and the application password are encrypted with a key held in the Android Keystore.
- The app talks only to the site it was given, over HTTPS. Plain HTTP is turned off.
- App data is left out of device backups and transfers. An unsent entry exists only on the phone, and is deleted from it once the site confirms it.

## Licences

The day number in the capture dialog is set in Schibsted Grotesk, used under the SIL Open Font License 1.1. The licence is in `app/src/main/assets/licenses/`.
