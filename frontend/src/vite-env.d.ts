/// <reference types="vite/client" />

/** Local FIN calculator UAT is opt-in only; default production build is OFF. */
interface ImportMetaEnv {
  readonly VITE_FIN_INTAKE_UAT?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
