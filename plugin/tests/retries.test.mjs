import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { test } from 'node:test';
import { api, at, dayAllocator, pluginDir, readPost, sendEntry, uuid } from './helpers.mjs';

const day = dayAllocator( 2 );

const occurrences = ( haystack, needle ) => haystack.split( needle ).length - 1;

test( 'a repeated id answers 200 and changes nothing', async () => {
	const d = day();
	const fields = { id: uuid(), written_at: at( d, '09:00:00' ), text: 'Only once.' };
	const first = await sendEntry( fields );
	const before = ( await readPost( first.body.post_id ) ).content.raw;

	const second = await sendEntry( fields );
	assert.equal( second.status, 200 );
	assert.deepEqual( second.body, { status: 'duplicate', post_id: first.body.post_id } );
	assert.equal( ( await readPost( first.body.post_id ) ).content.raw, before );
} );

test( 'an id is the same entry whatever its letter case', async () => {
	const d = day();
	const id = uuid();
	const first = await sendEntry( { id, written_at: at( d, '09:00:00' ), text: 'Case.' } );
	const second = await sendEntry( { id: id.toUpperCase(), written_at: at( d, '09:00:00' ), text: 'Case.' } );
	assert.equal( second.status, 200 );
	assert.equal( second.body.post_id, first.body.post_id );
} );

test( 'a trashed day post is ignored and a new one is created', async () => {
	const d = day();
	const first = await sendEntry( { id: uuid(), written_at: at( d, '09:00:00' ), text: 'Old.' } );
	await api( 'DELETE', `/wp/v2/posts/${ first.body.post_id }` );

	const second = await sendEntry( { id: uuid(), written_at: at( d, '10:00:00' ), text: 'New.' } );
	assert.equal( second.status, 201 );
	assert.notEqual( second.body.post_id, first.body.post_id );
	assert.equal( occurrences( ( await readPost( second.body.post_id ) ).content.raw, 'Old.' ), 0 );
} );

test( 'entries arriving at once for a new day share one post and each appears once', async () => {
	const d = day();
	const texts = Array.from( { length: 8 }, ( _, index ) => `Simultaneous entry number ${ index }.` );
	const responses = await Promise.all(
		texts.map( ( text ) => sendEntry( { id: uuid(), written_at: at( d, '12:00:00' ), text } ) )
	);
	assert.deepEqual( responses.map( ( response ) => response.status ), Array( 8 ).fill( 201 ) );
	assert.equal( new Set( responses.map( ( response ) => response.body.post_id ) ).size, 1 );

	const content = ( await readPost( responses[ 0 ].body.post_id ) ).content.raw;
	for ( const text of texts ) {
		assert.equal( occurrences( content, text ), 1, text );
	}
} );

test( 'the same entry sent twice at once is stored once', async () => {
	const d = day();
	const fields = { id: uuid(), written_at: at( d, '12:00:00' ), text: 'Racing retry.' };
	const responses = await Promise.all( [ sendEntry( fields ), sendEntry( fields ) ] );
	assert.deepEqual( responses.map( ( response ) => response.status ).sort(), [ 200, 201 ] );
	const content = ( await readPost( responses[ 0 ].body.post_id ) ).content.raw;
	assert.equal( occurrences( content, 'Racing retry.' ), 1 );
} );

test( 'an entry answers 503 while the day is locked and succeeds afterwards', async () => {
	const d = day();
	// Hold the day's lock from a separate database connection for 9 seconds.
	const php = `global $wpdb; $wpdb->get_var( "SELECT GET_LOCK('{$wpdb->prefix}journal_${ d }', 0)" ); echo "LOCKED\\n"; flush(); sleep( 9 );`;
	const holder = spawn( 'npx', [ 'wp-env', 'run', 'cli', 'wp', 'eval', php ], { cwd: pluginDir } );
	const released = new Promise( ( resolve ) => holder.on( 'close', resolve ) );
	await new Promise( ( resolve, reject ) => {
		const timer = setTimeout( () => reject( new Error( 'The lock holder never reported LOCKED.' ) ), 30000 );
		holder.stdout.on( 'data', ( chunk ) => {
			if ( chunk.toString().includes( 'LOCKED' ) ) {
				clearTimeout( timer );
				resolve();
			}
		} );
	} );

	const fields = { id: uuid(), written_at: at( d, '12:00:00' ), text: 'Waited.' };
	const blocked = await sendEntry( fields );
	assert.equal( blocked.status, 503 );
	assert.equal( blocked.body.code, 'journal_busy' );

	await released;
	const retried = await sendEntry( fields );
	assert.equal( retried.status, 201 );
} );
