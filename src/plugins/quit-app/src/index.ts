import { registerPlugin } from '@capacitor/core'
import type { QuitAppPlugin } from './definitions'
import { QuitAppWeb } from './web'

const QuitApp = registerPlugin<QuitAppPlugin>('QuitApp', {
  web: () => new QuitAppWeb(),
})

export { QuitApp }
export type { QuitAppPlugin } from './definitions'
