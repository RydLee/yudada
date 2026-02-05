<template>
  <div class="ai-add-app-page">
    <div class="page-header">
      <h2>AI 创建应用</h2>
    </div>
    <div class="split-container">
      <!-- 左侧：题目预览区 -->
      <div class="preview-section">
        <div class="section-header">
          <span>题目预览</span>
          <a-tag v-if="questions.length > 0" color="blue">
            {{ questions.length }} 道题目
          </a-tag>
        </div>
        <div class="question-list" v-if="questions.length > 0">
          <a-card
            v-for="(question, index) in questions"
            :key="index"
            class="question-card"
            :bordered="false"
          >
            <div class="question-header">
              <span class="question-number">Q{{ index + 1 }}</span>
              <a-tag :color="getQuestionTypeColor(question.questionType)">
                {{ getQuestionTypeName(question.questionType) }}
              </a-tag>
            </div>
            <div class="question-content">{{ question.questionContent }}</div>
            <div class="question-options" v-if="question.options">
              <div
                v-for="(option, optIndex) in question.options"
                :key="optIndex"
                class="option-item"
              >
                <span class="option-label">{{ String.fromCharCode(65 + optIndex) }}.</span>
                <span class="option-text">{{ option.text }}</span>
                <span v-if="option.score" class="option-score">({{ option.score }}分)</span>
              </div>
            </div>
          </a-card>
        </div>
        <div class="empty-preview" v-else>
          <a-empty description="上传文档后将在此处显示生成的题目">
            <template #image>
              <icon-file size="64" />
            </template>
          </a-empty>
        </div>
      </div>

      <!-- 右侧：AI 聊天室 -->
      <div class="chat-section">
        <div class="section-header">
          <span>AI 助手</span>
        </div>
        <div class="chat-container">
          <div class="chat-messages" ref="chatContainerRef">
            <div v-if="!hasUploaded" class="upload-state">
              <div class="upload-tip">
                <icon-robot size="48" />
                <p>请上传 PDF 或文档文件，AI 将为您生成题目</p>
              </div>
              <a-upload
                draggable
                :limit="1"
                accept=".pdf,.doc,.docx,.txt"
                :auto-upload="false"
                :show-file-list="false"
                @change="handleFileChange"
              >
                <template #upload-button>
                  <div class="upload-area">
                    <icon-upload size="32" />
                    <span>点击或拖拽文件到此处上传</span>
                    <span class="upload-hint">支持 PDF、Word、TXT 格式</span>
                  </div>
                </template>
              </a-upload>
            </div>
            <template v-else>
              <div
                v-for="(msg, index) in messages"
                :key="index"
                :class="['message-item', msg.role === 'user' ? 'user' : 'ai']"
              >
                <div class="message-avatar">
                  <icon-user v-if="msg.role === 'user'" size="20" />
                  <icon-robot v-else size="20" />
                </div>
                <div class="message-content">
                  <div class="message-text" v-html="msg.content"></div>
                </div>
              </div>
              <div v-if="loading" class="loading-indicator">
                <a-spin size="small" />
                <span>AI 正在思考...</span>
              </div>
            </template>
          </div>
          <div class="chat-input" v-if="hasUploaded">
            <a-input
              v-model="inputMessage"
              placeholder="输入消息与 AI 对话..."
              @press-enter="sendMessage"
              allow-clear
            >
              <template #append>
                <a-button type="primary" @click="sendMessage" :loading="loading">
                  <icon-send />
                </a-button>
              </template>
            </a-input>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, nextTick } from "vue";
import { IconFile, IconUpload, IconRobot, IconUser, IconSend } from "@arco-design/web-vue/es/icon";
import { Message } from "@arco-design/web-vue";
import API from "@/api";

// 题目选项类型
interface QuestionOption {
  text: string;
  score?: number;
}

// 题目类型
interface Question {
  questionContent: string;
  questionType: number;
  options?: QuestionOption[];
}

// 聊天消息类型
interface ChatMessage {
  role: "user" | "ai";
  content: string;
}

// 题目类型映射
const QUESTION_TYPE_MAP: Record<number, string> = {
  0: "单选",
  1: "多选",
  2: "问答",
};

const QUESTION_TYPE_COLOR_MAP: Record<number, string> = {
  0: "blue",
  1: "green",
  2: "orange",
};

// 响应式数据
const questions = ref<Question[]>([]);
const messages = ref<ChatMessage[]>([]);
const inputMessage = ref("");
const loading = ref(false);
const hasUploaded = ref(false);
const chatContainerRef = ref<HTMLElement>();

// 获取题目类型名称
const getQuestionTypeName = (type: number) => {
  return QUESTION_TYPE_MAP[type] || "未知";
};

// 获取题目类型颜色
const getQuestionTypeColor = (type: number) => {
  return QUESTION_TYPE_COLOR_MAP[type] || "default";
};

// 处理文件上传
const handleFileChange = async (file: File) => {
  if (!file) return;

  // 模拟上传成功
  hasUploaded.value = true;
  Message.success("文件上传成功，AI 正在分析...");

  // 添加 AI 欢迎消息
  messages.value.push({
    role: "ai",
    content: `已成功上传 <strong>${file.name}</strong>。我已分析完文档内容，现在可以开始生成题目了。请问您需要：<br/>1. 生成固定数量的题目<br/>2. 根据文档内容自动生成合适的题目<br/>3. 指定题目类型（单选、多选、问答）`,
  });

  await nextTick();
  scrollToBottom();
};

// 发送消息
const sendMessage = async () => {
  if (!inputMessage.value.trim() || loading.value) return;

  const userMsg = inputMessage.value.trim();
  inputMessage.value = "";

  messages.value.push({
    role: "user",
    content: userMsg,
  });

  loading.value = true;
  await nextTick();
  scrollToBottom();

  // 模拟 AI 响应
  setTimeout(() => {
    // 模拟生成题目
    if (userMsg.includes("生成") || userMsg.includes("题目")) {
      questions.value = [
        {
          questionContent: "本测试主要评估您在工作中的哪种特质？",
          questionType: 0,
          options: [
            { text: "领导力", score: 5 },
            { text: "执行力", score: 3 },
            { text: "创新力", score: 4 },
            { text: "协作力", score: 2 },
          ],
        },
        {
          questionContent: "面对压力时，您通常如何应对？",
          questionType: 0,
          options: [
            { text: "主动解决问题", score: 5 },
            { text: "寻求他人帮助", score: 3 },
            { text: "暂时回避", score: 2 },
            { text: "冷静分析", score: 4 },
          ],
        },
        {
          questionContent: "请描述一次您成功完成困难任务的经历。",
          questionType: 2,
        },
      ];
      messages.value.push({
        role: "ai",
        content: `已根据文档内容生成了 <strong>${questions.value.length} 道题目</strong>，包含单选题和问答题。左侧预览区可以查看生成的题目。您可以：<br/>- 点击题目查看详情<br/>- 输入修改意见调整题目<br/>- 确认后点击提交按钮保存`,
      });
    } else {
      messages.value.push({
        role: "ai",
        content: "我理解了。请告诉我您对题目的具体要求，我会根据您的需求进行调整。",
      });
    }

    loading.value = false;
    nextTick(() => scrollToBottom());
  }, 1500);
};

// 滚动到底部
const scrollToBottom = () => {
  if (chatContainerRef.value) {
    chatContainerRef.value.scrollTop = chatContainerRef.value.scrollHeight;
  }
};
</script>

<style scoped>
.ai-add-app-page {
  height: 100%;
  display: flex;
  flex-direction: column;
  padding: 24px;
  background: #f7f8fa;
}

.page-header {
  margin-bottom: 24px;
}

.page-header h2 {
  margin: 0;
  font-size: 24px;
  font-weight: 600;
  color: var(--color-text-1);
}

.split-container {
  flex: 1;
  display: flex;
  gap: 24px;
  min-height: 0;
}

.preview-section {
  flex: 3;
  background: #fff;
  border-radius: 8px;
  display: flex;
  flex-direction: column;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
}

.chat-section {
  flex: 2;
  background: #fff;
  border-radius: 8px;
  display: flex;
  flex-direction: column;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
}

.section-header {
  padding: 16px 20px;
  border-bottom: 1px solid var(--color-border-2);
  font-weight: 600;
  font-size: 16px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.question-list {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
}

.question-card {
  margin-bottom: 16px;
  border-radius: 8px;
  transition: box-shadow 0.2s;
}

.question-card:hover {
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);
}

.question-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.question-number {
  font-weight: 600;
  color: var(--color-primary);
}

.question-content {
  font-size: 15px;
  line-height: 1.6;
  color: var(--color-text-1);
  margin-bottom: 16px;
}

.question-options {
  background: var(--color-fill-1);
  padding: 12px;
  border-radius: 6px;
}

.option-item {
  display: flex;
  align-items: center;
  padding: 8px 0;
  border-bottom: 1px dashed var(--color-border-2);
}

.option-item:last-child {
  border-bottom: none;
}

.option-label {
  font-weight: 500;
  margin-right: 8px;
  color: var(--color-text-2);
}

.option-text {
  flex: 1;
}

.option-score {
  color: var(--color-primary);
  font-weight: 500;
}

.empty-preview {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
}

.chat-container {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
}

.upload-state {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 24px;
}

.upload-tip {
  text-align: center;
  color: var(--color-text-2);
}

.upload-tip p {
  margin-top: 12px;
  font-size: 14px;
}

.upload-area {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 32px;
  border: 2px dashed var(--color-border-2);
  border-radius: 8px;
  background: var(--color-fill-1);
  cursor: pointer;
  transition: all 0.2s;
}

.upload-area:hover {
  border-color: var(--color-primary);
  background: var(--color-primary-light-1);
}

.upload-area span {
  margin-top: 8px;
  color: var(--color-text-2);
}

.upload-hint {
  font-size: 12px !important;
  color: var(--color-text-3) !important;
}

.message-item {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
}

.message-item.user {
  flex-direction: row-reverse;
}

.message-avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.message-item.user .message-avatar {
  background: var(--color-primary-light-1);
  color: var(--color-primary);
}

.message-item.ai .message-avatar {
  background: var(--color-success-light-1);
  color: var(--color-success);
}

.message-content {
  max-width: 80%;
}

.message-text {
  padding: 12px 16px;
  border-radius: 12px;
  font-size: 14px;
  line-height: 1.6;
}

.message-item.user .message-text {
  background: var(--color-primary);
  color: #fff;
  border-bottom-right-radius: 4px;
}

.message-item.ai .message-text {
  background: var(--color-fill-2);
  color: var(--color-text-1);
  border-bottom-left-radius: 4px;
}

.loading-indicator {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 0;
  color: var(--color-text-3);
  font-size: 13px;
}

.chat-input {
  padding: 16px;
  border-top: 1px solid var(--color-border-2);
}
</style>
