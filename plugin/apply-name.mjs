// Writes the plugin's visible name from project.json into the files that have to spell it out.
// After editing project.json, run `npm run apply-name`, or `npm run build` to also make the zip.
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const projectFile = new URL( '../project.json', import.meta.url );
const pluginFile = new URL( './jot2press/jot2press.php', import.meta.url );
const readmeFile = new URL( './README.md', import.meta.url );

/** A visible name is one line of 1 to 60 characters, with no outer spaces, that cannot close a PHP comment. */
function checkName( name ) {
	const usable =
		typeof name === 'string' &&
		name !== '' &&
		name === name.trim() &&
		name.length <= 60 &&
		! /[\r\n]|\*\//.test( name );
	if ( ! usable ) {
		throw new Error( `Not a usable name: ${ JSON.stringify( name ) }` );
	}
	return name;
}

/** Reads the visible names of the plugin and the app. */
export function readNames() {
	const { pluginName, appName } = JSON.parse( readFileSync( projectFile, 'utf8' ) );
	return { pluginName: checkName( pluginName ), appName: checkName( appName ) };
}

/** Replaces the first match of `pattern`, or throws if there is none. */
function replaceFirst( source, pattern, replacement, what ) {
	if ( ! pattern.test( source ) ) {
		throw new Error( `There is no ${ what } to write the name into.` );
	}
	// A function, so that a "$" in the name is not read as a replacement pattern.
	return source.replace( pattern, replacement );
}

/** Returns the plugin file's source with its "Plugin Name:" header line set to the given name. */
export function stampHeader( source, pluginName ) {
	checkName( pluginName );
	return replaceFirst( source, /^( \* Plugin Name: +).*$/m, ( line, label ) => label + pluginName, 'Plugin Name header' );
}

/** Returns the README's source with its first heading set to the given name. */
export function stampReadme( source, pluginName ) {
	checkName( pluginName );
	return replaceFirst( source, /^# .*$/m, () => `# ${ pluginName }`, 'README heading' );
}

// Run as a script, not imported by a test.
if ( process.argv[ 1 ] === fileURLToPath( import.meta.url ) ) {
	const { pluginName } = readNames();
	const targets = [ [ pluginFile, stampHeader ] ];
	if ( existsSync( readmeFile ) ) {
		targets.push( [ readmeFile, stampReadme ] );
	}
	for ( const [ file, stamp ] of targets ) {
		writeFileSync( file, stamp( readFileSync( file, 'utf8' ), pluginName ) );
	}
	console.log( `Plugin name set to "${ pluginName }".` );
}
