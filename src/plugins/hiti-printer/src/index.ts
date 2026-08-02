import { registerPlugin } from '@capacitor/core'
import type { HiTiPrinterPlugin } from './definitions'
import { HiTiPrinterWeb } from './web'

const HiTiPrinter = registerPlugin<HiTiPrinterPlugin>('HiTiPrinter', {
  web: () => new HiTiPrinterWeb(),
})

export { HiTiPrinter }
export type {
  HiTiPrinterPlugin,
  HiTiResult,
  HiTiPrintPhotoOptions,
  HiTiErrorCode,
  HiTiPrinterStatus,
} from './definitions'