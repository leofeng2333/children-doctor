export default {
  plugins: {
    'postcss-px-to-viewport': {
      unitToConvert: 'px', // 要转换的单位
      viewportWidth: 375, // 设计稿宽度（移动端）
      unitPrecision: 5, // 转换后的精度
      propList: ['*'], // 要转换的 CSS 属性列表，* 表示全部
      viewportUnit: 'vw', // 转换后的单位
      fontViewportUnit: 'vw', // 字体转换后的单位
      selectorBlackList: [], // 不进行转换的选择器
      minPixelValue: 1, // 最小转换值
      mediaQuery: false, // 是否转换媒体查询中的 px
      exclude: [/node_modules/, /exclude/], // 排除的文件（正则匹配）
      landscape: false // 是否转换横屏状态
    }
  }
}
