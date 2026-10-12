<?php
/**
 * Plugin Name:       Journal Entries
 * Description:       Appends short journal entries to one post per day, private by default. Server side of the journal capture app for Android.
 * Version:           1.0.0
 * Requires at least: 5.6
 * Requires PHP:      7.4
 * License:           GPL-2.0-or-later
 */

defined( 'ABSPATH' ) || exit;

// The default category: a day's post is filed here when the request names no category, or one that does not exist.
// Created on first use if missing.
// To change it, define this constant in wp-config.php: an edit made here is lost when the plugin is updated.
defined( 'JOURNAL_ENTRIES_CATEGORY_SLUG' ) || define( 'JOURNAL_ENTRIES_CATEGORY_SLUG', 'journal' );

const JOURNAL_ENTRIES_VERSION   = '1.0.0';
const JOURNAL_ENTRIES_NAMESPACE = 'journal/v1';

// How long a request waits for another request appending to the same day.
const JOURNAL_ENTRIES_LOCK_SECONDS = 5;

// The most photos one entry can show.
const JOURNAL_ENTRIES_MAX_PHOTOS = 10;

// The most revisions WordPress keeps of a day's post. Each entry leaves one, which is where to look for an entry
// that an editor left open overwrote; a site that keeps fewer (or none) is respected.
const JOURNAL_ENTRIES_REVISIONS = 30;

// Journal photos that no post shows and that are attached to no post are deleted once they are older than this many
// days. Only a whole number of days from 1 to 999999 turns the cleanup on: anything else, including 0, false, text
// and a decimal such as 30.0, turns it off.
// To change it, define this constant in wp-config.php.
defined( 'JOURNAL_ENTRIES_ORPHAN_DAYS' ) || define( 'JOURNAL_ENTRIES_ORPHAN_DAYS', 30 );

register_activation_hook( __FILE__, 'journal_entries_category' );
add_action( 'rest_api_init', 'journal_entries_register_routes' );
register_deactivation_hook( __FILE__, 'journal_entries_deactivate' );
add_action( 'init', 'journal_entries_schedule_cleanup' );
add_action( 'journal_entries_cleanup', 'journal_entries_cleanup_orphans' );
add_filter( 'wp_revisions_to_keep', 'journal_entries_revisions_to_keep', 10, 2 );

function journal_entries_error( string $code, string $message, int $status ): WP_Error {
	return new WP_Error( $code, $message, array( 'status' => $status ) );
}

/** Caps the revisions of a day's post (a post with a _journal_date) at JOURNAL_ENTRIES_REVISIONS. */
function journal_entries_revisions_to_keep( $num, $post ) {
	$unlimited_or_more = $num < 0 || $num > JOURNAL_ENTRIES_REVISIONS;
	if ( $unlimited_or_more && $post instanceof WP_Post && '' !== (string) get_post_meta( $post->ID, '_journal_date', true ) ) {
		return JOURNAL_ENTRIES_REVISIONS;
	}
	return $num;
}

/** Whether a value is an id: a UUID of exactly 36 characters (wp_is_uuid() alone accepts a trailing line break). */
function journal_entries_is_id( $value ): bool {
	return is_string( $value ) && 36 === strlen( $value ) && wp_is_uuid( $value );
}

function journal_entries_can_publish(): bool {
	return current_user_can( 'publish_posts' );
}

/**
 * Returns the default category, creating it if it does not exist.
 *
 * @return WP_Term|WP_Error
 */
function journal_entries_category() {
	$term = get_term_by( 'slug', JOURNAL_ENTRIES_CATEGORY_SLUG, 'category' );
	if ( $term instanceof WP_Term ) {
		return $term;
	}
	$created = wp_insert_term( 'Journal', 'category', array( 'slug' => JOURNAL_ENTRIES_CATEGORY_SLUG ) );
	if ( is_wp_error( $created ) ) {
		return $created;
	}
	return get_term( $created['term_id'], 'category' );
}

function journal_entries_register_routes(): void {
	register_rest_route(
		JOURNAL_ENTRIES_NAMESPACE,
		'/status',
		array(
			'methods'             => WP_REST_Server::READABLE,
			'callback'            => 'journal_entries_handle_status',
			'permission_callback' => 'journal_entries_can_publish',
		)
	);

	register_rest_route(
		JOURNAL_ENTRIES_NAMESPACE,
		'/entries',
		array(
			'methods'             => WP_REST_Server::CREATABLE,
			'callback'            => 'journal_entries_handle_entry',
			'permission_callback' => 'journal_entries_can_publish',
		)
	);

	register_rest_route(
		JOURNAL_ENTRIES_NAMESPACE,
		'/photos',
		array(
			'methods'             => WP_REST_Server::CREATABLE,
			'callback'            => 'journal_entries_handle_photo',
			'permission_callback' => 'journal_entries_can_publish',
		)
	);
}

/** Describes a category the way the app shows it: its slug and its readable name. */
function journal_entries_category_summary( WP_Term $term ): array {
	return array(
		'slug' => $term->slug,
		// WordPress stores names with entities ("Tea &amp; Cake"); the app shows plain text.
		'name' => wp_specialchars_decode( $term->name, ENT_QUOTES ),
	);
}

/** Lists every category of the site, used or not, sorted by name. */
function journal_entries_categories(): array {
	$terms = get_terms(
		array(
			'taxonomy'   => 'category',
			'hide_empty' => false,
			'orderby'    => 'name',
			'order'      => 'ASC',
		)
	);
	return is_wp_error( $terms ) ? array() : array_map( 'journal_entries_category_summary', array_values( $terms ) );
}

function journal_entries_handle_status() {
	// Asked for first, so that the default category exists before the categories are listed.
	$category = journal_entries_category();
	if ( is_wp_error( $category ) ) {
		return journal_entries_error( 'journal_failure', $category->get_error_message(), 500 );
	}
	return new WP_REST_Response(
		array(
			'ok'               => true,
			'user'             => wp_get_current_user()->user_login,
			'category'         => journal_entries_category_summary( $category ),
			'categories'       => journal_entries_categories(),
			'max_upload_bytes' => (int) wp_max_upload_size(),
			'version'          => JOURNAL_ENTRIES_VERSION,
		),
		200
	);
}

/**
 * Parses an ISO 8601 date-time that carries a UTC offset. Returns null when invalid.
 * The returned object keeps the offset it was written with, so formatting it gives
 * the wall-clock date and time of the phone.
 */
function journal_entries_parse_written_at( $value ): ?DateTimeImmutable {
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
function journal_entries_parse_location( $value ) {
	if ( null === $value ) {
		return null;
	}
	$lat = is_array( $value ) && isset( $value['lat'] ) ? $value['lat'] : null;
	$lng = is_array( $value ) && isset( $value['lng'] ) ? $value['lng'] : null;
	// Numbers only: a JSON string such as "12.5" is rejected rather than guessed at.
	$ok = ( is_int( $lat ) || is_float( $lat ) ) && ( is_int( $lng ) || is_float( $lng ) )
		&& abs( $lat ) <= 90 && abs( $lng ) <= 180;
	if ( ! $ok ) {
		return journal_entries_error( 'invalid_location', 'location must hold lat (-90 to 90) and lng (-180 to 180) as numbers.', 400 );
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
function journal_entries_validate_entry( WP_REST_Request $request ) {
	$id = $request->get_param( 'id' );
	$id = is_string( $id ) ? strtolower( $id ) : '';
	if ( ! journal_entries_is_id( $id ) ) {
		return journal_entries_error( 'invalid_id', 'id must be a UUID.', 400 );
	}

	$written = journal_entries_parse_written_at( $request->get_param( 'written_at' ) );
	if ( null === $written ) {
		return journal_entries_error( 'invalid_written_at', 'written_at must be an ISO 8601 date-time with a UTC offset.', 400 );
	}

	$text = $request->get_param( 'text' );
	if ( null !== $text && ! is_string( $text ) ) {
		return journal_entries_error( 'invalid_text', 'text must be a string.', 400 );
	}
	$text = trim( (string) $text );

	$title = $request->get_param( 'title' );
	if ( null !== $title && ( ! is_string( $title ) || mb_strlen( $title ) > 200 ) ) {
		return journal_entries_error( 'invalid_title', 'title must be a string of at most 200 characters.', 400 );
	}

	// Whether the category exists is settled later, and only if this entry creates the day's post.
	$category = $request->get_param( 'category' );
	if ( null !== $category && ( ! is_string( $category ) || '' === trim( $category ) ) ) {
		return journal_entries_error( 'invalid_category', 'category must be a category slug.', 400 );
	}

	$post_status = $request->get_param( 'post_status' );
	if ( null === $post_status ) {
		$post_status = 'private';
	}
	if ( ! in_array( $post_status, array( 'private', 'draft', 'publish' ), true ) ) {
		return journal_entries_error( 'invalid_post_status', 'post_status must be private, draft or publish.', 400 );
	}

	$location = journal_entries_parse_location( $request->get_param( 'location' ) );
	if ( is_wp_error( $location ) ) {
		return $location;
	}

	$photo_ids = $request->get_param( 'photo_ids' );
	$photo_ids = null === $photo_ids ? array() : $photo_ids;
	if ( ! is_array( $photo_ids ) ) {
		return journal_entries_error( 'invalid_photo_ids', 'photo_ids must be a list of photo ids.', 400 );
	}
	if ( count( $photo_ids ) > JOURNAL_ENTRIES_MAX_PHOTOS ) {
		return journal_entries_error( 'too_many_photos', 'An entry can have at most 10 photos.', 400 );
	}
	foreach ( $photo_ids as $index => $photo_id ) {
		$photo_id = is_string( $photo_id ) ? strtolower( $photo_id ) : '';
		if ( ! journal_entries_is_id( $photo_id ) ) {
			return journal_entries_error( 'invalid_photo_ids', 'photo_ids must be a list of photo ids.', 400 );
		}
		$photo_ids[ $index ] = $photo_id;
	}

	if ( '' === $text && ! $photo_ids ) {
		return journal_entries_error( 'empty_entry', 'An entry needs text or photos.', 400 );
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
function journal_entries_find_day_post( string $day ): int {
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
function journal_entries_pick_category( ?string $slug ) {
	if ( null !== $slug ) {
		$term = get_term_by( 'slug', $slug, 'category' );
		if ( $term instanceof WP_Term ) {
			return array(
				'term'     => $term,
				'fallback' => false,
			);
		}
	}

	$default = journal_entries_category();
	if ( is_wp_error( $default ) ) {
		return $default;
	}
	return array(
		'term'     => $default,
		'fallback' => null !== $slug,
	);
}

/**
 * Creates the post for a day, with the given status (private, draft or publish) and category.
 *
 * @return int|WP_Error
 */
function journal_entries_create_day_post( string $day, DateTimeImmutable $written, ?string $title, string $status, WP_Term $category ) {
	$title = null === $title ? '' : sanitize_text_field( $title );
	if ( '' === $title ) {
		// DateTime::format() is never localised, so this stays in English on any site.
		$title = $day . ' · ' . $written->format( 'l, F j' );
	}

	return wp_insert_post(
		wp_slash(
			array(
				'post_type'     => 'post',
				'post_status'   => $status,
				'post_title'    => $title,
				'post_name'     => $day,
				'post_content'  => '',
				// The date is the phone's wall clock; the GMT date is the real moment. Giving both keeps a draft's
				// date from moving to the day it is published, and keeps a public post from being scheduled
				// when the phone's clock is ahead of the site's.
				'post_date'     => $written->format( 'Y-m-d H:i:s' ),
				'post_date_gmt' => $written->setTimezone( new DateTimeZone( 'UTC' ) )->format( 'Y-m-d H:i:s' ),
				'post_category' => array( $category->term_id ),
				'meta_input'    => array( '_journal_date' => $day ),
			)
		),
		true
	);
}

/** Builds the fragment that follows an entry's time: the coordinates and a Google Maps link. */
function journal_entries_render_location( array $location ): string {
	// Five decimals is about one metre. Both values are numbers formatted here, so they need no escaping.
	$lat = number_format( $location['lat'], 5, '.', '' );
	$lng = number_format( $location['lng'], 5, '.', '' );
	return " · {$lat}, {$lng} (<a href=\"https://www.google.com/maps/search/?api=1&amp;query={$lat}%2C{$lng}\">Google Maps</a>)";
}

/** The CSS class that carries an entry's id inside its own markup, so the id is saved together with the text. */
function journal_entries_marker( string $id ): string {
	return 'journal-entry-' . $id;
}

/** Builds the block markup for one entry: a paragraph with the time, place and text, then one image per photo. */
function journal_entries_render( string $id, DateTimeImmutable $written, string $text, ?array $location, array $attachment_ids ): string {
	$line = '<strong>' . $written->format( 'H:i' ) . '</strong>';
	if ( null !== $location ) {
		$line .= journal_entries_render_location( $location );
	}
	if ( '' !== $text ) {
		// Not esc_html(), which leaves existing entities alone ("&amp;" would show as "&"). The brackets are escaped
		// so that WordPress does not run "[gallery]" and the like as shortcodes. Invalid UTF-8 is replaced, not dropped.
		$safe  = htmlspecialchars( $text, ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8' );
		$safe  = str_replace( array( '[', ']' ), array( '&#91;', '&#93;' ), $safe );
		$line .= ' — ' . str_replace( array( "\r\n", "\r", "\n" ), '<br>', $safe );
	}
	$marker = journal_entries_marker( $id );
	$blocks = array( "<!-- wp:paragraph {\"className\":\"{$marker}\"} -->\n<p class=\"{$marker}\">{$line}</p>\n<!-- /wp:paragraph -->" );

	foreach ( $attachment_ids as $attachment_id ) {
		$blocks[] = sprintf(
			"<!-- wp:image {\"id\":%1\$d,\"sizeSlug\":\"large\",\"linkDestination\":\"none\"} -->\n" .
			"<figure class=\"wp-block-image size-large\"><img src=\"%2\$s\" alt=\"\" class=\"wp-image-%1\$d\"/></figure>\n" .
			'<!-- /wp:image -->',
			$attachment_id,
			esc_url( wp_get_attachment_image_url( $attachment_id, 'large' ) )
		);
	}

	return implode( "\n\n", $blocks );
}

/**
 * Appends markup to the end of a post's current content. WordPress saves a revision of the result, as for any edit.
 *
 * @return int|WP_Error
 */
function journal_entries_append( int $post_id, string $markup ) {
	// Read the content from the database, not from a copy cached earlier in this request.
	clean_post_cache( $post_id );
	$content = rtrim( get_post( $post_id )->post_content );
	$content = '' === $content ? $markup : $content . "\n\n" . $markup;

	return wp_update_post(
		wp_slash(
			array(
				'ID'           => $post_id,
				'post_content' => $content,
			)
		),
		true
	);
}

/**
 * Finds or creates the day's post and appends the entry to it.
 *
 * @param array        $entry    A validated entry, with the `attachments` key (attachment ids) the handler adds.
 * @param WP_Term|null $fallback Output. Set to the default category when this entry created the day's post
 *                               and the category it asked for does not exist; left untouched otherwise.
 * @return int|WP_Error The id of the day's post.
 */
function journal_entries_store_entry( array $entry, ?WP_Term &$fallback = null ) {
	$post_id = journal_entries_find_day_post( $entry['day'] );
	if ( ! $post_id ) {
		$picked = journal_entries_pick_category( $entry['category'] );
		if ( is_wp_error( $picked ) ) {
			return $picked;
		}
		$post_id = journal_entries_create_day_post( $entry['day'], $entry['written'], $entry['title'], $entry['post_status'], $picked['term'] );
		if ( is_wp_error( $post_id ) ) {
			return $post_id;
		}
		if ( $picked['fallback'] ) {
			$fallback = $picked['term'];
		}
	}

	// The text is written only once every photo is attached to the day's post and has an image to show: the cleanup
	// deletes old photos attached to nothing that no post shows, so a post must never show a photo that is not attached.
	foreach ( $entry['attachments'] as $attachment_id ) {
		if ( ! wp_get_attachment_image_url( $attachment_id, 'large' ) ) {
			return new WP_Error( 'journal_photo_missing', 'Photo ' . $attachment_id . ' has no image to show.' );
		}
	}
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

	$result = journal_entries_append(
		$post_id,
		journal_entries_render( $entry['id'], $entry['written'], $entry['text'], $entry['location'], $entry['attachments'] )
	);
	if ( is_wp_error( $result ) ) {
		return $result;
	}

	add_post_meta( $post_id, '_journal_entry_id', $entry['id'] );

	return $post_id;
}

/** Builds the 201 answer. `$fallback` is the category used in place of one that does not exist, when that happened. */
function journal_entries_created_response( int $post_id, ?WP_Term $fallback ): WP_REST_Response {
	$body = array(
		'status'  => 'created',
		'post_id' => $post_id,
	);
	if ( null !== $fallback ) {
		$body['category_fallback'] = journal_entries_category_summary( $fallback );
	}
	return new WP_REST_Response( $body, 201 );
}

function journal_entries_handle_entry( WP_REST_Request $request ) {
	$entry = journal_entries_validate_entry( $request );
	if ( is_wp_error( $entry ) ) {
		return $entry;
	}

	$existing = journal_entries_find_entry_post( $entry['id'] );
	if ( $existing ) {
		return journal_entries_duplicate_response( $existing );
	}

	if ( ! journal_entries_lock( $entry['day'] ) ) {
		return journal_entries_error( 'journal_busy', 'Another entry for this day is being stored. Try again.', 503 );
	}
	$fallback = null;
	try {
		// The same entry may have been stored by another request while this one waited for the lock.
		$existing = journal_entries_find_entry_post( $entry['id'] );
		if ( ! $existing ) {
			// Or its text may have been written by a store that failed before the id row was.
			$existing = journal_entries_recover_entry( $entry );
		}
		if ( $existing ) {
			return journal_entries_duplicate_response( $existing );
		}
		// Resolved last, inside the lock: a stored entry answers 200 even if its photos were since deleted, and
		// the photos are looked up and attached with nothing but this request's own code in between.
		$attachments = journal_entries_resolve_photos( $entry['photo_ids'] );
		if ( is_wp_error( $attachments ) ) {
			return $attachments;
		}
		$entry['attachments'] = $attachments;
		$post_id              = journal_entries_store_entry( $entry, $fallback );
	} finally {
		journal_entries_unlock( $entry['day'] );
	}

	if ( is_wp_error( $post_id ) ) {
		return journal_entries_error( 'journal_failure', $post_id->get_error_message(), 500 );
	}

	return journal_entries_created_response( $post_id, $fallback );
}

/** Returns the id of the post an entry was stored in, or 0 if it never was. */
function journal_entries_find_entry_post( string $id ): int {
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
 * The name of the database lock for a day or a photo id. Hashed so that it is 40 characters whatever the table
 * prefix (MySQL refuses names over 64), and so that two installs on one database server do not share locks.
 */
function journal_entries_lock_name( string $name ): string {
	global $wpdb;
	return 'journal_' . md5( DB_NAME . $wpdb->prefix . $name );
}

/** Takes the database lock for a day or a photo id. Returns false if another request holds it for too long. */
function journal_entries_lock( string $name ): bool {
	global $wpdb;
	$taken = $wpdb->get_var(
		$wpdb->prepare( 'SELECT GET_LOCK(%s, %d)', journal_entries_lock_name( $name ), JOURNAL_ENTRIES_LOCK_SECONDS )
	);
	return '1' === (string) $taken;
}

function journal_entries_unlock( string $name ): void {
	global $wpdb;
	$wpdb->query( $wpdb->prepare( 'SELECT RELEASE_LOCK(%s)', journal_entries_lock_name( $name ) ) );
}

function journal_entries_duplicate_response( int $post_id ): WP_REST_Response {
	return new WP_REST_Response(
		array(
			'status'  => 'duplicate',
			'post_id' => $post_id,
		),
		200
	);
}

function journal_entries_photo_duplicate_response( int $attachment_id ): WP_REST_Response {
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
function journal_entries_recover_entry( array $entry ): int {
	$post_id = journal_entries_find_day_post( $entry['day'] );
	if ( ! $post_id ) {
		return 0;
	}
	// Read the content from the database, not from a copy cached earlier in this request.
	clean_post_cache( $post_id );
	$post = get_post( $post_id );
	if ( ! $post || false === strpos( $post->post_content, journal_entries_marker( $entry['id'] ) ) ) {
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
function journal_entries_resolve_photos( array $photo_ids ) {
	$attachment_ids = array();
	foreach ( $photo_ids as $photo_id ) {
		$attachment_id = journal_entries_find_photo( $photo_id );
		if ( ! $attachment_id ) {
			return journal_entries_error( 'unknown_photo', 'Photo ' . $photo_id . ' has not been uploaded.', 400 );
		}
		$attachment_ids[] = $attachment_id;
	}
	return $attachment_ids;
}

/** Returns the attachment id of an uploaded journal photo, or 0 if the id is unknown. */
function journal_entries_find_photo( string $id ): int {
	$ids = get_posts(
		array(
			'post_type'     => 'attachment',
			'post_status'   => array( 'private', 'inherit' ),
			'meta_key'      => '_journal_photo_id',
			'meta_value'    => $id,
			'numberposts'   => 1,
			'fields'        => 'ids',
			'cache_results' => false,
		)
	);
	return $ids ? (int) $ids[0] : 0;
}

/** Sends journal photos to uploads/journal/YYYY/MM, apart from the blog's other media. */
function journal_entries_upload_dir( array $dirs ): array {
	$subdir         = '/journal/' . current_time( 'Y/m' );
	$dirs['subdir'] = $subdir;
	$dirs['path']   = $dirs['basedir'] . $subdir;
	$dirs['url']    = $dirs['baseurl'] . $subdir;
	return $dirs;
}

/** Replaces the uploaded filename with one that cannot be guessed. */
function journal_entries_random_filename( string $dir, string $name, string $ext ): string {
	return bin2hex( random_bytes( 16 ) ) . strtolower( $ext );
}

/** Puts an empty index.html in a journal folder and in each of its parents up to uploads/journal. */
function journal_entries_protect_dirs( string $dir ): void {
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

function journal_entries_handle_photo( WP_REST_Request $request ) {
	// A body larger than PHP's post_max_size arrives with no fields and no files at all.
	if ( empty( $_POST ) && empty( $_FILES ) && (int) ( $_SERVER['CONTENT_LENGTH'] ?? 0 ) > 0 ) {
		return journal_entries_error( 'photo_too_large', 'The upload exceeds the server limit.', 413 );
	}

	$id = $request->get_param( 'id' );
	$id = is_string( $id ) ? strtolower( $id ) : '';
	if ( ! journal_entries_is_id( $id ) ) {
		return journal_entries_error( 'invalid_id', 'id must be a UUID.', 400 );
	}

	$existing = journal_entries_find_photo( $id );
	if ( $existing ) {
		return journal_entries_photo_duplicate_response( $existing );
	}

	$files = $request->get_file_params();
	if ( empty( $files['photo'] ) ) {
		return journal_entries_error( 'missing_photo', 'A photo file is required.', 400 );
	}
	$upload_error = (int) $files['photo']['error'];
	if ( UPLOAD_ERR_INI_SIZE === $upload_error || UPLOAD_ERR_FORM_SIZE === $upload_error ) {
		return journal_entries_error( 'photo_too_large', 'The photo exceeds the server limit.', 413 );
	}
	if ( UPLOAD_ERR_OK !== $upload_error ) {
		return journal_entries_error( 'missing_photo', 'The photo did not arrive complete.', 400 );
	}

	// The handler decides what is a photo from the file's own bytes, never from its name or claimed type.
	$types    = array(
		'image/jpeg' => 'jpg',
		'image/png'  => 'png',
		'image/webp' => 'webp',
	);
	$detected = wp_get_image_mime( $files['photo']['tmp_name'] );
	if ( ! $detected || ! isset( $types[ $detected ] ) || ! in_array( $detected, get_allowed_mime_types(), true ) ) {
		return journal_entries_error( 'invalid_photo', 'The file is not a JPEG, PNG or WebP photo.', 400 );
	}

	require_once ABSPATH . 'wp-admin/includes/file.php';
	require_once ABSPATH . 'wp-admin/includes/image.php';
	require_once ABSPATH . 'wp-admin/includes/media.php';

	if ( ! journal_entries_lock( $id ) ) {
		return journal_entries_error( 'journal_busy', 'This photo is being stored. Try again.', 503 );
	}
	try {
		// The same photo may have been stored by another request while this one waited for the lock.
		$existing = journal_entries_find_photo( $id );
		if ( $existing ) {
			return journal_entries_photo_duplicate_response( $existing );
		}

		// The stored extension follows the detected type, whatever name the client sent.
		$_FILES['photo']['name'] = 'photo.' . $types[ $detected ];

		add_filter( 'upload_dir', 'journal_entries_upload_dir' );
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
					'mimes'                    => array(
						'jpg|jpeg|jpe' => 'image/jpeg',
						'png'          => 'image/png',
						'webp'         => 'image/webp',
					),
					'unique_filename_callback' => 'journal_entries_random_filename',
				)
			);
		} finally {
			remove_filter( 'upload_dir', 'journal_entries_upload_dir' );
		}
	} finally {
		journal_entries_unlock( $id );
	}

	if ( is_wp_error( $attachment_id ) ) {
		// The file was checked above, so what failed is the server's side of the store.
		return journal_entries_error( 'journal_failure', 'The photo could not be stored.', 500 );
	}

	journal_entries_protect_dirs( dirname( get_attached_file( $attachment_id ) ) );

	return new WP_REST_Response(
		array(
			'status'        => 'created',
			'attachment_id' => $attachment_id,
		),
		201
	);
}

function journal_entries_schedule_cleanup(): void {
	if ( ! wp_next_scheduled( 'journal_entries_cleanup' ) ) {
		wp_schedule_event( time() + DAY_IN_SECONDS, 'daily', 'journal_entries_cleanup' );
	}
}

function journal_entries_deactivate(): void {
	wp_clear_scheduled_hook( 'journal_entries_cleanup' );
}

/**
 * Whether a journal photo is shown by a post (trashed ones included, they can be restored): its file appears in
 * a post's content, found by the random 32-hex name every size of the photo shares, or it is some post's
 * featured image. A photo referenced only by its id in a [gallery] shortcode is not recognised. Every doubt
 * counts as shown, so that the photo is kept: a name that cannot be read, a query that fails.
 */
function journal_entries_photo_is_shown( int $attachment_id ): bool {
	global $wpdb;
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
 * The number of days after which an unused photo is deleted: JOURNAL_ENTRIES_ORPHAN_DAYS (or the value given)
 * when it is a whole number from 1 to 999999, as an integer or a string of digits only. Anything else returns 0, which turns the
 * cleanup off: a value that is not understood must never shorten the time a photo is kept.
 *
 * @param mixed $value The value to judge.
 */
function journal_entries_orphan_days( $value = JOURNAL_ENTRIES_ORPHAN_DAYS ): int {
	if ( is_string( $value ) && preg_match( '/^\d{1,6}$/', $value ) ) {
		$value = (int) $value;
	}
	return is_int( $value ) && $value >= 1 && $value <= 999999 ? $value : 0;
}

/**
 * Deletes journal photos that no post shows and that are attached to no post, once they are older than
 * JOURNAL_ENTRIES_ORPHAN_DAYS: uploads whose entry never arrived, and photos of a day's post that was
 * deleted permanently. Old and unattached only makes a photo a candidate; it is deleted only if no post shows it.
 */
function journal_entries_cleanup_orphans(): void {
	$days = journal_entries_orphan_days();
	if ( ! $days ) {
		return;
	}

	$orphans = get_posts(
		array(
			'post_type'     => 'attachment',
			'post_status'   => array( 'private', 'inherit' ),
			'post_parent'   => 0,
			'meta_key'      => '_journal_photo_id',
			'date_query'    => array(
				array( 'before' => $days . ' days ago' ),
			),
			'numberposts'   => -1,
			'fields'        => 'ids',
			'cache_results' => false,
		)
	);
	foreach ( $orphans as $attachment_id ) {
		// Read again just before deleting: an entry may have attached or used the photo since the list was made.
		clean_post_cache( $attachment_id );
		$attachment = get_post( $attachment_id );
		if ( ! $attachment || 0 !== (int) $attachment->post_parent || journal_entries_photo_is_shown( $attachment_id ) ) {
			continue;
		}
		wp_delete_attachment( $attachment_id, true );
	}
}
