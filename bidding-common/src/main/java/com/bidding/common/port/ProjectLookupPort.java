package com.bidding.common.port;

import java.util.List;

/**
 * 项目只读查询（供 review 等模块校验归属；实现在 project 模块）。
 */
public interface ProjectLookupPort {

    /**
     * 按 ID 查未删除项目；不存在或已删除返回 null。
     */
    ProjectRef findActiveById(long projectId);

    /**
     * 本组织未删除项目（不含 DELETED），名称关键字可选，按 id 降序。
     */
    List<ProjectRef> searchByOrgId(long orgId, String keyword, int offset, int limit);

    /** 跨模块传递的项目摘要。 */
    class ProjectRef {
        private long id;
        private long orgId;
        private String name;
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

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }
    }
}
