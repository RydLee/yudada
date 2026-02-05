<template>
  <div class="ai-add-app-page">
    <div class="page-header">
      <h2>AI 创建应用</h2>
      <a-space>
        <a-button v-if="questions.length > 0" type="primary" @click="handleSaveApp">
          <template #icon><icon-check /></template>
          保存应用
        </a-button>
        <a-button v-if="sessionId" @click="handleEndSession">
          <template #icon><icon-close /></template>
          结束会话
        </a-button>
      </a-space>
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
              <a-form :model="uploadForm" layout="vertical" class="upload-form">
                <a-form-item field="appName" label="应用名称（选填）">
                  <a-input
                    v-model="uploadForm.appName"
                    placeholder="请输入应用名称，AI 将生成更贴合的题目"
                    allow-clear
                  />
                </a-form-item>
                <a-form-item field="appDesc" label="应用描述（选填）">
                  <a-textarea
                    v-model="uploadForm.appDesc"
                    placeholder="请输入应用描述，帮助 AI 更好地理解您的需求"
                    :max-length="200"
                    show-word-limit
                    :rows="2"
                  />
                </a-form-item>
              </a-form>
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
              <!-- 应用信息展示 -->
              <div v-if="uploadForm.appName || uploadForm.appDesc" class="app-info-card">
                <div v-if="uploadForm.appName" class="app-info-item">
                  <span class="app-info-label">应用名称：</span>
                  <span class="app-info-value">{{ uploadForm.appName }}</span>
                </div>
                <div v-if="uploadForm.appDesc" class="app-info-item">
                  <span class="app-info-label">应用描述：</span>
                  <span class="app-info-value">{{ uploadForm.appDesc }}</span>
                </div>
              </div>
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
import { ref, onMounted, nextTick, watch } from "vue";
import {
  IconFile,
  IconUpload,
  IconRobot,
  IconUser,
  IconSend,
  IconBot,
  IconCheck,
  IconClose,
} from "@arco-design/web-vue/es/icon";
import { Message } from "@arco-design/web-vue";
import API from "@/api";
import {
  createSessionUsingPost,
  deleteSessionUsingPost,
  generateExamUsingPost,
  modifyQuestionUsingPost,
  saveExamUsingPost,
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

// SessionStorage Key
const STORAGE_KEY = "ai_exam_data";

// 当前修改的题目索引
let currentModifyIndex: number | null = null;

// 上传表单数据
const uploadForm = ref({
  appName: "",
  appDesc: "",
});

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
 * 从 sessionStorage 恢复数据
 */
const restoreFromStorage = () => {
  try {
    const saved = sessionStorage.getItem(STORAGE_KEY);
    if (saved) {
      const data = JSON.parse(saved);
      if (data.sessionId && data.questions && data.questions.length > 0) {
        sessionId.value = data.sessionId;
        questions.value = data.questions;
        hasUploaded.value = true;
        // 恢复聊天消息
        if (data.messages && data.messages.length > 0) {
          messages.value = data.messages;
        }
        // 恢复表单数据
        if (data.uploadForm) {
          uploadForm.value = data.uploadForm;
        }
        console.log("已从缓存恢复数据，sessionId:", sessionId.value);
        return true;
      }
    }
  } catch (e) {
    console.error("恢复缓存数据失败:", e);
  }
  return false;
};

/**
 * 保存数据到 sessionStorage
 */
const saveToStorage = () => {
  try {
    const data = {
      sessionId: sessionId.value,
      questions: questions.value,
      messages: messages.value,
      uploadForm: uploadForm.value,
    };
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(data));
    console.log("数据已保存到缓存");
  } catch (e) {
    console.error("保存缓存数据失败:", e);
  }
};

/**
 * 页面挂载时
 */
onMounted(async () => {
  // 先尝试从缓存恢复数据
  if (restoreFromStorage()) {
    return;
  }

  // 没有缓存，创建新 Session
  try {
    console.log("正在创建 Session...");
    const res = await createSessionUsingPost();
    console.log("响应数据:", res.data);
    if (res.data?.code === 0 && res.data?.data) {
      sessionId.value = res.data.data;
      console.log("Session 创建成功:", sessionId.value);
    } else {
      Message.error("创建会话失败：" + (res.data?.message || "未知错误"));
    }
  } catch (error: any) {
    console.error("创建 Session 失败:", error);
    Message.error("连接服务器失败，请检查后端是否运行");
  }
});

// 监听数据变化，自动保存到 sessionStorage
watch([questions, messages, uploadForm], () => {
  if (sessionId.value) {
    saveToStorage();
  }
}, { deep: true });

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
const handleFileChange = (fileObj: any) => {
  console.log("文件变化事件参数:", fileObj);

  let file: File | undefined;

  if (Array.isArray(fileObj) && fileObj.length > 0) {
    // fileObj[0].file 直接就是 File 对象
    file = fileObj[0].file;
  } else if (fileObj?.file) {
    file = fileObj.file;
  }

  if (!file) {
    Message.warning("无法获取文件，请重试");
    return;
  }

  console.log("获取到文件:", file.name, "大小:", file.size);

  if (!sessionId.value) {
    Message.warning("会话未建立，请刷新页面重试");
    return;
  }

  // 立即切换到对话界面
  hasUploaded.value = true;
  // 添加用户上传文件的消息
  messages.value.push({
    role: "user",
    content: `上传了文件：<strong>${file.name}</strong>`,
  });
  // 添加 AI 等待消息
  messages.value.push({
    role: "ai",
    content: `收到文件，正在分析文档并生成题目，请稍候...`,
  });
  nextTick(() => scrollToBottom());

  uploading.value = true;

  /**
   * 执行上传
   */
  const doUpload = async () => {
    try {
      console.log("开始上传... appName:", uploadForm.value.appName, "appDesc:", uploadForm.value.appDesc);
      const res = await generateExamUsingPost(
        file!,
        sessionId.value,
        uploadForm.value.appName || undefined,
        uploadForm.value.appDesc || undefined
      );
      console.log("上传响应:", res.data);

      if (res.data.code === 0 && res.data.data) {
        questions.value = res.data.data;

        // 移除 AI 的等待消息，添加成功消息
        messages.value.pop();
        messages.value.push({
          role: "ai",
          content: `已成功上传并分析文件，生成 <strong>${questions.value.length} 道题目</strong>。左侧预览区可以查看生成的题目。您可以点击题目上的"AI 修改"按钮进行调整。`,
        });

        saveToStorage();
        Message.success("题目生成成功！");
        nextTick(() => scrollToBottom());
      } else {
        // 移除等待消息
        messages.value.pop();
        const errorCode = res.data.code;
        if (errorCode === 40400) {
          messages.value.push({
            role: "ai",
            content: `会话已过期，请刷新页面重新上传`,
          });
          sessionId.value = "";
        } else {
          messages.value.push({
            role: "ai",
            content: `生成失败：${res.data.message || "未知错误"}`,
          });
        }
        nextTick(() => scrollToBottom());
      }
    } catch (error: any) {
      // 移除等待消息
      messages.value.pop();
      console.error("上传失败:", error);
      if (error.response?.data?.code === 40400) {
        messages.value.push({
          role: "ai",
          content: `会话已过期，请刷新页面重新上传`,
        });
        sessionId.value = "";
      } else {
        messages.value.push({
          role: "ai",
          content: `上传失败，请检查网络连接`,
        });
      }
      nextTick(() => scrollToBottom());
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

/**
 * 保存应用
 */
const handleSaveApp = async () => {
  if (!sessionId.value) {
    Message.warning("会话已过期，请重新上传文件");
    return;
  }

  if (questions.value.length === 0) {
    Message.warning("没有可保存的题目");
    return;
  }

  // 如果用户已经填写了应用名称和描述，使用表单中的值
  let appName = uploadForm.value.appName;
  let appDesc = uploadForm.value.appDesc;

  // 如果没有填写，提示用户
  if (!appName) {
    appName = prompt("请输入应用名称：");
    if (!appName) {
      return;
    }
  }

  try {
    const res = await saveExamUsingPost(
      sessionId.value,
      appName,
      appDesc || undefined
    );

    if (res.data.code === 0 && res.data.data) {
      Message.success("应用保存成功！");
      // 清除缓存
      sessionStorage.removeItem(STORAGE_KEY);
      // 跳转到应用详情页
      setTimeout(() => {
        window.location.href = `/app/detail/${res.data.data}`;
      }, 1500);
    } else {
      Message.error("保存失败：" + (res.data.message || "未知错误"));
    }
  } catch (error: any) {
    console.error("保存失败:", error);
    Message.error("保存失败，请检查网络连接");
  }
};

/**
 * 结束会话
 */
const handleEndSession = async () => {
  if (!sessionId.value) {
    Message.warning("会话已结束或不存在");
    resetPage();
    return;
  }

  try {
    const res = await deleteSessionUsingPost(sessionId.value);
    if (res.data.code === 0) {
      Message.success("会话已结束");
    } else {
      Message.warning("会话已结束");
    }
  } catch (error) {
    // 即使请求失败，也视为会话已结束
    console.log("结束会话请求失败，但可能已结束");
  } finally {
    resetPage();
  }
};

/**
 * 重置页面状态
 */
const resetPage = () => {
  sessionId.value = "";
  questions.value = [];
  messages.value = [];
  hasUploaded.value = false;
  uploadForm.value = { appName: "", appDesc: "" };
  sessionStorage.removeItem(STORAGE_KEY);
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
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 24px;
}

.page-header h2 {
  margin: 0;
  font-size: 24px;
  font-weight: 600;
  color: var(--color-text-1);
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

.upload-form {
  width: 100%;
  max-width: 400px;
  margin-bottom: 16px;
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

.app-info-card {
  background: linear-gradient(135deg, var(--color-primary-light-2) 0%, var(--color-primary-light-1) 100%);
  border-radius: 8px;
  padding: 16px;
  margin-bottom: 16px;
}

.app-info-item {
  display: flex;
  align-items: flex-start;
  margin-bottom: 8px;
}

.app-info-item:last-child {
  margin-bottom: 0;
}

.app-info-label {
  color: var(--color-text-2);
  font-size: 13px;
  white-space: nowrap;
  margin-right: 8px;
}

.app-info-value {
  color: var(--color-text-1);
  font-size: 14px;
  word-break: break-word;
}
</style>
