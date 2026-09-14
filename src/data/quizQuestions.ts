/**
 * Quiz 题目数据
 *
 * 数据来源: 趣味问答8.28.docx
 *
 * 解析规则:
 *   - 文档中加粗的 cell 视为题面
 *   - 紧随其后的 4 个非加粗 cell 视为 4 个选项 (A/B/C/D 顺序)
 *   - Word 高亮 (highlight val="yellow") 视为正确答案
 *
 * 配图来源: src/assets/images/儿童科普问答插图/
 *   - NN-q.png: 第N个问题的总览图（题干大插图）
 *   - NN-a-A/B/C/D.png: 第N个问题的选项A/B/C/D图片（选项小插图）
 */

/**
 * 图片资源别名解析 —— 把 '@/assets/.../xx.png' 字面量转成 vite 处理后的 URL。
 *
 * 为什么需要：data 里 image 字段写的是 alias 路径(纯字符串),在模板里
 *   <img :src="opt.image" />
 * 这种动态绑定场景下,vite 不会像编译时 <img src="..."> 那样解析别名,
 * 浏览器按字面量请求 '@/assets/...' 会 404。
 *
 * 解决：用 import.meta.glob('eager') 把目录下所有 png 在 build 时一次性
 * 收集成 {绝对路径: vite URL} 映射,然后把 data 里的字符串别名转为 vite URL。
 *
 * 这种方式优于每个图片都手写 import —— 17+ 个 import 会污染文件头部,
 * glob 一次性收集,代码可读性更好。
 */
const rawImages = import.meta.glob(
  '@/assets/images/儿童科普问答插图/*.png',
  { eager: true, query: '?url', import: 'default' },
) as Record<string, string>

const resolveImg = (aliasPath: string): string => {
  // aliasPath: '@/assets/images/儿童科普问答插图/01-q.png'
  // glob key: '/src/assets/images/儿童科普问答插图/01-q.png'
  // —— '@/' 在 vite 里就是 /src 的别名,所以把 '@' 替换成 '/src' 就能命中 map
  const abs = aliasPath.startsWith('@/') ? aliasPath.replace(/^@\//, '/src/') : aliasPath
  return rawImages[abs] ?? aliasPath
}

export interface QuizOption {
  label: string
  text: string
  /** 选项配图, vite 解析后的 URL(或别名原值,仅在图片未匹配时) */
  image?: string
}

export interface QuizQuestion {
  question: string
  options: QuizOption[]
  /** 正确答案 label (A/B/C/D) */
  answer: string
  /** 题干大插图, vite 解析后的 URL */
  image?: string
}

/**
 * 字面量数组 —— 不导出。资源路径以 '@/' 别名形式存放,便于人类阅读和编辑器跳转。
 * 真正 export 出去的是下面经过 resolveImg 预解析的版本。
 */
const rawQuestions: QuizQuestion[] = [
  {
    "question": "乳牙一共有多少颗？",
    "image": "@/assets/images/儿童科普问答插图/01-q.png",
    "options": [
      { "label": "A", "text": "18颗" },
      { "label": "B", "text": "20颗" },
      { "label": "C", "text": "24颗" },
      { "label": "D", "text": "28颗" }
    ],
    "answer": "B"
  },
  {
    "question": "人类通常拥有多少颗恒牙？",
    "image": "@/assets/images/儿童科普问答插图/02-q.png",
    "options": [
      { "label": "A", "text": "20颗" },
      { "label": "B", "text": "24颗" },
      { "label": "C", "text": "28颗" },
      { "label": "D", "text": "32颗" }
    ],
    "answer": "D"
  },
  {
    "question": "每天至少要刷几次牙？",
    "image": "@/assets/images/儿童科普问答插图/03-q.png",
    "options": [
      { "label": "A", "text": "0次" },
      { "label": "B", "text": "1次" },
      { "label": "C", "text": "2次" },
      { "label": "D", "text": "3次" }
    ],
    "answer": "C"
  },
  {
    "question": "哪种牙膏成分对预防蛀牙最有效？",
    "options": [
      { "label": "A", "text": "薄荷", "image": "@/assets/images/儿童科普问答插图/04-a-A.png" },
      { "label": "B", "text": "钙", "image": "@/assets/images/儿童科普问答插图/04-a-B.png" },
      { "label": "C", "text": "氟", "image": "@/assets/images/儿童科普问答插图/04-a-C.png" },
      { "label": "D", "text": "维生素", "image": "@/assets/images/儿童科普问答插图/04-a-D.png" }
    ],
    "answer": "C"
  },
  {
    "question": "哪种食物最容易导致蛀牙？",
    "options": [
      { "label": "A", "text": "糖果", "image": "@/assets/images/儿童科普问答插图/05-a-A.png" },
      { "label": "B", "text": "苹果", "image": "@/assets/images/儿童科普问答插图/05-a-B.png" },
      { "label": "C", "text": "胡萝卜", "image": "@/assets/images/儿童科普问答插图/05-a-C.png" },
      { "label": "D", "text": "牛肉", "image": "@/assets/images/儿童科普问答插图/05-a-D.png" }
    ],
    "answer": "A"
  },
  {
    "question": "为什么小朋友也要使用牙线？",
    "image": "@/assets/images/儿童科普问答插图/06-q.png",
    "options": [
      { "label": "A", "text": "让牙齿更白" },
      { "label": "B", "text": "代替刷牙" },
      { "label": "C", "text": "清理牙缝残渣" },
      { "label": "D", "text": "锻炼手指灵活度" }
    ],
    "answer": "C"
  },
  {
    "question": "哪个习惯对牙齿不好？",
    "options": [
      { "label": "A", "text": "饭后漱口", "image": "@/assets/images/儿童科普问答插图/07-a-A.png" },
      { "label": "B", "text": "用吸管喝饮料", "image": "@/assets/images/儿童科普问答插图/07-a-B.png" },
      { "label": "C", "text": "睡前喝奶不刷牙", "image": "@/assets/images/儿童科普问答插图/07-a-C.png" },
      { "label": "D", "text": "嚼冰块", "image": "@/assets/images/儿童科普问答插图/07-a-D.png" }
    ],
    "answer": "D"
  },
  {
    "question": "小朋友摔跤把牙齿摔断了，哪种做法是错误的？",
    "options": [
      { "label": "A", "text": "捡回摔掉的牙", "image": "@/assets/images/儿童科普问答插图/08-a-A.png" },
      { "label": "B", "text": "立刻去医院", "image": "@/assets/images/儿童科普问答插图/08-a-B.png" },
      { "label": "C", "text": "纸巾包好断牙", "image": "@/assets/images/儿童科普问答插图/08-a-C.png" },
      { "label": "D", "text": "纱布按压止血", "image": "@/assets/images/儿童科普问答插图/08-a-D.png" }
    ],
    "answer": "C"
  },
  {
    "question": "低碳生活中的 \"碳\" 主要指的是什么？",
    "options": [
      { "label": "A", "text": "煤炭、木炭", "image": "@/assets/images/儿童科普问答插图/09-a-A.png" },
      { "label": "B", "text": "黑色炭粉", "image": "@/assets/images/儿童科普问答插图/09-a-B.png" },
      { "label": "C", "text": "碳水化合物", "image": "@/assets/images/儿童科普问答插图/09-a-C.png" },
      { "label": "D", "text": "二氧化碳", "image": "@/assets/images/儿童科普问答插图/09-a-D.png" }
    ],
    "answer": "D"
  },
  {
    "question": "下列哪种垃圾属于有害垃圾？",
    "options": [
      { "label": "A", "text": "废电池", "image": "@/assets/images/儿童科普问答插图/10-a-A.png" },
      { "label": "B", "text": "香蕉皮", "image": "@/assets/images/儿童科普问答插图/10-a-B.png" },
      { "label": "C", "text": "玻璃瓶", "image": "@/assets/images/儿童科普问答插图/10-a-C.png" },
      { "label": "D", "text": "旧衣服", "image": "@/assets/images/儿童科普问答插图/10-a-D.png" }
    ],
    "answer": "A"
  },
  {
    "question": "下列哪种行为属于\"绿色低碳出行\"？",
    "options": [
      { "label": "A", "text": "骑摩托车", "image": "@/assets/images/儿童科普问答插图/11-a-A.png" },
      { "label": "B", "text": "天天打车", "image": "@/assets/images/儿童科普问答插图/11-a-B.png" },
      { "label": "C", "text": "骑自行车", "image": "@/assets/images/儿童科普问答插图/11-a-C.png" },
      { "label": "D", "text": "开私家车", "image": "@/assets/images/儿童科普问答插图/11-a-D.png" }
    ],
    "answer": "C"
  },
  {
    "question": "森林被称为\"地球之肺\"，是因为它能大量吸收什么气体？",
    "image": "@/assets/images/儿童科普问答插图/12-q.png",
    "options": [
      { "label": "A", "text": "氧气" },
      { "label": "B", "text": "二氧化碳" },
      { "label": "C", "text": "氮气" },
      { "label": "D", "text": "一氧化碳" }
    ],
    "answer": "B"
  },
  {
    "question": "哪种做法可以减少空气污染？",
    "options": [
      { "label": "A", "text": "燃放烟花爆竹", "image": "@/assets/images/儿童科普问答插图/13-a-A.png" },
      { "label": "B", "text": "植树造林", "image": "@/assets/images/儿童科普问答插图/13-a-B.png" },
      { "label": "C", "text": "工厂烟囱排烟", "image": "@/assets/images/儿童科普问答插图/13-a-C.png" },
      { "label": "D", "text": "露天焚烧垃圾", "image": "@/assets/images/儿童科普问答插图/13-a-D.png" }
    ],
    "answer": "B"
  },
  {
    "question": "不穿的旧衣服，如何处理比较绿色环保？",
    "options": [
      { "label": "A", "text": "捐赠回收", "image": "@/assets/images/儿童科普问答插图/14-a-A.png" },
      { "label": "B", "text": "随手丢弃", "image": "@/assets/images/儿童科普问答插图/14-a-B.png" },
      { "label": "C", "text": "焚烧处理", "image": "@/assets/images/儿童科普问答插图/14-a-C.png" },
      { "label": "D", "text": "埋进土里", "image": "@/assets/images/儿童科普问答插图/14-a-D.png" }
    ],
    "answer": "A"
  },
  {
    "question": "哪种做法可以减少食物浪费？",
    "options": [
      { "label": "A", "text": "点餐越多越好", "image": "@/assets/images/儿童科普问答插图/15-a-A.png" },
      { "label": "B", "text": "不爱吃直接丢掉", "image": "@/assets/images/儿童科普问答插图/15-a-B.png" },
      { "label": "C", "text": "按需点餐，吃不完打包", "image": "@/assets/images/儿童科普问答插图/15-a-C.png" },
      { "label": "D", "text": "随意倾倒剩饭剩菜", "image": "@/assets/images/儿童科普问答插图/15-a-D.png" }
    ],
    "answer": "C"
  },
  {
    "question": "\"碳足迹\"是什么意思？",
    "image": "@/assets/images/儿童科普问答插图/16-q.png",
    "options": [
      { "label": "A", "text": "煤炭燃烧留下的痕迹" },
      { "label": "B", "text": "走路留下的脚印" },
      { "label": "C", "text": "植树造林留下的痕迹" },
      { "label": "D", "text": "日常活动的碳排放总量" }
    ],
    "answer": "D"
  },
  {
    "question": "生态环境遭到破坏的后果，不包括哪一项？",
    "options": [
      { "label": "A", "text": "气候异常", "image": "@/assets/images/儿童科普问答插图/17-a-A.png" },
      { "label": "B", "text": "物种减少", "image": "@/assets/images/儿童科普问答插图/17-a-B.png" },
      { "label": "C", "text": "水质变差", "image": "@/assets/images/儿童科普问答插图/17-a-C.png" },
      { "label": "D", "text": "空气质量变好", "image": "@/assets/images/儿童科普问答插图/17-a-D.png" }
    ],
    "answer": "D"
  }
]

/**
 * 导出前预解析 —— 把 data 里的 '@/assets/...' 别名转成 vite URL,
 * 模板里 :src="opt.image" 即可直接渲染,无须再过 resolveImg。
 *
 * 为什么放在 export 时统一处理而不是每个调用方自己 resolve:
 *   - 调用方(目前只有 QuizView.vue)不必关心资源解析细节
 *   - data 文件是资源的"单一真实源",解析逻辑放在这里不会分散到各组件
 *   - 后续如果新增题目只管在原数组里加条目,无须改任何工具代码
 */
export const quizQuestions: QuizQuestion[] = rawQuestions.map((q) => ({
  ...q,
  image: q.image ? resolveImg(q.image) : q.image,
  options: q.options.map((o) => ({
    ...o,
    image: o.image ? resolveImg(o.image) : o.image,
  })),
}))
