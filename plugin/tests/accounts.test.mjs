import assert from 'node:assert/strict';
import { test } from 'node:test';
import { at, dayAllocator, readPost, sendEntry, uuid } from './helpers.mjs';

const day = dayAllocator( 5 );

test( 'an account that can publish keeps its own day post, whoever wrote first', async () => {
	const first = day();
	const second = day();

	// The author writes first on one day, the owner on the other.
	const authorFirst = await sendEntry( { id: uuid(), written_at: at( first, '09:00:00' ), text: 'Author secret one.' }, { who: 'author' } );
	const ownerSame = await sendEntry( { id: uuid(), written_at: at( first, '10:00:00' ), text: 'Owner private one.' } );
	assert.equal( authorFirst.status, 201 );
	assert.equal( ownerSame.status, 201 );
	assert.notEqual( ownerSame.body.post_id, authorFirst.body.post_id );

	const ownerFirst = await sendEntry( { id: uuid(), written_at: at( second, '09:00:00' ), text: 'Owner private two.' } );
	const authorSame = await sendEntry( { id: uuid(), written_at: at( second, '10:00:00' ), text: 'Author secret two.' }, { who: 'author' } );
	assert.equal( authorSame.status, 201 );
	assert.notEqual( authorSame.body.post_id, ownerFirst.body.post_id );

	const contents = {};
	for ( const [ name, response ] of Object.entries( { authorFirst, ownerSame, ownerFirst, authorSame } ) ) {
		contents[ name ] = ( await readPost( response.body.post_id ) ).content.raw;
	}
	assert.ok( contents.authorFirst.includes( 'Author secret one.' ) && ! contents.authorFirst.includes( 'Owner' ) );
	assert.ok( contents.ownerSame.includes( 'Owner private one.' ) && ! contents.ownerSame.includes( 'Author' ) );
	assert.ok( contents.ownerFirst.includes( 'Owner private two.' ) && ! contents.ownerFirst.includes( 'Author' ) );
	assert.ok( contents.authorSame.includes( 'Author secret two.' ) && ! contents.authorSame.includes( 'Owner' ) );
} );

test( 'a second entry from the same account still lands in that account\'s post', async () => {
	const d = day();
	const a = await sendEntry( { id: uuid(), written_at: at( d, '09:00:00' ), text: 'One.' }, { who: 'author' } );
	const b = await sendEntry( { id: uuid(), written_at: at( d, '10:00:00' ), text: 'Two.' }, { who: 'author' } );
	assert.equal( b.body.post_id, a.body.post_id );
} );
