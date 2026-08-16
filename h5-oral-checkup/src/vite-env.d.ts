/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_USE_HASH_ROUTER?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
