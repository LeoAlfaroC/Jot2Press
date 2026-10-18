# Jot2Press

A WordPress plugin that appends short journal entries to one post per day, private unless the app asks otherwise. It is the server side of the Android capture app in this repository.

## Requirements

- WordPress 7.1 or later, on any PHP version it supports (7.4 or later), with MySQL or MariaDB.
- The site served over HTTPS (Application Passwords require it).

The plugin targets the current major version of WordPress and whatever that version supports. The test suite passes on WordPress 7.1.3 with PHP 7.4, the oldest it supports, and with PHP 8.3, both on MariaDB 12.3. It has not been run on MySQL.

## Install

1. Build the zip: `cd plugin && npm run build`
2. In WordPress: Plugins → Add New → Upload Plugin → choose `jot2press.zip` → Activate.
3. In WordPress: Users → Profile → Application Passwords → add one for the app and copy it.

Activation creates the default category, `journal`, if it does not exist.

## Configure

Two values can be changed by defining a constant in `wp-config.php`, above the line that says to stop editing:

```php
define( 'JOT2PRESS_CATEGORY_SLUG', 'journal' ); // default category of the day posts
define( 'JOT2PRESS_ORPHAN_DAYS', 30 );          // days before a photo that no post shows is deleted; anything but 1 to 999999 turns it off
```

Values set there survive plugin updates.

Once a day the site deletes journal photos that no post shows and that are attached to no post, when they are older than `JOT2PRESS_ORPHAN_DAYS` days (a whole number of days from 1 to 999999; anything else, including `0`, `false`, text and a decimal such as `30.0`, turns the cleanup off). That includes the photos of a day's post that was deleted permanently. Nothing else in the media library is touched.

Each account that can publish keeps its own day posts: an entry is added to the post of the account that sent it, never to another account's post for the same day.

Each entry leaves a revision of the day's post, and WordPress keeps at most 30 for a day post (fewer if the site is set to keep fewer). They are where to look if an entry disappears because the post was open in an editor while the entry arrived: wp-admin → the post → Revisions.

An entry's photos show below its line as square tiles, three to a row on every screen, so that a lone photo is the size of any other; a tap opens the whole photo in WordPress's own viewer. They are ordinary Group and Image blocks, editable in the block editor. Posts written before this layout keep their photos one under the other.

A photo that an entry used at the very moment the photo was still being processed is embedded at full size, without its smaller sizes; `wp media regenerate <id>` makes them.

If a photo's file disappears from the server (deleted by hand, or the uploads folder restored without it), an entry that uses that photo is refused until the app uploads the photo again; that upload replaces the broken one.

The category and the status of the day posts are chosen in the app, not here. The app sends them with each entry and the plugin uses them when that entry creates the day's post; it never changes a post that already exists. If the app names a category that no longer exists, the post goes to the default category and the response says so.

## Rename

The name shown in the WordPress plugin list is `pluginName` in `project.json`, at the root of the repository. To change it, edit that file, run `cd plugin && npm run build`, and upload the new zip over the installed plugin: WordPress treats it as an update, because the plugin's folder is the same.

Only the visible name changes. The folder `jot2press`, the address `jot2press/v1`, the uploads folder `journal` and the keys that mark the day posts and the photos stay as they are, which is why a rename loses nothing. The uploads folder and those keys carry the project's first name, journal, on purpose: the posts and photos already on a site are found by them.

## Moving from the journal-entries plugin

Before it was called Jot2Press, this plugin lived in the folder `journal-entries` and answered at `journal/v1`. The Jot2Press plugin installs beside it rather than over it, and the app talks only to the new one, so update the plugin and the app together:

1. In the old app, wait until Settings → Queue says "Nothing is waiting to be sent."
2. If `wp-config.php` defines `JOURNAL_ENTRIES_CATEGORY_SLUG` or `JOURNAL_ENTRIES_ORPHAN_DAYS`, rename them to `JOT2PRESS_CATEGORY_SLUG` and `JOT2PRESS_ORPHAN_DAYS`. The old names are no longer read, and the defaults (`journal`, 30 days) would apply without a warning.
3. Upload `jot2press.zip` and activate it.
4. Deactivate and delete the old plugin, the one in the folder `journal-entries`; it shows the same name in the list. Deleting it removes only its files: the day posts, their photos and the category stay, and the new plugin keeps appending to them.
5. Install the new app, as the Android README describes under "Moving from the journal build".

## Upload limits

If photos are rejected as too large, raise the limits on the server. PHP: `upload_max_filesize` and `post_max_size`. nginx: `client_max_body_size` (its default is 1 MB).

## Check it works

Replace the site, user and password:

```bash
SITE=https://example.com
AUTH='user:xxxx xxxx xxxx xxxx xxxx xxxx'

# Credentials and upload limit
curl -s -u "$AUTH" "$SITE/wp-json/jot2press/v1/status"

# A text entry
curl -s -u "$AUTH" -H 'Content-Type: application/json' "$SITE/wp-json/jot2press/v1/entries" \
  -d "{\"id\":\"$(uuidgen | tr A-Z a-z)\",\"written_at\":\"$(date +%Y-%m-%dT%H:%M:%S%z | sed 's/\(..\)$/:\1/')\",\"text\":\"Hello from curl.\"}"

# A photo, then an entry that uses it
PHOTO=$(uuidgen | tr A-Z a-z)
curl -s -u "$AUTH" -F "id=$PHOTO" -F "photo=@/path/to/photo.jpg" "$SITE/wp-json/jot2press/v1/photos"
curl -s -u "$AUTH" -H 'Content-Type: application/json' "$SITE/wp-json/jot2press/v1/entries" \
  -d "{\"id\":\"$(uuidgen | tr A-Z a-z)\",\"written_at\":\"$(date +%Y-%m-%dT%H:%M:%S%z | sed 's/\(..\)$/:\1/')\",\"photo_ids\":[\"$PHOTO\"]}"
```

The `/wp-json/…` addresses need pretty permalinks. With plain permalinks the same route is `$SITE/?rest_route=/jot2press/v1/status`.

Then open today's post in Jetpack or the WordPress admin: it is private, in the Journal category, and shows both entries with their times.

To try another category or status, add `"category":"a-category-slug"` or `"post_status":"draft"` (or `"publish"`) to the JSON of the first entry of a day.

## Endpoints

| Route | Purpose |
|---|---|
| `GET /wp-json/jot2press/v1/status` | Confirms credentials; reports the upload limit, the default category and every category of the site |
| `POST /wp-json/jot2press/v1/photos` | Uploads one photo (`multipart/form-data`: `id`, `photo`) |
| `POST /wp-json/jot2press/v1/entries` | Appends an entry (JSON: `id`, `written_at`, `text`, `photo_ids`, `title`, `category`, `post_status`, `location`) |

Sending the same `id` again is safe: nothing is stored twice.

## Privacy

- Day posts are private unless the app asks for another status: visible only to the account that wrote them and to logged-in editors and administrators. A draft is visible to the same users.
- A day post created as `publish` is public from its first entry, and every later entry of that day is public the moment it arrives, with its photos and its location. WordPress announces the post when it is created: it enters the feeds, and plugins that email subscribers or share to social networks may send it out with only that first entry.
- Photos are private attachments under random filenames in `wp-content/uploads/journal/`, whatever the status of their post. WordPress does not list them to visitors, but the file itself is served to anyone who has its exact URL, which is how a public post shows them.
- When the app sends a location, the entry shows its coordinates and a Google Maps link. They are part of the post content; opening the link sends the coordinates to Google.
- Once a day, journal photos that no post shows and that are attached to no post are deleted for good, once they are older than the number of days set above. That includes the photos of a day's post that was deleted permanently. A photo is kept when it is shown: its file appears in the content of any post, even one in the trash (which is how this plugin's entries, the block editor's image and gallery blocks, and the Jetpack and WordPress apps show a photo), or it is some post's featured image, or a classic `[gallery]` shortcode names it by its id. Nothing else in the media library is touched.

## Tests

Requires Docker and Node 24.

```bash
cd plugin
npm install
npx wp-env start
npm test
```

`npm test` deletes every post and attachment in the test site before running.
