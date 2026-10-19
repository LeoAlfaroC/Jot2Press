# Android app

The capture app. A widget on the home screen opens a small dialog over the launcher, and what is typed there is sent to the plugin in this repository, which adds it to the day's post.

This version sends text, photos and, when you turn it on, where each entry was written.

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

### Build a release

A release build is signed with a key that lives outside the repository. Create it once:

```bash
keytool -genkeypair -keystore ~/.android/jot2press-release.jks -storetype PKCS12 -alias jot2press -keyalg RSA -keysize 4096 -validity 11000
```

Then name it in `~/.gradle/gradle.properties`, with the password chosen above:

```properties
JOT2PRESS_RELEASE_STORE_FILE=/Users/you/.android/jot2press-release.jks
JOT2PRESS_RELEASE_STORE_PASSWORD=the password
JOT2PRESS_RELEASE_KEY_ALIAS=jot2press
```

Build and install it:

```bash
android/gradlew -p android :app:assembleRelease
adb install -r android/app/build/outputs/apk/release/app-release.apk
```

Without those three properties the release build stops and names the missing ones; debug builds do not need them.

Keep a copy of the `.jks` file and its password somewhere safe. Every later release has to be signed with the same key: without it, the only way to install a new version is to uninstall the app, and its queue and settings go with it. A release build and a debug build cannot replace each other for the same reason.

## Set up

1. Open the app. It opens on settings.
2. Under Connection, enter the site URL (`https://…`), the username and the application password. `Test connection` checks them with the site and says which user it connected as and the site's upload limit; it stores nothing. `Save` stores them, tested or not.
3. The categories to choose from come from a successful `Test connection` of the credentials that are saved, before or after `Save`.
4. Add the widget: long-press the home screen → Widgets → the app's name.

Until credentials are saved, the widget's write button opens settings. Once saved they show as text; `Edit` opens them again, and `Cancel` drops what was typed.

## Use

- The write button on the widget opens the capture dialog. `Send` stores the entry and closes the dialog.
- The camera and photos buttons beside `Send` add up to 10 photos to an entry: the system photo picker for several at once, the phone's camera app for one. An entry can be photos alone. A photo taken with the camera is also saved to the gallery, in an album with the app's name; that copy is yours and stays.
- JPEG, PNG and WebP photos are sent as they are when they fit the site's upload limit. Other types (HEIC, for example) and larger ones are re-encoded as JPEG until they fit.
- `Attach location`, under Sending in settings, asks for the location permission, and on Android 13 and later for notifications too, so that the notice below can show. Precise location is best; approximate also works. Refusing notifications does not turn the location off: the search then goes on without its notice. With it on, a pin beside the photos button says whether the entry sends where it was written: filled for yes, crossed out for no. A tap changes it, and the next entry starts the way the last one was left. `Edit` on the last entry shows the pin as that entry had it.
- An entry sent with the pin on takes the most precise position found while the dialog was open. When that is not within 20 m, the app keeps looking during the grace window, for at most a minute, and a notification says so: `Getting a precise location for your entry`. It does not keep looking with approximate location only, or with a grace window of 0.
- An entry waits for the grace window, 60 seconds unless changed, before it is sent. During that time the dialog shows it at the top, with `Edit` and `Delete`.
- Closing the dialog with text or photos in it keeps them as a draft.
- The widget is one blue square with a pen. A red badge appears on its corner only when something needs fixing: a number for entries that could not be sent, `!` when the site rejected the sign-in. Tapping the badge opens the queue, where those entries are retried or deleted, or settings for the sign-in. Entries waiting to be sent and a kept draft show nothing.
- While something needs fixing, the capture dialog says what on its first line, with `Review` (the queue) or `Fix` (settings). Writing still works: what is sent waits on the phone.
- A widget added when it was two squares wide keeps that size after an update: remove it and add it again.

## Rename

The name on the launcher and in the list of widgets, and the name TalkBack reads for the logo at the top of settings, is `appName` in `project.json` at the repository root. The logo itself is a drawing and keeps reading Jot2Press. Change it there, then build and install again. The queue, the draft and the settings stay, because Android recognises the app by its id, `io.github.leoalfaroc.jot2press`, which never changes.

## Moving from the journal build

Builds made before the name Jot2Press had the id `io.github.leoalfaroc.journal`. Android treats the new id as a different app, which installs beside the old one with nothing in it, and the new app talks only to the Jot2Press plugin. Update the plugin at the same time, as the plugin README describes under "Moving from the journal-entries plugin", and on the phone:

1. In the old app, wait until Settings → Queue says "Nothing is waiting to be sent.", and note your settings.
2. Uninstall the old app and install the new one.
3. Enter the site, the user and the application password again (the same password works) and press Test connection.
4. Set your settings again, allow location again if Attach location is on, and add the widget again.

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
- The app keeps its own copy of each photo of an entry until the site confirms the entry, then deletes it. Originals in the gallery are never changed.
- A photo re-encoded to fit the upload limit loses its metadata, location included. A photo sent as it is keeps whatever its file holds, which can include where it was taken.
- Location is off until you turn on `Attach location`. Only coordinates are kept, never an address, and they are sent only for an entry whose pin was on. A position the phone kept for an entry whose pin was then turned off is never sent, and is deleted with the entry.
- In the post, the coordinates can be read by whoever can read the post, and opening its Google Maps link sends them to Google.

## Licences

The day number and the headings are set in Shantell Sans, and everything else in Atkinson Hyperlegible, both used under the SIL Open Font License 1.1. Their licences are in `app/src/main/assets/licenses/`.
