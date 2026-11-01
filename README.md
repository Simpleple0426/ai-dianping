# AI 点评

基于 Spring Boot、MySQL、Redis 和 Vue 的本地生活应用，支持商户查询、优惠券秒杀、笔记、预约及 AI 内容生成。

## 目录

- `AI-dianping-backend`：Java 17 后端、业务接口、AI 技能及 MCP 服务。
- `AI-dianping-frontend/html/app`：前端静态页面。
- `AI-dianping-frontend/conf`：Nginx 配置。
- `db.sql`：14 张表的结构及内置 AI 技能模板，不含用户、订单、商户和聊天数据。

## 启动

1. 安装 JDK 17、Maven、MySQL 8、Redis 和 Nginx。
2. 新建空数据库 `ai_dianping`，导入 `db.sql`。脚本仅用于空库初始化。
3. 设置后端环境变量：

| 变量 | 用途 |
| --- | --- |
| `DB_URL` | JDBC 地址，默认本机 3306 的 ai_dianping 库 |
| `DB_USERNAME` | 数据库用户，默认 root |
| `DB_PASSWORD` | 数据库密码 |
| `REDIS_HOST` | Redis 地址，默认 127.0.0.1 |
| `REDIS_PORT` | Redis 端口，默认 6379 |
| `REDIS_PASSWORD` | Redis 密码，默认空 |
| `AI_BASE_URL` | OpenAI 兼容接口地址，默认 https://api.openai.com/v1 |
| `AI_API_KEY` | 模型服务密钥，必填 |
| `AI_MODEL` | 模型名称，必填 |
| `IMAGE_UPLOAD_DIR` | 图片保存目录，指向前端 app/imgs 的绝对路径 |

也可在后端目录创建 `application-local.yml` 设置本机参数，该文件已忽略。不要将凭据写入源码。

4. 在后端目录运行 `mvn spring-boot:run`，默认监听 8081。
5. 将前端 `html/app` 和 `html/50x.html` 放到 Nginx 的对应目录，使用提供的 `nginx.conf` 和 `mime.types`。Nginx 监听 8080，`/api` 转发到后端。

数据库只包含结构和 AI 技能模板；商户、分类、优惠券等业务数据需自行录入。验证码登录为开发演示流程，不含生产短信服务。AI 功能需要可用的模型服务配置。

## 验证

在后端目录运行不依赖外部服务的回归测试：

```shell
mvn -Dtest=UserMapperGeneratedKeyTest,UserLoginRegressionTest,ShopServiceRegressionTest,OrderMessageProcessorTest,VoucherOrderProcessingTest,OrderConsumerRecoveryTest,SeckillTimeValidationTest,McpServerConfigTest test
```

`AiDianpingApplicationTests` 需要本机数据库、Redis 和模型配置；`SeckillLuaRedisTest` 仅在显式启用 `SECKILL_REDIS_TEST=true` 时执行 Redis 联调。

秒杀消费者支持失败退避、Pending 恢复及失败留档。跨 MySQL/Redis 的一致性、失败补偿和多实例消费仍需按部署场景验证。AI 生成内容应经人工核实。
