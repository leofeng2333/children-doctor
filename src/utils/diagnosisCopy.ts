/**
 * AI 预测面型诊断文案
 *
 * 来源：`正畸AI面容预测诊断文案 - 9.6.docx`
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
 *   0 NORMAL              -> 正常
 *   1 ASYMMETRY           -> 偏𬌗
 *   2 ANTERIOR_CROSSBITE  -> 反𬌗
 *   3 OPEN_BITE           -> 开𬌗
 *   4 GUMMY_SMILE         -> 露龈笑
 *   5 UPPER_PROTRUSION    -> 前突
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
  title: '正常',
  opening: '你好棒，颌面发育正常！',
  bodyPrimary: [
    '温馨提醒：线上评估仅供参考，牙齿和面型会随着成长发生变化，建议每6个月做一次口腔检查，持续关注颌面发育情况。',
  ],
  bodySecondary: [],
  careTips:
    '坚持每天认真刷牙，使用牙线清洁牙缝，保持良好的口腔卫生习惯。',
  habitNote: '',
}

const CROWDING: DiagnosisCopy = {
  title: '牙列拥挤',
  opening: '根据分析评估，你可能存在牙列拥挤的情况哦！',
  bodyPrimary: [
    '牙列拥挤容易造成牙齿清洁不到位，滋生蛀牙、牙结石，还会影响牙齿整齐度、面部美观，严重时还会干扰正常咬合。替牙期（6~12岁）是黄金干预时机，建议尽早咨询专业正畸医生，优先通过早期干预扩弓排齐，尽量避免后期拔牙矫正。',
  ],
  bodySecondary: [],
  careTips:
    '日常多吃玉米、苹果、坚果等偏硬食物，充分咀嚼促进颌骨正常发育，给牙齿足够生长空间；认真刷牙，别忘了用牙线或冲牙器清理拥挤的牙缝哦。',
  habitNote: '',
}

const SPACING: DiagnosisCopy = {
  title: '牙列稀疏',
  opening: '根据分析评估，你可能存在牙列稀疏的情况哦！',
  bodyPrimary: [
    '牙缝过大容易卡住食物残渣，引发蛀牙、牙周问题，牙齿稳定性变差，还可能伴随咬合异常，影响面部发育。建议定期带宝贝去专业口腔医院检查咬合关系、牙齿松动情况，必要时早期矫治关闭缝隙，引导牙齿正常排列。',
    '如果门牙缝隙在换牙期暂时出现，多数情况下会自己长好，您可以暂时保持观察。',
  ],
  bodySecondary: [],
  careTips:
    '及时排查咬嘴唇、吮手指、吐舌头等不良口腔习惯；学会正确吞咽（舌尖顶住上颚，而不是伸到牙齿之间）。',
  habitNote: '',
}

const ANTERIOR_PROTRUSION: DiagnosisCopy = {
  title: '前突',
  opening: '根据分析评估，你可能存在牙齿前突（龅牙）的情况哦！',
  bodyPrimary: [
    '这种情况大多是由于宝贝口呼吸、咬下唇、吮指及不当喂养习惯或遗传因素引发的，容易导致嘴唇闭合不全，影响面部美观和容貌自信，长此以往还会加重咬合紊乱。',
    '建议尽早带宝贝就医面诊，通过早期矫治内收前牙，改善面型，避免成年后骨骼定型矫正难度加大。',
  ],
  bodySecondary: [],
  careTips: '纠正张口呼吸、咬唇、咬手指等不良习惯；及时排查并治疗鼻炎、腺样体肥大等问题。',
  habitNote: '',
}

const ANTERIOR_CROSSBITE: DiagnosisCopy = {
  title: '反𬌗',
  opening: '根据分析评估，你可能存在反𬌗（地包天）的情况哦！',
  bodyPrimary: [
    '反𬌗（地包天）是指下牙包住上牙，长期不加干预会导致下巴前伸、面中部凹陷，形成"月牙脸"，还会损伤牙齿、颞下颌关节，严重时会影响面部骨骼发育。',
    '此类情况不用等到宝贝换完牙，越早干预越好！3~6岁是"地包天"的黄金干预期，该阶段孩子的颌骨还没"定型"，通过活动矫治器或功能矫治器，通常3~6个月就能把下巴"拉"回来，面型恢复效果较好。',
    '等到骨骼发育定型后，矫正难度增大，成年后甚至需要通过正颌手术才能解决。',
  ],
  bodySecondary: [],
  careTips: '排查扁桃体肥大、腺样体肥大、蛀牙疼痛、喂奶姿势不当等诱因；纠正咬上唇、下颌前伸等不良习惯。',
  habitNote: '',
}

const OPEN_BITE: DiagnosisCopy = {
  title: '开𬌗',
  opening: '根据分析评估，你可能存在开𬌗的情况哦！',
  bodyPrimary: [
    '开𬌗是指上下牙齿无法正常咬合对齐，影响咀嚼、发音，长期会导致面部发育异常，还可能伴随颞下颌关节问题。建议尽早找正畸医生面诊，检查关节是否有异常、张嘴是否有弹响或者疼痛，早期干预关闭咬合。',
  ],
  bodySecondary: [],
  careTips:
    '坚决戒掉宝贝咬手指、咬指甲、咬笔头等不良习惯；练习用舌头弹上颚（"哒哒哒"的声音），帮助舌头归位；双侧均衡咀嚼，避免单侧受力。',
  habitNote: '',
}

const GUMMY_SMILE: DiagnosisCopy = {
  title: '露龈笑',
  opening: '根据分析评估，你可能存在露龈笑的情况哦！',
  bodyPrimary: [
    '露龈笑是指孩子微笑或大笑时，牙龈会明显外露。这种情况通常是由于上唇过短或上唇肌肉力量过强、门牙萌出不足或牙龈增生、上颌骨发育过度等原因导致的。',
    '儿童轻度露龈笑属于发育阶段正常现象，无需过度担心。若大笑时牙龈外露较多，或是到了9~10岁仍然明显，应及时到正畸科面诊，排查颌骨、唇部功能问题。',
  ],
  bodySecondary: [],
  careTips: '纠正口呼吸、吮吸手指等不良口腔习惯；在家进行唇部肌肉练习。',
  habitNote: '',
}

/** 骨性版本：上颌前突/下颌后缩（对应 docx ⑧） */
const UPPER_PROTRUSION_SKELETAL: DiagnosisCopy = {
  title: '上颌前突/下颌后缩',
  opening: '根据分析评估，你可能存在上颌前突/下颌后缩的情况哦！',
  bodyPrimary: [
    '上颌前突/下颌后缩通常是由宝贝长期口呼吸、咬唇、吮指、腺样体问题导致的，此类问题会让脸型悄悄变成凸嘴、下巴又短又缩。更麻烦的是，它还会影响孩子的呼吸和睡眠，白天注意力不集中，甚至耽误生长发育。',
    '建议尽早面诊检查咬合与骨骼发育，通过功能矫治引导下颌正常生长，抑制上颌过度前突，改善面型。',
  ],
  bodySecondary: [],
  careTips:
    '第一时间纠正张口呼吸、咬唇、吮指等习惯；排查并及时治疗鼻炎、腺样体问题；吃饭时坚持双侧均衡咀嚼，适当吃偏硬食物，促进下颌发育。',
  habitNote: '对应不良口腔习惯：张口呼吸',
}

const ASYMMETRY: DiagnosisCopy = {
  title: '偏𬌗',
  opening: '根据分析评估，你可能存在偏颌/大小脸的情况哦！',
  bodyPrimary: [
    '偏𬌗/大小脸可能与偏侧咀嚼、咬合干扰、牙齿疾病、颌骨发育等多种因素有关，长期会加重面部不对称，损伤颞下颌关节。若是由于蛀牙或牙痛导致偏侧咀嚼，一定要尽早治疗；如果治疗后仍然偏斜，则需要到正畸科调整咬合，防止面部对称的问题持续加重。',
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
  opening: '你有不良口腔习惯哦！',
  bodyPrimary: [
    '如果长期不改正不良口腔习惯，可能会导致下巴前伸、面中部凹陷，形成"月牙脸"，变成"地包天"，不仅影响容貌美观，还会损伤牙齿、颞下颌关节，严重时还会影响面部骨骼发育哦！',
  ],
  bodySecondary: [
    '家长可以这样做：',
    '主动纠正——引导孩子有意识地自我控制，同时留意孩子容易出现这些动作的场景，通过转移注意力或轻声提醒，针对性中断习惯。',
    '专业干预——如果宝贝自己改不掉不良习惯，可以请儿童口腔科或正畸医生评估，进行口周肌功能训练，或佩戴简单矫治器帮助破除习惯。',
    '小提醒：多数不良口腔习惯在7岁前通过行为引导和简单干预即可纠正，牙齿和颌骨仍有很强的自我调整能力。越早纠正习惯，越能避免发展为真正的"地包天"！',
  ],
  careTips: '',
  habitNote: '对应不良口腔习惯：吮唇、下颌前伸',
}

/** 龅牙/开𬌗诱因：吮指、吮颊、啃异物、吐舌、舔牙齿 */
const HABIT_PROTRUSION: DiagnosisCopy = {
  title: '不良口腔习惯——吮指、吮颊、啃异物、吐舌、舔牙齿',
  opening: '你有不良口腔习惯哦！',
  bodyPrimary: [
    '如果长期不改正不良口腔习惯，可能会导致上前牙向外龅出（龅牙）、前牙咬不上（开𬌗），不仅影响宝贝的容貌美观，还会导致发音不清、咀嚼效率下降。',
  ],
  bodySecondary: [
    '家长可以这样做：',
    '主动纠正——引导孩子有意识地自我控制，同时留意孩子容易出现这些动作的场景，通过转移注意力或轻声提醒，针对性中断习惯。',
    '专业干预——如果自己改不掉不良习惯，可以请儿童口腔科或正畸医生评估，进行肌功能训练或佩戴矫正器，物理阻断不良习惯。',
    '小提醒：多数不良习惯在7岁前纠正，牙齿和脸型还有很强的自我调整能力。越早纠正，效果越好！',
  ],
  careTips: '',
  habitNote: '对应不良口腔习惯：吮指、吮颊、啃异物（如笔、被角、筷子）、吐舌、舔牙齿',
}

/** 上颌前突/下颌后缩诱因：张口呼吸 */
const HABIT_BREATH: DiagnosisCopy = {
  title: '不良口腔习惯——张口呼吸',
  opening: '你有不良口腔习惯哦！',
  bodyPrimary: [
    '如果长期不改正不良口腔习惯，可能会导致宝贝上颌骨过度前突、下巴后缩，不仅影响容貌美观，还会降低睡眠质量，影响生长激素的夜间分泌，进而影响身高、体重等全身生长发育。',
  ],
  bodySecondary: [
    '家长可以这样做：',
    '去耳鼻喉科排查鼻炎、腺样体肥大等病因，并进行针对性治疗；在确保鼻腔通气问题已解决的前提下，请儿童口腔科或正畸医生评估，进行唇肌功能训练或佩戴功能矫治器，引导鼻呼吸；若口呼吸习惯已经导致了牙弓狭窄或颌骨发育异常，则需佩戴矫治器纠正骨骼畸形。',
    '小提醒：目前宝贝尚未出现明显的颌面畸形，此时是纠正口呼吸习惯的黄金窗口期。建议先到耳鼻喉科解决鼻塞问题，再进行正畸干预。越早纠正习惯，越能避免宝贝发展为真正的骨骼畸形！',
  ],
  careTips: '',
  habitNote: '对应不良口腔习惯：张口呼吸',
}

/** 大小脸、偏𬌗诱因：偏侧咀嚼、托腮 */
const HABIT_ASYMMETRY: DiagnosisCopy = {
  title: '不良口腔习惯——偏侧咀嚼、托腮',
  opening: '你有不良口腔习惯哦！',
  bodyPrimary: [
    '如果长期不改正不良口腔习惯，可能会导致面部不对称（大小脸）、下巴偏斜、牙齿中线不齐，笑起来嘴巴歪向一侧。不仅宝贝的外貌受影响，还会导致颞下颌关节紊乱、双侧牙齿磨损不均匀。',
  ],
  bodySecondary: [
    '家长可以这样做：',
    '检查孩子是否存在单侧蛀牙、缺牙或牙齿疼痛，先治疗牙齿疾病，解除根本原因；引导孩子有意识地用两侧后牙轮流咀嚼，纠正托腮、歪头写作业的习惯。',
    '专业干预——如果自己改不掉不良习惯，可以请儿童口腔科或正畸医生评估，进行肌功能训练，或佩戴简单矫治器辅助纠正偏侧咀嚼和托腮习惯。',
    '小提醒：目前孩子尚未形成明显的偏𬌗或大小脸，此时是纠正不良习惯的黄金窗口期。越早纠正习惯，越能避免发展为真正的"大小脸"或"下巴偏斜"。',
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
    return DIAGNOSIS_COPY_MAP[DiagnosisCode.SPACING]
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
      '根据分析评估，你可能有牙列拥挤/牙列稀疏/前突/反𬌗/开𬌗/露龈笑/上颌前突/下颌后缩/偏𬌗的问题，请爸爸妈妈尽早带你去医院详细检查哦！',
  },
  BAD_HABIT: {
    title: '有不良口腔习惯',
    content:
      '你有不良口腔习惯，不纠正可能会有前突/反𬌗/开𬌗/上颌前突/下颌后缩/偏𬌗的问题，请爸爸妈妈尽早带你去医院详细检查哦！',
  },
}

/* -------------------------------------------------------------------------- */
/* 页面级文案（集中维护，便于一处替换）                                          */
/* -------------------------------------------------------------------------- */

/**
 * docx 中每条分类的 careTips 小标题都使用相同措辞"日常护理小贴士："，
 * 集中维护避免各处硬编码导致措辞漂移。
 *
 * title —— 详情页顶部标题的三段式文案（按 DetailAnalysisFailed 的 stage 状态机切）。
 *   - static: 首屏（坏面容图）时的标题，{trouble} 在渲染时被 diagnosisCopy.title
 *     替换（"偏𬌗"/"反𬌗"/"牙列拥挤"等）。占位符用花括号包裹而非 ${} 模板字符串,
 *     是为了让"裸字符串"在 copy 里一眼可读,不需要找反引号和 ${}。
 *   - preview: 看好面容图（中间过渡态）时的标题,引导用户关注并解决问题。
 *   - swiperGood: 进入 swiper 且停在第 0 页（矫正后好面容）的标题。
 *   - swiperBad: 进入 swiper 且停在第 1 页（当前坏面容）的标题。
 *
 * 改文案只需要改这一个对象,无需翻模板。注意 title 是 `as const`,
 * 模板里用 .replace('{trouble}', ...) 替换 —— 修改后请保留 {trouble} 占位符
 * 或同步去掉 DetailAnalysisTitle 里的 .replace 调用。
 */
export const DETAIL_PAGE_COPY = {
  /** "日常护理小贴士：" 前缀（与 docx 中每条分类的小标题一致） */
  careTipsPrefix: '日常护理小贴士：',

  /**
   * 详情页顶部标题文案 —— 按 (branch) × (stage × swiperIndex) 两维切换。
   *
   * 横向维度 branch（区分不健康子分支）：
   *   - 'default'：默认分支，D 项 categoryCode 1~7（识别出牙颌面问题）
   *   - 'habit'  ：坏习惯分支，H 项 categoryCode=0 但问卷触发了坏习惯
   *                （面型尚未畸形,但已有导致畸形的不良习惯）
   *
   * 纵向维度 stage × swiperIndex —— 两个 branch 内部的结构完全一致：
   *   - static         ：首屏看坏面容图
   *   - preview        ：看好面容图（中间过渡态）
   *   - swiperGood     ：stage='swiper' 且 swiperIndex===0（矫正后好面容）
   *   - swiperBad      ：stage='swiper' 且 swiperIndex===1（当前坏面容）
   *
   * 同台 stage 的两条文案仅是表达侧重不同（牙颌面问题 vs. 不良习惯诱因），
   * 阶段切换时机保持不变（都受 DetailAnalysisFailed 三段式状态机驱动）。
   *
   * 占位符 {trouble}：会被 DetailAnalysisTitle 的 trouble prop 替换,
   * 由 diagnosisCopy.title 充当（默认分支=具体分类名,坏习惯分支=习惯名）。
   * 不希望带占位符的 branch / stage 可直接写死一句话,
   * 模板侧 split('{trouble}') 找不到占位符时等价于原文,不需要特殊处理。
   *
   * 添加新 branch 步骤：
   *   1. 在此对象里加一个同结构分支(4 段文案 + 同名 stage 字段)
   *   2. 在 DetailAnalysisTitle.vue 的 TitleBranch union 里加一个字面量
   *   3. 在 DetailAnalysisView.vue 算出对应分支判断并把字面量传过来
   */
  title: {
    /** 默认分支 —— 非坏习惯(D 项 categoryCode 1~7) */
    default: {
      /** stage === 'static'：首屏看坏面容图。{trouble} 会被 diagnosisCopy.title 替换 */
      static: '根据预测，你可能存在{trouble}的情况哦！',
      /** stage === 'preview'：看好面容图（中间过渡态） */
      preview: '亲爱的宝贝，你能关注到并解决这个问题吗？',
      /** stage === 'swiper' && swiperIndex === 0：矫正后好面容 */
      swiperGood: '关注口腔问题，及时治疗，来看看长大后的样子吧！',
      /** stage === 'swiper' && swiperIndex === 1：当前坏面容 */
      swiperBad: '根据预测，你可能存在{trouble}的情况哦！',
    },

    /**
     * 坏习惯分支 —— H 项（categoryCode=0 + badHabits 非空）
     *
     * ⚠️ TODO(运营/产品)：下面 4 段文案是占位,请根据实际场景替换。
     * 当前默认走 DetailAnalysisFailed.effectiveCopy 的提示语境
     * （家长教育向、引导行为纠正），但具体话术需要业务定。
     * 占位符 {trouble} 可保留,会被传入诊断习惯名
     * （例如 "不良口腔习惯——吮唇、下颌前伸"）替换;
     * 也可以直接写死一句完整话术,不必使用占位符。
     */
    habit: {
      static: '根据预测，你有不良口腔习惯哦',
      preview: '亲爱的宝贝，你能关注到并解决这个问题吗？',
      swiperGood: '关注口腔问题，及时治疗，来看看长大后的样子吧！',
      swiperBad: '根据预测，你有不良口腔习惯哦',
    },
  },
} as const

/* -------------------------------------------------------------------------- */
/* 通用：把多行 body 合并成单段（便于部分页面直接渲染）                          */
/* -------------------------------------------------------------------------- */

/**
 * 将 bodyPrimary 和 bodySecondary 合并成单段返回
 */
export function joinBody(copy: DiagnosisCopy, separator = '\n\n'): string {
  return [...copy.bodyPrimary, ...copy.bodySecondary].join(separator)
}
