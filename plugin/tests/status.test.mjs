import assert from 'node:assert/strict';
import { test } from 'node:test';
import { api } from './helpers.mjs';

test( 'status reports the user, the default category, the upload limit and the version', async () => {
	const { status, body } = await api( 'GET', '/journal/v1/status' );
	assert.equal( status, 200 );
	assert.equal( body.ok, true );
	assert.equal( body.user, 'admin' );
	assert.deepEqual( body.category, { slug: 'journal', name: 'Journal' } );
	assert.ok( Number.isInteger( body.max_upload_bytes ) && body.max_upload_bytes > 0 );
	assert.equal( body.version, '1.0.0' );
} );

test( 'status lists every category of the site by name, used or not', async () => {
	const { body } = await api( 'GET', '/journal/v1/status' );
	// 'Tea & Cake' has no posts, and WordPress stores its name as 'Tea &amp; Cake'.
	assert.deepEqual( body.categories, [
		{ slug: 'journal', name: 'Journal' },
		{ slug: 'tea-cake', name: 'Tea & Cake' },
		{ slug: 'travel', name: 'Travel' },
		{ slug: 'uncategorized', name: 'Uncategorized' },
	] );
} );

test( 'status rejects a request without credentials', async () => {
	const { status } = await api( 'GET', '/journal/v1/status', { who: 'anonymous' } );
	assert.equal( status, 401 );
} );

test( 'status rejects a wrong password', async () => {
	const { status } = await api( 'GET', '/journal/v1/status', { who: 'wrong' } );
	assert.equal( status, 401 );
} );

test( 'status rejects a user who cannot publish posts', async () => {
	const { status } = await api( 'GET', '/journal/v1/status', { who: 'reader' } );
	assert.equal( status, 403 );
} );
