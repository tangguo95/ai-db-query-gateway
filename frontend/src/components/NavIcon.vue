<script setup lang="ts">
defineProps<{ name: string }>()

const iconPaths: Record<string, string[]> = {
  home: [
    'M3 10.5 12 3l9 7.5',
    'M5 9.5V21h14V9.5',
    'M9 21v-6h6v6'
  ],
  database: [
    'M4 5c0-1.1 3.6-2 8-2s8 .9 8 2-3.6 2-8 2-8-.9-8-2Z',
    'M4 5v7c0 1.1 3.6 2 8 2s8-.9 8-2V5',
    'M4 12v7c0 1.1 3.6 2 8 2s8-.9 8-2v-7'
  ],
  terminal: [
    'm5 7 4 4-4 4',
    'M12 15h6'
  ],
  check: [
    'm5 12 4 4L19 6'
  ],
  audit: [
    'M4 5h16v14H4z',
    'M8 9h8',
    'M8 13h5'
  ],
  key: [
    'M6 8h12v8H6z',
    'M9 8V6h6v2',
    'M9 12h6'
  ],
  shield: [
    'm12 3 7 3v5c0 4.8-3 8-7 10-4-2-7-5.2-7-10V6l7-3Z',
    'm9 12 2 2 4-4'
  ],
  sun: [
    'M12 3v2',
    'M12 19v2',
    'm4.2 4.2 1.4-1.4',
    'm18.4 18.4-1.4-1.4',
    'M3 12h2',
    'M19 12h2',
    'm4.2 19.8 1.4 1.4',
    'm18.4 5.6-1.4 1.4',
    'M12 16a4 4 0 1 0 0-8 4 4 0 0 0 0 8Z'
  ],
  moon: [
    'M20.5 14.7A8.5 8.5 0 0 1 9.3 3.5 8.5 8.5 0 1 0 20.5 14.7Z'
  ]
}
</script>

<template>
  <svg class="nav-icon" viewBox="0 0 24 24" aria-hidden="true">
    <path
      v-for="(path, index) in (iconPaths[name] || iconPaths.home)"
      :key="index"
      :d="path"
    />
  </svg>
</template>

<style scoped>
.nav-icon {
  width: 17px;
  height: 17px;
  fill: none;
  stroke: currentColor;
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-width: 1.8;
}
</style>
