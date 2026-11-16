#!/usr/bin/env node
// FIN-R8: check Flyway's normalized version identity before tests/application startup.
import {readdirSync} from "node:fs";
import {resolve} from "node:path";

const directory=resolve(process.argv[2]??"backend/src/main/resources/db/migration");
const files=readdirSync(directory).filter(name=>name.endsWith(".sql")).sort();
const seen=new Map();
const errors=[];
let count=0;
for(const filename of files){
  if(!filename.startsWith("V"))continue; // Repeatable R__ migrations are versionless.
  const match=/^V([0-9]+(?:[._][0-9]+)*)__[A-Za-z0-9][A-Za-z0-9_.-]*\.sql$/.exec(filename);
  if(!match){
    errors.push("FLYWAY_INVALID_MIGRATION_FILENAME "+filename);
    continue;
  }
  const segments=match[1].split(/[._]/).map(component=>BigInt(component).toString());
  while(segments.length>1&&segments[segments.length-1]==="0")segments.pop();
  const version=segments.join(".");
  const existing=seen.get(version);
  if(existing)errors.push("FLYWAY_DUPLICATE_VERSION "+version+": "+existing+" <> "+filename);
  else seen.set(version,filename);
  count++;
}
if(errors.length){
  for(const error of errors)console.error(error);
  process.exitCode=1;
}else{
  console.log("FLYWAY_VERSIONS_UNIQUE "+count+" versioned SQL migrations; root="+directory);
}
