/**
 * 身份与权限模块：组织、用户、平台角色、登录鉴权。
 * <p>
 * 分层约定：
 * <ul>
 *   <li>adapter.web — Controller，收 HTTP</li>
 *   <li>application — 用例（登录、管角色）</li>
 *   <li>domain — 模型与 Repository 端口</li>
 *   <li>infrastructure — JDBC、JWT、Security</li>
 * </ul>
 */
package com.bidding.identity;
