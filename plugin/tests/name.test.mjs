import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { readNames, stampHeader, stampReadme } from '../apply-name.mjs';

const pluginFile = new URL( '../journal-entries/journal-entries.php', import.meta.url );

test( 'stampHeader replaces only the Plugin Name line', () => {
	const source = '<?php\n/**\n * Plugin Name:       Old Name\n * Description:       Unchanged.\n */\n';
	assert.equal(
		stampHeader( source, 'New Name' ),
		'<?php\n/**\n * Plugin Name:       New Name\n * Description:       Unchanged.\n */\n'
	);
} );

test( 'stampReadme replaces only the first heading', () => {
	const source = '# Old Name\n\nText.\n\n## Install\n\n# A comment inside an example\n';
	assert.equal(
		stampReadme( source, 'New Name' ),
		'# New Name\n\nText.\n\n## Install\n\n# A comment inside an example\n'
	);
} );

test( 'a name with a dollar sign is written as it is', () => {
	const source = '<?php\n/**\n * Plugin Name:       Old Name\n */\n';
	assert.ok( stampHeader( source, 'Cash $1 & $& Co' ).includes( ' * Plugin Name:       Cash $1 & $& Co\n' ) );
} );

test( 'a file without the line to stamp is an error, not a silent no-op', () => {
	assert.throws( () => stampHeader( '<?php\n', 'New Name' ) );
	assert.throws( () => stampReadme( 'No heading here.\n', 'New Name' ) );
} );

test( 'names that would break a file header are refused', () => {
	const source = '<?php\n/**\n * Plugin Name:       Old Name\n */\n';
	for ( const name of [ '', '   ', ' padded ', 'Two\nlines', 'Ends the comment */', 'x'.repeat( 61 ), 42 ] ) {
		assert.throws( () => stampHeader( source, name ), JSON.stringify( name ) );
	}
} );

test( 'the plugin header carries the name in project.json', () => {
	const { pluginName } = readNames();
	assert.ok( readFileSync( pluginFile, 'utf8' ).includes( `\n * Plugin Name:       ${ pluginName }\n` ) );
} );
