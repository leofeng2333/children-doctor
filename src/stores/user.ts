import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useUserStore = defineStore('user', () => {
  const nickname = ref('')
  const phone = ref('')

  const isEmpty = () => !nickname.value && !phone.value
  const hasNickname = () => !!nickname.value
  const hasPhone = () => !!phone.value
  const setNickname = (name: string) => { nickname.value = name }
  const setPhone = (tel: string) => { phone.value = tel }
  const update = (name: string, tel: string) => { nickname.value = name; phone.value = tel }
  const clear = () => { nickname.value = ''; phone.value = '' }

  return { nickname, phone, isEmpty, hasNickname, hasPhone, setNickname, setPhone, update, clear }
})
