# 修复 OpenAI 503 错误状态码映射

## 问题描述

当 OpenAI 上游服务返回 503 Service Unavailable 错误时，系统错误地将其映射为 502 Bad Gateway 返回给客户端。这会导致：

1. 客户端无法准确识别错误类型
2. 503 表示服务暂时不可用（可重试），而 502 表示网关错误（可能不可重试）
3. 影响客户端的错误处理和重试策略

### 错误示例

```json
{
  "success": false,
  "error": {
    "code": "UPSTREAM_ERROR",
    "message": "OpenAI upstream error 503: {\"error\":{\"message\":\"Service temporarily unavailable\",\"type\":\"api_error\"}}"
  },
  "timestamp": "2026-06-13T10:16:00.225492Z"
}
```

实际返回状态码：`502 Bad Gateway` ❌  
期望返回状态码：`503 Service Unavailable` ✅

## 修复方案

### 修改文件
`service/src/main/java/xyz/star4y/api/proxy/ProxyFacade.java`

### 修改内容

#### 1. 增强 `mapError` 方法
```java
private Throwable mapError(Throwable error) {
    Throwable failure = rootFailure(error);
    return switch (failure) {
        case ApiException api -> api;
        case UpstreamException upstream -> {
            HttpStatus status = mapUpstreamStatusCode(upstream.getStatusCode());
            yield new ApiException(status, "UPSTREAM_ERROR", upstream.getMessage());
        }
        default -> new ApiException(HttpStatus.BAD_GATEWAY, "UPSTREAM_ERROR", failure.getMessage());
    };
}
```

#### 2. 新增 `mapUpstreamStatusCode` 方法
```java
private HttpStatus mapUpstreamStatusCode(int upstreamStatusCode) {
    return switch (upstreamStatusCode) {
        case 400 -> HttpStatus.BAD_REQUEST;
        case 401 -> HttpStatus.UNAUTHORIZED;
        case 402, 429 -> HttpStatus.TOO_MANY_REQUESTS;
        case 403 -> HttpStatus.FORBIDDEN;
        case 404 -> HttpStatus.NOT_FOUND;
        case 408 -> HttpStatus.REQUEST_TIMEOUT;
        case 422 -> HttpStatus.UNPROCESSABLE_ENTITY;
        case 500 -> HttpStatus.INTERNAL_SERVER_ERROR;
        case 502 -> HttpStatus.BAD_GATEWAY;
        case 503 -> HttpStatus.SERVICE_UNAVAILABLE;
        case 504 -> HttpStatus.GATEWAY_TIMEOUT;
        default -> upstreamStatusCode >= 500 && upstreamStatusCode < 600
            ? HttpStatus.BAD_GATEWAY
            : HttpStatus.BAD_REQUEST;
    };
}
```

## 状态码映射表

| 上游状态码 | 返回状态码 | HTTP 状态 | 说明 |
|-----------|-----------|----------|------|
| 400 | 400 | Bad Request | 错误的请求 |
| 401 | 401 | Unauthorized | 未授权 |
| 402 | 429 | Too Many Requests | 配额不足（映射为速率限制） |
| 403 | 403 | Forbidden | 禁止访问 |
| 404 | 404 | Not Found | 资源未找到 |
| 408 | 408 | Request Timeout | 请求超时 |
| 422 | 422 | Unprocessable Entity | 无法处理的实体 |
| 429 | 429 | Too Many Requests | 速率限制 |
| 500 | 500 | Internal Server Error | 内部服务器错误 |
| 502 | 502 | Bad Gateway | 网关错误 |
| **503** | **503** | **Service Unavailable** | **服务暂时不可用** ✅ |
| 504 | 504 | Gateway Timeout | 网关超时 |
| 其他5xx | 502 | Bad Gateway | 其他服务器错误 |
| 其他 | 400 | Bad Request | 其他客户端错误 |

## 重试逻辑

重试逻辑**保持不变**，仍然正常工作：

1. **OpenAiUpstreamClient.java**
   - `isRecoverableStatus(503)` 返回 `true`（因为 503 >= 500）
   - 503 错误被标记为可恢复

2. **ProxyFacade.java**
   - `isRetryableUpstreamError()` 检测到 503 >= 500
   - 自动尝试使用其他可用的 OpenAI 账户重试
   - 只有所有账户都失败后，才返回 503 给客户端

## 测试验证

```bash
# 运行所有测试
./gradlew :service:test

# 构建项目
./gradlew :service:build
```

所有测试通过 ✅

## 影响范围

- ✅ 准确反映上游错误状态
- ✅ 改善客户端错误处理体验
- ✅ 保持现有重试逻辑不变
- ✅ 兼容现有 API 行为
- ✅ 无破坏性变更

## 相关文件

- `service/src/main/java/xyz/star4y/api/proxy/ProxyFacade.java` - 主要修改
- `service/src/main/java/xyz/star4y/api/platform/openai/OpenAiUpstreamClient.java` - 重试逻辑
- `service/src/main/java/xyz/star4y/api/platform/upstream/UpstreamException.java` - 异常定义
- `service/src/main/java/xyz/star4y/api/common/GlobalExceptionHandler.java` - 全局异常处理
