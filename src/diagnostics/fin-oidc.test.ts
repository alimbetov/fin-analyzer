import { describe,expect,it } from "vitest";
import { tokenOrganizationHints } from "./fin-oidc";

function token(body:Record<string,unknown>):string{
  const data=btoa(JSON.stringify(body)).replace(/\+/g,"-").replace(/\//g,"_").replace(/=+$/,"");
  return "ignored."+data+".ignored";
}
describe("FIN P0 Keycloak organization display hints",()=>{
  it("lists only organization-specific scope hints and never invents permissions",()=>{
    const org="11111111-1111-4111-8111-111111111111";
    expect(tokenOrganizationHints(token({fin_access:[
      {organizationId:org,permissions:["FIN_SUBMIT","FIN_READ"]},
      {organizationId:"bad id",permissions:["FIN_PUBLISH"]},
      {organizationId:org,permissions:"FIN_PUBLISH"}
    ]}))).toEqual([{organizationId:org,permissions:["FIN_SUBMIT","FIN_READ"]}]);
  });
  it("fails closed on a non-JWT, a missing claim or a malformed payload",()=>{
    expect(tokenOrganizationHints("not-a-token")).toEqual([]);
    expect(tokenOrganizationHints(token({realm_access:{roles:["ADMIN"]}}))).toEqual([]);
    expect(tokenOrganizationHints("a.!b.c")).toEqual([]);
  });
});
