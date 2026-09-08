<script setup lang="ts">
/**
 * 通用确认对话框。
 *
 * 用法：
 *   <ConfirmDialog
 *     :visible="show"
 *     title="确认退出"
 *     message="确定要退出应用吗？"
 *     confirm-text="退出"
 *     cancel-text="取消"
 *     @confirm="onConfirm"
 *     @cancel="onCancel"
 *     @close="onCancel"
 *   />
 *
 * 视觉上沿用 PasswordDialog 的样式（同样的圆角、字号、按钮配色），
 * 这样首页右上角的双击退出对话框看起来和原本的管理员密码框一致。
 */
withDefaults(
  defineProps<{
    visible: boolean
    title?: string
    message?: string
    confirmText?: string
    cancelText?: string
    /** 确认按钮主题色，默认与 PasswordDialog 一致 #ff9900 */
    confirmColor?: string
  }>(),
  {
    title: '确认',
    message: '',
    confirmText: '确认',
    cancelText: '取消',
    confirmColor: '#ff9900',
  },
)

const emit = defineEmits<{
  confirm: []
  cancel: []
  close: []
}>()

const handleConfirm = () => emit('confirm')
const handleCancel = () => emit('cancel')
const handleClose = () => emit('close')
</script>

<template>
  <Teleport to="body">
    <Transition name="dialog-fade">
      <div v-if="visible" class="dialog-overlay" role="dialog" aria-modal="true">
        <div class="dialog-card">
          <!-- Header -->
          <div class="dialog-header">
            <h2 class="dialog-title">{{ title }}</h2>
            <button class="dialog-close" type="button" aria-label="关闭" @click="handleClose">
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none">
                <path
                  d="M18 6L6 18M6 6l12 12"
                  stroke="#BCBCBC"
                  stroke-width="2"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                />
              </svg>
            </button>
          </div>

          <!-- Body -->
          <div class="dialog-body">
            <p class="message">{{ message }}</p>
          </div>

          <!-- Footer -->
          <div class="dialog-footer">
            <button class="btn-cancel" type="button" @click="handleCancel">{{ cancelText }}</button>
            <button
              class="btn-confirm"
              type="button"
              :style="{ background: confirmColor }"
              @click="handleConfirm"
            >
              {{ confirmText }}
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped lang="scss">
.dialog-overlay {
  position: fixed;
  inset: 0;
  z-index: 1000;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
}

.dialog-card {
  width: 100%;
  max-width: 720px;
  background: #ffffff;
  border-radius: 56px;
  padding: 64px;
  box-shadow: 0 16px 48px rgba(0, 0, 0, 0.15);
  box-sizing: border-box;
}

.dialog-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 48px;
}

.dialog-title {
  font-family:
    'Inter',
    -apple-system,
    BlinkMacSystemFont,
    sans-serif;
  font-size: 38px;
  font-weight: 700;
  color: #000;
  margin: 0;
}

.dialog-close {
  width: 56px;
  height: 56px;
  background: transparent;
  border: none;
  cursor: pointer;
  padding: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  transition: background-color 0.2s;

  &:hover {
    background: #f5f5f5;
  }

  &:active {
    background: #ebebeb;
  }
}

.dialog-body {
  margin-bottom: 48px;
}

.message {
  font-family:
    'Inter',
    -apple-system,
    BlinkMacSystemFont,
    sans-serif;
  font-size: 30px;
  line-height: 1.6;
  color: #333;
  margin: 0;
  white-space: pre-line;
}

.dialog-footer {
  display: flex;
  gap: 28px;
}

.btn-cancel {
  flex: 1;
  height: 76px;
  background: transparent;
  border: 2px solid #d9d9d9;
  border-radius: 50px;
  font-size: 30px;
  font-weight: 600;
  color: #000;
  cursor: pointer;
  transition: all 0.2s;

  &:hover {
    border-color: #bebebe;
    background: #fafafa;
  }

  &:active {
    transform: scale(0.98);
  }
}

.btn-confirm {
  flex: 1;
  height: 76px;
  border: none;
  border-radius: 50px;
  font-size: 30px;
  font-weight: 600;
  color: #fff;
  cursor: pointer;
  box-shadow: 0 8px 24px rgba(255, 153, 0, 0.4);
  transition: all 0.2s;

  &:hover {
    filter: brightness(0.95);
  }

  &:active {
    transform: scale(0.98);
    box-shadow: 0 4px 12px rgba(255, 153, 0, 0.3);
  }
}

.dialog-fade-enter-active,
.dialog-fade-leave-active {
  transition: opacity 0.25s ease;

  .dialog-card {
    transition:
      transform 0.25s ease,
      opacity 0.25s ease;
  }
}

.dialog-fade-enter-from,
.dialog-fade-leave-to {
  opacity: 0;

  .dialog-card {
    transform: scale(0.9);
    opacity: 0;
  }
}
</style>
