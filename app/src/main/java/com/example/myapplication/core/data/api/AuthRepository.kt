package com.example.myapplication.core.data.api

/** 登录用户（契约数据类） */
data class AuthUser(val id: String, val name: String, val role: String? = null, val phone: String? = null)

/**
 * 登录凭据（账号 + 密码）。
 * **仅随「连接设备」请求上报**（`POST /api/v1/devices/bind`，见 docs/后端接口设计文档.md 第 4.2 节），
 * 不做本地持久化：应用重启需重新登录。
 */
data class AuthCredentials(val username: String, val password: String)

/** 登录会话（契约数据类） */
data class AuthSession(val token: String, val refreshToken: String? = null, val user: AuthUser)

/**
 * 账号接口（对外接口契约）——登录/登出；token 供其他 HTTP 仓库携带 `Authorization`。
 * 设备信息按登录用户获取（见 docs/后端接口设计文档.md 第 3/4 节）。
 *
 * 实现分布（本文件为纯接口，不含实现）：
 * - [com.example.myapplication.core.data.auth.HttpAuthRepository] 服务端 `POST /api/v1/auth/login`
 *
 * 调试默认账号：`demo / 123456`（登录页已预填；Mock 服务端内置该账号并绑定两台演示设备；
 * 未登录/跳过登录时服务端按默认用户 demo 处理，见 tools/mock-server/README.md）。
 */
interface AuthRepository {
    /** 当前登录 token（未登录为 null） */
    val currentToken: String?

    /** 当前登录用户（未登录为 null） */
    val currentUser: AuthUser?

    /**
     * 当前登录凭据（未登录 / 跳过登录为 null）。
     * 连接设备时与设备编号、绑定码一并上报（服务端据此校验账号与设备的绑定关系）。
     */
    val currentCredentials: AuthCredentials?

    suspend fun login(username: String, password: String): Result<AuthSession>

    /** 登出：清空本地会话（服务端尽力通知，失败不影响本地清除） */
    suspend fun logout()
}
