<script setup lang="ts">
defineProps<{ title: string; subtitle?: string; status?: string }>()
</script>

<template>
  <header class="record-heading">
    <div>
      <h2>{{ title }}</h2>
      <p v-if="subtitle" class="record-subtitle">{{ subtitle }}</p>
    </div>
    <span v-if="status" class="record-status">{{ status }}</span>
    <slot />
  </header>
</template>
