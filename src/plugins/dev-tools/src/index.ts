import { registerPlugin } from '@capacitor/core'
import type { DevToolsDialogPlugin } from './definitions'
import { DevToolsDialogWeb } from './web'

const DevToolsDialog = registerPlugin<DevToolsDialogPlugin>('DevToolsDialog', {
  web: () => new DevToolsDialogWeb(),
})

export { DevToolsDialog }
export type {
  DevToolsDialogPlugin,
  DevToolsDialogSlot,
  DevToolsDialogResult,
} from './definitions'
