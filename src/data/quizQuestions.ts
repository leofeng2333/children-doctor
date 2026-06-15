/**
 * Quiz 题目数据
 *
 * 数据来源: 儿童问答题目(1)(1).docx
 *
 * 解析规则:
 *   - 段落以 "?" / "？" 结尾视为题目
 *   - 紧随其后的 4 段非空文字视为 4 个选项 (A/B/C/D 顺序)
 *   - Word 高亮 (highlight) 视为正确答案
 *   - 同一段落或紧随选项的图片标记视为对应选项配图
 *
 * 题目图片位于 public/quiz-images/, image 字段以 "/quiz-images/xxx" 形式存储
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
    "question": "地球上牙齿最多的动物是什么？",
    "options": [
      {
        "label": "A",
        "text": "鲨鱼",
        "image": "/quiz-images/image1.jpeg"
      },
      {
        "label": "B",
        "text": "蜗牛",
        "image": "/quiz-images/image2.png"
      },
      {
        "label": "C",
        "text": "海豚",
        "image": "/quiz-images/image3.jpeg"
      },
      {
        "label": "D",
        "text": "鳄鱼",
        "image": "/quiz-images/image4.jpeg"
      }
    ],
    "answer": "B"
  },
  {
    "question": "哪种动物没有牙齿？",
    "options": [
      {
        "label": "A",
        "text": "鸡",
        "image": "/quiz-images/image5.png"
      },
      {
        "label": "B",
        "text": "小丑鱼",
        "image": "/quiz-images/image6.jpeg"
      },
      {
        "label": "C",
        "text": "熊猫",
        "image": "/quiz-images/image7.png"
      },
      {
        "label": "D",
        "text": "斑马",
        "image": "/quiz-images/image8.jpeg"
      }
    ],
    "answer": "A"
  },
  {
    "question": "哪种动物的牙齿长得最快？",
    "options": [
      {
        "label": "A",
        "text": "老鼠",
        "image": "/quiz-images/image9.png"
      },
      {
        "label": "B",
        "text": "老虎",
        "image": "/quiz-images/image10.png"
      },
      {
        "label": "C",
        "text": "长颈鹿",
        "image": "/quiz-images/image11.png"
      },
      {
        "label": "D",
        "text": "河马",
        "image": "/quiz-images/image12.png"
      }
    ],
    "answer": "A"
  },
  {
    "question": "3-6岁的小朋友通常一共有几颗乳牙？",
    "options": [
      {
        "label": "A",
        "text": "18颗"
      },
      {
        "label": "B",
        "text": "20颗"
      },
      {
        "label": "C",
        "text": "24颗"
      },
      {
        "label": "D",
        "text": "28颗"
      }
    ],
    "answer": "B"
  },
  {
    "question": "成年人通常一共有几颗牙齿（不含智齿）？",
    "options": [
      {
        "label": "A",
        "text": "20颗"
      },
      {
        "label": "B",
        "text": "24颗"
      },
      {
        "label": "C",
        "text": "28颗"
      },
      {
        "label": "D",
        "text": "32颗"
      }
    ],
    "answer": "C"
  },
  {
    "question": "每天至少要刷几次牙？",
    "options": [
      {
        "label": "A",
        "text": "0次"
      },
      {
        "label": "B",
        "text": "1次"
      },
      {
        "label": "C",
        "text": "2次"
      },
      {
        "label": "D",
        "text": "3次"
      }
    ],
    "answer": "C"
  },
  {
    "question": "哪种牙膏成分对预防蛀牙最有效？",
    "options": [
      {
        "label": "A",
        "text": "薄荷"
      },
      {
        "label": "B",
        "text": "钙"
      },
      {
        "label": "C",
        "text": "氟"
      },
      {
        "label": "D",
        "text": "维生素"
      }
    ],
    "answer": "C"
  },
  {
    "question": "哪种食物最容易导致蛀牙？",
    "options": [
      {
        "label": "A",
        "text": "糖果",
        "image": "/quiz-images/image13.png"
      },
      {
        "label": "B",
        "text": "苹果",
        "image": "/quiz-images/image14.png"
      },
      {
        "label": "C",
        "text": "胡萝卜",
        "image": "/quiz-images/image15.png"
      },
      {
        "label": "D",
        "text": "牛肉",
        "image": "/quiz-images/image16.png"
      }
    ],
    "answer": "A"
  },
  {
    "question": "为什么小朋友也要使用牙线？",
    "options": [
      {
        "label": "A",
        "text": "让牙齿变得更白"
      },
      {
        "label": "B",
        "text": "代替刷牙"
      },
      {
        "label": "C",
        "text": "清理牙刷刷不到的牙缝"
      },
      {
        "label": "D",
        "text": "锻炼手指灵活度"
      }
    ],
    "answer": "C"
  },
  {
    "question": "哪个习惯对牙齿最不好？",
    "options": [
      {
        "label": "A",
        "text": "饭后漱口"
      },
      {
        "label": "B",
        "text": "用吸管喝饮料"
      },
      {
        "label": "C",
        "text": "晚上喝了牛奶后再刷牙"
      },
      {
        "label": "D",
        "text": "嚼冰块"
      }
    ],
    "answer": "D"
  },
  {
    "question": "摔跤把牙齿摔断了，哪个做法是错误的？",
    "options": [
      {
        "label": "A",
        "text": "把摔断的牙齿捡回来",
        "image": "/quiz-images/image17.png"
      },
      {
        "label": "B",
        "text": "赶紧去医院检查治疗",
        "image": "/quiz-images/image18.png"
      },
      {
        "label": "C",
        "text": "用纸巾把摔断的牙齿包好",
        "image": "/quiz-images/image19.png"
      },
      {
        "label": "D",
        "text": "用干净纱布轻轻按压止血",
        "image": "/quiz-images/image20.png"
      }
    ],
    "answer": "C"
  },
  {
    "question": "下列哪种垃圾属于有害垃圾？",
    "options": [
      {
        "label": "A",
        "text": "废电池",
        "image": "/quiz-images/image21.png"
      },
      {
        "label": "B",
        "text": "香蕉皮",
        "image": "/quiz-images/image22.png"
      },
      {
        "label": "C",
        "text": "玻璃瓶",
        "image": "/quiz-images/image23.png"
      },
      {
        "label": "D",
        "text": "旧衣服",
        "image": "/quiz-images/image24.png"
      }
    ],
    "answer": "A"
  },
  {
    "question": "下列哪种行为属于“绿色低碳出行”？",
    "options": [
      {
        "label": "A",
        "text": "骑摩托车",
        "image": "/quiz-images/image25.png"
      },
      {
        "label": "B",
        "text": "天天打车",
        "image": "/quiz-images/image26.png"
      },
      {
        "label": "C",
        "text": "骑自行车",
        "image": "/quiz-images/image27.png"
      },
      {
        "label": "D",
        "text": "开私家车",
        "image": "/quiz-images/image28.png"
      }
    ],
    "answer": "C"
  },
  {
    "question": "森林被称为“地球之肺”，是因为它们能大量吸收什么气体？",
    "options": [
      {
        "label": "A",
        "text": "氧气"
      },
      {
        "label": "B",
        "text": "二氧化碳"
      },
      {
        "label": "C",
        "text": "氮气"
      },
      {
        "label": "D",
        "text": "一氧化碳"
      }
    ],
    "answer": "B"
  },
  {
    "question": "哪种做法可以减少空气污染？",
    "options": [
      {
        "label": "A",
        "text": "燃放烟花爆竹"
      },
      {
        "label": "B",
        "text": "植树造林"
      },
      {
        "label": "C",
        "text": "工厂烟囱排烟"
      },
      {
        "label": "D",
        "text": "露天焚烧垃圾"
      }
    ],
    "answer": "B"
  },
  {
    "question": "旧衣服不穿了，怎样处理比较绿色环保？",
    "options": [
      {
        "label": "A",
        "text": "捐赠回收"
      },
      {
        "label": "B",
        "text": "随手丢弃"
      },
      {
        "label": "C",
        "text": "焚烧处理"
      },
      {
        "label": "D",
        "text": "埋进土里"
      }
    ],
    "answer": "A"
  },
  {
    "question": "哪种做法可以减少食物浪费？",
    "options": [
      {
        "label": "A",
        "text": "点餐越多越好"
      },
      {
        "label": "B",
        "text": "不爱吃直接丢掉"
      },
      {
        "label": "C",
        "text": "按需点餐，吃不完打包"
      },
      {
        "label": "D",
        "text": "随意倾倒剩饭剩菜"
      }
    ],
    "answer": "C"
  },
  {
    "question": "“碳足迹”是什么意思？",
    "options": [
      {
        "label": "A",
        "text": "煤炭燃烧留下的痕迹"
      },
      {
        "label": "B",
        "text": "走路留下的脚印"
      },
      {
        "label": "C",
        "text": "植树造林留下的痕迹"
      },
      {
        "label": "D",
        "text": "日常活动产生的二氧化碳总量"
      }
    ],
    "answer": "D"
  },
  {
    "question": "生态环境遭到破坏的后果，不包括哪一项？",
    "options": [
      {
        "label": "A",
        "text": "气候异常"
      },
      {
        "label": "B",
        "text": "物种减少"
      },
      {
        "label": "C",
        "text": "水质变差"
      },
      {
        "label": "D",
        "text": "空气质量变好"
      }
    ],
    "answer": "D"
  }
]
