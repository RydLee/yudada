import myAxios from "@/request";
import { AxiosPromise } from "axios";

/**
 * 创建考试 Session
 * @returns sessionId
 */
export const createSessionUsingPost = (): AxiosPromise<string> => {
  return myAxios({
    url: "/api/exam/session/create",
    method: "post",
    headers: {
      "Content-Type": "application/x-www-form-urlencoded",
    },
  });
};

/**
 * 生成试卷
 * @param file PDF 文件
 * @param sessionId 会话 ID
 * @param appName 应用名称（可选，帮助 AI 生成更贴合的题目）
 * @param appDesc 应用描述（可选）
 * @returns 题目列表
 */
export const generateExamUsingPost = (
  file: File,
  sessionId: string,
  appName?: string,
  appDesc?: string
): AxiosPromise<API.QuestionContentDTO[]> => {
  const formData = new FormData();
  formData.append("file", file);
  formData.append("sessionId", sessionId);
  if (appName) {
    formData.append("appName", appName);
  }
  if (appDesc) {
    formData.append("appDesc", appDesc);
  }
  return myAxios({
    url: "/api/exam/generate",
    method: "post",
    data: formData,
    headers: {
      "Content-Type": "multipart/form-data",
    },
  });
};

/**
 * 获取考试结果
 * @param sessionId 会话 ID
 * @returns 题目列表
 */
export const getExamResultUsingGet = (
  sessionId: string
): AxiosPromise<API.QuestionContentDTO[]> => {
  return myAxios({
    url: "/api/exam/result",
    method: "get",
    params: {
      sessionId,
    },
  });
};

/**
 * 删除考试 Session
 * @param sessionId 会话 ID
 * @returns 是否成功
 */
export const deleteSessionUsingPost = (
  sessionId: string
): AxiosPromise<boolean> => {
  return myAxios({
    url: "/api/exam/session/delete",
    method: "post",
    params: {
      sessionId,
    },
  });
};

/**
 * 修改题目
 * @param sessionId 会话 ID
 * @param index 题目序号（从 1 开始）
 * @param userPrompt 用户修改提示
 * @returns 更新后的题目列表
 */
export const modifyQuestionUsingPost = (
  sessionId: string,
  index: number,
  userPrompt: string
): AxiosPromise<API.QuestionContentDTO[]> => {
  return myAxios({
    url: "/api/exam/modify",
    method: "post",
    params: {
      sessionId,
      index,
      userPrompt,
    },
    headers: {
      "Content-Type": "application/x-www-form-urlencoded",
    },
  });
};

/**
 * 保存应用（从 Session 创建应用和题目）
 * @param sessionId 会话 ID
 * @param appName 应用名称
 * @param appDesc 应用描述（可选）
 * @param appType 应用类型（0-得分类，1-测评类，默认0）
 * @param scoringStrategy 评分策略（0-自定义，1-AI，默认0）
 * @returns 创建的应用 ID
 */
export const saveExamUsingPost = (
  sessionId: string,
  appName: string,
  appDesc?: string,
  appType: number = 0,
  scoringStrategy: number = 0
): AxiosPromise<number> => {
  return myAxios({
    url: "/api/exam/save",
    method: "post",
    params: {
      sessionId,
      appName,
      appDesc,
      appType,
      scoringStrategy,
    },
  });
};
