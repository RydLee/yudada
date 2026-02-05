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
              <a-tag :color="getQuestionTypeColor(question.options)">
                {{ getQuestionTypeName(question.options) }}
              </a-tag>
              <a-button type="text" size="small" @click="handleAiModify(index)">
                <template #icon><icon-bot /></template>
                AI 修改
              </a-button>
            </div>
            <div class="question-content">{{ question.title }}</div>
            <div class="question-options" v-if="question.options && question.options.length > 0">
              <div
                v-for="(option, optIndex) in question.options"
                :key="optIndex"
                class="option-item"
              >
                <span class="option-label">{{ option.key }}.</span>
                <span class="option-text">{{ option.value }}</span>
                <span v-if="option.score !== undefined" class="option-score">
                  ({{ option.score }}分)
                </span>
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
          <a-tag v-if="sessionId" color="green" size="small">已连接</a-tag>
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
                :loading="uploading"
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
                  <div class="message-text" v-html="formatMessage(msg.content)"></div>
                </div>
              </div>
              <div v-if="loading" class="loading-indicator">
                <a-spin size="small" />
                <span>AI 正在思考...</span>
              </div>
              <a-skeleton v-if="loading && messages.length === 0" :animation="true">
                <a-skeleton-item style="width: 80%" />
                <a-skeleton-item style="width: 60%" />
                <a-skeleton-item style="width: 70%" />
              </a-skeleton>
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
import { ref, onMounted, nextTick } from "vue";
import {
  IconFile,
  IconUpload,
  IconRobot,
  IconUser,
  IconSend,
  IconBot,
} from "@arco-design/web-vue/es/icon";
import { Message } from "@arco-design/web-vue";
import API from "@/api";
import {
  createSessionUsingPost,
  generateExamUsingPost,
  modifyQuestionUsingPost,
} from "@/api/examController";

// 题目选项类型（复用 API 定义的 Option）
type QuestionOption = API.Option;

// 题目类型（复用 API 定义的 QuestionContentDTO）
interface Question extends API.QuestionContentDTO {}

// 聊天消息类型
interface ChatMessage {
  role: "user" | "ai";
  content: string;
}

// 当前修改的题目索引
let currentModifyIndex: number | null = null;

// 响应式数据
const questions = ref<Question[]>([]);
const messages = ref<ChatMessage[]>([]);
const inputMessage = ref("");
const loading = ref(false);
const uploading = ref(false);
const hasUploaded = ref(false);
const sessionId = ref<string>("");
const chatContainerRef = ref<HTMLElement>();

/**
 * 页面挂载时创建 Session
 */
onMounted(async () => {
  try {
    const res = await createSessionUsingPost();
    if (res.data.code === 0 && res.data.data) {
      sessionId.value = res.data.data;
      console.log("Session 创建成功:", sessionId.value);
    } else {
      Message.error("创建会话失败，请刷新重试");
    }
  } catch (error) {
    console.error("创建 Session 失败:", error);
    Message.error("连接服务器失败，请检查网络");
  }
});

/**
 * 获取题目类型名称
 */
const getQuestionTypeName = (options?: QuestionOption[]) => {
  if (!options || options.length === 0) return "问答";
  // 单选：2-4 个选项；多选：超过 4 个选项
  return options.length <= 4 ? "单选" : "多选";
};

/**
 * 获取题目类型颜色
 */
const getQuestionTypeColor = (options?: QuestionOption[]) => {
  if (!options || options.length === 0) return "orange";
  return options.length <= 4 ? "blue" : "green";
};

/**
 * 处理文件上传
 * @param fileObj Arco UploadChange 事件对象
 */
const handleFileChange = (fileObj: { file: { originFile?: File }; fileList: unknown[]; event: unknown }) => {
  const file = fileObj.file?.originFile;
  if (!file || !sessionId.value) {
    Message.warning("会话未建立，请刷新页面重试");
    return;
  }

  uploading.value = true;

  /**
   * 上传文件
   */
  const doUpload = async () => {
    try {
      const res = await generateExamUsingPost(file, sessionId.value);

      if (res.data.code === 0 && res.data.data) {
        hasUploaded.value = true;
        questions.value = res.data.data;

        // 添加 AI 欢迎消息
        messages.value.push({
          role: "ai",
          content: `已成功上传并分析文件，生成 <strong>${questions.value.length} 道题目</strong>。左侧预览区可以查看生成的题目。您可以：<br/>- 点击题目卡片上的"AI 修改"按钮调整题目<br/>- 在下方输入框中描述修改需求`,
        });

        Message.success("题目生成成功！");
        nextTick(() => scrollToBottom());
      } else {
        const errorCode = res.data.code;
        if (errorCode === 40400) {
          Message.error("会话已过期，请刷新页面重新上传");
          sessionId.value = "";
        } else {
          Message.error("生成失败：" + (res.data.message || "未知错误"));
        }
      }
    } catch (error: any) {
      console.error("上传失败:", error);
      if (error.response?.data?.code === 40400) {
        Message.error("会话已过期，请刷新页面重新上传");
        sessionId.value = "";
      } else {
        Message.error("上传失败，请检查网络连接");
      }
    } finally {
      uploading.value = false;
    }
  };

  doUpload();
};

/**
 * 点击 AI 修改按钮
 */
const handleAiModify = (index: number) => {
  currentModifyIndex = index;
  inputMessage.value = `修改第 ${index + 1} 题：`;
  Message.info(`正在修改第 ${index + 1} 题，请在右侧输入修改要求后发送`);
};

/**
 * 发送消息
 */
const sendMessage = async () => {
  if (!inputMessage.value.trim() || loading.value) return;

  if (!sessionId.value) {
    Message.error("会话已过期，请刷新页面");
    return;
  }

  const userMsg = inputMessage.value.trim();
  inputMessage.value = "";

  messages.value.push({
    role: "user",
    content: userMsg,
  });

  loading.value = true;
  await nextTick();
  scrollToBottom();

  try {
    let res;

    // 判断是否是修改题目请求
    if (currentModifyIndex !== null && userMsg.includes("修改第")) {
      res = await modifyQuestionUsingPost(
        sessionId.value,
        currentModifyIndex + 1, // 接口要求从 1 开始
        userMsg
      );

      if (res.data.code === 0 && res.data.data) {
        // 更新题目列表
        questions.value = res.data.data;
        currentModifyIndex = null;

        messages.value.push({
          role: "ai",
          content: `题目已修改完成！左侧预览区已更新。`,
        });

        Message.success("题目修改成功！");
      } else {
        const errorCode = res.data.code;
        if (errorCode === 40400) {
          Message.error("会话已过期，请刷新页面");
          sessionId.value = "";
        } else if (errorCode === 40000) {
          Message.error("修改失败：" + res.data.message);
        } else {
          Message.error("修改失败：" + (res.data.message || "未知错误"));
        }
      }
    } else {
      // 普通对话，模拟 AI 响应
      await new Promise((resolve) => setTimeout(resolve, 1000));
      messages.value.push({
        role: "ai",
        content: `我已收到您的消息。如果您需要修改题目，请点击左侧题目卡片上的"AI 修改"按钮，或直接输入"修改第 X 题：..."。`,
      });
    }
  } catch (error: any) {
    console.error("请求失败:", error);
    if (error.response?.data?.code === 40400) {
      Message.error("会话已过期，请刷新页面");
      sessionId.value = "";
    } else {
      Message.error("请求失败，请检查网络连接");
    }
  } finally {
    loading.value = false;
    await nextTick();
    scrollToBottom();
  }
};

/**
 * 格式化消息内容
 */
const formatMessage = (content: string) => {
  return content.replace(/\n/g, "<br/>");
};

/**
 * 滚动到底部
 */
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
