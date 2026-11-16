import {
  UserManager, WebStorageStateStore, type User
} from "oidc-client-ts";

export interface FinOidcConfig {
  issuer: string; clientId: string; apiBase: string;
}
function safeUrl(value: string): string {
  if (!value) return "";
  const url=new URL(value);
  if (url.protocol !== "https:" && !(url.protocol==="http:" && url.hostname==="localhost"))
    throw new Error("FIN_OIDC_HTTPS_REQUIRED");
  if (url.username || url.password || url.search || url.hash)
    throw new Error("FIN_OIDC_URL_INVALID");
  return url.toString().replace(/\/$/,"");
}
export function configuredFinOidc():FinOidcConfig|null {
  const issuer=import.meta.env.VITE_FIN_OIDC_ISSUER?.trim() ?? "";
  const clientId=import.meta.env.VITE_FIN_OIDC_CLIENT_ID?.trim() ?? "";
  const apiBase=import.meta.env.VITE_FIN_API_BASE?.trim() ?? "";
  if(!issuer || !clientId || !apiBase)return null;
  if(!/^[A-Za-z0-9._-]{2,128}$/.test(clientId))
    throw new Error("FIN_OIDC_CLIENT_ID_INVALID");
  return {issuer:safeUrl(issuer),clientId,apiBase:safeUrl(apiBase)};
}
let manager:UserManager|null=null;
export function finUserManager(config:FinOidcConfig):UserManager {
  if(manager)return manager;
  manager=new UserManager({
    authority:config.issuer,
    client_id:config.clientId,
    redirect_uri:window.location.origin+window.location.pathname,
    post_logout_redirect_uri:window.location.origin+window.location.pathname,
    response_type:"code",
    scope:"openid profile email",
    userStore:new WebStorageStateStore({store:window.sessionStorage}),
    automaticSilentRenew:false,
    monitorSession:false
  });
  return manager;
}
export async function loadFinUser(manager:UserManager):Promise<User|null>{
  const query=new URLSearchParams(window.location.search);
  if(query.has("code") && query.has("state")){
    const user=await manager.signinRedirectCallback();
    window.history.replaceState({},document.title,window.location.pathname);
    return user;
  }
  return await manager.getUser();
}
export interface OrganizationGrant {
  organizationId:string;
  permissions:readonly string[];
}
/** Display hint only. The API verifies signature/issuer/audience and scope itself. */
export function tokenOrganizationHints(accessToken:string):readonly OrganizationGrant[] {
  try {
    const part=accessToken.split(".")[1];
    if(!part)return [];
    const normalized=part.replace(/-/g,"+").replace(/_/g,"/");
    const parsed=JSON.parse(atob(normalized.padEnd(Math.ceil(normalized.length/4)*4,"="))) as
      {fin_access?:unknown};
    if(!Array.isArray(parsed.fin_access))return [];
    const grants:OrganizationGrant[]=[];
    for(const row of parsed.fin_access){
      if(!row || typeof row!=="object")continue;
      const entry=row as Record<string,unknown>;
      if(typeof entry.organizationId!=="string" ||
          !/^[0-9a-f-]{36}$/i.test(entry.organizationId) ||
          !Array.isArray(entry.permissions))continue;
      const permissions=entry.permissions.filter((p):p is string=>typeof p==="string");
      grants.push({organizationId:entry.organizationId,permissions});
    }
    return grants;
  }catch{return [];}
}
export async function finApi<T>(
  config:FinOidcConfig,user:User,organizationId:string,resource:string,
  init:RequestInit={}
):Promise<T>{
  if(user.expired || !user.access_token)throw new Error("FIN_SESSION_EXPIRED");
  if(!resource.startsWith("/")||resource.includes("..")||resource.includes("://"))
    throw new Error("FIN_API_PATH_INVALID");
  const target=config.apiBase+"/api/v1/financial/organizations/"+
    encodeURIComponent(organizationId)+resource;
  const headers=new Headers(init.headers);
  headers.set("Authorization","Bearer "+user.access_token);
  if(init.body && !(init.body instanceof FormData))
    headers.set("Content-Type","application/json");
  const response=await fetch(target,{...init,headers,credentials:"omit",redirect:"error"});
  if(!response.ok){
    let code="FIN_API_HTTP_"+response.status;
    try {
      const json=await response.json() as {code?:string};
      if(typeof json.code==="string")code=json.code;
    }catch{ /* do not surface raw server responses */ }
    throw new Error(code);
  }
  if(response.headers.get("Content-Type")?.includes("application/vnd.openxmlformats"))
    return await response.blob() as T;
  return await response.json() as T;
}
