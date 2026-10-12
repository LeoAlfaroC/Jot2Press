import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

export const pluginDir = fileURLToPath( new URL( '..', import.meta.url ) );
const credentials = JSON.parse( readFileSync( new URL( './.credentials.json', import.meta.url ), 'utf8' ) );

export const BASE = credentials.base;
export const uuid = randomUUID;

/** Authorization header for 'admin', 'reader' (a subscriber) or 'wrong'; none for 'anonymous'. */
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
