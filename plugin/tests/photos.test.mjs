import assert from 'node:assert/strict';
import { test } from 'node:test';
import { api, BASE, fixture, readMedia, uploadPhoto, uuid } from './helpers.mjs';

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
	}
} );

test( 'a request without a file or with a bad id is rejected', async () => {
	const noFile = new FormData();
	noFile.set( 'id', uuid() );
	const missing = await api( 'POST', '/journal/v1/photos', { form: noFile } );
	assert.equal( missing.status, 400 );
	assert.equal( missing.body.code, 'missing_photo' );

	const badId = await uploadPhoto( { id: 'not-a-uuid' } );
	assert.equal( badId.status, 400 );
	assert.equal( badId.body.code, 'invalid_id' );
} );

test( 'photo uploads need credentials and the right to publish', async () => {
	assert.equal( ( await uploadPhoto( { who: 'anonymous' } ) ).status, 401 );
	assert.equal( ( await uploadPhoto( { who: 'reader' } ) ).status, 403 );
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
