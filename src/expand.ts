declare global {
  interface Array<T> {
    lastItem: T
  }
}

Object.defineProperty(Array.prototype, 'lastItem', {
  get() {
    return this[this.length - 1]
  },
})
