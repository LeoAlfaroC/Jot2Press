// Generates the image fixtures with the GD library inside the test site's PHP. Run once; the results are committed.
import { execFileSync } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const pluginDir = fileURLToPath( new URL( '..', import.meta.url ) );
const fixturesDir = new URL( './fixtures/', import.meta.url );
mkdirSync( fixturesDir, { recursive: true } );

function generate( encoder, width = 64, height = 48 ) {
	const php = `$i = imagecreatetruecolor( ${ width }, ${ height } ); imagefill( $i, 0, 0, imagecolorallocate( $i, 200, 80, 40 ) ); ob_start(); ${ encoder }( $i ); echo "FIXTURE:" . base64_encode( ob_get_clean() ) . ":END";`;
	const output = execFileSync( 'npx', [ 'wp-env', 'run', 'wordpress', 'php', '-r', php ], {
		cwd: pluginDir,
		encoding: 'utf8',
		stdio: [ 'ignore', 'pipe', 'pipe' ],
	} );
	const match = output.match( /FIXTURE:([A-Za-z0-9+/=]+):END/ );
	if ( ! match ) {
		throw new Error( `${ encoder } produced no image:\n${ output }` );
	}
	return Buffer.from( match[ 1 ], 'base64' );
}

writeFileSync( new URL( 'photo.jpg', fixturesDir ), generate( 'imagejpeg' ) );
writeFileSync( new URL( 'photo.png', fixturesDir ), generate( 'imagepng' ) );
writeFileSync( new URL( 'photo.webp', fixturesDir ), generate( 'imagewebp' ) );
writeFileSync( new URL( 'photo.gif', fixturesDir ), generate( 'imagegif' ) );
// 2600x1950: wider than the 2560 px at which WordPress makes a '-scaled' copy, as every real phone photo is.
// Made once with this script (GD inside the test site, a plain colour) and committed.
writeFileSync( new URL( 'photo-large.jpg', fixturesDir ), generate( 'imagejpeg', 2600, 1950 ) );
writeFileSync( new URL( 'not-a-photo.jpg', fixturesDir ), 'This is plain text, not an image.\n' );
console.log( 'Fixtures written.' );
