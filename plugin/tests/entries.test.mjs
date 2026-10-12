import assert from 'node:assert/strict';
import { test } from 'node:test';
import { api, at, dayAllocator, readPost, sendEntry, uuid, wp } from './helpers.mjs';

const day = dayAllocator( 1 );

const paragraph = ( id, time, text, where = '' ) =>
	`<!-- wp:paragraph {"className":"journal-entry-${ id }"} -->\n<p class="journal-entry-${ id }"><strong>${ time }</strong>${ where } — ${ text }</p>\n<!-- /wp:paragraph -->`;

const place = ( lat, lng ) =>
	` · ${ lat }, ${ lng } (<a href="https://www.google.com/maps/search/?api=1&amp;query=${ lat }%2C${ lng }">Google Maps</a>)`;

function englishDate( d ) {
	return new Intl.DateTimeFormat( 'en-US', {
		weekday: 'long',
		month: 'long',
		day: 'numeric',
		timeZone: 'UTC',
	} ).format( new Date( `${ d }T00:00:00Z` ) );
}

test( 'the first entry of a day creates a private post in the journal category', async () => {
	const d = day();
	const id = uuid();
	const { status, body } = await sendEntry( {
		id,
		written_at: at( d, '09:15:00' ),
		text: 'Breakfast with Ana.',
	} );
	assert.equal( status, 201 );
	assert.equal( body.status, 'created' );

	const post = await readPost( body.post_id );
	const { body: categories } = await api( 'GET', '/wp/v2/categories', { query: '&slug=journal' } );
	assert.equal( post.status, 'private' );
	assert.equal( post.slug, d );
	assert.equal( post.date, `${ d }T09:15:00` );
	assert.deepEqual( post.categories, [ categories[ 0 ].id ] );
	assert.equal( post.content.raw, paragraph( id, '09:15', 'Breakfast with Ana.' ) );
} );

test( 'without a title field the post is titled with the ISO date and the English date', async () => {
	const d = day();
	const { body } = await sendEntry( { id: uuid(), written_at: at( d, '08:00:00' ), text: 'Hello.' } );
	const post = await readPost( body.post_id );
	assert.equal( post.title.raw, `${ d } · ${ englishDate( d ) }` );
} );

test( 'the fallback title stays in English when the site language is Spanish', async ( t ) => {
	wp( 'language', 'core', 'install', 'es_ES' );
	wp( 'site', 'switch-language', 'es_ES' );
	t.after( () => wp( 'site', 'switch-language', 'en_US' ) );

	const d = day();
	const { body } = await sendEntry( { id: uuid(), written_at: at( d, '08:00:00' ), text: 'Hola.' } );
	const post = await readPost( body.post_id );
	assert.equal( post.title.raw, `${ d } · ${ englishDate( d ) }` );
} );

test( 'a title field sets the title of a newly created post', async () => {
	const d = day();
	const { body } = await sendEntry( {
		id: uuid(),
		written_at: at( d, '08:00:00' ),
		text: 'Hola.',
		title: `${ d } · domingo 11 de octubre`,
	} );
	const post = await readPost( body.post_id );
	assert.equal( post.title.raw, `${ d } · domingo 11 de octubre` );
} );

test( 'a second entry is appended below the first and its title field is ignored', async () => {
	const d = day();
	const [ id1, id2 ] = [ uuid(), uuid() ];
	const first = await sendEntry( { id: id1, written_at: at( d, '09:15:00' ), text: 'First.', title: 'Original title' } );
	const second = await sendEntry( { id: id2, written_at: at( d, '14:32:00' ), text: 'Second.', title: 'Another title' } );
	assert.equal( second.status, 201 );
	assert.equal( second.body.post_id, first.body.post_id );

	const post = await readPost( first.body.post_id );
	assert.equal( post.title.raw, 'Original title' );
	assert.equal( post.content.raw, paragraph( id1, '09:15', 'First.' ) + '\n\n' + paragraph( id2, '14:32', 'Second.' ) );
} );

test( 'the day and the time come from written_at as written, not from UTC', async () => {
	const d = day();
	// 23:58 at UTC-5 is already the next day in UTC.
	const id = uuid();
	const { body } = await sendEntry( { id, written_at: at( d, '23:58:00', '-05:00' ), text: 'Late.' } );
	const post = await readPost( body.post_id );
	assert.equal( post.slug, d );
	assert.equal( post.content.raw, paragraph( id, '23:58', 'Late.' ) );
} );

test( 'text is stored exactly as typed', async () => {
	const d = day();
	const text = 'Path C:\\notes\\today & "quoted" <b>not bold</b> it\'s 🎉\nsecond line';
	const id = uuid();
	const { body } = await sendEntry( { id, written_at: at( d, '10:00:00' ), text } );
	const post = await readPost( body.post_id );
	assert.equal(
		post.content.raw,
		paragraph(
			id,
			'10:00',
			'Path C:\\notes\\today &amp; &quot;quoted&quot; &lt;b&gt;not bold&lt;/b&gt; it&#039;s 🎉<br>second line'
		)
	);
} );

test( 'surrounding whitespace is trimmed from the text', async () => {
	const d = day();
	const id = uuid();
	const { body } = await sendEntry( { id, written_at: at( d, '10:00:00' ), text: '  padded \n' } );
	const post = await readPost( body.post_id );
	assert.equal( post.content.raw, paragraph( id, '10:00', 'padded' ) );
} );

test( 'a location is shown after the time as coordinates with a Google Maps link', async () => {
	const d = day();
	const id = uuid();
	const { status, body } = await sendEntry( {
		id,
		written_at: at( d, '14:32:00' ),
		text: 'At the pier.',
		location: { lat: -12.0464, lng: -77.042793 },
	} );
	assert.equal( status, 201 );
	const post = await readPost( body.post_id );
	// Five decimals: -12.0464 is padded, -77.042793 is rounded.
	assert.equal( post.content.raw, paragraph( id, '14:32', 'At the pier.', place( '-12.04640', '-77.04279' ) ) );
} );

test( 'whole-number coordinates and a null location are accepted', async () => {
	const d = day();
	const [ id1, id2 ] = [ uuid(), uuid() ];
	const first = await sendEntry( { id: id1, written_at: at( d, '08:00:00' ), text: 'North.', location: { lat: 90, lng: 180 } } );
	const second = await sendEntry( { id: id2, written_at: at( d, '09:00:00' ), text: 'Nowhere.', location: null } );
	assert.equal( first.status, 201 );
	assert.equal( second.status, 201 );
	const post = await readPost( first.body.post_id );
	assert.equal(
		post.content.raw,
		paragraph( id1, '08:00', 'North.', place( '90.00000', '180.00000' ) ) + '\n\n' + paragraph( id2, '09:00', 'Nowhere.' )
	);
} );

test( 'category and post_status fields set the category and status of a newly created post', async () => {
	const d = day();
	const { status, body } = await sendEntry( {
		id: uuid(),
		written_at: at( d, '09:15:00' ),
		text: 'On the road.',
		category: 'travel',
		post_status: 'draft',
	} );
	assert.equal( status, 201 );
	assert.deepEqual( body, { status: 'created', post_id: body.post_id } );

	const post = await readPost( body.post_id );
	const { body: categories } = await api( 'GET', '/wp/v2/categories', { query: '&slug=travel' } );
	assert.equal( post.status, 'draft' );
	assert.deepEqual( post.categories, [ categories[ 0 ].id ] );
} );

test( 'category and post_status fields are ignored when the day post already exists', async () => {
	const d = day();
	const first = await sendEntry( { id: uuid(), written_at: at( d, '09:00:00' ), text: 'First.' } );
	const second = await sendEntry( {
		id: uuid(),
		written_at: at( d, '10:00:00' ),
		text: 'Second.',
		category: 'travel',
		post_status: 'publish',
	} );
	assert.deepEqual( second.body, { status: 'created', post_id: first.body.post_id } );

	const post = await readPost( first.body.post_id );
	const { body: categories } = await api( 'GET', '/wp/v2/categories', { query: '&slug=journal' } );
	assert.equal( post.status, 'private' );
	assert.deepEqual( post.categories, [ categories[ 0 ].id ] );
} );

test( 'a category that does not exist falls back to the default one, and the response says so once', async () => {
	const d = day();
	const first = await sendEntry( {
		id: uuid(),
		written_at: at( d, '09:00:00' ),
		text: 'First.',
		category: 'no-such-category',
	} );
	assert.equal( first.status, 201 );
	assert.deepEqual( first.body, {
		status: 'created',
		post_id: first.body.post_id,
		category_fallback: { slug: 'journal', name: 'Journal' },
	} );

	const post = await readPost( first.body.post_id );
	const { body: categories } = await api( 'GET', '/wp/v2/categories', { query: '&slug=journal' } );
	assert.deepEqual( post.categories, [ categories[ 0 ].id ] );

	// The missing category is not created.
	const { body: missing } = await api( 'GET', '/wp/v2/categories', { query: '&slug=no-such-category' } );
	assert.deepEqual( missing, [] );

	// A later entry of the same day creates no post, so it reports no fallback.
	const second = await sendEntry( {
		id: uuid(),
		written_at: at( d, '10:00:00' ),
		text: 'Second.',
		category: 'no-such-category',
	} );
	assert.deepEqual( second.body, { status: 'created', post_id: first.body.post_id } );
} );

test( 'a draft day post receives later entries and keeps its day when it is published', async () => {
	const d = day();
	const [ id1, id2 ] = [ uuid(), uuid() ];
	const first = await sendEntry( { id: id1, written_at: at( d, '09:15:00' ), text: 'Draft one.', post_status: 'draft' } );
	const second = await sendEntry( { id: id2, written_at: at( d, '11:00:00' ), text: 'Draft two.' } );
	assert.equal( second.body.post_id, first.body.post_id );

	let post = await readPost( first.body.post_id );
	assert.equal( post.status, 'draft' );
	assert.equal( post.date, `${ d }T09:15:00` );
	assert.equal( post.content.raw, paragraph( id1, '09:15', 'Draft one.' ) + '\n\n' + paragraph( id2, '11:00', 'Draft two.' ) );

	// Published by hand later, as from Jetpack: WordPress would give a dateless draft today's date.
	await api( 'POST', `/wp/v2/posts/${ first.body.post_id }`, { json: { status: 'publish' } } );
	post = await readPost( first.body.post_id );
	assert.equal( post.status, 'publish' );
	assert.equal( post.date, `${ d }T09:15:00` );
} );

test( 'a public day post can be read by a visitor', async () => {
	const d = day();
	const { status, body } = await sendEntry( {
		id: uuid(),
		written_at: at( d, '10:00:00' ),
		text: 'Out in the open.',
		post_status: 'publish',
	} );
	assert.equal( status, 201 );
	const single = await api( 'GET', `/wp/v2/posts/${ body.post_id }`, { who: 'anonymous' } );
	assert.equal( single.status, 200 );
	assert.equal( single.body.status, 'publish' );
} );

test( 'a public post written from a phone ahead of the site clock is published at once, not scheduled', async () => {
	// The site runs on UTC. A phone at UTC+14 shows a wall-clock time 14 hours ahead of it,
	// and WordPress schedules a post whose date it believes to be in the future.
	const written_at = new Date( Date.now() + 14 * 3600000 ).toISOString().slice( 0, 19 ) + '+14:00';
	const { status, body } = await sendEntry( {
		id: uuid(),
		written_at,
		text: 'Later on the wall clock, now in fact.',
		post_status: 'publish',
	} );
	assert.equal( status, 201 );
	const post = await readPost( body.post_id );
	assert.equal( post.status, 'publish' );
	assert.equal( post.date, written_at.slice( 0, 19 ) );
} );

test( 'invalid requests are rejected with a specific code', async () => {
	const d = day();
	const valid = { id: uuid(), written_at: at( d, '10:00:00' ), text: 'ok' };
	const cases = [
		[ 'invalid_id', { ...valid, id: undefined } ],
		[ 'invalid_id', { ...valid, id: 'not-a-uuid' } ],
		[ 'invalid_written_at', { ...valid, written_at: undefined } ],
		[ 'invalid_written_at', { ...valid, written_at: `${ d }T10:00:00` } ],
		[ 'invalid_written_at', { ...valid, written_at: '2002-02-30T10:00:00-05:00' } ],
		[ 'invalid_written_at', { ...valid, written_at: 'yesterday' } ],
		[ 'invalid_text', { ...valid, text: 42 } ],
		[ 'invalid_title', { ...valid, title: 'x'.repeat( 201 ) } ],
		[ 'invalid_category', { ...valid, category: 42 } ],
		[ 'invalid_category', { ...valid, category: '  ' } ],
		[ 'invalid_post_status', { ...valid, post_status: 'future' } ],
		[ 'invalid_post_status', { ...valid, post_status: 7 } ],
		[ 'invalid_location', { ...valid, location: 'here' } ],
		[ 'invalid_location', { ...valid, location: [ 12.5, 3 ] } ],
		[ 'invalid_location', { ...valid, location: { lat: 12.5 } } ],
		[ 'invalid_location', { ...valid, location: { lat: '12.5', lng: 3 } } ],
		[ 'invalid_location', { ...valid, location: { lat: 90.5, lng: 0 } } ],
		[ 'invalid_location', { ...valid, location: { lat: 0, lng: -180.5 } } ],
		[ 'empty_entry', { ...valid, text: undefined } ],
		[ 'empty_entry', { ...valid, text: '   \n ' } ],
	];
	for ( const [ code, fields ] of cases ) {
		const { status, body } = await sendEntry( fields );
		assert.equal( status, 400, `${ code }: ${ JSON.stringify( fields ) }` );
		assert.equal( body.code, code );
	}
} );

test( 'a title of exactly 200 characters is accepted', async () => {
	const d = day();
	const title = 'x'.repeat( 200 );
	const { status, body } = await sendEntry( { id: uuid(), written_at: at( d, '10:00:00' ), text: 'ok', title } );
	assert.equal( status, 201 );
	assert.equal( ( await readPost( body.post_id ) ).title.raw, title );
} );

test( 'entries need credentials and the right to publish', async () => {
	const fields = { id: uuid(), written_at: at( day(), '10:00:00' ), text: 'ok' };
	assert.equal( ( await sendEntry( fields, { who: 'anonymous' } ) ).status, 401 );
	assert.equal( ( await sendEntry( fields, { who: 'reader' } ) ).status, 403 );
} );

test( 'a visitor cannot read the day post', async () => {
	const d = day();
	const { body } = await sendEntry( { id: uuid(), written_at: at( d, '10:00:00' ), text: 'Secret.' } );
	const single = await api( 'GET', `/wp/v2/posts/${ body.post_id }`, { who: 'anonymous' } );
	assert.equal( single.status, 401 );
	const list = await api( 'GET', '/wp/v2/posts', { who: 'anonymous', query: '&per_page=100' } );
	assert.ok( list.body.every( ( post ) => post.id !== body.post_id ) );
} );

test( 'a post edited by hand keeps its edits and still receives the next entry', async () => {
	const d = day();
	const first = await sendEntry( { id: uuid(), written_at: at( d, '09:00:00' ), text: 'Before.' } );
	await api( 'POST', `/wp/v2/posts/${ first.body.post_id }`, {
		json: { title: 'Renamed by hand', slug: 'renamed-by-hand', content: 'Rewritten by hand.' },
	} );

	const id2 = uuid();
	const second = await sendEntry( { id: id2, written_at: at( d, '11:00:00' ), text: 'After the edit.' } );
	assert.equal( second.body.post_id, first.body.post_id );

	const post = await readPost( first.body.post_id );
	assert.equal( post.title.raw, 'Renamed by hand' );
	assert.equal( post.slug, 'renamed-by-hand' );
	assert.equal( post.status, 'private' );
	assert.equal( post.content.raw, 'Rewritten by hand.\n\n' + paragraph( id2, '11:00', 'After the edit.' ) );
} );

test( 'appending entries does not pile up revisions', async () => {
	const d = day();
	const revisions = async ( id ) => ( await api( 'GET', `/wp/v2/posts/${ id }/revisions` ) ).body.length;
	const first = await sendEntry( { id: uuid(), written_at: at( d, '09:00:00' ), text: 'One.' } );
	await sendEntry( { id: uuid(), written_at: at( d, '09:01:00' ), text: 'Two.' } );
	const before = await revisions( first.body.post_id );
	await sendEntry( { id: uuid(), written_at: at( d, '09:02:00' ), text: 'Three.' } );
	await sendEntry( { id: uuid(), written_at: at( d, '09:03:00' ), text: 'Four.' } );
	assert.equal( await revisions( first.body.post_id ), before );
} );
