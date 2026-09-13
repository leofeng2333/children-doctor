/**
 * 诊断文案与编码映射
 *
 * 内容来源：`src/utils/diagnosisCopy.ts`（主项目 Vue 端）。
 * 本文件与主项目保持一致，文案如有调整请同时同步两边。
 *
 * 文案结构适配：
 *   - 主项目 `DiagnosisCopy`：`{ title, opening, bodyPrimary[], bodySecondary[], careTips, habitNote }`
 *   - h5 项目 `DiagnosisCopy`：`{ title, opening, body[], careTips, habitNote }`
 *   - 同步时将主项目的 `bodyPrimary` 与 `bodySecondary` 合并为 h5 的 `body`
 *     （主项目当前 `bodySecondary` 全部为空，因此等价于直接取 `bodyPrimary`）。
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
    title: '正常',
    opening: '根据分析，宝贝的颌面发育正常，棒棒哒！',
    body: [
      '温馨提醒：线上评估仅供参考，牙齿和面型会随着成长发生变化，建议每6个月做一次口腔检查，持续关注颌面发育情况。',
    ],
    careTips:
      '坚持每天认真刷牙，使用牙线清洁牙缝，保持良好的口腔卫生习惯。',
    habitNote: '',
  },
  [DiagnosisCode.ASYMMETRY]: {
    title: '偏𬌗',
    opening: '根据分析评估，你可能存在偏颌/大小脸的情况哦！',
    body: [
      '偏𬌗/大小脸可能与偏侧咀嚼、咬合干扰、牙齿疾病、颌骨发育等多种因素有关，长期会加重面部不对称，损伤颞下颌关节。若是由于蛀牙或牙痛导致偏侧咀嚼，一定要尽早治疗；如果治疗后仍然偏斜，则需要到正畸科调整咬合，防止面部对称的问题持续加重。',
    ],
    careTips: '吃饭时提醒两边均匀咀嚼；及时改正托腮、歪头写作业的习惯。',
    habitNote: '',
  },
  [DiagnosisCode.ANTERIOR_CROSSBITE]: {
    title: '反𬌗',
    opening: '根据分析评估，你可能存在反𬌗（地包天）的情况哦！',
    body: [
      '反𬌗（地包天）是指下牙包住上牙，长期不加干预会导致下巴前伸、面中部凹陷，形成"月牙脸"，还会损伤牙齿、颞下颌关节，严重时会影响面部骨骼发育。',
      '此类情况不用等到宝贝换完牙，越早干预越好！3~6岁是"地包天"的黄金干预期，该阶段孩子的颌骨还没"定型"，通过活动矫治器或功能矫治器，通常3~6个月就能把下巴"拉"回来，面型恢复效果较好。',
      '等到骨骼发育定型后，矫正难度增大，成年后甚至需要通过正颌手术才能解决。',
    ],
    careTips:
      '排查扁桃体肥大、腺样体肥大、蛀牙疼痛、喂奶姿势不当等诱因；纠正咬上唇、下颌前伸等不良习惯。',
    habitNote: '',
  },
  [DiagnosisCode.OPEN_BITE]: {
    title: '开𬌗',
    opening: '根据分析评估，你可能存在开𬌗的情况哦！',
    body: [
      '开𬌗是指上下牙齿无法正常咬合对齐，影响咀嚼、发音，长期会导致面部发育异常，还可能伴随颞下颌关节问题。建议尽早找正畸医生面诊，检查关节是否有异常、张嘴是否有弹响或者疼痛，早期干预关闭咬合。',
    ],
    careTips:
      '坚决戒掉宝贝咬手指、咬指甲、咬笔头等不良习惯；练习用舌头弹上颚（"哒哒哒"的声音），帮助舌头归位；双侧均衡咀嚼，避免单侧受力。',
    habitNote: '',
  },
  [DiagnosisCode.GUMMY_SMILE]: {
    title: '露龈笑',
    opening: '根据分析评估，你可能存在露龈笑的情况哦！',
    body: [
      '露龈笑是指孩子微笑或大笑时，牙龈会明显外露。这种情况通常是由于上唇过短或上唇肌肉力量过强、门牙萌出不足或牙龈增生、上颌骨发育过度等原因导致的。',
      '儿童轻度露龈笑属于发育阶段正常现象，无需过度担心。若大笑时牙龈外露较多，或是到了9~10岁仍然明显，应及时到正畸科面诊，排查颌骨、唇部功能问题。',
    ],
    careTips: '纠正口呼吸、吮吸手指等不良口腔习惯；在家进行唇部肌肉练习。',
    habitNote: '',
  },
  [DiagnosisCode.UPPER_PROTRUSION]: {
    title: '前突',
    opening: '根据分析评估，你可能存在牙齿前突（龅牙）的情况哦！',
    body: [
      '这种情况大多是由于宝贝口呼吸、咬下唇、吮指及不当喂养习惯或遗传因素引发的，容易导致嘴唇闭合不全，影响面部美观和容貌自信，长此以往还会加重咬合紊乱。',
      '建议尽早带宝贝就医面诊，通过早期矫治内收前牙，改善面型，避免成年后骨骼定型矫正难度加大。',
    ],
    careTips: '纠正张口呼吸、咬唇、咬手指等不良习惯；及时排查并治疗鼻炎、腺样体肥大等问题。',
    habitNote: '',
  },
  [DiagnosisCode.CROWDING]: {
    title: '牙列拥挤',
    opening: '根据分析评估，你可能存在牙列拥挤的情况哦！',
    body: [
      '牙列拥挤容易造成牙齿清洁不到位，滋生蛀牙、牙结石，还会影响牙齿整齐度、面部美观，严重时还会干扰正常咬合。替牙期（6~12岁）是黄金干预时机，建议尽早咨询专业正畸医生，优先通过早期干预扩弓排齐，尽量避免后期拔牙矫正。',
    ],
    careTips:
      '日常多吃玉米、苹果、坚果等偏硬食物，充分咀嚼促进颌骨正常发育，给牙齿足够生长空间；认真刷牙，别忘了用牙线或冲牙器清理拥挤的牙缝哦。',
    habitNote: '',
  },
  [DiagnosisCode.SPACING]: {
    title: '牙列稀疏',
    opening: '根据分析评估，你可能存在牙列稀疏的情况哦！',
    body: [
      '牙缝过大容易卡住食物残渣，引发蛀牙、牙周问题，牙齿稳定性变差，还可能伴随咬合异常，影响面部发育。建议定期带宝贝去专业口腔医院检查咬合关系、牙齿松动情况，必要时早期矫治关闭缝隙，引导牙齿正常排列。',
      '如果门牙缝隙在换牙期暂时出现，多数情况下会自己长好，您可以暂时保持观察。',
    ],
    careTips:
      '及时排查咬嘴唇、吮手指、吐舌头等不良口腔习惯；学会正确吞咽（舌尖顶住上颚，而不是伸到牙齿之间）。',
    habitNote: '',
  },
}

export const UPPER_PROTRUSION_BONE: DiagnosisCopy = {
  title: '上颌前突/下颌后缩',
  opening: '根据分析评估，你可能存在上颌前突/下颌后缩的情况哦！',
  body: [
    '上颌前突/下颌后缩通常是由宝贝长期口呼吸、咬唇、吮指、腺样体问题导致的，此类问题会让脸型悄悄变成凸嘴、下巴又短又缩。更麻烦的是，它还会影响孩子的呼吸和睡眠，白天注意力不集中，甚至耽误生长发育。',
    '建议尽早面诊检查咬合与骨骼发育，通过功能矫治引导下颌正常生长，抑制上颌过度前突，改善面型。',
  ],
  careTips:
    '第一时间纠正张口呼吸、咬唇、吮指等习惯；排查并及时治疗鼻炎、腺样体问题；吃饭时坚持双侧均衡咀嚼，适当吃偏硬食物，促进下颌发育。',
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
