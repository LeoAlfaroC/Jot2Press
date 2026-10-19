import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { test } from 'node:test';
import {
	api,
	authHeader,
	BASE,
	fixture,
	lockName,
	pluginDir,
	readMedia,
	uploadPhoto,
	uuid,
	withUploadLimits,
	wp,
} from './helpers.mjs';

const storedPath = ( extension ) =>
	new RegExp( `^/wp-content/uploads/journal/\\d{4}/\\d{2}/[0-9a-f]{32}\\.${ extension }$` );

test( 'a photo is stored as a private, unattached attachment under a random name', async () => {
	const { status, body } = await uploadPhoto();
	assert.equal( status, 201 );
	assert.equal( body.status, 'created' );

	const media = await readMedia( body.attachment_id );
	assert.equal( media.status, 'private' );
	assert.equal( media.post, null );
	assert.equal( media.mime_type, 'image/jpeg' );
	assert.match( new URL( media.source_url ).pathname, storedPath( 'jpg' ) );
} );

test( 'the stored file is the uploaded file, byte for byte', async () => {
	const { body } = await uploadPhoto();
	const media = await readMedia( body.attachment_id );
	const stored = Buffer.from( await ( await fetch( media.source_url ) ).arrayBuffer() );
	assert.ok( stored.equals( fixture( 'photo.jpg' ) ) );
} );

test( 'PNG and WebP photos are accepted', async () => {
	for ( const [ file, mime, extension ] of [
		[ 'photo.png', 'image/png', 'png' ],
		[ 'photo.webp', 'image/webp', 'webp' ],
	] ) {
		const { status, body } = await uploadPhoto( { file } );
		assert.equal( status, 201, file );
		const media = await readMedia( body.attachment_id );
		assert.equal( media.mime_type, mime );
		assert.match( new URL( media.source_url ).pathname, storedPath( extension ) );
	}
} );

test( 'the original filename never reaches the stored name', async () => {
	const { status, body } = await uploadPhoto( { name: 'My Vacation PHOTO.JPG' } );
	assert.equal( status, 201 );
	const media = await readMedia( body.attachment_id );
	assert.match( new URL( media.source_url ).pathname, storedPath( 'jpg' ) );
	assert.equal( media.title.raw, 'Journal photo' );
} );

test( 'a repeated photo id answers 200 with the same attachment', async () => {
	const first = await uploadPhoto();
	const second = await uploadPhoto( { id: first.id } );
	assert.equal( second.status, 200 );
	assert.deepEqual( second.body, { status: 'duplicate', attachment_id: first.body.attachment_id } );
} );

test( 'files that are not JPEG, PNG or WebP are rejected', async () => {
	const cases = [
		{ file: 'photo.gif' },
		{ file: 'photo.gif', name: 'photo.jpg' },
		{ file: 'not-a-photo.jpg' },
	];
	for ( const options of cases ) {
		const { status, body } = await uploadPhoto( options );
		assert.equal( status, 400, JSON.stringify( options ) );
		assert.equal( body.code, 'invalid_photo' );
		assert.equal( body.message, 'The file is not a JPEG, PNG or WebP photo.' );
	}
} );

test( 'a PNG sent under a .jpg name is stored as a PNG', async () => {
	const { status, body } = await uploadPhoto( { file: 'photo.png', name: 'photo.jpg' } );
	assert.equal( status, 201 );
	const media = await readMedia( body.attachment_id );
	assert.equal( media.mime_type, 'image/png' );
	assert.match( new URL( media.source_url ).pathname, storedPath( 'png' ) );
} );

test( 'an upload waits its turn while the same photo id is being stored', async () => {
	const id = uuid();
	// Hold the photo id's lock from a separate database connection for 9 seconds.
	const php = `global $wpdb; $wpdb->get_var( "SELECT GET_LOCK('${ lockName( id ) }', 0)" ); echo "LOCKED\\n"; flush(); sleep( 9 );`;
	const holder = spawn( 'npx', [ 'wp-env', 'run', 'cli', 'wp', 'eval', php ], { cwd: pluginDir } );
	const released = new Promise( ( resolve ) => holder.on( 'close', resolve ) );
	try {
		await new Promise( ( resolve, reject ) => {
			const timer = setTimeout( () => reject( new Error( 'The lock holder never reported LOCKED.' ) ), 30000 );
			holder.stdout.on( 'data', ( chunk ) => {
				if ( chunk.toString().includes( 'LOCKED' ) ) {
					clearTimeout( timer );
					resolve();
				}
			} );
		} );

		const blocked = await uploadPhoto( { id } );
		assert.equal( blocked.status, 503 );
		assert.equal( blocked.body.code, 'jot2press_busy' );

		await released;
		const retried = await uploadPhoto( { id } );
		assert.equal( retried.status, 201 );
	} finally {
		holder.kill();
	}
} );

test( 'the same photo sent twice at once is stored once', async () => {
	const id = uuid();
	const responses = await Promise.all( [ uploadPhoto( { id } ), uploadPhoto( { id } ) ] );
	assert.deepEqual( responses.map( ( response ) => response.status ).sort(), [ 200, 201 ] );
	assert.equal( responses[ 0 ].body.attachment_id, responses[ 1 ].body.attachment_id );

	const count = wp( 'post', 'list', '--post_type=attachment', '--post_status=any', '--meta_key=_journal_photo_id', `--meta_value=${ id }`, '--format=count' );
	assert.equal( count.trim(), '1' );
} );

test( 'a request without a file or with a bad id is rejected', async () => {
	const noFile = new FormData();
	noFile.set( 'id', uuid() );
	const missing = await api( 'POST', '/jot2press/v1/photos', { form: noFile } );
	assert.equal( missing.status, 400 );
	assert.equal( missing.body.code, 'missing_photo' );

	const badId = await uploadPhoto( { id: 'not-a-uuid' } );
	assert.equal( badId.status, 400 );
	assert.equal( badId.body.code, 'invalid_id' );

	// A valid UUID followed by a line break is not an id. FormData would turn the line break into CRLF, so the
	// multipart body is written by hand.
	const boundary = 'journal-test-boundary';
	const body = Buffer.concat( [
		Buffer.from( `--${ boundary }\r\nContent-Disposition: form-data; name="id"\r\n\r\n${ uuid() }\n\r\n` ),
		Buffer.from( `--${ boundary }\r\nContent-Disposition: form-data; name="photo"; filename="photo.jpg"\r\nContent-Type: image/jpeg\r\n\r\n` ),
		fixture( 'photo.jpg' ),
		Buffer.from( `\r\n--${ boundary }--\r\n` ),
	] );
	const response = await fetch( `${ BASE }/?rest_route=/jot2press/v1/photos`, {
		method: 'POST',
		headers: { ...authHeader(), 'Content-Type': `multipart/form-data; boundary=${ boundary }` },
		body,
	} );
	assert.equal( response.status, 400 );
	assert.equal( ( await response.json() ).code, 'invalid_id' );
} );

test( 'a photo over the server\'s upload limit is answered 413 and can be sent again once it fits', async () => {
	// photo-large.jpg is about 80 KB.
	const id = uuid();
	const cases = [
		[ 'the file is over upload_max_filesize', { file: '16K', body: '8M' } ],
		[ 'the whole request is over post_max_size', { file: '8M', body: '16K' } ],
	];
	for ( const [ label, limits ] of cases ) {
		const { limit, answer } = await withUploadLimits( limits, async () => ( {
			limit: ( await api( 'GET', '/jot2press/v1/status' ) ).body.max_upload_bytes,
			answer: await uploadPhoto( { id, file: 'photo-large.jpg' } ),
		} ) );
		assert.equal( limit, 16 * 1024, label );
		assert.equal( answer.status, 413, label );
		assert.equal( answer.body.code, 'photo_too_large', label );
	}

	const retry = await uploadPhoto( { id, file: 'photo-large.jpg' } );
	assert.equal( retry.status, 201 );
} );

test( 'photo uploads need credentials and the right to publish', async () => {
	assert.equal( ( await uploadPhoto( { who: 'anonymous' } ) ).status, 401 );
	assert.equal( ( await uploadPhoto( { who: 'reader' } ) ).status, 403 );
} );

test( 'photo uploads need the right to upload files, refused with a code of their own', async () => {
	const { status, body } = await uploadPhoto( { who: 'nouploads' } );
	assert.equal( status, 403 );
	assert.equal( body.code, 'cannot_upload_photos' );
} );

test( 'a visitor cannot find an unattached photo through WordPress', async () => {
	const { body } = await uploadPhoto();
	const id = body.attachment_id;

	const single = await api( 'GET', `/wp/v2/media/${ id }`, { who: 'anonymous' } );
	assert.equal( single.status, 401 );

	const list = await api( 'GET', '/wp/v2/media', { who: 'anonymous', query: '&per_page=100' } );
	assert.ok( list.body.every( ( media ) => media.id !== id ) );

	const page = await fetch( `${ BASE }/?attachment_id=${ id }`, { redirect: 'manual' } );
	assert.equal( page.status, 404 );
} );

test( 'the journal upload folders cannot be listed', async () => {
	const { body } = await uploadPhoto();
	const media = await readMedia( body.attachment_id );
	const monthFolder = media.source_url.slice( 0, media.source_url.lastIndexOf( '/' ) + 1 );
	const yearFolder = monthFolder.replace( /\d{2}\/$/, '' );
	const journalFolder = yearFolder.replace( /\d{4}\/$/, '' );
	assert.ok( journalFolder.endsWith( '/uploads/journal/' ) );

	for ( const folder of [ journalFolder, yearFolder, monthFolder ] ) {
		const response = await fetch( folder );
		assert.equal( response.status, 200, folder );
		assert.equal( await response.text(), '', folder );
	}
} );

test( 'a photo the server cannot store is answered as a server failure', async () => {
	// Inside the test site: hand the handler a real JPEG that PHP did not receive as an upload, which WordPress refuses to move.
	const bytes = fixture( 'photo.jpg' ).toString( 'base64' );
	const php = `
		$tmp = wp_tempnam( 'journal-test' );
		file_put_contents( $tmp, base64_decode( '${ bytes }' ) );
		$_FILES['photo'] = array( 'name' => 'photo.jpg', 'type' => 'image/jpeg', 'tmp_name' => $tmp, 'error' => 0, 'size' => filesize( $tmp ) );
		wp_set_current_user( get_user_by( 'login', 'admin' )->ID );
		$request = new WP_REST_Request( 'POST', '/jot2press/v1/photos' );
		$request->set_body_params( array( 'id' => '${ uuid() }' ) );
		$request->set_file_params( $_FILES );
		$response = rest_do_request( $request );
		echo 'RESULT:' . wp_json_encode( array( $response->get_status(), $response->get_data() ) ) . ':END';
		unlink( $tmp );
	`;
	const output = wp( 'eval', php );
	const [ status, body ] = JSON.parse( output.match( /RESULT:(.*):END/s )[ 1 ] );
	assert.equal( status, 500 );
	assert.equal( body.code, 'jot2press_failure' );
	assert.equal( body.message, 'The photo could not be stored.' );
} );

test( 'an upload the server could not receive is answered as a server failure, so the app tries it again', () => {
	// PHP's own codes: 3 partial, 6 no temporary folder, 7 cannot write to disk, 8 stopped by an extension; 4 no file.
	const php = `
		wp_set_current_user( get_user_by( 'login', 'admin' )->ID );
		$answers = array();
		foreach ( array( 3, 4, 6, 7, 8 ) as $error ) {
			$files   = array( 'photo' => array( 'name' => 'photo.jpg', 'type' => 'image/jpeg', 'tmp_name' => '', 'error' => $error, 'size' => 0 ) );
			$request = new WP_REST_Request( 'POST', '/jot2press/v1/photos' );
			$request->set_body_params( array( 'id' => wp_generate_uuid4() ) );
			$request->set_file_params( $files );
			$response          = rest_do_request( $request );
			$answers[ $error ] = array( $response->get_status(), $response->get_data()['code'] );
		}
		echo 'RESULT:' . wp_json_encode( $answers ) . ':END';
	`;
	const answers = JSON.parse( wp( 'eval', php ).match( /RESULT:(.*):END/s )[ 1 ] );
	for ( const error of [ '3', '6', '7', '8' ] ) {
		assert.deepEqual( answers[ error ], [ 500, 'jot2press_failure' ], `error ${ error }` );
	}
	assert.deepEqual( answers[ '4' ], [ 400, 'missing_photo' ] );
} );

test( 'each photo gets its own random slug, not one derived from a title', async () => {
	const first = await uploadPhoto();
	const second = await uploadPhoto();
	const slugs = [];
	for ( const upload of [ first, second ] ) {
		slugs.push( ( await readMedia( upload.body.attachment_id ) ).slug );
	}
	for ( const slug of slugs ) {
		assert.match( slug, /^journal-photo-[0-9a-f]{16}$/ );
	}
	assert.notEqual( slugs[ 0 ], slugs[ 1 ] );
} );

test( 'lock names are 42 characters whatever the table prefix, and differ between installs', () => {
	const output = wp(
		'eval',
		`global $wpdb; $a = jot2press_lock_name( "2026-01-01" ); $b = jot2press_lock_name( "2026-01-02" ); $old = $wpdb->prefix; $wpdb->prefix = str_repeat( "x", 60 ) . "_"; $long = jot2press_lock_name( "2026-01-01" ); $wpdb->prefix = $old; echo "RESULT:" . wp_json_encode( array( strlen( $a ), strlen( $long ), $a === $long, $a === $b ) );`
	);
	assert.deepEqual( JSON.parse( output.match( /RESULT:(\[.*\])/ )[ 1 ] ), [ 42, 42, false, false ] );
} );
