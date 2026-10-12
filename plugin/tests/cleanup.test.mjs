import assert from 'node:assert/strict';
import { test } from 'node:test';
import { api, at, authHeader, BASE, dayAllocator, fixture, readMedia, sendEntry, uploadPhoto, uuid, wp } from './helpers.mjs';

const day = dayAllocator( 4 );

/** Makes an attachment look as if it had been uploaded in the year 2000. */
function backdate( attachmentId ) {
	const id = Number( attachmentId );
	wp(
		'eval',
		`global $wpdb; $wpdb->update( $wpdb->posts, array( "post_date" => "2000-01-01 00:00:00", "post_date_gmt" => "2000-01-01 00:00:00" ), array( "ID" => ${ id } ) ); clean_post_cache( ${ id } );`
	);
}

const runCleanup = () => wp( 'cron', 'event', 'run', 'journal_entries_cleanup' );

const mediaStatus = async ( attachmentId ) => ( await api( 'GET', `/wp/v2/media/${ attachmentId }` ) ).status;

test( 'cleanup deletes journal photos left unattached for more than 30 days', async () => {
	const stale = await uploadPhoto();
	const staleUrl = ( await readMedia( stale.body.attachment_id ) ).source_url;
	backdate( stale.body.attachment_id );

	const recent = await uploadPhoto();

	const used = await uploadPhoto();
	backdate( used.body.attachment_id );
	await sendEntry( { id: uuid(), written_at: at( day(), '10:00:00' ), photo_ids: [ used.id ] } );

	runCleanup();

	assert.equal( await mediaStatus( stale.body.attachment_id ), 404 );
	assert.equal( ( await fetch( staleUrl ) ).status, 404 );
	assert.equal( await mediaStatus( recent.body.attachment_id ), 200 );
	assert.equal( await mediaStatus( used.body.attachment_id ), 200 );
} );

test( 'cleanup never touches media that did not come through the journal', async () => {
	const response = await fetch( `${ BASE }/?rest_route=/wp/v2/media`, {
		method: 'POST',
		headers: {
			...authHeader(),
			'Content-Type': 'image/jpeg',
			'Content-Disposition': 'attachment; filename="blog-photo.jpg"',
		},
		body: fixture( 'photo.jpg' ),
	} );
	assert.equal( response.status, 201 );
	const { id } = await response.json();
	backdate( id );

	runCleanup();

	assert.equal( await mediaStatus( id ), 200 );
} );

test( 'cleanup is scheduled to run daily', () => {
	const output = wp( 'cron', 'event', 'list', '--fields=hook,recurrence', '--format=json' );
	const events = JSON.parse( output.slice( output.indexOf( '[' ), output.lastIndexOf( ']' ) + 1 ) );
	const event = events.find( ( candidate ) => candidate.hook === 'journal_entries_cleanup' );
	assert.ok( event, 'journal_entries_cleanup is not scheduled' );
	assert.equal( event.recurrence, '1 day' );
} );
