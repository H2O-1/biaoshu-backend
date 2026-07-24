package com.bidding.common.port;

/**
 * 标包只读查询（供 file 等模块校验归属；实现在 project 模块）。
 */
public interface PackageLookupPort {

    /**
     * 按 ID 查未删除标包；不存在或已删除返回 null。
     */
    PackageRef findActiveById(long packageId);

    /** 跨模块传递的标包摘要（避免 file 依赖 project 模型）。 */
    class PackageRef {
        private long id;
        private long orgId;
        private Long projectId;
        private String type;
        private String status;

        public long getId() {
            return id;
        }

        public void setId(long id) {
            this.id = id;
        }

        public long getOrgId() {
            return orgId;
        }

        public void setOrgId(long orgId) {
            this.orgId = orgId;
        }

        public Long getProjectId() {
            return projectId;
        }

        public void setProjectId(Long projectId) {
            this.projectId = projectId;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }
    }
}
