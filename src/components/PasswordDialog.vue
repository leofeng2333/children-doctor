<script setup lang="ts">
import { ref } from 'vue'

defineProps<{
  visible: boolean
}>()

const emit = defineEmits<{
  close: []
  success: []
}>()

const HARD_CODED_PASSWORD = 'admin123'

const passwordInput = ref('')
const errorMessage = ref('')

const handleConfirm = () => {
  if (passwordInput.value === HARD_CODED_PASSWORD) {
    errorMessage.value = ''
    passwordInput.value = ''
    emit('success')
  } else {
    errorMessage.value = '密码错误，请重试'
  }
}

const handleCancel = () => {
  passwordInput.value = ''
  errorMessage.value = ''
  emit('close')
}

const handleClose = () => {
  passwordInput.value = ''
  errorMessage.value = ''
  emit('close')
}
</script>

<template>
  <Teleport to="body">
    <Transition name="dialog-fade">
      <div v-if="visible" class="dialog-overlay" role="dialog" aria-modal="true">
        <div class="dialog-card">
          <!-- Header -->
          <div class="dialog-header">
            <h2 class="dialog-title">请输入密码</h2>
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
            <input
              v-model="passwordInput"
              class="password-input"
              type="password"
              placeholder="请输入密码"
              autocomplete="off"
              @keydown.enter="handleConfirm"
            />
            <p v-if="errorMessage" class="error-text">{{ errorMessage }}</p>
          </div>

          <!-- Footer -->
          <div class="dialog-footer">
            <button class="btn-cancel" type="button" @click="handleCancel">取消</button>
            <button class="btn-confirm" type="button" @click="handleConfirm">确认</button>
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
  font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
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

.password-input {
  width: 100%;
  height: 76px;
  background: #d9d9d9;
  border: none;
  border-radius: 18px;
  padding: 0 32px;
  font-size: 30px;
  color: #000;
  box-sizing: border-box;
  transition: background-color 0.2s;

  &::placeholder {
    color: #bebebe;
  }

  &:focus {
    outline: none;
    background: #e5e5e5;
  }
}

.error-text {
  font-size: 22px;
  color: #ff4d4f;
  margin: 14px 0 0 0;
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
  background: #ff9900;
  border: none;
  border-radius: 50px;
  font-size: 30px;
  font-weight: 600;
  color: #fff;
  cursor: pointer;
  box-shadow: 0 8px 24px rgba(255, 153, 0, 0.4);
  transition: all 0.2s;

  &:hover {
    background: #e68a00;
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
    transition: transform 0.25s ease, opacity 0.25s ease;
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
