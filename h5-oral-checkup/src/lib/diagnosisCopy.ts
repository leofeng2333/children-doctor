/**
 * 诊断文案与编码映射
 *
 * 与 Vue 项目 `src/utils/diagnosisCopy.ts` 100% 对齐，
 * 保持 8 套文案（编码 0~7）以及骨性版本的备用文案。
 */

export const DiagnosisCode = {
  NORMAL: 0,
  ASYMMETRY: 1,
  ANTERIOR_CROSSBITE: 2,
  OPEN_BITE: 3,
  GUMMY_SMILE: 4,
  UPPER_PROTRUSION: 5,
  CROWDING: 6,
  SPACING: 7,
} as const

Object.freeze(DiagnosisCode)

export type DiagnosisCodeValue = (typeof DiagnosisCode)[keyof typeof DiagnosisCode]

export interface DiagnosisCopy {
  title: string
  opening: string
  body: string[]
  careTips: string
  habitNote: string
}

export const DIAGNOSIS_COPY_MAP: Record<DiagnosisCodeValue, DiagnosisCopy> = {
  [DiagnosisCode.NORMAL]: {
    title: '正常面容',
    opening: '恭喜宝贝！根据拍摄的照片分析，你的面型发育正常，五官协调，棒棒哒！',
    body: [
      '温馨提醒：线上评估仅供参考，牙齿和面型也会随着成长发生变化，建议每6个月做一次口腔检查，继续好好爱护牙齿，保持健康习惯，给快乐成长持续护航哦~',
    ],
    careTips:
      '坚持每天认真刷牙，使用牙线清洁牙缝，每6个月做一次口腔检查，继续保持良好的口腔习惯吧！',
    habitNote: '',
  },
  [DiagnosisCode.ASYMMETRY]: {
    title: '偏颌/大小脸',
    opening: '宝贝有偏颌、大小脸的迹象啦～',
    body: [
      '通常是由于蛀牙疼痛、单侧咀嚼、咬合偏斜、不良睡姿引发，长期会加重面部不对称，损伤颞下颌关节。若是由于蛀牙或牙痛导致偏侧咀嚼，一定要尽早治疗；如果治疗后仍然偏斜，则需要到正畸科调整咬合，防止面部对称的问题持续加重。',
    ],
    careTips: '吃饭时提醒两边均匀咀嚼；及时改正托腮、歪头写作业的习惯。',
    habitNote: '对应不良口腔习惯：偏侧咀嚼、托腮',
  },
  [DiagnosisCode.ANTERIOR_CROSSBITE]: {
    title: '反颌（地包天）',
    opening: '宝贝出现反颌（地包天），要重视啦！',
    body: [
      '下牙包住上牙，长期会导致下巴前伸、面中部凹陷，形成"月牙脸"，还会损伤牙齿、颞下颌关节，严重时还会影响面部骨骼发育。',
      '此类情况越早干预越好，不用等到换完牙！3~6岁是"地包天"的黄金干预期，这个阶段孩子的颌骨还没"定型"，通过简单的活动矫治器或功能矫治器，通常3~6个月就能把下巴"拉"回来，面型恢复效果最好。',
      '等到骨骼发育定型后，矫正难度增大，成年后甚至只能通过正颌手术才能解决。',
    ],
    careTips:
      '排查扁桃体肥大、腺样体肥大、蛀牙疼痛、喂奶姿势不当等诱因；纠正咬上唇、下颌前伸等坏习惯。',
    habitNote: '对应不良口腔习惯：吮唇、下颌前伸',
  },
  [DiagnosisCode.OPEN_BITE]: {
    title: '开颌',
    opening: '宝贝可能存在开颌问题哦～',
    body: [
      '上下牙齿无法正常咬合对齐，影响咀嚼、发音，长期会导致面部发育异常，还可能伴随颞下颌关节问题。',
      '及时找正畸医生面诊，检查关节是否有异常、张嘴是否有弹响或者疼痛，早期干预关闭咬合。',
    ],
    careTips:
      '坚决戒掉咬手指、咬指甲、咬笔头等不良习惯；练习用舌头弹上颚（"哒哒哒"的声音），帮助舌头归位；双侧均衡咀嚼，避免单侧受力。',
    habitNote: '对应不良口腔习惯：吮指、吮颊、啃异物（如笔、被角、筷子）、吐舌、舔牙齿',
  },
  [DiagnosisCode.GUMMY_SMILE]: {
    title: '露龈笑',
    opening: '宝贝有点露龈笑哦~',
    body: [
      '孩子微笑或大笑时，牙龈会明显外露，这种表现就叫做露龈笑。通常是由于上唇过短或上唇肌肉力量过强/门牙萌出不足或牙龈增生/上颌骨发育过度等原因导致。',
      '小朋友轻度露龈笑属于发育阶段正常现象，无需过度担心。若大笑时牙龈外露较多，或是到了9-10岁仍然明显，应及时到正畸科面诊，排查颌骨、唇部功能问题。',
    ],
    careTips:
      '纠正口呼吸、吮吸手指等不良口腔习惯；在家进行唇部肌肉练习，抿嘴微笑放松上唇肌肉。',
    habitNote: '',
  },
  [DiagnosisCode.UPPER_PROTRUSION]: {
    title: '牙齿前突（龅牙）',
    opening: '宝贝可能有点牙齿前突（龅牙）哦。',
    body: [
      '这种情况大多是由于口呼吸、咬下唇、吮指、不当喂养习惯或遗传因素引发，容易导致嘴唇闭合不全，影响面部美观和容貌自信，长此以往还会加重咬合紊乱。',
      '建议爸爸妈妈尽早带宝贝就医面诊，通过早期矫治内收前牙，改善面型，避免成年后骨骼定型矫正难度加大。',
    ],
    careTips: '纠正张口呼吸、咬唇、咬手指等坏习惯；及时排查并治疗鼻炎、腺样体肥大等问题。',
    habitNote: '对应不良口腔习惯：吮指、吮颊、啃异物（如笔、被角、筷子）、吐舌、舔牙齿',
  },
  [DiagnosisCode.CROWDING]: {
    title: '牙列拥挤',
    opening: '宝贝可能存在牙列拥挤的情况哦～',
    body: [
      '牙列拥挤容易造成牙齿清洁不到位，滋生蛀牙、牙结石，还会影响牙齿整齐度、面部美观，严重时还会干扰正常咬合。',
      '替牙期（6~12岁）是最佳干预时机，建议尽早咨询专业正畸医生，优先通过早期干预扩弓排齐，尽量避免后期拔牙矫正。',
    ],
    careTips:
      '日常多吃玉米、苹果、坚果等偏硬食物，充分咀嚼促进颌骨正常发育，给牙齿足够生长空间；认真刷牙，别忘了用牙线或冲牙器清理拥挤的牙缝哦。',
    habitNote: '',
  },
  [DiagnosisCode.SPACING]: {
    title: '牙列稀疏',
    opening: '宝贝可能有牙列稀疏的问题哦～',
    body: [
      '牙缝过大容易卡住食物残渣，引发蛀牙、牙周问题，牙齿稳定性变差，还可能伴随咬合异常，影响面部发育。',
      '建议爸爸妈妈定期带宝贝去专业的口腔医院检查咬合关系、牙齿松动情况，必要时早期矫治关闭缝隙，引导牙齿正常排列。',
      '如果门牙缝隙在换牙期暂时出现，多数会自己长好，爸爸妈妈可以暂时保持观察。',
    ],
    careTips:
      '及时排查咬嘴唇、吮手指、吐舌头等不良口腔习惯；学会正确吞咽（舌尖顶住上颚，而不是伸到牙齿之间）。',
    habitNote: '',
  },
}

export const UPPER_PROTRUSION_BONE: DiagnosisCopy = {
  title: '上颌前突/下颌后缩',
  opening: '宝贝存在上颌前突 / 下颌后缩情况哦～',
  body: [
    '这通常是由长期口呼吸、咬唇、吮指、腺样体问题导致，让脸型悄悄变成凸嘴、下巴又短又缩。更麻烦的是，它还会影响孩子的呼吸和睡眠，白天注意力不集中，甚至耽误生长发育。',
    '建议尽早面诊检查咬合与骨骼发育，通过功能矫治引导下颌正常生长，抑制上颌过度前突，改善面型。',
  ],
  careTips:
    '第一时间纠正张口呼吸、咬唇、吮指等习惯；排查并及时治疗鼻炎、腺样体问题；吃饭坚持双侧均衡咀嚼，适当吃偏硬的食物，促进下颌发育。',
  habitNote: '对应不良口腔习惯：张口呼吸',
}

function normalizeCode(rawCode: unknown): DiagnosisCodeValue | null {
  if (typeof rawCode === 'number' && Number.isInteger(rawCode)) {
    return rawCode as DiagnosisCodeValue
  }
  if (typeof rawCode === 'string') {
    const n = Number(rawCode)
    if (Number.isInteger(n)) return n as DiagnosisCodeValue
  }
  return null
}

export function getDiagnosisCopy(code: DiagnosisCodeValue | null): DiagnosisCopy {
  if (code == null) return DIAGNOSIS_COPY_MAP[DiagnosisCode.NORMAL]
  return DIAGNOSIS_COPY_MAP[code] ?? DIAGNOSIS_COPY_MAP[DiagnosisCode.NORMAL]
}

export function getDiagnosisCopyFromLLMResult(llmResult: unknown): DiagnosisCopy {
  if (!llmResult || typeof llmResult !== 'object') {
    return DIAGNOSIS_COPY_MAP[DiagnosisCode.NORMAL]
  }
  const code = normalizeCode((llmResult as { categoryCode?: unknown }).categoryCode)
  return getDiagnosisCopy(code)
}

export function getHabitShort(habitNote: string): string {
  return (habitNote || '').replace(/^对应不良口腔习惯[：:]\s*/, '').trim()
}
