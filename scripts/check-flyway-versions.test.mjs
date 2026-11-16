import {test} from "node:test";
import assert from "node:assert/strict";
import {spawnSync} from "node:child_process";
import {mkdtempSync,writeFileSync,rmSync} from "node:fs";
import {tmpdir} from "node:os";
import {join,resolve} from "node:path";

const script=resolve("scripts/check-flyway-versions.mjs");
function verify(files){
  const directory=mkdtempSync(join(tmpdir(),"fin-r8-flyway-"));
  try{
    for(const file of files)writeFileSync(join(directory,file),"-- test fixture\n");
    return spawnSync(process.execPath,[script,directory],{encoding:"utf8"});
  }finally{rmSync(directory,{recursive:true,force:true});}
}
test("accepts distinct Flyway SQL versions",()=>{
  const result=verify(["V1__first.sql","V2__second.sql","V20261116_08__cat.sql","V20261116_09__sku.sql"]);
  assert.equal(result.status,0,result.stderr);
  assert.match(result.stdout,/FLYWAY_VERSIONS_UNIQUE 4/);
});
test("rejects a duplicate across decimal and underscore notation",()=>{
  const result=verify(["V20261116_08__cat.sql","V20261116.8__sku.sql"]);
  assert.notEqual(result.status,0);
  assert.match(result.stderr,/FLYWAY_DUPLICATE_VERSION 20261116\.8/);
});
test("rejects malformed versioned SQL files",()=>{
  const result=verify(["V20261116_08__good.sql","V20261116_bad.sql"]);
  assert.notEqual(result.status,0);
  assert.match(result.stderr,/FLYWAY_INVALID_MIGRATION_FILENAME/);
});
test("rejects equivalent leading/trailing zero representations",()=>{
  const result=verify(["V1__one.sql","V01_0__same.sql"]);
  assert.notEqual(result.status,0);
  assert.match(result.stderr,/FLYWAY_DUPLICATE_VERSION 1/);
});
