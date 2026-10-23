<?php
/**
 * Plugin Name:       Jot2Press
 * Description:       Appends short journal entries to one post per day, private by default. Server side of the capture app for Android.
 * Author:            Leo Alfaro
 * Plugin URI:        https://github.com/LeoAlfaroC/Jot2Press
 * Version:           1.0.0
 * Requires at least: 7.1
 * Requires PHP:      7.4
 * License:           GPL-2.0-or-later
 * Update URI:        false
 */

defined( 'ABSPATH' ) || exit;

// The default category: a day's post is filed here when the request names no category, or one that does not exist.
// Created on first use if missing.
// To change it, define this constant in wp-config.php: an edit made here is lost when the plugin is updated.
defined( 'JOT2PRESS_CATEGORY_SLUG' ) || define( 'JOT2PRESS_CATEGORY_SLUG', 'journal' );

const JOT2PRESS_VERSION   = '1.0.0';
const JOT2PRESS_NAMESPACE = 'jot2press/v1';

// How long a request waits for another request appending to the same day.
const JOT2PRESS_LOCK_SECONDS = 5;

// The most photos one entry can show.
const JOT2PRESS_MAX_PHOTOS = 10;

// The most revisions WordPress keeps of a day's post. Each entry leaves one, which is where to look for an entry
// that an editor left open overwrote; a site that keeps fewer (or none) is respected.
const JOT2PRESS_REVISIONS = 30;

// Journal photos that no post shows and that are attached to no post are deleted once they are older than this many
// days. Only a whole number of days from 1 to 999999 turns the cleanup on: anything else, including 0, false, text
// and a decimal such as 30.0, turns it off.
// To change it, define this constant in wp-config.php.
defined( 'JOT2PRESS_ORPHAN_DAYS' ) || define( 'JOT2PRESS_ORPHAN_DAYS', 30 );

// The cleanup looks at this many photos at a time, and goes on to the next batch until it has run this many seconds;
// what is left waits for a run a minute later, so that a long list never holds up the site's other scheduled tasks.
const JOT2PRESS_CLEANUP_BATCH   = 100;
const JOT2PRESS_CLEANUP_SECONDS = 20;

register_activation_hook( __FILE__, 'jot2press_category' );
add_action( 'rest_api_init', 'jot2press_register_routes' );
register_deactivation_hook( __FILE__, 'jot2press_deactivate' );
add_action( 'init', 'jot2press_schedule_cleanup' );
add_action( 'jot2press_cleanup', 'jot2press_cleanup_orphans', 10, 1 );
add_filter( 'wp_revisions_to_keep', 'jot2press_revisions_to_keep', 10, 2 );

function jot2press_error( string $code, string $message, int $status ): WP_Error {
	return new WP_Error( $code, $message, array( 'status' => $status ) );
}

/** Caps the revisions of a day's post (a post with a _journal_date) at JOT2PRESS_REVISIONS. */
function jot2press_revisions_to_keep( $num, $post ) {
	$unlimited_or_more = $num < 0 || $num > JOT2PRESS_REVISIONS;
	if ( $unlimited_or_more && $post instanceof WP_Post && '' !== (string) get_post_meta( $post->ID, '_journal_date', true ) ) {
		return JOT2PRESS_REVISIONS;
	}
	return $num;
}

/** Whether a value is an id: a UUID of exactly 36 characters (wp_is_uuid() alone accepts a trailing line break). */
function jot2press_is_id( $value ): bool {
	return is_string( $value ) && 36 === strlen( $value ) && wp_is_uuid( $value );
}

/** An id as a request sent it, lowercased; '' when the value is not an id. */
function jot2press_read_id( $value ): string {
	$id = is_string( $value ) ? strtolower( $value ) : '';
	return jot2press_is_id( $id ) ? $id : '';
}

function jot2press_can_publish(): bool {
	return current_user_can( 'publish_posts' );
}

/**
 * The refusal for an account that may publish but not upload files. 403 like WordPress's own, with a code of its own:
 * the app reads a bare 403 as rejected credentials and holds every entry, where this refuses only entries with photos.
 */
function jot2press_cannot_upload(): WP_Error {
	return jot2press_error( 'cannot_upload_photos', 'This account may not upload files to the site, so it cannot send photos.', 403 );
}

/**
 * Returns the default category, creating it if it does not exist.
 *
 * @return WP_Term|WP_Error
 */
function jot2press_category() {
	$term = get_term_by( 'slug', JOT2PRESS_CATEGORY_SLUG, 'category' );
	if ( $term instanceof WP_Term ) {
		return $term;
	}
	$created = wp_insert_term( 'Journal', 'category', array( 'slug' => JOT2PRESS_CATEGORY_SLUG ) );
	if ( is_wp_error( $created ) ) {
		return $created;
	}
	return get_term( $created['term_id'], 'category' );
}

function jot2press_register_routes(): void {
	register_rest_route(
		JOT2PRESS_NAMESPACE,
		'/status',
		array(
			'methods'             => WP_REST_Server::READABLE,
			'callback'            => 'jot2press_handle_status',
			'permission_callback' => 'jot2press_can_publish',
		)
	);

	register_rest_route(
		JOT2PRESS_NAMESPACE,
		'/entries',
		array(
			'methods'             => WP_REST_Server::CREATABLE,
			'callback'            => 'jot2press_handle_entry',
			'permission_callback' => 'jot2press_can_publish',
		)
	);

	register_rest_route(
		JOT2PRESS_NAMESPACE,
		'/photos',
		array(
			'methods'             => WP_REST_Server::CREATABLE,
			'callback'            => 'jot2press_handle_photo',
			'permission_callback' => 'jot2press_can_publish',
		)
	);
}

/** Describes a category the way the app shows it: its slug and its readable name. */
function jot2press_category_summary( WP_Term $term ): array {
	return array(
		'slug' => $term->slug,
		// WordPress stores names with entities ("Tea &amp; Cake"); the app shows plain text.
		'name' => wp_specialchars_decode( $term->name, ENT_QUOTES ),
	);
}

/** Lists every category of the site, used or not, sorted by name. */
function jot2press_categories(): array {
	$terms = get_terms(
		array(
			'taxonomy'   => 'category',
			'hide_empty' => false,
			'orderby'    => 'name',
			'order'      => 'ASC',
		)
	);
	return is_wp_error( $terms ) ? array() : array_map( 'jot2press_category_summary', array_values( $terms ) );
}

/** The photo types this plugin stores, by MIME type, with the extension each is stored under and its name. */
const JOT2PRESS_PHOTO_TYPES = array(
	'image/jpeg' => array( 'jpg', 'JPEG' ),
	'image/png'  => array( 'png', 'PNG' ),
	'image/webp' => array( 'webp', 'WebP' ),
);

/** The photo types the site lets this account upload: the plugin's own, less any the site has taken off its list. */
function jot2press_accepted_photo_types(): array {
	return array_values( array_intersect( array_keys( JOT2PRESS_PHOTO_TYPES ), get_allowed_mime_types() ) );
}

function jot2press_handle_status() {
	// Asked for first, so that the default category exists before the categories are listed.
	$category = jot2press_category();
	if ( is_wp_error( $category ) ) {
		return jot2press_error( 'jot2press_failure', $category->get_error_message(), 500 );
	}
	return new WP_REST_Response(
		array(
			'ok'               => true,
			'user'             => wp_get_current_user()->user_login,
			'category'         => jot2press_category_summary( $category ),
			'categories'       => jot2press_categories(),
			'max_upload_bytes' => (int) wp_max_upload_size(),
			// The app turns any other photo into a JPEG before sending it.
			'photo_types'      => jot2press_accepted_photo_types(),
			'version'          => JOT2PRESS_VERSION,
		),
		200
	);
}

/**
 * Parses an ISO 8601 date-time that carries a UTC offset. Returns null when invalid.
 * The returned object keeps the offset it was written with, so formatting it gives
 * the wall-clock date and time of the phone.
 */
function jot2press_parse_written_at( $value ): ?DateTimeImmutable {
	if ( ! is_string( $value ) || ! preg_match( '/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})$/', $value ) ) {
		return null;
	}
	try {
		$parsed = new DateTimeImmutable( $value );
	} catch ( Exception $e ) {
		return null;
	}
	// PHP rolls impossible dates over (Feb 30 becomes Mar 2); reject those.
	return $parsed->format( 'Y-m-d\TH:i:s' ) === substr( $value, 0, 19 ) ? $parsed : null;
}

/**
 * Reads the optional location of an entry.
 *
 * @return array|null|WP_Error Floats under the keys lat and lng; null when the entry has no location.
 */
function jot2press_parse_location( $value ) {
	if ( null === $value ) {
		return null;
	}
	$lat = is_array( $value ) && isset( $value['lat'] ) ? $value['lat'] : null;
	$lng = is_array( $value ) && isset( $value['lng'] ) ? $value['lng'] : null;
	// Numbers only: a JSON string such as "12.5" is rejected rather than guessed at.
	$ok = ( is_int( $lat ) || is_float( $lat ) ) && ( is_int( $lng ) || is_float( $lng ) )
		&& abs( $lat ) <= 90 && abs( $lng ) <= 180;
	if ( ! $ok ) {
		return jot2press_error( 'invalid_location', 'location must hold lat (-90 to 90) and lng (-180 to 180) as numbers.', 400 );
	}
	return array(
		'lat' => (float) $lat,
		'lng' => (float) $lng,
	);
}

/**
 * Checks the shape of an entry request.
 *
 * @return array|WP_Error Keys: id, written, day, text, title, category, post_status, location, photo_ids.
 */
function jot2press_validate_entry( WP_REST_Request $request ) {
	$id = jot2press_read_id( $request->get_param( 'id' ) );
	if ( '' === $id ) {
		return jot2press_error( 'invalid_id', 'id must be a UUID.', 400 );
	}

	$written = jot2press_parse_written_at( $request->get_param( 'written_at' ) );
	if ( null === $written ) {
		return jot2press_error( 'invalid_written_at', 'written_at must be an ISO 8601 date-time with a UTC offset.', 400 );
	}

	$text = $request->get_param( 'text' );
	if ( null !== $text && ! is_string( $text ) ) {
		return jot2press_error( 'invalid_text', 'text must be a string.', 400 );
	}
	$text = trim( (string) $text );

	$title = $request->get_param( 'title' );
	if ( null !== $title && ( ! is_string( $title ) || mb_strlen( $title ) > 200 ) ) {
		return jot2press_error( 'invalid_title', 'title must be a string of at most 200 characters.', 400 );
	}

	// Whether the category exists is settled later, and only if this entry creates the day's post.
	$category = $request->get_param( 'category' );
	if ( null !== $category && ( ! is_string( $category ) || '' === trim( $category ) ) ) {
		return jot2press_error( 'invalid_category', 'category must be a category slug.', 400 );
	}

	$post_status = $request->get_param( 'post_status' );
	if ( null === $post_status ) {
		$post_status = 'private';
	}
	if ( ! in_array( $post_status, array( 'private', 'draft', 'publish' ), true ) ) {
		return jot2press_error( 'invalid_post_status', 'post_status must be private, draft or publish.', 400 );
	}

	$location = jot2press_parse_location( $request->get_param( 'location' ) );
	if ( is_wp_error( $location ) ) {
		return $location;
	}

	$photo_ids = $request->get_param( 'photo_ids' );
	$photo_ids = null === $photo_ids ? array() : $photo_ids;
	if ( ! is_array( $photo_ids ) ) {
		return jot2press_error( 'invalid_photo_ids', 'photo_ids must be a list of photo ids.', 400 );
	}
	if ( count( $photo_ids ) > JOT2PRESS_MAX_PHOTOS ) {
		return jot2press_error( 'too_many_photos', 'An entry can have at most ' . JOT2PRESS_MAX_PHOTOS . ' photos.', 400 );
	}
	$photo_ids = array_map( 'jot2press_read_id', $photo_ids );
	if ( in_array( '', $photo_ids, true ) ) {
		return jot2press_error( 'invalid_photo_ids', 'photo_ids must be a list of photo ids.', 400 );
	}

	if ( '' === $text && ! $photo_ids ) {
		return jot2press_error( 'empty_entry', 'An entry needs text or photos.', 400 );
	}

	return array(
		'id'          => $id,
		'written'     => $written,
		'day'         => $written->format( 'Y-m-d' ),
		'text'        => $text,
		'title'       => $title,
		'category'    => null === $category ? null : trim( $category ),
		'post_status' => $post_status,
		'location'    => $location,
		'photo_ids'   => array_values( $photo_ids ),
	);
}

/**
 * Returns the id of the current user's post for a day (YYYY-MM-DD), or 0. Trashed posts do not count, and neither
 * do other accounts' posts: each account keeps its own journal. (Entry ids are looked up site-wide, though.)
 */
function jot2press_find_day_post( string $day ): int {
	$ids = get_posts(
		array(
			'post_type'     => 'post',
			'post_status'   => 'any',
			'author'        => get_current_user_id(),
			'meta_key'      => '_journal_date',
			'meta_value'    => $day,
			'orderby'       => 'ID',
			'order'         => 'ASC',
			'numberposts'   => 1,
			'fields'        => 'ids',
			// A concurrent request may have created the post a moment ago; never answer from a cached query.
			'cache_results' => false,
		)
	);
	return $ids ? (int) $ids[0] : 0;
}

/**
 * Picks the category for a new day post: the one asked for, or the default one
 * when none was asked for or the one asked for does not exist.
 *
 * @return array|WP_Error Keys: term (WP_Term) and fallback (true when the category asked for does not exist).
 */
function jot2press_pick_category( ?string $slug ) {
	if ( null !== $slug ) {
		$term = get_term_by( 'slug', $slug, 'category' );
		if ( $term instanceof WP_Term ) {
			return array(
				'term'     => $term,
				'fallback' => false,
			);
		}
	}

	$default = jot2press_category();
	if ( is_wp_error( $default ) ) {
		return $default;
	}
	return array(
		'term'     => $default,
		'fallback' => null !== $slug,
	);
}

/**
 * Creates the post for a day, in the given category. It starts as an empty draft and takes the status asked for
 * (private, draft or publish), kept in _journal_status until then, with its first entry: a public post must never
 * be out, or announced, without it.
 *
 * @return int|WP_Error
 */
function jot2press_create_day_post( string $day, DateTimeImmutable $written, ?string $title, string $status, WP_Term $category ) {
	// Kept as typed, on one line: not sanitize_text_field(), which drops "<template>" and "%ab". A title is HTML to
	// WordPress, so the characters it would read as markup become entities. Invalid UTF-8 is replaced, not dropped.
	$title = null === $title ? '' : trim( preg_replace( '/[\x00-\x20\x7F]+/', ' ', $title ) );
	$title = htmlspecialchars( $title, ENT_NOQUOTES | ENT_SUBSTITUTE, 'UTF-8' );
	if ( '' === $title ) {
		// DateTime::format() is never localised, so this stays in English on any site.
		$title = $day . ' · ' . $written->format( 'l, F j' );
	}

	// A phone whose clock runs fast would have WordPress schedule a public post rather than publish it: such a post is
	// dated now instead, on the phone's wall clock.
	$dated = $written;
	if ( 'publish' === $status && $written->getTimestamp() > time() ) {
		$dated = ( new DateTimeImmutable( '@' . time() ) )->setTimezone( $written->getTimezone() );
	}

	return wp_insert_post(
		wp_slash(
			array(
				'post_type'     => 'post',
				'post_status'   => 'draft',
				'post_title'    => $title,
				'post_name'     => $day,
				'post_content'  => '',
				// The date is the phone's wall clock; the GMT date is the real moment. Giving both keeps a draft's
				// date from moving to the day it is published, and keeps a public post from being scheduled
				// when the phone's clock is ahead of the site's.
				'post_date'     => $dated->format( 'Y-m-d H:i:s' ),
				'post_date_gmt' => $dated->setTimezone( new DateTimeZone( 'UTC' ) )->format( 'Y-m-d H:i:s' ),
				'post_category' => array( $category->term_id ),
				'meta_input'    => array(
					'_journal_date'   => $day,
					'_journal_status' => $status,
				),
			)
		),
		true
	);
}

/** Reads a post from the database, not from a copy cached earlier in this request. */
function jot2press_fresh_post( int $post_id ): ?WP_Post {
	clean_post_cache( $post_id );
	return get_post( $post_id );
}

/** Builds the fragment that follows an entry's time: the coordinates and a Google Maps link. */
function jot2press_render_location( array $location ): string {
	// Five decimals is about one metre. Both values are numbers formatted here, so they need no escaping.
	$lat = number_format( $location['lat'], 5, '.', '' );
	$lng = number_format( $location['lng'], 5, '.', '' );
	return " · {$lat}, {$lng} (<a href=\"https://www.google.com/maps/search/?api=1&amp;query={$lat}%2C{$lng}\">Google Maps</a>)";
}

/** The CSS class that carries an entry's id inside its own markup, so the id is saved together with the text. */
function jot2press_marker( string $id ): string {
	return 'journal-entry-' . $id;
}

/**
 * Builds the block markup for one entry: a paragraph with the time, place and text, then its photos.
 * The photos are square tiles three to a row, whatever their number, so that a lone photo is the size of any other
 * in the day's post; a tap opens the whole photo in WordPress's own viewer. The markup is what the block editor saves.
 */
function jot2press_render( string $id, DateTimeImmutable $written, string $text, ?array $location, array $attachment_ids ): string {
	$line = '<strong>' . $written->format( 'H:i' ) . '</strong>';
	if ( null !== $location ) {
		$line .= jot2press_render_location( $location );
	}
	if ( '' !== $text ) {
		// Not esc_html(), which leaves existing entities alone ("&amp;" would show as "&"). The brackets are escaped
		// so that WordPress does not run "[gallery]" and the like as shortcodes. Invalid UTF-8 is replaced, not dropped.
		$safe  = htmlspecialchars( $text, ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8' );
		$safe  = str_replace( array( '[', ']' ), array( '&#91;', '&#93;' ), $safe );
		$line .= ' — ' . str_replace( array( "\r\n", "\r", "\n" ), '<br>', $safe );
	}
	$marker = jot2press_marker( $id );
	$blocks = array( "<!-- wp:paragraph {\"className\":\"{$marker}\"} -->\n<p class=\"{$marker}\">{$line}</p>\n<!-- /wp:paragraph -->" );

	if ( $attachment_ids ) {
		$images = array();
		foreach ( $attachment_ids as $attachment_id ) {
			$images[] = sprintf(
				"<!-- wp:image {\"lightbox\":{\"enabled\":true},\"id\":%1\$d,\"aspectRatio\":\"1\",\"scale\":\"cover\",\"sizeSlug\":\"large\",\"linkDestination\":\"none\"} -->\n" .
				"<figure class=\"wp-block-image size-large\"><img src=\"%2\$s\" alt=\"\" class=\"wp-image-%1\$d\" style=\"aspect-ratio:1;object-fit:cover\"/></figure>\n" .
				'<!-- /wp:image -->',
				$attachment_id,
				esc_url( wp_get_attachment_image_url( $attachment_id, 'large' ) )
			);
		}
		$blocks[] = "<!-- wp:group {\"layout\":{\"type\":\"grid\",\"columnCount\":3}} -->\n" .
			'<div class="wp-block-group">' . implode( "\n\n", $images ) . "</div>\n" .
			'<!-- /wp:group -->';
	}

	return implode( "\n\n", $blocks );
}

/**
 * Appends markup to the end of a post's current content. WordPress saves a revision of the result, as for any edit.
 * A day post still waiting for its first entry takes the status it was created for in the same write.
 *
 * @return int|WP_Error
 */
function jot2press_append( int $post_id, string $markup ) {
	$post    = jot2press_fresh_post( $post_id );
	$content = rtrim( $post->post_content );
	$content = '' === $content ? $markup : $content . "\n\n" . $markup;

	$changes = array(
		'ID'           => $post_id,
		'post_content' => $content,
	);
	$status  = (string) get_post_meta( $post_id, '_journal_status', true );
	// Only while it is the draft this plugin made: a status someone chose since then stays.
	if ( '' !== $status && 'draft' === $post->post_status ) {
		$changes['post_status'] = $status;
	}

	$result = wp_update_post( wp_slash( $changes ), true );
	if ( ! is_wp_error( $result ) && '' !== $status ) {
		delete_post_meta( $post_id, '_journal_status' );
	}
	return $result;
}

/**
 * Finds or creates the day's post and appends the entry to it.
 *
 * @param array        $entry    A validated entry, with the `attachments` key (attachment ids) the handler adds.
 * @param WP_Term|null $fallback Output. Set to the default category when this entry created the day's post
 *                               and the category it asked for does not exist; left untouched otherwise.
 * @return int|WP_Error The id of the day's post.
 */
function jot2press_store_entry( array $entry, ?WP_Term &$fallback = null ) {
	$post_id = jot2press_find_day_post( $entry['day'] );
	if ( ! $post_id ) {
		$picked = jot2press_pick_category( $entry['category'] );
		if ( is_wp_error( $picked ) ) {
			return $picked;
		}
		$post_id = jot2press_create_day_post( $entry['day'], $entry['written'], $entry['title'], $entry['post_status'], $picked['term'] );
		if ( is_wp_error( $post_id ) ) {
			return $post_id;
		}
		if ( $picked['fallback'] ) {
			$fallback = $picked['term'];
		}
	}

	// Checked before anything changes: a refusal leaves the post and the photos as they were.
	if ( ! current_user_can( 'edit_post', $post_id ) ) {
		return jot2press_error( 'cannot_edit_day_post', "This account may not edit the day's post any more, so the entry cannot be added to it.", 403 );
	}
	foreach ( $entry['attachments'] as $attachment_id ) {
		if ( ! current_user_can( 'edit_post', $attachment_id ) ) {
			return jot2press_cannot_upload();
		}
	}

	// The text is written only once every photo is attached to the day's post (jot2press_resolve_photos made sure each
	// has an image to show): the cleanup deletes old photos attached to nothing that no post shows, so a post must never
	// show a photo that is not attached.
	foreach ( $entry['attachments'] as $attachment_id ) {
		$attached = wp_update_post(
			array(
				'ID'          => $attachment_id,
				'post_parent' => $post_id,
			),
			true
		);
		if ( is_wp_error( $attached ) ) {
			return $attached;
		}
	}

	$result = jot2press_append(
		$post_id,
		jot2press_render( $entry['id'], $entry['written'], $entry['text'], $entry['location'], $entry['attachments'] )
	);
	if ( is_wp_error( $result ) ) {
		return $result;
	}

	add_post_meta( $post_id, '_journal_entry_id', $entry['id'] );

	return $post_id;
}

/** Builds the 201 answer. `$fallback` is the category used in place of one that does not exist, when that happened. */
function jot2press_created_response( int $post_id, ?WP_Term $fallback ): WP_REST_Response {
	$body = array(
		'status'  => 'created',
		'post_id' => $post_id,
	);
	if ( null !== $fallback ) {
		$body['category_fallback'] = jot2press_category_summary( $fallback );
	}
	return new WP_REST_Response( $body, 201 );
}

function jot2press_handle_entry( WP_REST_Request $request ) {
	$entry = jot2press_validate_entry( $request );
	if ( is_wp_error( $entry ) ) {
		return $entry;
	}

	$existing = jot2press_find_entry_post( $entry['id'] );
	if ( $existing ) {
		return jot2press_duplicate_response( $existing );
	}

	if ( $entry['photo_ids'] && ! current_user_can( 'upload_files' ) ) {
		return jot2press_cannot_upload();
	}

	if ( ! jot2press_lock( $entry['day'] ) ) {
		return jot2press_error( 'jot2press_busy', 'Another entry for this day is being stored. Try again.', 503 );
	}
	$fallback = null;
	try {
		// The same entry may have been stored by another request while this one waited for the lock.
		$existing = jot2press_find_entry_post( $entry['id'] );
		if ( ! $existing ) {
			// Or its text may have been written by a store that failed before the id row was.
			$existing = jot2press_recover_entry( $entry );
		}
		if ( $existing ) {
			return jot2press_duplicate_response( $existing );
		}
		$post_id = jot2press_store_with_photos( $entry, $fallback );
	} finally {
		jot2press_unlock( $entry['day'] );
	}

	if ( is_wp_error( $post_id ) ) {
		// A refusal of this plugin's own carries its status; any other failure is the server's.
		$data = $post_id->get_error_data();
		if ( is_array( $data ) && isset( $data['status'] ) ) {
			return $post_id;
		}
		return jot2press_error( 'jot2press_failure', $post_id->get_error_message(), 500 );
	}

	return jot2press_created_response( $post_id, $fallback );
}

/**
 * Stores an entry with each of its photos locked while it is looked up, attached and shown, as the cleanup locks a
 * photo to check and delete it: otherwise the cleanup could delete a photo this request has just found.
 * Call it only while the day is locked.
 *
 * @param WP_Term|null $fallback Output, as for jot2press_store_entry().
 * @return int|WP_Error The id of the day's post.
 */
function jot2press_store_with_photos( array $entry, ?WP_Term &$fallback ) {
	$locked = jot2press_lock_all( jot2press_can_hold_locks() ? $entry['photo_ids'] : array() );
	if ( null === $locked ) {
		return jot2press_error( 'jot2press_busy', 'A photo of this entry is being stored. Try again.', 503 );
	}
	try {
		// Resolved last, inside the lock: a stored entry answers 200 even if its photos were since deleted, and
		// the photos are looked up and attached with nothing but this request's own code in between.
		$attachments = jot2press_resolve_photos( $entry['photo_ids'] );
		if ( is_wp_error( $attachments ) ) {
			return $attachments;
		}
		$entry['attachments'] = $attachments;
		return jot2press_store_entry( $entry, $fallback );
	} finally {
		jot2press_unlock_all( $locked );
	}
}

/** Returns the id of the post an entry was stored in, or 0 if it never was. */
function jot2press_find_entry_post( string $id ): int {
	$ids = get_posts(
		array(
			'post_type'     => 'post',
			'post_status'   => 'any',
			'meta_key'      => '_journal_entry_id',
			'meta_value'    => $id,
			'numberposts'   => 1,
			'fields'        => 'ids',
			// Asked twice per request, before and after waiting for the lock; the second answer must be fresh.
			'cache_results' => false,
		)
	);
	return $ids ? (int) $ids[0] : 0;
}

/**
 * The name of the database lock for a day or a photo id. Hashed so that it is 42 characters whatever the table
 * prefix (MySQL refuses names over 64), and so that two installs on one database server do not share locks.
 */
function jot2press_lock_name( string $name ): string {
	global $wpdb;
	return 'jot2press_' . md5( DB_NAME . $wpdb->prefix . $name );
}

/**
 * Takes the database lock for a day or a photo id. Returns false if another request holds it for longer than
 * `$seconds`.
 */
function jot2press_lock( string $name, int $seconds = JOT2PRESS_LOCK_SECONDS ): bool {
	global $wpdb;
	$taken = $wpdb->get_var(
		$wpdb->prepare( 'SELECT GET_LOCK(%s, %d)', jot2press_lock_name( $name ), $seconds )
	);
	return '1' === (string) $taken;
}

/**
 * Whether one request can hold several locks at once. Before MySQL 5.7.5 (and in MariaDB 5.5), taking a lock lets go
 * of the one held: an entry there keeps only its day's lock, and its photos go unlocked as they did before.
 */
function jot2press_can_hold_locks(): bool {
	global $wpdb;
	return version_compare( $wpdb->db_version(), '5.7.5', '>=' );
}

function jot2press_unlock( string $name ): void {
	global $wpdb;
	$wpdb->query( $wpdb->prepare( 'SELECT RELEASE_LOCK(%s)', jot2press_lock_name( $name ) ) );
}

/**
 * Takes the lock of every name, sorted so that two requests sharing names take them in the same order. Returns the
 * names locked, or null when one is held elsewhere for too long: the ones already taken are let go then.
 *
 * @return string[]|null
 */
function jot2press_lock_all( array $names ): ?array {
	$names = array_unique( $names );
	sort( $names );
	$held = array();
	foreach ( $names as $name ) {
		if ( ! jot2press_lock( $name ) ) {
			jot2press_unlock_all( $held );
			return null;
		}
		$held[] = $name;
	}
	return $held;
}

function jot2press_unlock_all( array $names ): void {
	foreach ( $names as $name ) {
		jot2press_unlock( $name );
	}
}

function jot2press_duplicate_response( int $post_id ): WP_REST_Response {
	return new WP_REST_Response(
		array(
			'status'  => 'duplicate',
			'post_id' => $post_id,
		),
		200
	);
}

function jot2press_photo_duplicate_response( int $attachment_id ): WP_REST_Response {
	return new WP_REST_Response(
		array(
			'status'        => 'duplicate',
			'attachment_id' => $attachment_id,
		),
		200
	);
}

/**
 * Finds an entry whose text reached the day's post although its id row was never written (a store that failed
 * after the content write), records the id row, and returns the day post's id.
 * Returns 0 when the day's post does not hold this entry. Call it only while the day is locked.
 */
function jot2press_recover_entry( array $entry ): int {
	$post_id = jot2press_find_day_post( $entry['day'] );
	if ( ! $post_id ) {
		return 0;
	}
	$post = jot2press_fresh_post( $post_id );
	if ( ! $post || false === strpos( $post->post_content, jot2press_marker( $entry['id'] ) ) ) {
		return 0;
	}
	add_post_meta( $post_id, '_journal_entry_id', $entry['id'] );
	return $post_id;
}

/**
 * Turns photo ids into attachment ids, keeping their order.
 *
 * @return int[]|WP_Error
 */
function jot2press_resolve_photos( array $photo_ids ) {
	$attachment_ids = array();
	foreach ( $photo_ids as $photo_id ) {
		$attachment_id = jot2press_find_photo( $photo_id );
		if ( ! $attachment_id ) {
			return jot2press_error( 'unknown_photo', 'Photo ' . $photo_id . ' has not been uploaded.', 400 );
		}
		// Same code as a photo never uploaded, so the app's answer is the same: upload it again.
		if ( ! jot2press_photo_is_usable( $attachment_id ) ) {
			return jot2press_error( 'unknown_photo', 'Photo ' . $photo_id . ' is no longer on the server. Upload it again.', 400 );
		}
		$attachment_ids[] = $attachment_id;
	}
	return $attachment_ids;
}

/**
 * Returns the attachment id of a journal photo the current user uploaded, or 0 if the id is unknown to them.
 * Another account's photo is never found, as its day posts are not.
 */
function jot2press_find_photo( string $id ): int {
	$ids = get_posts(
		array(
			'post_type'     => 'attachment',
			'post_status'   => array( 'private', 'inherit' ),
			'author'        => get_current_user_id(),
			'meta_key'      => '_journal_photo_id',
			'meta_value'    => $id,
			'numberposts'   => 1,
			'fields'        => 'ids',
			'cache_results' => false,
		)
	);
	return $ids ? (int) $ids[0] : 0;
}

/**
 * Whether a stored photo can still be shown: WordPress has an image for it and its file is where it should be.
 * A photo that fails this is replaced when it is uploaded again, and an entry naming it is refused until then.
 */
function jot2press_photo_is_usable( int $attachment_id ): bool {
	if ( ! wp_get_attachment_image_url( $attachment_id, 'large' ) ) {
		return false;
	}
	$file = get_attached_file( $attachment_id );
	if ( $file && file_exists( $file ) ) {
		return true;
	}
	// A site that keeps its uploads elsewhere serves them from another address and may hold no copy here:
	// only a photo served from this server's uploads folder needs its file in it.
	$uploads = wp_get_upload_dir();
	return 0 !== strpos( (string) wp_get_attachment_url( $attachment_id ), $uploads['baseurl'] );
}

/** Sends journal photos to uploads/journal/YYYY/MM, apart from the blog's other media. */
function jot2press_upload_dir( array $dirs ): array {
	$subdir         = '/journal/' . current_time( 'Y/m' );
	$dirs['subdir'] = $subdir;
	$dirs['path']   = $dirs['basedir'] . $subdir;
	$dirs['url']    = $dirs['baseurl'] . $subdir;
	return $dirs;
}

/** Replaces the uploaded filename with one that cannot be guessed. */
function jot2press_random_filename( string $dir, string $name, string $ext ): string {
	return bin2hex( random_bytes( 16 ) ) . strtolower( $ext );
}

/** Puts an empty index.html in a journal folder and in each of its parents up to uploads/journal. */
function jot2press_protect_dirs( string $dir ): void {
	$root = trailingslashit( wp_get_upload_dir()['basedir'] ) . 'journal';
	while ( 0 === strpos( $dir, $root ) ) {
		$index = $dir . '/index.html';
		if ( ! file_exists( $index ) ) {
			file_put_contents( $index, '' );
		}
		if ( $dir === $root ) {
			break;
		}
		$dir = dirname( $dir );
	}
}

function jot2press_handle_photo( WP_REST_Request $request ) {
	if ( ! current_user_can( 'upload_files' ) ) {
		return jot2press_cannot_upload();
	}

	// A body larger than PHP's post_max_size arrives with no fields and no files at all.
	if ( empty( $_POST ) && empty( $_FILES ) && (int) ( $_SERVER['CONTENT_LENGTH'] ?? 0 ) > 0 ) {
		return jot2press_error( 'photo_too_large', 'The upload exceeds the server limit.', 413 );
	}

	$id = jot2press_read_id( $request->get_param( 'id' ) );
	if ( '' === $id ) {
		return jot2press_error( 'invalid_id', 'id must be a UUID.', 400 );
	}

	$existing = jot2press_find_photo( $id );
	if ( $existing && jot2press_photo_is_usable( $existing ) ) {
		return jot2press_photo_duplicate_response( $existing );
	}

	$files = $request->get_file_params();
	if ( empty( $files['photo'] ) ) {
		return jot2press_error( 'missing_photo', 'A photo file is required.', 400 );
	}
	$upload_error = (int) $files['photo']['error'];
	if ( UPLOAD_ERR_INI_SIZE === $upload_error || UPLOAD_ERR_FORM_SIZE === $upload_error ) {
		return jot2press_error( 'photo_too_large', 'The photo exceeds the server limit.', 413 );
	}
	if ( UPLOAD_ERR_NO_FILE === $upload_error ) {
		return jot2press_error( 'missing_photo', 'A photo file is required.', 400 );
	}
	if ( UPLOAD_ERR_OK !== $upload_error ) {
		// A cut-off upload, a missing temporary folder, a full disk, an extension that stopped it: none is the photo's
		// fault, and sending it again can work. A 4xx would make the app fail the entry for good.
		return jot2press_error( 'jot2press_failure', 'The server could not receive the photo (PHP upload error ' . $upload_error . ').', 500 );
	}

	// The handler decides what is a photo from the file's own bytes, never from its name or claimed type.
	$detected = wp_get_image_mime( $files['photo']['tmp_name'] );
	if ( ! $detected || ! isset( JOT2PRESS_PHOTO_TYPES[ $detected ] ) ) {
		return jot2press_error( 'invalid_photo', 'The file is not a JPEG, PNG or WebP photo.', 400 );
	}
	if ( ! in_array( $detected, jot2press_accepted_photo_types(), true ) ) {
		return jot2press_error( 'photo_type_not_allowed', 'This site does not accept ' . JOT2PRESS_PHOTO_TYPES[ $detected ][1] . ' photos.', 400 );
	}

	require_once ABSPATH . 'wp-admin/includes/file.php';
	require_once ABSPATH . 'wp-admin/includes/image.php';
	require_once ABSPATH . 'wp-admin/includes/media.php';

	if ( ! jot2press_lock( $id ) ) {
		return jot2press_error( 'jot2press_busy', 'This photo is being stored. Try again.', 503 );
	}
	try {
		// The same photo may have been stored by another request while this one waited for the lock.
		$existing = jot2press_find_photo( $id );
		if ( $existing && jot2press_photo_is_usable( $existing ) ) {
			return jot2press_photo_duplicate_response( $existing );
		}
		// A stored photo that lost its file is replaced by this upload: answering "duplicate" would leave it broken for good.
		if ( $existing && ! wp_delete_attachment( $existing, true ) ) {
			return jot2press_error( 'jot2press_failure', 'The photo could not be stored.', 500 );
		}

		// The stored extension follows the detected type, whatever name the client sent.
		$extension               = JOT2PRESS_PHOTO_TYPES[ $detected ][0];
		$_FILES['photo']['name'] = 'photo.' . $extension;

		add_filter( 'upload_dir', 'jot2press_upload_dir' );
		try {
			$attachment_id = media_handle_upload(
				'photo',
				0,
				array(
					'post_title'  => 'Journal photo',
					// Its own slug: left to WordPress, every photo would try journal-photo, -2, -3 ... one query each.
					'post_name'   => 'journal-photo-' . bin2hex( random_bytes( 8 ) ),
					// Private for good: WordPress lists ordinary unattached media to anonymous visitors.
					'post_status' => 'private',
					// Written with the attachment, so a retry finds it even if the request dies while sizing the image.
					'meta_input'  => array( '_journal_photo_id' => $id ),
				),
				array(
					'test_form'                => false,
					'mimes'                    => array( $extension => $detected ),
					'unique_filename_callback' => 'jot2press_random_filename',
				)
			);
		} finally {
			remove_filter( 'upload_dir', 'jot2press_upload_dir' );
		}
	} finally {
		jot2press_unlock( $id );
	}

	if ( is_wp_error( $attachment_id ) ) {
		// The file was checked above, so what failed is the server's side of the store. WordPress's message, meant for
		// the person uploading, says what to fix: a full quota, a folder it cannot write, a plugin that refused the file.
		return jot2press_error( 'jot2press_failure', 'The photo could not be stored: ' . $attachment_id->get_error_message(), 500 );
	}

	jot2press_protect_dirs( dirname( get_attached_file( $attachment_id ) ) );

	return new WP_REST_Response(
		array(
			'status'        => 'created',
			'attachment_id' => $attachment_id,
		),
		201
	);
}

function jot2press_schedule_cleanup(): void {
	if ( ! wp_next_scheduled( 'jot2press_cleanup' ) ) {
		wp_schedule_event( time() + DAY_IN_SECONDS, 'daily', 'jot2press_cleanup' );
	}
}

/**
 * Takes the cleanup off the schedule, its continuations included, on every site of a network when the plugin was
 * active on all of them.
 *
 * @param bool $network_wide Whether the plugin is deactivated for the whole network.
 */
function jot2press_deactivate( $network_wide = false ): void {
	if ( ! is_multisite() || ! $network_wide ) {
		wp_unschedule_hook( 'jot2press_cleanup' );
		return;
	}
	foreach ( get_sites( array( 'fields' => 'ids', 'number' => 0 ) ) as $site_id ) {
		switch_to_blog( $site_id );
		wp_unschedule_hook( 'jot2press_cleanup' );
		restore_current_blog();
	}
}

/**
 * The ids of the photos that classic [gallery] shortcodes name, in any post but a revision: [gallery ids="1,2"], or
 * include="1,2" as older posts wrote it. Null when the posts cannot be read.
 *
 * @return array<int, true>|null
 */
function jot2press_gallery_ids(): ?array {
	global $wpdb;
	$with_gallery = $wpdb->get_col( "SELECT post_content FROM {$wpdb->posts} WHERE post_type <> 'revision' AND post_content LIKE '%[gallery%'" );
	if ( '' !== $wpdb->last_error ) {
		return null;
	}
	$ids = array();
	foreach ( $with_gallery as $content ) {
		preg_match_all( '/\[gallery\b([^\]]*)\]/', $content, $galleries );
		foreach ( $galleries[1] as $attributes ) {
			$attributes = (array) shortcode_parse_atts( $attributes );
			foreach ( array( 'ids', 'include' ) as $name ) {
				if ( isset( $attributes[ $name ] ) ) {
					$ids += array_fill_keys( wp_parse_id_list( $attributes[ $name ] ), true );
				}
			}
		}
	}
	return $ids;
}

/**
 * Whether a journal photo is shown by a post (trashed ones included, they can be restored): its file appears in
 * a post's content, found by the random 32-hex name every size of the photo shares, or it is some post's
 * featured image, or it is among `$gallery_ids`, the photos classic [gallery] shortcodes name. Every doubt counts as
 * shown, so that the photo is kept: a name that cannot be read, a query that fails.
 *
 * @param array<int, true> $gallery_ids From jot2press_gallery_ids().
 */
function jot2press_photo_is_shown( int $attachment_id, array $gallery_ids ): bool {
	global $wpdb;
	if ( isset( $gallery_ids[ $attachment_id ] ) ) {
		return true;
	}
	$file = get_attached_file( $attachment_id );
	if ( ! $file || ! preg_match( '/^[0-9a-f]{32}/', basename( $file ), $stem ) ) {
		return true;
	}

	$in_content = $wpdb->get_var(
		$wpdb->prepare(
			"SELECT ID FROM {$wpdb->posts} WHERE post_type <> 'revision' AND post_content LIKE %s LIMIT 1",
			'%' . $wpdb->esc_like( $stem[0] ) . '%'
		)
	);
	if ( '' !== $wpdb->last_error || null !== $in_content ) {
		return true;
	}

	$as_thumbnail = $wpdb->get_var(
		$wpdb->prepare(
			"SELECT post_id FROM {$wpdb->postmeta} WHERE meta_key = '_thumbnail_id' AND meta_value = %s LIMIT 1",
			(string) $attachment_id
		)
	);
	return '' !== $wpdb->last_error || null !== $as_thumbnail;
}

/**
 * The number of days after which an unused photo is deleted: JOT2PRESS_ORPHAN_DAYS (or the value given)
 * when it is a whole number from 1 to 999999, as an integer or a string of digits only. Anything else returns 0, which turns the
 * cleanup off: a value that is not understood must never shorten the time a photo is kept.
 *
 * @param mixed $value The value to judge.
 */
function jot2press_orphan_days( $value = JOT2PRESS_ORPHAN_DAYS ): int {
	if ( is_string( $value ) && preg_match( '/^\d{1,6}$/', $value ) ) {
		$value = (int) $value;
	}
	return is_int( $value ) && $value >= 1 && $value <= 999999 ? $value : 0;
}

/**
 * Deletes journal photos that no post shows and that are attached to no post, once they are older than
 * JOT2PRESS_ORPHAN_DAYS: uploads whose entry never arrived, and photos of a day's post that was
 * deleted permanently. Old and unattached only makes a photo a candidate; it is deleted only if no post shows it.
 *
 * @param mixed $after   The attachment id the run resumes after; cron passes it to a continuation, nothing otherwise.
 * @param int   $batch   How many photos to look at a time.
 * @param int   $seconds How long the run may go on before it leaves the rest to a run a minute later.
 */
function jot2press_cleanup_orphans( $after = 0, int $batch = JOT2PRESS_CLEANUP_BATCH, int $seconds = JOT2PRESS_CLEANUP_SECONDS ): void {
	global $wpdb;
	$days = jot2press_orphan_days();
	if ( ! $days ) {
		return;
	}
	// Read once for the whole run, not once a photo: a site can have many gallery posts.
	$gallery_ids = jot2press_gallery_ids();
	if ( null === $gallery_ids ) {
		return;
	}

	$after   = (int) $after;
	$cutoff  = gmdate( 'Y-m-d H:i:s', time() - $days * DAY_IN_SECONDS );
	$started = time();
	while ( true ) {
		$orphans = $wpdb->get_col(
			$wpdb->prepare(
				"SELECT p.ID FROM {$wpdb->posts} p INNER JOIN {$wpdb->postmeta} m ON m.post_id = p.ID AND m.meta_key = '_journal_photo_id'
				WHERE p.post_type = 'attachment' AND p.post_status IN ('private', 'inherit') AND p.post_parent = 0
				AND p.post_date_gmt < %s AND p.ID > %d ORDER BY p.ID LIMIT %d",
				$cutoff,
				$after,
				$batch
			)
		);
		if ( '' !== $wpdb->last_error ) {
			return;
		}
		foreach ( $orphans as $attachment_id ) {
			jot2press_delete_orphan( (int) $attachment_id, $gallery_ids );
		}
		if ( count( $orphans ) < $batch ) {
			return;
		}
		$after = (int) end( $orphans );
		if ( time() - $started >= $seconds ) {
			wp_schedule_single_event( time() + MINUTE_IN_SECONDS, 'jot2press_cleanup', array( $after ) );
			return;
		}
	}
}

/**
 * Deletes one candidate of the cleanup, unless an entry or an upload is busy with it, or it is attached or shown by
 * the time it is read again.
 *
 * @param array<int, true> $gallery_ids From jot2press_gallery_ids().
 */
function jot2press_delete_orphan( int $attachment_id, array $gallery_ids ): void {
	// Left for a later run while an entry or an upload is busy with it: the lock is the photo's, and the entry
	// holds it from looking the photo up until the post shows it.
	$photo_id = (string) get_post_meta( $attachment_id, '_journal_photo_id', true );
	if ( '' === $photo_id || ! jot2press_lock( $photo_id, 0 ) ) {
		return;
	}
	try {
		// Read again just before deleting: an entry may have attached or used the photo since the list was made.
		$attachment = jot2press_fresh_post( $attachment_id );
		if ( ! $attachment || 0 !== (int) $attachment->post_parent || jot2press_photo_is_shown( $attachment_id, $gallery_ids ) ) {
			return;
		}
		wp_delete_attachment( $attachment_id, true );
	} finally {
		jot2press_unlock( $photo_id );
	}
}
