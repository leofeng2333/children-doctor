/**
 * AI 预测面型诊断文案
 *
 * 来源：`AI预测面型诊断文案.docx`
 * 用途：通过 `DiagnosisCode` / `HabitCode` 匹配到对应的诊断文案，
 *      渲染在拍照后即时反馈页 / 扫码后详情页。
 *
 * 文档将全部条目分为三大类：
 *   正常           —— 面型发育正常，无需干预
 *   存在问题（DiagnosisCode）—— 已有可识别的牙颌面问题，需正畸/口腔科介入
 *   存在不良习惯（HabitCode）—— 尚无明显畸形，但有不良口腔习惯，如不纠正
 *                               将导致对应颌面问题；属于预防性/早期干预文案
 *
 * 【DiagnosisCode 诊断编码 0~7】
 *   0 NORMAL              -> 正常面容
 *   1 ASYMMETRY           -> 偏颌/大小脸
 *   2 ANTERIOR_CROSSBITE  -> 反颌（地包天）
 *   3 OPEN_BITE           -> 开颌
 *   4 GUMMY_SMILE         -> 露龈笑
 *   5 UPPER_PROTRUSION    -> 牙齿前突（龅牙，牙性，儿童更常见）
 *   6 CROWDING            -> 牙列拥挤
 *   7 SPACING             -> 牙列稀疏
 *   （注：编码 5 还有骨性版本 UPPER_PROTRUSION_BONE，即上颌前突/下颌后缩，
 *    见下方 UPPER_PROTRUSION_SKELETAL 常量及 UPPER_PROTRUSION_BONE 别名）
 *
 * 【HabitCode 不良习惯编码 A~D】
 *   A HABIT_ANTIJOINT     -> 可能导致"地包天"的不良习惯（吮唇、下颌前伸）
 *   B HABIT_PROTRUSION    -> 可能导致"龅牙/开颌"的不良习惯（吮指、啃异物、吐舌舔牙等）
 *   C HABIT_BREATH        -> 可能导致"上颌前突/下颌后缩"的不良习惯（张口呼吸）
 *   D HABIT_ASYMMETRY     -> 可能导致"大小脸/偏颌"的不良习惯（偏侧咀嚼、托腮）
 *
 * 数据来源约定（来自 `/api/ai/analyze` 接口）：
 *   `analysisResult.llmAnalysis.result.categoryCode` 为数字 0~7，
 *   表示对应的面型诊断分类。读取入口：
 *     `getDiagnosisCopyFromLLMResult(llmResult)`
 *   非法 / 缺失值统一回退到 NORMAL 文案。
 */

/* ============================ 诊断编码（Problems） ============================ */

/** 后端返回的诊断编码枚举 */
export enum DiagnosisCode {
  NORMAL = 0,
  ASYMMETRY = 1,
  ANTERIOR_CROSSBITE = 2,
  OPEN_BITE = 3,
  GUMMY_SMILE = 4,
  UPPER_PROTRUSION = 5,
  CROWDING = 6,
  SPACING = 7,
}

export interface DiagnosisCopy {
  /** 章节标题（不含圈数字序号） */
  title: string
  /** 引导句（粗体小标题之后的第 1 句） */
  opening: string
  /** 危害描述 + 干预建议（第一部分） */
  bodyPrimary: string[]
  /** 危害描述 + 干预建议（第二部分） */
  bodySecondary: string[]
  /** "日常护理小贴士"正文 */
  careTips: string
  /** "有不良口腔习惯"章节关联的坏习惯，空串表示无关联 */
  habitNote: string
}

/* ============================ 诊断文案常量（DiagnosisCode 0~7） ============================ */

const NORMAL: DiagnosisCopy = {
  title: '正常面容',
  opening: '你好棒，颌面发育正常！',
  bodyPrimary: [
    '温馨提醒：线上评估仅供参考，牙齿和面型也会随着成长发生变化，建议每6个月做一次口腔检查，继续好好爱护牙齿，保持健康习惯，给快乐成长持续护航哦~',
  ],
  bodySecondary: [],
  careTips:
    '坚持每天认真刷牙，使用牙线清洁牙缝，每6个月做一次口腔检查，继续保持良好的口腔习惯吧！',
  habitNote: '',
}

const CROWDING: DiagnosisCopy = {
  title: '牙列拥挤',
  opening: '宝贝可能存在牙列拥挤的情况哦～',
  bodyPrimary: [
    '牙列拥挤容易造成牙齿清洁不到位，滋生蛀牙、牙结石，还会影响牙齿整齐度、面部美观，严重时还会干扰正常咬合。',
    '替牙期（6~12岁）是最佳干预时机，建议尽早咨询专业正畸医生，优先通过早期干预扩弓排齐，尽量避免后期拔牙矫正。',
  ],
  bodySecondary: [],
  careTips:
    '日常多吃玉米、苹果、坚果等偏硬食物，充分咀嚼促进颌骨正常发育，给牙齿足够生长空间；认真刷牙，别忘了用牙线或冲牙器清理拥挤的牙缝哦。',
  habitNote: '',
}

const SPACING: DiagnosisCopy = {
  title: '牙列稀疏',
  opening: '宝贝可能有牙列稀疏的问题哦～',
  bodyPrimary: [
    '牙缝过大容易卡住食物残渣，引发蛀牙、牙周问题，牙齿稳定性变差，还可能伴随咬合异常，影响面部发育。',
    '建议爸爸妈妈定期带宝贝去专业的口腔医院检查咬合关系、牙齿松动情况，必要时早期矫治关闭缝隙，引导牙齿正常排列。',
  ],
  bodySecondary: [
    '如果门牙缝隙在换牙期暂时出现，多数会自己长好，爸爸妈妈可以暂时保持观察。',
  ],
  careTips:
    '及时排查咬嘴唇、吮手指、吐舌头等不良口腔习惯；学会正确吞咽（舌尖顶住上颚，而不是伸到牙齿之间）。',
  habitNote: '',
}

const ANTERIOR_PROTRUSION: DiagnosisCopy = {
  title: '牙齿前突（龅牙）',
  opening: '宝贝可能有点牙齿前突（龅牙）哦。',
  bodyPrimary: [
    '这种情况大多是由于口呼吸、咬下唇、吮指、不当喂养习惯或遗传因素引发，容易导致嘴唇闭合不全，影响面部美观和容貌自信，长此以往还会加重咬合紊乱。',
    '建议爸爸妈妈尽早带宝贝就医面诊，通过早期矫治内收前牙，改善面型，避免成年后骨骼定型矫正难度加大。',
  ],
  bodySecondary: [],
  careTips: '纠正张口呼吸、咬唇、咬手指等坏习惯；及时排查并治疗鼻炎、腺样体肥大等问题。',
  habitNote: '',
}

const ANTERIOR_CROSSBITE: DiagnosisCopy = {
  title: '反颌（地包天）',
  opening: '宝贝出现反颌（地包天），要重视啦！',
  bodyPrimary: [
    '下牙包住上牙，长期会导致下巴前伸、面中部凹陷，形成"月牙脸"，还会损伤牙齿、颞下颌关节，严重时还会影响面部骨骼发育。',
    '此类情况越早干预越好，不用等到换完牙！3~6岁是"地包天"的黄金干预期，这个阶段孩子的颌骨还没"定型"，通过简单的活动矫治器或功能矫治器，通常3~6个月就能把下巴"拉"回来，面型恢复效果最好。',
  ],
  bodySecondary: [
    '等到骨骼发育定型后，矫正难度增大，成年后甚至只能通过正颌手术才能解决。',
  ],
  careTips: '排查扁桃体肥大、腺样体肥大、蛀牙疼痛、喂奶姿势不当等诱因；纠正咬上唇、下颌前伸等坏习惯。',
  habitNote: '',
}

const OPEN_BITE: DiagnosisCopy = {
  title: '开颌',
  opening: '宝贝可能存在开颌问题哦～',
  bodyPrimary: [
    '上下牙齿无法正常咬合对齐，影响咀嚼、发音，长期会导致面部发育异常，还可能伴随颞下颌关节问题。',
    '及时找正畸医生面诊，检查关节是否有异常、张嘴是否有弹响或者疼痛，早期干预关闭咬合。',
  ],
  bodySecondary: [],
  careTips:
    '坚决戒掉咬手指、咬指甲、咬笔头等不良习惯；练习用舌头弹上颚（"哒哒哒"的声音），帮助舌头归位；双侧均衡咀嚼，避免单侧受力。',
  habitNote: '',
}

const GUMMY_SMILE: DiagnosisCopy = {
  title: '露龈笑',
  opening: '宝贝有点露龈笑哦~',
  bodyPrimary: [
    '孩子微笑或大笑时，牙龈会明显外露，这种表现就叫做露龈笑。通常是由于上唇过短或上唇肌肉力量过强/门牙萌出不足或牙龈增生/上颌骨发育过度等原因导致。',
    '小朋友轻度露龈笑属于发育阶段正常现象，无需过度担心。若大笑时牙龈外露较多，或是到了9-10岁仍然明显，应及时到正畸科面诊，排查颌骨、唇部功能问题。',
  ],
  bodySecondary: [],
  careTips: '纠正口呼吸、吮吸手指等不良口腔习惯；在家进行唇部肌肉练习，抿嘴微笑放松上唇肌肉。',
  habitNote: '',
}

/** 骨性版本：上颌前突/下颌后缩（对应 docx ⑧） */
const UPPER_PROTRUSION_SKELETAL: DiagnosisCopy = {
  title: '上颌前突/下颌后缩',
  opening: '宝贝存在上颌前突 / 下颌后缩情况哦～',
  bodyPrimary: [
    '这通常是由长期口呼吸、咬唇、吮指、腺样体问题导致，让脸型悄悄变成凸嘴、下巴又短又缩。更麻烦的是，它还会影响孩子的呼吸和睡眠，白天注意力不集中，甚至耽误生长发育。',
    '建议尽早面诊检查咬合与骨骼发育，通过功能矫治引导下颌正常生长，抑制上颌过度前突，改善面型。',
  ],
  bodySecondary: [],
  careTips:
    '第一时间纠正张口呼吸、咬唇、吮指等习惯；排查并及时治疗鼻炎、腺样体问题；吃饭坚持双侧均衡咀嚼，适当吃偏硬的食物，促进下颌发育。',
  habitNote: '对应不良口腔习惯：张口呼吸',
}

const ASYMMETRY: DiagnosisCopy = {
  title: '偏颌/大小脸',
  opening: '宝贝有偏颌、大小脸的迹象啦～',
  bodyPrimary: [
    '通常是由于蛀牙疼痛、单侧咀嚼、咬合偏斜、不良睡姿引发，长期会加重面部不对称，损伤颞下颌关节。若是由于蛀牙或牙痛导致偏侧咀嚼，一定要尽早治疗；如果治疗后仍然偏斜，则需要到正畸科调整咬合，防止面部对称的问题持续加重。',
  ],
  bodySecondary: [],
  careTips: '吃饭时提醒两边均匀咀嚼；及时改正托腮、歪头写作业的习惯。',
  habitNote: '',
}

/** 编码 -> 诊断文案（仅含 DiagnosisCode 0~7） */
export const DIAGNOSIS_COPY_MAP: Record<DiagnosisCode, DiagnosisCopy> = {
  [DiagnosisCode.NORMAL]: NORMAL,
  [DiagnosisCode.ASYMMETRY]: ASYMMETRY,
  [DiagnosisCode.ANTERIOR_CROSSBITE]: ANTERIOR_CROSSBITE,
  [DiagnosisCode.OPEN_BITE]: OPEN_BITE,
  [DiagnosisCode.GUMMY_SMILE]: GUMMY_SMILE,
  /**
   * 编码 5 默认使用牙性版本（儿童期更常见的"牙齿前突/龅牙"）。
   * 如需展示骨性版本（上颌前突/下颌后缩），可访问 UPPER_PROTRUSION_BONE。
   * 后续若后端把 5 拆分为 5a / 5b，再分别映射即可。
   */
  [DiagnosisCode.UPPER_PROTRUSION]: ANTERIOR_PROTRUSION,
  [DiagnosisCode.CROWDING]: CROWDING,
  [DiagnosisCode.SPACING]: SPACING,
}

/** 编码 5 的骨性版本（上颌前突/下颌后缩），按需取用 */
export const UPPER_PROTRUSION_BONE = UPPER_PROTRUSION_SKELETAL

/**
 * 根据诊断编码获取文案。
 * 未知编码（null/undefined 或超出范围）回退到 NORMAL。
 */
export function getDiagnosisCopy(code: number | null | undefined): DiagnosisCopy {
  if (code == null) return DIAGNOSIS_COPY_MAP[DiagnosisCode.NORMAL]
  const found = DIAGNOSIS_COPY_MAP[code as DiagnosisCode]
  return found ?? DIAGNOSIS_COPY_MAP[DiagnosisCode.NORMAL]
}

/* ============================ 不良习惯编码（HabitCode A~D） ============================ */

/**
 * 不良习惯编码（独立于 DiagnosisCode，单独使用）。
 *
 * 这类条目在 docx 中属于"存在不良习惯"章节——用户尚无明显颌面畸形，
 * 但有不良口腔习惯；文案为预防性干预，告知家长如何识别和纠正。
 */
export enum HabitCode {
  /** 可能导致"地包天"：吮唇、下颌前伸 */
  HABIT_ANTIJOINT = 'A',
  /** 可能导致"龅牙/开颌"：吮指、啃异物、吐舌舔牙 */
  HABIT_PROTRUSION = 'B',
  /** 可能导致"上颌前突/下颌后缩"：张口呼吸 */
  HABIT_BREATH = 'C',
  /** 可能导致"大小脸/偏颌"：偏侧咀嚼、托腮 */
  HABIT_ASYMMETRY = 'D',
}

/* ============================ 不良习惯文案常量 ============================ */

/** 地包天诱因：吮唇、下颌前伸 */
const HABIT_ANTIJOINT: DiagnosisCopy = {
  title: '不良口腔习惯——吮唇、下颌前伸',
  opening: '宝贝有口腔不良习惯哦～',
  bodyPrimary: [
    '如果长期不改正，可能会导致下巴前伸、面中部凹陷，形成"月牙脸"，变成"地包天"，不仅影响容貌美观，还会损伤牙齿、颞下颌关节，严重时还会影响面部骨骼发育哦！',
    '家长可以这样做：主动纠正——引导孩子有意识地自我控制，同时留意孩子最容易出现这些动作的场景，通过转移注意力或轻声提醒，针对性中断习惯；专业干预——如果宝贝自己改不掉，可以请儿童口腔科或正畸医生评估，进行口周肌功能训练，或佩戴简单矫正器帮助破除习惯。',
  ],
  bodySecondary: [
    '小提醒：大部分不良口腔习惯在7岁前通过行为引导和简单干预即可纠正，牙齿和颌骨仍有很强的自我调整能力。越早纠正习惯，越能避免发展为真正的"地包天"！',
  ],
  careTips: '',
  habitNote: '对应不良口腔习惯：吮唇、下颌前伸',
}

/** 龅牙/开颌诱因：吮指、啃异物、吐舌舔牙 */
const HABIT_PROTRUSION: DiagnosisCopy = {
  title: '不良口腔习惯——吮指、啃异物、吐舌舔牙',
  opening: '宝贝有口腔不良习惯哦～',
  bodyPrimary: [
    '如果长期不改正，可能会导致上前牙向外龅出（龅牙）、前牙咬不上（开颌），不仅影响容貌美观，还会导致发音不清、咀嚼效率下降。',
    '家长可以这样做：主动纠正——引导孩子有意识地自我控制，同时留意孩子最容易出现这些动作的场景，通过转移注意力或轻声提醒，针对性中断习惯；专业干预——如果自己改不掉，可以请儿童口腔科或正畸医生评估，进行肌功能训练或佩戴矫正器，物理阻断不良习惯。',
  ],
  bodySecondary: [
    '小提醒：大部分不良习惯在7岁前纠正，牙齿和脸型还有很强的自我调整能力。越早纠正，效果越好！',
  ],
  careTips: '',
  habitNote: '对应不良口腔习惯：吮指、吮颊、啃异物（如笔、被角、筷子）、吐舌、舔牙齿',
}

/** 上颌前突/下颌后缩诱因：张口呼吸 */
const HABIT_BREATH: DiagnosisCopy = {
  title: '不良口腔习惯——张口呼吸',
  opening: '宝贝有口腔不良习惯哦～',
  bodyPrimary: [
    '如果长期不改正，可能会导致上颌骨过度前突、下巴后缩，不仅影响容貌美观，还会降低睡眠质量，影响生长激素的夜间分泌，进而影响身高、体重等全身生长发育。',
    '家长可以这样做：去耳鼻喉科排查鼻炎、腺样体肥大等病因，并进行针对性治疗；在确保鼻腔通气已解决的前提下，请儿童口腔科或正畸医生评估，进行唇肌功能训练或佩戴功能矫治器，引导鼻呼吸；若口呼吸习惯已经导致了牙弓狭窄或颌骨发育异常，则需佩戴矫治器纠正骨骼畸形。',
  ],
  bodySecondary: [
    '小提醒：目前宝贝尚未出现明显的颌面畸形，此时是纠正口呼吸习惯的黄金窗口期。先看耳鼻喉科解决鼻塞问题，再进行正畸干预。越早纠正习惯，越能避免发展为真正的骨骼畸形！',
  ],
  careTips: '',
  habitNote: '对应不良口腔习惯：张口呼吸',
}

/** 大小脸/偏颌诱因：偏侧咀嚼、托腮 */
const HABIT_ASYMMETRY: DiagnosisCopy = {
  title: '不良口腔习惯——偏侧咀嚼、托腮',
  opening: '宝贝有口腔不良习惯哦～',
  bodyPrimary: [
    '如果长期不改正，可能会导致面部不对称（大小脸）、下巴偏斜、牙齿中线不齐，笑起来嘴巴歪向一侧。不仅外貌受影响，还会导致颞下颌关节紊乱、双侧牙齿磨损不均匀。',
    '家长可以这样做：检查孩子是否存在单侧蛀牙、缺牙或牙齿疼痛，先治疗牙齿疾病，解除根本原因；引导孩子有意识地用两侧后牙轮流咀嚼，纠正托腮、歪头写作业的习惯；专业干预——如果自己改不掉，可以请儿童口腔科或正畸医生评估，进行肌功能训练，或佩戴简单矫治器辅助纠正偏侧咀嚼和托腮习惯。',
  ],
  bodySecondary: [
    '小提醒：目前孩子尚未形成明显的偏颌或大小脸，此时是纠正不良习惯的黄金窗口期。越早纠正习惯，越能避免发展为真正的"大小脸"或"下巴偏斜"。',
  ],
  careTips: '',
  habitNote: '对应不良口腔习惯：偏侧咀嚼、托腮',
}

/** 不良习惯文案映射 */
export const HABIT_COPY_MAP: Record<HabitCode, DiagnosisCopy> = {
  [HabitCode.HABIT_ANTIJOINT]: HABIT_ANTIJOINT,
  [HabitCode.HABIT_PROTRUSION]: HABIT_PROTRUSION,
  [HabitCode.HABIT_BREATH]: HABIT_BREATH,
  [HabitCode.HABIT_ASYMMETRY]: HABIT_ASYMMETRY,
}

/**
 * 根据不良习惯编码获取文案。
 * 未知编码回退到 HABIT_ANTIJOINT。
 */
export function getHabitCopy(code: string | null | undefined): DiagnosisCopy {
  if (code == null) return HABIT_COPY_MAP[HabitCode.HABIT_ANTIJOINT]
  const found = HABIT_COPY_MAP[code as HabitCode]
  return found ?? HABIT_COPY_MAP[HabitCode.HABIT_ANTIJOINT]
}

/* ============================ 从 LLM 结果读取诊断文案 ============================ */

/**
 * 从 LLM 分析结果中获取诊断文案。
 *
 * 数据来源约定（来自 `/api/ai/analyze` 接口）：
 *   `analysisResult.llmAnalysis.result.categoryCode` 为数字 0~7，
 *   表示对应的面型诊断分类。后端已移除 `diagnosis.issues` 字段，
 *   此处只信任 `categoryCode`。
 *
 * 行为：
 *   - 传入对象缺失 / `categoryCode` 不是合法整数 0~7 → 回退到 NORMAL 文案
 *   - 兼容字符串数字（如 "5"）
 *
 * 用法：
 *   const copy = getDiagnosisCopyFromLLMResult(analysisResult.value?.llmAnalysis?.result)
 */
export function getDiagnosisCopyFromLLMResult(
  llmResult: unknown,
): DiagnosisCopy {
  if (!llmResult || typeof llmResult !== 'object') {
    return DIAGNOSIS_COPY_MAP[DiagnosisCode.NORMAL]
  }
  const obj = llmResult as Record<string, any>
  const rawCode = obj.categoryCode

  let code: number | null = null
  if (typeof rawCode === 'number' && Number.isInteger(rawCode)) {
    code = rawCode
  } else if (typeof rawCode === 'string') {
    const n = Number(rawCode)
    if (Number.isInteger(n)) code = n
  }

  if (code == null) {
    return DIAGNOSIS_COPY_MAP[DiagnosisCode.NORMAL]
  }
  return DIAGNOSIS_COPY_MAP[code as DiagnosisCode]
    ?? DIAGNOSIS_COPY_MAP[DiagnosisCode.NORMAL]
}

/* -------------------------------------------------------------------------- */
/* 拍照后即时反馈文案（屏幕显示文案）                                          */
/* -------------------------------------------------------------------------- */

/** 拍照后即时反馈的 3 种状态 */
export type InstantFeedbackKind = 'NORMAL' | 'TOOTH_PROBLEM' | 'BAD_HABIT'

export const INSTANT_FEEDBACK: Record<
  InstantFeedbackKind,
  { title: string; content: string }
> = {
  NORMAL: {
    title: '正常面容',
    content: '你的面容发育正常，请定期检查，继续保持哦！',
  },
  TOOTH_PROBLEM: {
    title: '牙齿存在问题',
    content:
      '根据预判结果，你可能会有牙列拥挤/牙列稀疏/牙齿前突（龅牙）/反颌（地包天）/开颌/露龈笑/上颌前突/下颌后缩/偏颌/大小脸的问题，请爸爸妈妈尽早带你去医院详细检查哦！',
  },
  BAD_HABIT: {
    title: '有不良口腔习惯',
    content:
      '你有不良口腔习惯，不纠正可能会有牙齿前突（龅牙）/反颌（地包天）/开颌/上颌前突/下颌后缩/偏颌/大小脸的问题，请爸爸妈妈尽早带你去医院详细检查哦！',
  },
}

/* -------------------------------------------------------------------------- */
/* 通用：把多行 body 合并成单段（便于部分页面直接渲染）                          */
/* -------------------------------------------------------------------------- */

/**
 * 将 bodyPrimary 和 bodySecondary 合并成单段返回
 */
export function joinBody(copy: DiagnosisCopy, separator = '\n\n'): string {
  return [...copy.bodyPrimary, ...copy.bodySecondary].join(separator)
}
