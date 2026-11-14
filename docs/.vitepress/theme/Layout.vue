<script setup>
import DefaultTheme from 'vitepress/theme'
import MermaidLightbox from './components/MermaidLightbox.vue'
import { useMermaidClickPreview } from './composables/useMermaidClickPreview'
import { ref } from 'vue'

const lightboxRef = ref()

useMermaidClickPreview((svg) => {
  lightboxRef.value?.open(svg)
})
</script>

<template>
  <DefaultTheme.Layout />
  <MermaidLightbox ref="lightboxRef" />
</template>