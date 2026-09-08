import i1 from '@/assets/images/修订_牙齿问题示意图（7分类）/1.jpg'
import i2 from '@/assets/images/修订_牙齿问题示意图（7分类）/2.jpg'
import i3 from '@/assets/images/修订_牙齿问题示意图（7分类）/3.jpg'
import i4 from '@/assets/images/修订_牙齿问题示意图（7分类）/4.jpg'
import i5 from '@/assets/images/修订_牙齿问题示意图（7分类）/5.jpg'
import i6 from '@/assets/images/修订_牙齿问题示意图（7分类）/6.jpg'
import i7 from '@/assets/images/修订_牙齿问题示意图（7分类）/7.jpg'
import { DiagnosisCode } from './diagnosisCopy'

/**
 * 不健康面型 → 牙齿问题示意图 映射。
 *
 * 接口分类编码来自 `/api/ai/analyze` 的 `llmAnalysis.result.categoryCode`，
 * 与文档 `正畸AI面容预测诊断文案 - 9.6.docx` 中的 1~7 对齐：
 *   1 ASYMMETRY           偏𬌗
 *   2 ANTERIOR_CROSSBITE  反𬌗
 *   3 OPEN_BITE           开𬌗
 *   4 GUMMY_SMILE         露龈笑
 *   5 UPPER_PROTRUSION    前突
 *   6 CROWDING            牙列拥挤
 *   7 SPACING             牙列稀疏
 *
 * 图片源目录 `修订_牙齿问题示意图（7分类）/`，文件名编号与 categoryCode 一一对应，
 * 无需任何"占位/复用"逻辑。
 */
export const TOOTH_ISSUE_IMAGES: Record<number, string> = {
  [DiagnosisCode.ASYMMETRY]: i1, // 偏𬌗
  [DiagnosisCode.ANTERIOR_CROSSBITE]: i2, // 反𬌗
  [DiagnosisCode.OPEN_BITE]: i3, // 开𬌗
  [DiagnosisCode.GUMMY_SMILE]: i4, // 露龈笑
  [DiagnosisCode.UPPER_PROTRUSION]: i5, // 前突
  [DiagnosisCode.CROWDING]: i6, // 牙列拥挤
  [DiagnosisCode.SPACING]: i7, // 牙列稀疏
}

/**
 * 根据 categoryCode 获取对应示意图。
 * 非法/缺失值回退到 ASYMMETRY（偏𬌗）的示意图。
 */
export function getToothIssueImage(
  code: number | null | undefined,
): string {
  if (code == null || !TOOTH_ISSUE_IMAGES[code]) {
    return TOOTH_ISSUE_IMAGES[DiagnosisCode.ASYMMETRY]
  }
  return TOOTH_ISSUE_IMAGES[code]
}
