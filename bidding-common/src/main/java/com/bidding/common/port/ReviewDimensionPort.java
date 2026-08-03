package com.bidding.common.port;

import java.util.ArrayList;
import java.util.List;

/**
 * 全局审查维度字典（平台一份；实现在 review 模块）。
 */
public interface ReviewDimensionPort {

    /**
     * 列出维度；onlyEnabled=true 时只返回启用中的。
     */
    List<Dimension> listAll(boolean onlyEnabled);

    /**
     * 按数字 id 查询；不存在返回 null。
     */
    Dimension findById(long id);

    /**
     * 按 code 查询；不存在返回 null。
     */
    Dimension findByCode(String code);

    /**
     * 按 id 更新维度元数据，并全量覆盖其 query 列表。
     */
    void update(long id,
                String name,
                boolean defaultSelected,
                boolean enabled,
                int sortOrder,
                List<String> queries);

    /** 一个维度及其检索词。 */
    class Dimension {
        private long id;
        private String code;
        private String name;
        private boolean defaultSelected;
        private boolean enabled;
        private int sortOrder;
        private List<String> queries = new ArrayList<>();

        public long getId() {
            return id;
        }

        public void setId(long id) {
            this.id = id;
        }

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public boolean isDefaultSelected() {
            return defaultSelected;
        }

        public void setDefaultSelected(boolean defaultSelected) {
            this.defaultSelected = defaultSelected;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getSortOrder() {
            return sortOrder;
        }

        public void setSortOrder(int sortOrder) {
            this.sortOrder = sortOrder;
        }

        public List<String> getQueries() {
            return queries;
        }

        public void setQueries(List<String> queries) {
            if (queries == null) {
                this.queries = new ArrayList<>();
            } else {
                this.queries = queries;
            }
        }
    }
}
