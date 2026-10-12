import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

export const pluginDir = fileURLToPath( new URL( '..', import.meta.url ) );
const credentials = JSON.parse( readFileSync( new URL( './.credentials.json', import.meta.url ), 'utf8' ) );

export const BASE = credentials.base;
export const uuid = randomUUID;

/** Authorization header for 'admin', 'author' (a second account that can publish), 'reader' (a subscriber) or 'wrong'; none for 'anonymous'. */
export function authHeader( who = 'admin' ) {
	if ( who === 'anonymous' ) {
		return {};
	}
	const { user, password } = credentials[ who ];
	return { Authorization: 'Basic ' + Buffer.from( `${ user }:${ password }` ).toString( 'base64' ) };
}

/**
 * Calls a REST route by its path, for example '/journal/v1/status'.
 * `json` sends a JSON body, `form` a FormData body, `query` is appended as '&a=b'.
 */
export async function api( method, path, { json, form, who = 'admin', query = '' } = {} ) {
	const headers = { ...authHeader( who ) };
	let body;
	if ( json !== undefined ) {
		headers[ 'Content-Type' ] = 'application/json';
		body = JSON.stringify( json );
	}
	if ( form !== undefined ) {
		body = form;
	}
	const response = await fetch( `${ BASE }/?rest_route=${ path }${ query }`, { method, headers, body } );
	const text = await response.text();
	let parsed;
	try {
		parsed = JSON.parse( text );
	} catch {
		parsed = text;
	}
	return { status: response.status, body: parsed };
}

/** Runs a wp-cli command inside the test site and returns its output. */
export function wp( ...args ) {
	return execFileSync( 'npx', [ 'wp-env', 'run', 'cli', 'wp', ...args ], {
		cwd: pluginDir,
		encoding: 'utf8',
		stdio: [ 'ignore', 'pipe', 'pipe' ],
	} );
}

/**
 * Returns a function that yields a different day on every call.
 * Each test file passes its own block number, so files never share a day.
 */
export function dayAllocator( block ) {
	let offset = 0;
	return () => new Date( Date.UTC( 2001 + block, 0, 1 ) + offset++ * 86400000 ).toISOString().slice( 0, 10 );
}

/** Builds a written_at value: at( '2002-01-01', '09:15:00' ) is '2002-01-01T09:15:00-05:00'. */
export function at( day, time, offset = '-05:00' ) {
	return `${ day }T${ time }${ offset }`;
}

/** Posts an entry. `fields` is the JSON body; `options` are api() options such as { who }. */
export function sendEntry( fields, options = {} ) {
	return api( 'POST', '/journal/v1/entries', { json: fields, ...options } );
}

/** Reads a post as its author sees it: raw title, raw content, status, slug, date, categories. */
export async function readPost( id ) {
	const { body } = await api( 'GET', `/wp/v2/posts/${ id }`, { query: '&context=edit' } );
	return body;
}

/** Returns the bytes of a file in tests/fixtures. */
export function fixture( name ) {
	return readFileSync( new URL( `./fixtures/${ name }`, import.meta.url ) );
}

const MIME_TYPES = { jpg: 'image/jpeg', png: 'image/png', webp: 'image/webp', gif: 'image/gif' };

/**
 * Uploads a fixture through the photos endpoint.
 * `file` is the fixture to send, `name` the filename the request claims, `type` its claimed MIME type.
 */
export async function uploadPhoto( { id = uuid(), file = 'photo.jpg', name = file, type, who = 'admin' } = {} ) {
	const form = new FormData();
	form.set( 'id', id );
	const mime = type ?? MIME_TYPES[ name.split( '.' ).pop().toLowerCase() ] ?? 'application/octet-stream';
	form.set( 'photo', new Blob( [ fixture( file ) ], { type: mime } ), name );
	const { status, body } = await api( 'POST', '/journal/v1/photos', { form, who } );
	return { id, status, body };
}

/** Reads an attachment as its owner sees it: status, parent post, source_url, mime_type. */
export async function readMedia( attachmentId ) {
	const { body } = await api( 'GET', `/wp/v2/media/${ attachmentId }`, { query: '&context=edit' } );
	return body;
}

/** Asks the plugin for the name of the database lock that guards a day (YYYY-MM-DD) or a photo id. */
export function lockName( name ) {
	const output = wp( 'eval', `echo 'LOCK:' . journal_entries_lock_name( ${ JSON.stringify( name ) } ) . ':END';` );
	return output.match( /LOCK:(.*):END/ )[ 1 ];
}
