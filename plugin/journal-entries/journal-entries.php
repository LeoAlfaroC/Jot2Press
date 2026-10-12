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

register_activation_hook( __FILE__, 'journal_entries_category' );
add_action( 'rest_api_init', 'journal_entries_register_routes' );

function journal_entries_error( string $code, string $message, int $status ): WP_Error {
	return new WP_Error( $code, $message, array( 'status' => $status ) );
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
