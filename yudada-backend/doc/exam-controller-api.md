# Exam Controller 接口文档

## 基础信息

| 项目 | 说明 |
|------|------|
| 基础路径 | `/api/exam` |
| 缓存机制 | Redis，Key: `exam:session:{sessionId}`，过期时间: 30 分钟 |
| 响应格式 | 统一使用 `BaseResponse<T>` |

---

## 接口列表

### 1. 创建考试 Session

创建新的考试会话，获取 sessionId。

**请求**
```http
POST /api/exam/session/create
Content-Type: application/x-www-form-urlencoded
```

**响应**
```json
{
  "code": 0,
  "data": "f8a5c2d3e4b6"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| data | String | sessionId，后续请求需要传递此值 |

---

### 2. 生成试卷

上传 PDF 文件，生成题目。相同 sessionId 可复用缓存结果。

**请求**
```http
POST /api/exam/generate
Content-Type: multipart/form-data

file      = [PDF文件]
sessionId = f8a5c2d3e4b6
```

**响应**
```json
{
  "code": 0,
  "data": [
    {
      "title": "题目描述...",
      "options": [
        {
          "key": "A",
          "value": "选项A内容",
          "score": 1
        },
        {
          "key": "B",
          "value": "选项B内容",
          "score": 0
        }
      ]
    }
  ]
}
```

**逻辑说明**
1. 校验 sessionId
2. 检查 Redis 是否有缓存，有则直接返回
3. 解析 PDF，调用 AI 生成题目
4. 缓存结果到 Redis（30分钟过期）

---

### 3. 获取考试结果

通过 sessionId 获取已生成的题目列表。

**请求**
```http
GET /api/exam/result
Content-Type: application/x-www-form-urlencoded

sessionId = f8a5c2d3e4b6
```

**响应**
```json
{
  "code": 0,
  "data": [
    {
      "title": "题目描述...",
      "options": [...]
    }
  ]
}
```

**错误**
```json
{
  "code": 40400,
  "message": "考试结果不存在或已过期"
}
```

---

### 4. 删除考试 Session

清除指定 session 的缓存。

**请求**
```http
POST /api/exam/session/delete
Content-Type: application/x-www-form-urlencoded

sessionId = f8a5c2d3e4b6
```

**响应**
```json
{
  "code": 0,
  "data": true
}
```

---

### 5. 修改题目

调用 AI 修改指定题目，返回更新后的完整题目列表。

**请求**
```http
POST /api/exam/modify
Content-Type: application/x-www-form-urlencoded

sessionId   = f8a5c2d3e4b6
index       = 1          // 题目序号，从 1 开始
userPrompt  = 把选项变得更简单易懂
```

**响应**
```json
{
  "code": 0,
  "data": [
    {
      "title": "修改后的题目描述...",
      "options": [...]
    }
  ]
}
```

**逻辑说明**
1. 参数校验
2. 从 Redis 获取缓存
3. 校验 index 范围（1-based）
4. 构建二次 Prompt 调用 AI
5. 解析 AI 返回，更新指定题目
6. 写回 Redis（刷新过期时间）

**错误码**
| code | message | 场景 |
|------|---------|------|
| 40000 | sessionId 不能为空 | 未传 sessionId |
| 40000 | index 必须大于等于 1 | index < 1 |
| 40000 | index 超出题目范围 | index > 题目总数 |
| 40400 | 考试结果不存在或已过期 | session 过期或不存在 |
| 50000 | AI 修改失败 | AI 返回格式异常 |

---

## 通用响应格式

```json
{
  "code": 0,        // 0 表示成功，非 0 表示失败
  "data": {...},    // 业务数据
  "message": ""     // 错误信息（失败时）
}
```

**错误码定义**

| code | 说明 |
|------|------|
| 0 | 成功 |
| 40000 | 请求参数错误 |
| 40100 | 未登录 |
| 40101 | 无权限 |
| 40400 | 请求数据不存在 |
| 50000 | 系统内部异常 |

---

## 使用流程

```
1. 创建 Session
   POST /exam/session/create
   → 获取 sessionId

2. 生成试卷
   POST /exam/generate?file=xxx.pdf&sessionId=xxx
   → 返回题目列表（自动缓存）

3. 修改题目（如需要）
   POST /exam/modify?sessionId=xxx&index=1&userPrompt=xxx
   → 返回更新后的完整列表

4. 获取结果
   GET /exam/result?sessionId=xxx
   → 返回缓存的题目列表

5. 删除 Session（如需要）
   POST /exam/session/delete?sessionId=xxx
   → 清除缓存
```

---

## QuestionContentDTO 结构

```json
{
  "title": "题目标题",
  "options": [
    {
      "key": "A",
      "value": "选项内容",
      "score": 1
    }
  ]
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| title | String | 题目标题 |
| options | Array | 选项列表 |
| options[].key | String | 选项标识（A、B、C...） |
| options[].value | String | 选项内容 |
| options[].score | int | 该选项的分数 |

---

## 注意事项

1. **sessionId 必传**：除 `createSession` 外，所有接口都需要传递 sessionId
2. **index 从 1 开始**：modify 接口的 index 参数从 1 开始计数
3. **缓存复用**：相同 sessionId 再次调用 generate 会直接返回缓存结果
4. **AI 修改上下文**：modify 接口会携带历史对话（最多约 10 轮）
5. **临时文件**：generate 接口会自动清理 PDF 解析产生的临时文件
