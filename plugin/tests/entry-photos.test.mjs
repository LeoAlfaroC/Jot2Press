import assert from 'node:assert/strict';
import { test } from 'node:test';
import { api, at, BASE, dayAllocator, readMedia, readPost, sendEntry, uploadPhoto, uuid } from './helpers.mjs';

const day = dayAllocator( 3 );

// Every entry's paragraph carries the entry's id as a class, so a retry can recognise an entry already written.
const paragraph = ( id, time, text = '', where = '' ) =>
	`<!-- wp:paragraph {"className":"journal-entry-${ id }"} -->\n<p class="journal-entry-${ id }"><strong>${ time }</strong>${ where }${ text === '' ? '' : ` — ${ text }` }</p>\n<!-- /wp:paragraph -->`;

const place = ( lat, lng ) =>
	` · ${ lat }, ${ lng } (<a href="https://www.google.com/maps/search/?api=1&amp;query=${ lat }%2C${ lng }">Google Maps</a>)`;

const imageBlock = ( media ) =>
	`<!-- wp:image {"id":${ media.id },"sizeSlug":"large","linkDestination":"none"} -->\n` +
	`<figure class="wp-block-image size-large"><img src="${ media.source_url }" alt="" class="wp-image-${ media.id }"/></figure>\n` +
	`<!-- /wp:image -->`;

test( 'an entry with three photos attaches them and shows them in the given order', async () => {
	const d = day();
	const uploads = [
		await uploadPhoto(),
		await uploadPhoto( { file: 'photo.png' } ),
		await uploadPhoto( { file: 'photo.webp' } ),
	];
	const id = uuid();
	const { status, body } = await sendEntry( {
		id,
		written_at: at( d, '18:05:00' ),
		text: 'Walk on the pier.',
		photo_ids: uploads.map( ( upload ) => upload.id ),
	} );
	assert.equal( status, 201 );

	const media = [];
	for ( const upload of uploads ) {
		media.push( await readMedia( upload.body.attachment_id ) );
	}
	for ( const item of media ) {
		assert.equal( item.post, body.post_id );
		assert.equal( item.status, 'private' );
	}

	const post = await readPost( body.post_id );
	assert.equal(
		post.content.raw,
		[ paragraph( id, '18:05', 'Walk on the pier.' ), ...media.map( imageBlock ) ].join( '\n\n' )
	);
} );

test( 'an entry with a photo and no text shows only the time above the image', async () => {
	const d = day();
	const upload = await uploadPhoto();
	const id = uuid();
	const { status, body } = await sendEntry( { id, written_at: at( d, '07:30:00' ), photo_ids: [ upload.id ] } );
	assert.equal( status, 201 );

	const media = await readMedia( upload.body.attachment_id );
	const post = await readPost( body.post_id );
	assert.equal( post.content.raw, paragraph( id, '07:30' ) + '\n\n' + imageBlock( media ) );
} );

test( 'a located photo with no text shows the time and the place above the image', async () => {
	const d = day();
	const upload = await uploadPhoto();
	const id = uuid();
	const { status, body } = await sendEntry( {
		id,
		written_at: at( d, '07:45:00' ),
		photo_ids: [ upload.id ],
		location: { lat: 48.85837, lng: 2.294481 },
	} );
	assert.equal( status, 201 );

	const media = await readMedia( upload.body.attachment_id );
	const post = await readPost( body.post_id );
	assert.equal(
		post.content.raw,
		paragraph( id, '07:45', '', place( '48.85837', '2.29448' ) ) + '\n\n' + imageBlock( media )
	);
} );

test( 'a photo entry is appended below earlier entries of the day', async () => {
	const d = day();
	const [ id1, id2 ] = [ uuid(), uuid() ];
	const first = await sendEntry( { id: id1, written_at: at( d, '09:00:00' ), text: 'Morning.' } );
	const upload = await uploadPhoto();
	const second = await sendEntry( { id: id2, written_at: at( d, '10:00:00' ), text: 'Look.', photo_ids: [ upload.id ] } );
	assert.equal( second.body.post_id, first.body.post_id );

	const media = await readMedia( upload.body.attachment_id );
	const post = await readPost( first.body.post_id );
	assert.equal(
		post.content.raw,
		[ paragraph( id1, '09:00', 'Morning.' ), paragraph( id2, '10:00', 'Look.' ), imageBlock( media ) ].join( '\n\n' )
	);
} );

test( 'an entry naming a photo that was never uploaded is rejected and stores nothing', async () => {
	const d = day();
	const first = await sendEntry( { id: uuid(), written_at: at( d, '09:00:00' ), text: 'Morning.' } );
	const before = ( await readPost( first.body.post_id ) ).content.raw;
	const upload = await uploadPhoto();

	const { status, body } = await sendEntry( {
		id: uuid(),
		written_at: at( d, '10:00:00' ),
		text: 'Broken.',
		photo_ids: [ upload.id, uuid() ],
	} );
	assert.equal( status, 400 );
	assert.equal( body.code, 'unknown_photo' );
	assert.equal( ( await readPost( first.body.post_id ) ).content.raw, before );
	assert.equal( ( await readMedia( upload.body.attachment_id ) ).post, null );
} );

test( 'photo_ids must be a list of at most 10 ids', async () => {
	const base = { id: uuid(), written_at: at( day(), '10:00:00' ), text: 'ok' };
	const cases = [
		[ 'invalid_photo_ids', 'not-a-list' ],
		[ 'invalid_photo_ids', [ 42 ] ],
		[ 'invalid_photo_ids', [ 'not-a-uuid' ] ],
		[ 'too_many_photos', Array.from( { length: 11 }, () => uuid() ) ],
	];
	for ( const [ code, photo_ids ] of cases ) {
		const { status, body } = await sendEntry( { ...base, photo_ids } );
		assert.equal( status, 400, code );
		assert.equal( body.code, code );
	}
} );

test( 'an entry with neither text nor photos is rejected', async () => {
	const { status, body } = await sendEntry( { id: uuid(), written_at: at( day(), '10:00:00' ), photo_ids: [] } );
	assert.equal( status, 400 );
	assert.equal( body.code, 'empty_entry' );
} );

test( 'a repeated photo entry answers 200 and adds no second image', async () => {
	const d = day();
	const upload = await uploadPhoto();
	const fields = { id: uuid(), written_at: at( d, '10:00:00' ), text: 'Once.', photo_ids: [ upload.id ] };
	const first = await sendEntry( fields );
	const before = ( await readPost( first.body.post_id ) ).content.raw;
	const second = await sendEntry( fields );
	assert.equal( second.status, 200 );
	assert.equal( ( await readPost( first.body.post_id ) ).content.raw, before );
} );

test( 'a visitor cannot find a photo after it is attached to the day post', async () => {
	const upload = await uploadPhoto();
	await sendEntry( { id: uuid(), written_at: at( day(), '10:00:00' ), photo_ids: [ upload.id ] } );
	const id = upload.body.attachment_id;

	assert.equal( ( await api( 'GET', `/wp/v2/media/${ id }`, { who: 'anonymous' } ) ).status, 401 );
	const list = await api( 'GET', '/wp/v2/media', { who: 'anonymous', query: '&per_page=100' } );
	assert.ok( list.body.every( ( media ) => media.id !== id ) );
} );

test( 'a public day post shows its photos to a visitor while the photos stay unlisted', async () => {
	const upload = await uploadPhoto();
	const { status, body } = await sendEntry( {
		id: uuid(),
		written_at: at( day(), '16:00:00' ),
		text: 'For everyone.',
		photo_ids: [ upload.id ],
		post_status: 'publish',
	} );
	assert.equal( status, 201 );
	const id = upload.body.attachment_id;
	const media = await readMedia( id );
	assert.equal( media.status, 'private' );

	// A visitor reads the post, finds the image in it and can load the file.
	const post = await api( 'GET', `/wp/v2/posts/${ body.post_id }`, { who: 'anonymous' } );
	assert.equal( post.status, 200 );
	assert.ok( post.body.content.rendered.includes( media.source_url ) );
	assert.equal( ( await fetch( media.source_url ) ).status, 200 );

	// The attachment itself stays out of the public API and has no page of its own.
	assert.equal( ( await api( 'GET', `/wp/v2/media/${ id }`, { who: 'anonymous' } ) ).status, 401 );
	const list = await api( 'GET', '/wp/v2/media', { who: 'anonymous', query: '&per_page=100' } );
	assert.ok( list.body.every( ( item ) => item.id !== id ) );
	const page = await fetch( `${ BASE }/?attachment_id=${ id }`, { redirect: 'manual' } );
	assert.equal( page.status, 404 );
} );
