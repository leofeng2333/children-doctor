import { registerPlugin } from '@capacitor/core'
import type { HiTiPrinterCurrentPlugin } from './definitions'
import { HiTiPrinterCurrentWeb } from './web'

const HiTiPrinterCurrent = registerPlugin<HiTiPrinterCurrentPlugin>('HiTiPrinterCurrent', {
  web: () => new HiTiPrinterCurrentWeb(),
})

export { HiTiPrinterCurrent }
export type {
  HiTiPrinterCurrentPlugin,
  HiTiCurrentResult,
  HiTiCurrentPrintPhotoOptions,
  HiTiCurrentComposePrintOverlayOptions,
  HiTiCurrentComposePrintOverlayData,
  HiTiCurrentErrorCode,
  HiTiCurrentPrinterStatus,
} from './definitions'
