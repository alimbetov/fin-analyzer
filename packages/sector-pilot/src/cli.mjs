import {readFileSync} from 'node:fs';
import {evaluateSectorForms} from './index.mjs';
if(process.argv.length!==3){console.error('Usage: node src/cli.mjs examples/pilot.json');process.exitCode=2;}
else try{console.log(JSON.stringify(evaluateSectorForms(JSON.parse(readFileSync(process.argv[2],'utf8'))),null,2));}
catch(e){console.error('Invalid sector data: '+e.message);process.exitCode=1;}
