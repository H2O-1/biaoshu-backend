package com.bidding.common.port;

/**
 * 专家临时账号管理端口（实现在 identity 模块）。
 */
public interface ExpertAccountPort {

    /**
     * 创建一个临时专家账号。
     */
    CreateResult createTemporaryExpert(long orgId, String displayName, long createdBy);

    /**
     * 重置临时专家密码。
     */
    ResetPasswordResult resetTemporaryExpertPassword(long userId);

    /**
     * 禁用临时专家账号，并吊销已有会话。
     */
    void disableTemporaryExpert(long userId);

    /**
     * 删除临时专家账号。
     */
    void deleteTemporaryExpert(long userId);

    class CreateResult {
        private long userId;
        private String username;
        private String displayName;
        private String temporaryPassword;
        private String passwordHash;

        public long getUserId() {
            return userId;
        }

        public void setUserId(long userId) {
            this.userId = userId;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public String getTemporaryPassword() {
            return temporaryPassword;
        }

        public void setTemporaryPassword(String temporaryPassword) {
            this.temporaryPassword = temporaryPassword;
        }

        public String getPasswordHash() {
            return passwordHash;
        }

        public void setPasswordHash(String passwordHash) {
            this.passwordHash = passwordHash;
        }
    }

    class ResetPasswordResult {
        private long userId;
        private String temporaryPassword;

        public long getUserId() {
            return userId;
        }

        public void setUserId(long userId) {
            this.userId = userId;
        }

        public String getTemporaryPassword() {
            return temporaryPassword;
        }

        public void setTemporaryPassword(String temporaryPassword) {
            this.temporaryPassword = temporaryPassword;
        }
    }
}
