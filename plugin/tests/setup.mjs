// Resets the test site to a known state and writes the credentials the tests use.
import { execFileSync } from 'node:child_process';
import { writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const pluginDir = fileURLToPath( new URL( '..', import.meta.url ) );

function wp( ...args ) {
	return execFileSync( 'npx', [ 'wp-env', 'run', 'cli', 'wp', ...args ], {
		cwd: pluginDir,
		encoding: 'utf8',
		stdio: [ 'ignore', 'pipe', 'pipe' ],
	} );
}

function tryWp( ...args ) {
	try {
		return wp( ...args );
	} catch {
		return '';
	}
}

function applicationPassword( user ) {
	tryWp( 'user', 'application-password', 'delete', user, '--all' );
	const output = wp( 'user', 'application-password', 'create', user, 'journal-tests', '--porcelain' );
	const match = output.match( /^[A-Za-z0-9]{24}$/m );
	if ( ! match ) {
		throw new Error( `No application password found in:\n${ output }` );
	}
	return match[ 0 ];
}

// Delete every post and attachment left by an earlier run.
const leftovers = [ 'any', 'trash' ].flatMap( ( status ) =>
	wp( 'post', 'list', '--post_type=post,attachment', `--post_status=${ status }`, '--format=ids' )
		.split( /\s+/ )
		.filter( ( token ) => /^\d+$/.test( token ) )
);
if ( leftovers.length ) {
	wp( 'post', 'delete', ...leftovers, '--force' );
}

tryWp( 'user', 'create', 'reader', 'reader@example.com', '--role=subscriber' );
// A second account that can publish, to show that each account keeps its own journal.
tryWp( 'user', 'create', 'author', 'author@example.com', '--role=author' );
// Categories the tests rely on: one that entries are filed under, and one that no post ever uses.
tryWp( 'term', 'create', 'category', 'Travel', '--slug=travel' );
tryWp( 'term', 'create', 'category', 'Tea & Cake', '--slug=tea-cake' );
wp( 'site', 'switch-language', 'en_US' );
// One test relies on the site clock being UTC.
wp( 'option', 'update', 'timezone_string', 'UTC' );

writeFileSync(
	new URL( './.credentials.json', import.meta.url ),
	JSON.stringify(
		{
			base: 'http://localhost:8888',
			admin: { user: 'admin', password: applicationPassword( 'admin' ) },
			reader: { user: 'reader', password: applicationPassword( 'reader' ) },
			author: { user: 'author', password: applicationPassword( 'author' ) },
			wrong: { user: 'admin', password: 'wrong-wrong-wrong-wrong' },
		},
		null,
		2
	)
);
console.log( 'Test site reset; credentials written.' );
