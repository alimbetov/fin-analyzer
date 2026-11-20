/// <reference types="vite/client" />

/** Local FIN calculator UAT is opt-in only; default production build is OFF. */
interface ImportMetaEnv {
  readonly VITE_FIN_INTAKE_UAT?: string;
  readonly VITE_FIN_A05_UAT?: string;
  readonly VITE_FIN_MG_R04_UAT?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
