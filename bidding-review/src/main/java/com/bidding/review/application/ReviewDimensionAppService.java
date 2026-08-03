package com.bidding.review.application;

import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;
import com.bidding.common.port.ReviewDimensionPort;
import com.bidding.common.security.CurrentUser;
import com.bidding.common.security.UserContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 全局审查维度：招标方只读启用列表；平台管理员按 id 可改。
 */
@Service
public class ReviewDimensionAppService {

    private static final int MAX_NAME_LENGTH = 128;
    private static final int MAX_QUERY_LENGTH = 512;
    private static final int MAX_QUERIES = 20;

    private final ReviewDimensionPort reviewDimensionPort;

    public ReviewDimensionAppService(ReviewDimensionPort reviewDimensionPort) {
        this.reviewDimensionPort = reviewDimensionPort;
    }

    /**
     * 前端建清单勾选用：仅启用中的维度。
     */
    @Transactional(readOnly = true)
    public List<DimensionSummary> listEnabled() {
        requireLogin();
        return toSummaries(reviewDimensionPort.listAll(true));
    }

    /**
     * 平台管理：全部维度（含停用）。
     */
    @Transactional(readOnly = true)
    public List<DimensionSummary> listAllForAdmin() {
        requirePlatformAdmin();
        return toSummaries(reviewDimensionPort.listAll(false));
    }

    /**
     * 平台管理：按 id 更新维度元数据并全量覆盖 query。
     */
    @Transactional
    public DimensionSummary update(long id,
                                   String name,
                                   Boolean defaultSelected,
                                   Boolean enabled,
                                   Integer sortOrder,
                                   List<String> queries) {
        requirePlatformAdmin();

        ReviewDimensionPort.Dimension existing = reviewDimensionPort.findById(id);
        if (existing == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "维度不存在");
        }

        String safeName = normalizeName(name);
        boolean safeDefault = defaultSelected == null ? existing.isDefaultSelected() : defaultSelected;
        boolean safeEnabled = enabled == null ? existing.isEnabled() : enabled;
        int safeSort = sortOrder == null ? existing.getSortOrder() : sortOrder;
        List<String> safeQueries = normalizeQueries(queries);

        try {
            reviewDimensionPort.update(id, safeName, safeDefault, safeEnabled, safeSort, safeQueries);
        } catch (IllegalStateException ex) {
            throw new BizException(ErrorCode.NOT_FOUND, "维度不存在");
        }

        ReviewDimensionPort.Dimension updated = reviewDimensionPort.findById(id);
        return toSummary(updated);
    }

    private List<DimensionSummary> toSummaries(List<ReviewDimensionPort.Dimension> source) {
        List<DimensionSummary> list = new ArrayList<>();
        if (source == null) {
            return list;
        }
        for (int i = 0; i < source.size(); i++) {
            list.add(toSummary(source.get(i)));
        }
        return list;
    }

    private DimensionSummary toSummary(ReviewDimensionPort.Dimension dim) {
        DimensionSummary summary = new DimensionSummary();
        summary.setId(dim.getId());
        summary.setCode(dim.getCode());
        summary.setName(dim.getName());
        summary.setDefaultSelected(dim.isDefaultSelected());
        summary.setEnabled(dim.isEnabled());
        summary.setSortOrder(dim.getSortOrder());
        List<String> queries = new ArrayList<>();
        if (dim.getQueries() != null) {
            for (int i = 0; i < dim.getQueries().size(); i++) {
                queries.add(dim.getQueries().get(i));
            }
        }
        summary.setQueries(queries);
        return summary;
    }

    private String normalizeName(String name) {
        if (name == null || name.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "维度名称不能为空");
        }
        String trimmed = name.trim();
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new BizException(ErrorCode.BAD_REQUEST, "维度名称过长");
        }
        return trimmed;
    }

    private List<String> normalizeQueries(List<String> queries) {
        List<String> result = new ArrayList<>();
        if (queries == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "queries 不能为空（可传空数组清空）");
        }
        for (int i = 0; i < queries.size(); i++) {
            String text = queries.get(i);
            if (text == null) {
                continue;
            }
            String trimmed = text.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.length() > MAX_QUERY_LENGTH) {
                throw new BizException(ErrorCode.BAD_REQUEST, "query 过长（≤512）");
            }
            result.add(trimmed);
            if (result.size() > MAX_QUERIES) {
                throw new BizException(ErrorCode.BAD_REQUEST, "单维 query 最多 " + MAX_QUERIES + " 条");
            }
        }
        return result;
    }

    private CurrentUser requireLogin() {
        CurrentUser user = UserContext.get();
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return user;
    }

    private CurrentUser requirePlatformAdmin() {
        CurrentUser user = requireLogin();
        if (!user.isPlatformAdmin()) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅平台管理员可操作");
        }
        return user;
    }

    public static class DimensionSummary {
        private long id;
        private String code;
        private String name;
        private boolean defaultSelected;
        private boolean enabled;
        private int sortOrder;
        private List<String> queries;

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
            this.queries = queries;
        }
    }
}
