/**
 * 不良口腔习惯卡通图。
 *
 * 图片源目录：src/assets/images/不良口腔习惯卡通图/
 * 每个 HabitCode 可能对应多个具体行为（如 HABIT_PROTRUSION 同时涵盖吮指、啃异物、吐舌），
 * 因此用 string[] 存储全部图片。
 *
 * 与 src/utils/diagnosisCopy.ts 中 HABIT_COPY_MAP 的 habitNote 字段对齐：
 *   HABIT_ANTIJOINT (A)  -> 吮唇、下颌前伸
 *   HABIT_PROTRUSION (B) -> 吮指、啃异物、吐舌舔牙
 *   HABIT_BREATH (C)     -> 张口呼吸
 *   HABIT_ASYMMETRY (D)  -> 偏侧咀嚼、托腮
 */

import 下颌前伸 from '@/assets/images/不良口腔习惯卡通图/下颌前伸.png'
import 偏侧咀嚼 from '@/assets/images/不良口腔习惯卡通图/偏侧咀嚼.png'
import 口呼吸 from '@/assets/images/不良口腔习惯卡通图/口呼吸.png'
import 吐舌 from '@/assets/images/不良口腔习惯卡通图/吐舌.png'
import 吮吸手指 from '@/assets/images/不良口腔习惯卡通图/吮吸手指.png'
import 吮唇 from '@/assets/images/不良口腔习惯卡通图/吮唇.png'
import 啃异物 from '@/assets/images/不良口腔习惯卡通图/啃异物.png'
import 托腮 from '@/assets/images/不良口腔习惯卡通图/托腮.png'
import { HabitCode } from './diagnosisCopy'

/**
 * 不良习惯编码 -> 对应卡通图数组。
 */
export const HABIT_IMAGE_MAP: Record<HabitCode, string[]> = {
  [HabitCode.HABIT_ANTIJOINT]: [吮唇, 下颌前伸],
  [HabitCode.HABIT_PROTRUSION]: [吮吸手指, 啃异物, 吐舌],
  [HabitCode.HABIT_BREATH]: [口呼吸],
  [HabitCode.HABIT_ASYMMETRY]: [偏侧咀嚼, 托腮],
}

/**
 * 获取多个坏习惯对应的所有卡通图（按输入顺序、去重拼接）。
 *
 * 空数组返回空数组 —— 调用方根据是否为 0 决定走"坏习惯图"分支还是回退到
 * 牙齿问题示意图分支（getToothIssueImage）。
 */
export function getBadHabitImages(codes: HabitCode[]): string[] {
  const seen = new Set<string>()
  const result: string[] = []
  for (const code of codes) {
    const images = HABIT_IMAGE_MAP[code] ?? []
    for (const img of images) {
      if (!seen.has(img)) {
        seen.add(img)
        result.push(img)
      }
    }
  }
  return result
}

/**
 * 从多个坏习惯对应的卡通图数组中随机选一张。
 *
 * 用在 stage === 'static' 时的单图展示位：
 *   取到的图可能多张（一个习惯编码可能对应多张具体行为图），
 *   这里任选一张展示。空数组返回空串，由调用方走 fallback。
 */
export function pickRandomBadHabitImage(codes: HabitCode[]): string {
  const images = getBadHabitImages(codes)
  if (images.length === 0) return ''
  return images[Math.floor(Math.random() * images.length)]!
}
