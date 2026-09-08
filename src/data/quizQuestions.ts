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
 * 新版文档不含配图，因此不再保留 image 字段。
 */

export interface QuizOption {
  label: string
  text: string
  /** 选项配图, 相对站点根路径 (public/) */
  image?: string
}

export interface QuizQuestion {
  question: string
  options: QuizOption[]
  /** 正确答案 label (A/B/C/D) - 此页面仅作耗时用, 不参与判定 */
  answer: string
}

export const quizQuestions: QuizQuestion[] = [
  {
    "question": "乳牙一共有多少颗？",
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
      { "label": "A", "text": "薄荷" },
      { "label": "B", "text": "钙" },
      { "label": "C", "text": "氟" },
      { "label": "D", "text": "维生素" }
    ],
    "answer": "C"
  },
  {
    "question": "哪种食物最容易导致蛀牙？",
    "options": [
      { "label": "A", "text": "糖果" },
      { "label": "B", "text": "苹果" },
      { "label": "C", "text": "胡萝卜" },
      { "label": "D", "text": "牛肉" }
    ],
    "answer": "A"
  },
  {
    "question": "为什么小朋友也要使用牙线？",
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
      { "label": "A", "text": "饭后漱口" },
      { "label": "B", "text": "用吸管喝饮料" },
      { "label": "C", "text": "睡前喝奶不刷牙" },
      { "label": "D", "text": "嚼冰块" }
    ],
    "answer": "D"
  },
  {
    "question": "小朋友摔跤把牙齿摔断了，哪种做法是错误的？",
    "options": [
      { "label": "A", "text": "捡回摔掉的牙" },
      { "label": "B", "text": "立刻去医院" },
      { "label": "C", "text": "纸巾包好断牙" },
      { "label": "D", "text": "纱布按压止血" }
    ],
    "answer": "C"
  },
  {
    "question": "低碳生活中的 \"碳\" 主要指的是什么？",
    "options": [
      { "label": "A", "text": "煤炭、木炭" },
      { "label": "B", "text": "黑色炭粉" },
      { "label": "C", "text": "碳水化合物" },
      { "label": "D", "text": "二氧化碳" }
    ],
    "answer": "D"
  },
  {
    "question": "下列哪种垃圾属于有害垃圾？",
    "options": [
      { "label": "A", "text": "废电池" },
      { "label": "B", "text": "香蕉皮" },
      { "label": "C", "text": "玻璃瓶" },
      { "label": "D", "text": "旧衣服" }
    ],
    "answer": "A"
  },
  {
    "question": "下列哪种行为属于\"绿色低碳出行\"？",
    "options": [
      { "label": "A", "text": "骑摩托车" },
      { "label": "B", "text": "天天打车" },
      { "label": "C", "text": "骑自行车" },
      { "label": "D", "text": "开私家车" }
    ],
    "answer": "C"
  },
  {
    "question": "森林被称为\"地球之肺\"，是因为它能大量吸收什么气体？",
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
      { "label": "A", "text": "燃放烟花爆竹" },
      { "label": "B", "text": "植树造林" },
      { "label": "C", "text": "工厂烟囱排烟" },
      { "label": "D", "text": "露天焚烧垃圾" }
    ],
    "answer": "B"
  },
  {
    "question": "不穿的旧衣服，如何处理比较绿色环保？",
    "options": [
      { "label": "A", "text": "捐赠回收" },
      { "label": "B", "text": "随手丢弃" },
      { "label": "C", "text": "焚烧处理" },
      { "label": "D", "text": "埋进土里" }
    ],
    "answer": "A"
  },
  {
    "question": "哪种做法可以减少食物浪费？",
    "options": [
      { "label": "A", "text": "点餐越多越好" },
      { "label": "B", "text": "不爱吃直接丢掉" },
      { "label": "C", "text": "按需点餐，吃不完打包" },
      { "label": "D", "text": "随意倾倒剩饭剩菜" }
    ],
    "answer": "C"
  },
  {
    "question": "\"碳足迹\"是什么意思？",
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
      { "label": "A", "text": "气候异常" },
      { "label": "B", "text": "物种减少" },
      { "label": "C", "text": "水质变差" },
      { "label": "D", "text": "空气质量变好" }
    ],
    "answer": "D"
  }
]
