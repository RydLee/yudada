<template>
  <div id="basicLayout">
    <a-layout style="height: 100vh">
      <a-layout-header class="header">
        <GlobalHeader />
      </a-layout-header>
      <a-layout-content class="content">
        <router-view />
      </a-layout-content>
      <a-layout-footer class="footer">
        <a>Code by Ryd</a>
      </a-layout-footer>
    </a-layout>
  </div>
</template>
<script setup lang="ts">
import GlobalHeader from "@/components/GlobalHeader.vue";
</script>
<style scoped>
#basicLayout {
}
.header {
  margin-bottom: 16px;
  box-shadow: #eee 1px 1px 5px;
}
.content {
  max-width: 1200px;
  width: 100%;
  margin: 0 auto 28px;
  box-sizing: border-box;
  background: linear-gradient(to right, #fefefe, #fff);
  padding: 20px;
}
.footer {
  background: #efefef;
  padding: 16px;
  text-align: center;
}
</style>
