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

/** Makes an attachment look as if it had been uploaded the given number of days ago. */
function backdateDays( attachmentId, days ) {
	const id = Number( attachmentId );
	wp(
		'eval',
		`global $wpdb; $date = gmdate( "Y-m-d H:i:s", time() - ${ days } * DAY_IN_SECONDS ); $wpdb->update( $wpdb->posts, array( "post_date" => get_date_from_gmt( $date ), "post_date_gmt" => $date ), array( "ID" => ${ id } ) ); clean_post_cache( ${ id } );`
	);
}

const runCleanup = () => wp( 'cron', 'event', 'run', 'jot2press_cleanup' );

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
	const event = events.find( ( candidate ) => candidate.hook === 'jot2press_cleanup' );
	assert.ok( event, 'jot2press_cleanup is not scheduled' );
	assert.equal( event.recurrence, '1 day' );
} );

test( 'a photo that a post still shows is kept even when it is attached to nothing', async () => {
	const photo = await uploadPhoto();
	const url = ( await readMedia( photo.body.attachment_id ) ).source_url;
	await sendEntry( { id: uuid(), written_at: at( day(), '10:00:00' ), photo_ids: [ photo.id ] } );
	backdate( photo.body.attachment_id );
	wp( 'post', 'update', String( photo.body.attachment_id ), '--post_parent=0' );

	runCleanup();

	assert.equal( await mediaStatus( photo.body.attachment_id ), 200 );
	assert.equal( ( await fetch( url ) ).status, 200 );
} );

test( 'a photo is deleted only after the configured number of days', async () => {
	const young = await uploadPhoto();
	backdateDays( young.body.attachment_id, 29 );
	const old = await uploadPhoto();
	backdateDays( old.body.attachment_id, 31 );

	runCleanup();

	assert.equal( await mediaStatus( young.body.attachment_id ), 200 );
	assert.equal( await mediaStatus( old.body.attachment_id ), 404 );
} );

test( "the photos of a day's post deleted permanently are cleaned up", async () => {
	const photo = await uploadPhoto();
	const sent = await sendEntry( { id: uuid(), written_at: at( day(), '10:00:00' ), photo_ids: [ photo.id ] } );
	backdate( photo.body.attachment_id );
	wp( 'post', 'delete', String( sent.body.post_id ), '--force' );

	runCleanup();

	assert.equal( await mediaStatus( photo.body.attachment_id ), 404 );
} );

test( "a photo that is a post's featured image is kept", async () => {
	const photo = await uploadPhoto();
	const url = ( await readMedia( photo.body.attachment_id ) ).source_url;
	const sent = await sendEntry( { id: uuid(), written_at: at( day(), '10:00:00' ), photo_ids: [ photo.id ] } );
	const other = wp( 'post', 'create', '--post_title=Elsewhere', '--post_status=publish', '--porcelain' ).match( /^\d+$/m )[ 0 ];
	wp( 'post', 'meta', 'add', other, '_thumbnail_id', String( photo.body.attachment_id ) );
	backdate( photo.body.attachment_id );
	wp( 'post', 'delete', String( sent.body.post_id ), '--force' );

	runCleanup();

	assert.equal( await mediaStatus( photo.body.attachment_id ), 200 );
	assert.equal( ( await fetch( url ) ).status, 200 );
} );

test( 'the cleanup deletes nothing when the number of days is not a whole number of at least 1', () => {
	const output = wp(
		'eval',
		`$out = array(); foreach ( array( 30, "30", 1, 999999, "999999", 0, -5, false, null, "", "off", 0.5, 30.5, 30.0, "30d", "7 weeks", 1000000, "1000000", 20270101000000, PHP_INT_MAX ) as $value ) { $out[] = jot2press_orphan_days( $value ); } echo "RESULT:" . wp_json_encode( $out );`
	);
	const result = JSON.parse( output.match( /RESULT:(\[.*?\])/ )[ 1 ] );
	assert.deepEqual( result, [ 30, 30, 1, 999999, 999999, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 ] );
} );

/** Lists the files a photo has in its uploads folder: the attached one, the original and every size. */
function filesOfPhoto( attachmentId ) {
	const output = wp(
		'eval',
		`$file = get_attached_file( ${ Number( attachmentId ) } ); $stem = substr( basename( $file ), 0, 32 ); $dir = dirname( $file ); $names = array_map( 'basename', glob( $dir . '/' . $stem . '*' ) ); sort( $names ); echo 'RESULT:' . wp_json_encode( array( basename( $file ), $stem, $names ) );`
	);
	const [ attached, stem, names ] = JSON.parse( output.match( /RESULT:(\[.*\])/ )[ 1 ] );
	return { attached, stem, names };
}

test( 'the cleanup removes every file of a large photo: original, scaled copy and sizes', async () => {
	const photo = await uploadPhoto( { file: 'photo-large.jpg' } );
	const before = filesOfPhoto( photo.body.attachment_id );
	assert.match( before.attached, /^[0-9a-f]{32}-scaled\.jpg$/ );
	assert.ok( before.names.includes( `${ before.stem }.jpg` ), before.names.join() );
	assert.ok( before.names.includes( `${ before.stem }-1024x768.jpg` ), before.names.join() );
	assert.ok( before.names.length >= 4, before.names.join() );
	backdate( photo.body.attachment_id );

	runCleanup();

	assert.equal( await mediaStatus( photo.body.attachment_id ), 404 );
	const output = wp( 'eval', `$u = wp_get_upload_dir(); echo 'RESULT:' . wp_json_encode( array_map( 'basename', glob( $u['basedir'] . '/journal/*/*/${ before.stem }*' ) ) );` );
	assert.deepEqual( JSON.parse( output.match( /RESULT:(\[.*\])/ )[ 1 ] ), [] );
} );

test( 'a large photo that a post shows is kept when it is attached to nothing', async () => {
	const photo = await uploadPhoto( { file: 'photo-large.jpg' } );
	await sendEntry( { id: uuid(), written_at: at( day(), '10:00:00' ), photo_ids: [ photo.id ] } );
	// The post shows the -1024x768 file while the attachment's own file is the -scaled copy: the stem still matches.
	assert.match( filesOfPhoto( photo.body.attachment_id ).attached, /-scaled\.jpg$/ );
	backdate( photo.body.attachment_id );
	wp( 'post', 'update', String( photo.body.attachment_id ), '--post_parent=0' );

	runCleanup();

	assert.equal( await mediaStatus( photo.body.attachment_id ), 200 );
} );

test( 'a photo shown only through a classic gallery shortcode is kept', async () => {
	const forms = [
		( id ) => `[gallery ids="7,${ id },9"]`,
		( id ) => `[gallery columns="2" ids='${ id }']`,
		( id ) => `[gallery ids=${ id } link="none"]`,
		( id ) => `Before [gallery include="${ id }, 9"] after`,
	];
	const photos = [];
	for ( const form of forms ) {
		const photo = await uploadPhoto();
		backdate( photo.body.attachment_id );
		wp( 'post', 'create', '--post_title=Classic', '--post_status=private', `--post_content=${ form( photo.body.attachment_id ) }` );
		photos.push( photo );
	}

	runCleanup();

	for ( const [ index, photo ] of photos.entries() ) {
		assert.equal( await mediaStatus( photo.body.attachment_id ), 200, forms[ index ]( 'N' ) );
	}
} );

test( 'a gallery shortcode that names other photos does not keep this one', async () => {
	const photo = await uploadPhoto();
	const id = photo.body.attachment_id;
	backdate( id );
	wp( 'post', 'create', '--post_title=Classic', '--post_status=private', `--post_content=[gallery ids="${ id }0,9${ id }"] [gallery exclude="${ id }"] [audio ids="${ id }"]` );

	runCleanup();

	assert.equal( await mediaStatus( id ), 404 );
} );
